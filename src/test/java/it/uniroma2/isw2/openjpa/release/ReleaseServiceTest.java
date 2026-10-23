package it.uniroma2.isw2.openjpa.release;

import it.uniroma2.isw2.openjpa.git.GitClient;
import it.uniroma2.isw2.openjpa.model.RawRelease;
import it.uniroma2.isw2.openjpa.model.Release;
import it.uniroma2.isw2.openjpa.model.ReleaseRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReleaseServiceTest {

    @TempDir
    Path repository;

    @Test
    void selectsCeilingOfFirstThird() {
        List<Release> releases = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(index -> new Release(index, "j" + index, "1.0." + index,
                        LocalDate.of(2020, 1, 1).plusDays(index), "tag", "hash"))
                .toList();

        List<Release> selected = ReleaseService.selectFirstFraction(releases, 1.0 / 3.0);

        assertEquals(4, selected.size());
        assertEquals("1.0.4", selected.get(3).version());
    }

    @Test
    void comparesSemanticVersionsNumerically() {
        assertEquals(-1, Integer.signum(ReleaseService.compareVersions("2.9.0", "2.10.0")));
        assertEquals(0, ReleaseService.compareVersions("3.2.1", "3.2.1"));
    }

    @Test
    void prefersTagAndFallsBackToCommitBeforeReleaseDate() throws Exception {
        run("git", "init");
        run("git", "config", "user.email", "test@example.org");
        run("git", "config", "user.name", "Test");
        Files.writeString(repository.resolve("README.md"), "fixture\n");
        run("git", "add", ".");
        run("git", "commit", "-m", "initial");
        run("git", "tag", "1.0.0");

        LocalDate releaseDate = LocalDate.now().plusDays(1);
        List<Release> releases = ReleaseService.resolveSnapshots(
                List.of(
                        new RawRelease("1", "0.9.0", releaseDate),
                        new RawRelease("2", "1.0.0", releaseDate)
                ),
                List.of("${version}"),
                "HEAD",
                new GitClient(repository));

        assertEquals(2, releases.size());
        assertEquals("DATE_FALLBACK:HEAD", releases.get(0).snapshotRef());
        assertEquals("TAG:1.0.0", releases.get(1).snapshotRef());
        assertEquals(releases.get(0).commitHash(), releases.get(1).commitHash());
    }

    @Test
    void usesMergeBaseWhenReleaseHistoriesDiverge() throws Exception {
        run("git", "init");
        run("git", "config", "user.email", "test@example.org");
        run("git", "config", "user.name", "Test");
        Files.writeString(repository.resolve("base.txt"), "base\n");
        run("git", "add", ".");
        run("git", "commit", "-m", "base");
        String base = run("git", "rev-parse", "HEAD").get(0);

        run("git", "checkout", "-b", "previous");
        Files.writeString(repository.resolve("previous.txt"), "previous\n");
        run("git", "add", ".");
        run("git", "commit", "-m", "previous release");
        String previous = run("git", "rev-parse", "HEAD").get(0);

        run("git", "checkout", "-b", "current", base);
        Files.writeString(repository.resolve("current.txt"), "current\n");
        run("git", "add", ".");
        run("git", "commit", "-m", "current release");
        String current = run("git", "rev-parse", "HEAD").get(0);

        List<ReleaseRange> ranges = ReleaseService.resolveHistoryRanges(List.of(
                new Release(1, "1", "1.0.0", LocalDate.of(2020, 1, 1), "tag", previous),
                new Release(2, "2", "1.1.0", LocalDate.of(2020, 2, 1), "tag", current)
        ), new GitClient(repository));

        assertEquals(ReleaseRange.BoundaryType.MERGE_BASE, ranges.get(1).boundaryType());
        assertEquals(base, ranges.get(1).boundaryCommit());
        assertEquals("1.0.0", ranges.get(1).predecessor().version());
    }

    private List<String> run(String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(repository.toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        List<String> output = process.inputReader().lines().toList();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException(String.join("\n", output));
        }
        return output;
    }
}
