package it.uniroma2.isw2.openjpa.git;

import it.uniroma2.isw2.openjpa.model.CommitStats;
import it.uniroma2.isw2.openjpa.model.FixCommit;
import it.uniroma2.isw2.openjpa.model.SzzClass;
import it.uniroma2.isw2.openjpa.model.Touch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class GitClient {

    private final Path repository;
    private final GitHistory history;

    public GitClient(Path repository) throws IOException, InterruptedException {
        this(repository, null);
    }

    public GitClient(Path repository, String repositoryUrl) throws IOException, InterruptedException {
        this(repository, repositoryUrl, Set.of());
    }

    public GitClient(Path repository, String repositoryUrl, Collection<String> excludedCommits)
            throws IOException, InterruptedException {
        this.repository = repository.toAbsolutePath().normalize();
        this.history = new GitHistory(this, excludedCommits);
        ensureRepository(repositoryUrl);
    }

    private void ensureRepository(String repositoryUrl) throws IOException, InterruptedException {
        if (!Files.isDirectory(repository)) {
            if (repositoryUrl == null || repositoryUrl.isBlank()) {
                throw new IOException("Repository Git non trovata: " + repository);
            }
            cloneRepository(repositoryUrl);
        }
        try {
            run("git", "rev-parse", "--is-inside-work-tree");
        } catch (IOException e) {
            throw new IOException("Il percorso non contiene un repository Git valido: "
                    + repository, e);
        }
    }

    private void cloneRepository(String repositoryUrl) throws IOException, InterruptedException {
        Path parent = repository.getParent();
        if (parent == null) {
            throw new IOException("Percorso repository senza cartella padre: " + repository);
        }
        Files.createDirectories(parent);
        System.out.printf("Repository OpenJPA assente. Clone da %s in %s...%n",
                repositoryUrl, repository);

        ProcessBuilder builder = new ProcessBuilder(
                "git", "clone", "--no-checkout", repositoryUrl, repository.toString());
        builder.directory(parent.toFile());
        execute(builder, "Clone Git fallito: " + repositoryUrl);
        if (!Files.isDirectory(repository)) {
            throw new IOException("Clone Git completato senza creare il repository: " + repository);
        }
    }

    public String resolveTag(String tag) {
        try {
            List<String> lines = run(
                    "git", "rev-parse", "--verify", "refs/tags/" + tag + "^{commit}");
            return lines.isEmpty() ? null : lines.get(0).trim();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public String resolveLastCommitBefore(String ref, LocalDate releaseDate) {
        String cutoff = releaseDate.plusDays(1) + "T00:00:00Z";
        try {
            List<String> lines = run("git", "rev-list", "-1", "--before=" + cutoff, ref);
            return lines.isEmpty() ? null : lines.get(0).trim();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public List<String> listProductionClasses(String commitHash)
            throws IOException, InterruptedException {
        return run("git", "ls-tree", "-r", "--name-only", commitHash).stream()
                .map(GitClient::normalizePath)
                .filter(GitClient::isProductionJava)
                .sorted()
                .toList();
    }

    public List<FixCommit> findFixCommits(Collection<String> knownTickets)
            throws IOException, InterruptedException {
        return history.findFixCommits(knownTickets);
    }

    public List<SzzClass> runBasicSzz(String fixCommit)
            throws IOException, InterruptedException {
        return BasicSzz.analyze(this, fixCommit);
    }

    public List<Touch> fileHistory(String releaseCommit, String classPath)
            throws IOException, InterruptedException {
        return history.fileHistory(releaseCommit, classPath);
    }

    public String mergeBase(String leftCommit, String rightCommit)
            throws IOException, InterruptedException {
        List<String> lines = run("git", "merge-base", leftCommit, rightCommit);
        if (lines.isEmpty()) {
            throw new IOException("Merge base non trovato tra " + leftCommit + " e " + rightCommit);
        }
        return lines.get(0).trim();
    }

    public int countCommits(String boundaryExclusive, String currentCommit)
            throws IOException, InterruptedException {
        List<String> lines = run("git", "rev-list", "--count", "--no-merges",
                boundaryExclusive + ".." + currentCommit);
        return Integer.parseInt(lines.get(0).trim());
    }

    public Set<String> commitsInRange(String currentCommit, String boundaryExclusive)
            throws IOException, InterruptedException {
        return history.commitsInRange(currentCommit, boundaryExclusive);
    }

    public int excludedCommitCount() {
        return history.excludedCommitCount();
    }

    public Map<String, CommitStats> commitStats(Collection<String> releaseCommits)
            throws IOException, InterruptedException {
        return history.commitStats(releaseCommits);
    }

    public Path addWorktree(Path worktree, String commitHash)
            throws IOException, InterruptedException {
        Files.createDirectories(worktree.toAbsolutePath().getParent());
        run("git", "worktree", "add", "--detach", "--force",
                worktree.toAbsolutePath().toString(), commitHash);
        return worktree.toAbsolutePath();
    }

    public void removeWorktree(Path worktree) throws IOException, InterruptedException {
        run("git", "worktree", "remove", "--force", worktree.toAbsolutePath().toString());
    }

    String firstParent(String commitHash) throws IOException, InterruptedException {
        List<String> lines = run("git", "rev-list", "--parents", "-n", "1", commitHash);
        if (lines.isEmpty()) {
            return null;
        }
        String[] fields = lines.get(0).trim().split("\\s+");
        return fields.length >= 2 ? fields[1] : null;
    }

    boolean isExcludedCommit(String hash) {
        return history.isExcluded(hash);
    }

    static boolean isSvnTagAdministrativeMessage(String message) {
        return GitHistory.isSvnTagAdministrativeMessage(message);
    }

    static boolean isProductionJava(String path) {
        String normalized = normalizePath(path).toLowerCase(Locale.ROOT);
        return normalized.endsWith(".java")
                && (normalized.startsWith("src/main/java/")
                || normalized.contains("/src/main/java/"))
                && !normalized.contains("/src/test/")
                && !normalized.contains("/target/")
                && !normalized.contains("/generated/");
    }

    static String normalizePath(String path) {
        return path.replace('\\', '/').trim();
    }

    List<String> run(String... command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repository.toFile());
        return execute(builder, "Comando Git fallito: " + String.join(" ", command));
    }

    private static List<String> execute(ProcessBuilder builder, String failureMessage)
            throws IOException, InterruptedException {
        builder.redirectErrorStream(true);
        Process process = builder.start();
        List<String> output;
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            output = reader.lines().toList();
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException(failureMessage + " (" + exit + ")" + formatDetail(output));
        }
        return output;
    }

    private static String formatDetail(List<String> output) {
        String detail = String.join("\n", output.subList(0, Math.min(output.size(), 20)));
        return detail.isBlank() ? "" : "\n" + detail;
    }
}
