package it.uniroma2.isw2.openjpa.git;

import it.uniroma2.isw2.openjpa.model.SzzClass;
import it.uniroma2.isw2.openjpa.model.CommitStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitClientSzzTest {

    @TempDir
    Path repository;

    @Test
    void blamesRemovedLinesInProductionJavaFile() throws Exception {
        run("git", "init");
        run("git", "config", "user.email", "test@example.org");
        run("git", "config", "user.name", "Test");
        Path source = repository.resolve("module/src/main/java/sample/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package sample; class A { int value() { return 1; } }\n");
        run("git", "add", ".");
        run("git", "commit", "-m", "initial");
        Files.writeString(source, "package sample; class A { int value() { return 2; } }\n");
        run("git", "add", ".");
        run("git", "commit", "-m", "OPENJPA-1 fix value");
        String fixHash = run("git", "rev-parse", "HEAD").get(0);

        List<SzzClass> classes = new GitClient(repository).runBasicSzz(fixHash);

        assertEquals(1, classes.size());
        assertEquals("module/src/main/java/sample/A.java", classes.get(0).classPath());
        assertFalse(classes.get(0).bugIntroducingCommits().isEmpty());
    }

    @Test
    void clonesRepositoryWhenConfiguredPathDoesNotExist() throws Exception {
        Path origin = repository.resolve("origin");
        Files.createDirectories(origin);
        run(origin, "git", "init");
        run(origin, "git", "config", "user.email", "test@example.org");
        run(origin, "git", "config", "user.name", "Test");
        Files.writeString(origin.resolve("README.md"), "fixture\n");
        run(origin, "git", "add", ".");
        run(origin, "git", "commit", "-m", "initial");

        Path clone = repository.resolve("nested/clone");
        GitClient client = new GitClient(clone, origin.toString());
        String branch = run(origin, "git", "branch", "--show-current").get(0);
        String expectedCommit = run(origin, "git", "rev-parse", "HEAD").get(0);

        assertTrue(Files.isDirectory(clone.resolve(".git")));
        assertNull(client.resolveTag("missing"));
        assertEquals(expectedCommit, client.resolveLastCommitBefore(
                "refs/remotes/origin/" + branch, LocalDate.now().plusDays(1)));
        assertNull(client.resolveLastCommitBefore(
                "refs/remotes/origin/" + branch, LocalDate.of(1970, 1, 1)));
    }

    @Test
    void computesNormalizedCommitEntropyAcrossAllChangedFiles() throws Exception {
        run("git", "init");
        run("git", "config", "user.email", "test@example.org");
        run("git", "config", "user.name", "Test");
        Files.writeString(repository.resolve("A.txt"), "one\n");
        Files.writeString(repository.resolve("B.txt"), "one\n");
        run("git", "add", ".");
        run("git", "commit", "-m", "initial");
        Files.writeString(repository.resolve("A.txt"), "two\n");
        Files.writeString(repository.resolve("B.txt"), "two\n");
        run("git", "add", ".");
        run("git", "commit", "-m", "change both");
        String head = run("git", "rev-parse", "HEAD").get(0);

        Map<String, CommitStats> stats = new GitClient(repository).commitStats(List.of(head));

        assertEquals(2, stats.get(head).changedFiles());
        assertEquals(1.0, stats.get(head).normalizedEntropy(), 1.0e-9);
    }

    @Test
    void dateFallbackIncludesCommitsMadeDuringTheReleaseDay() throws Exception {
        run("git", "init");
        run("git", "config", "user.email", "test@example.org");
        run("git", "config", "user.name", "Test");
        Files.writeString(repository.resolve("README.md"), "release\n");
        run("git", "add", ".");
        runAt("2020-01-10T18:00:00Z", "git", "commit", "-m", "release day");
        String expected = run("git", "rev-parse", "HEAD").get(0);

        String actual = new GitClient(repository).resolveLastCommitBefore(
                "HEAD", LocalDate.of(2020, 1, 10));

        assertEquals(expected, actual);
    }

    private List<String> run(String... command) throws Exception {
        return run(repository, command);
    }

    private List<String> run(Path directory, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        Process process = builder.start();
        List<String> output = process.inputReader().lines().toList();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException(String.join("\n", output));
        }
        return output;
    }

    private List<String> runAt(String instant, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_AUTHOR_DATE", instant);
        builder.environment().put("GIT_COMMITTER_DATE", instant);
        Process process = builder.start();
        List<String> output = process.inputReader().lines().toList();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException(String.join("\n", output));
        }
        return output;
    }
}
