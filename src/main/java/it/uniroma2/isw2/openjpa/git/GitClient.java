package it.uniroma2.isw2.openjpa.git;

import it.uniroma2.isw2.openjpa.model.FixCommit;
import it.uniroma2.isw2.openjpa.model.CommitStats;
import it.uniroma2.isw2.openjpa.model.SzzClass;
import it.uniroma2.isw2.openjpa.model.Touch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GitClient {

    private static final Pattern TICKET_KEY = Pattern.compile("(?i)\\bOPENJPA-\\d+\\b");
    private static final Pattern HUNK = Pattern.compile("^@@ -(?<start>\\d+)(?:,(?<count>\\d+))? \\+.*$");
    private static final Pattern BLAME_HASH = Pattern.compile("^[0-9a-f]{40}\\s");
    private static final String COMMIT_MARKER = "__ISW2_COMMIT__";
    private static final String END_MARKER = "__ISW2_END__";
    private static final String HISTORY_MARKER = "__ISW2_HISTORY__";

    private final Path repository;

    public GitClient(Path repository) throws IOException, InterruptedException {
        this(repository, null);
    }

    public GitClient(Path repository, String repositoryUrl) throws IOException, InterruptedException {
        this.repository = repository.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.repository)) {
            if (repositoryUrl == null || repositoryUrl.isBlank()) {
                throw new IOException("Repository Git non trovata: " + this.repository);
            }
            cloneRepository(repositoryUrl);
        }
        try {
            run("git", "rev-parse", "--is-inside-work-tree");
        } catch (IOException e) {
            throw new IOException("Il percorso non contiene un repository Git valido: "
                    + this.repository, e);
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
        builder.redirectErrorStream(true);
        Process process = builder.start();
        List<String> lines;
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            lines = reader.lines().toList();
        }
        int exit = process.waitFor();
        if (exit != 0) {
            String detail = String.join("\n", lines.subList(0, Math.min(lines.size(), 20)));
            throw new IOException("Clone Git fallito (" + exit + "): " + repositoryUrl
                    + (detail.isBlank() ? "" : "\n" + detail));
        }
    }

    public String resolveTag(String tag) {
        try {
            List<String> lines = run("git", "rev-parse", "--verify", "refs/tags/" + tag + "^{commit}");
            return lines.isEmpty() ? null : lines.get(0).trim();
        } catch (IOException | InterruptedException e) {
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

    public List<String> listProductionClasses(String commitHash) throws IOException, InterruptedException {
        return run("git", "ls-tree", "-r", "--name-only", commitHash).stream()
                .map(GitClient::normalizePath)
                .filter(GitClient::isProductionJava)
                .sorted()
                .toList();
    }

    public String readFile(String commitHash, String path) throws IOException, InterruptedException {
        return String.join("\n", run("git", "show", commitHash + ":" + path));
    }

    public Instant commitTime(String commitHash) throws IOException, InterruptedException {
        List<String> lines = run("git", "show", "-s", "--format=%ct", commitHash);
        if (lines.isEmpty()) {
            throw new IOException("Timestamp non disponibile per il commit " + commitHash);
        }
        return Instant.ofEpochSecond(Long.parseLong(lines.get(0).trim()));
    }

    public List<FixCommit> findFixCommits(Collection<String> knownTickets)
            throws IOException, InterruptedException {
        Set<String> known = new HashSet<>();
        for (String ticket : knownTickets) {
            known.add(ticket.toUpperCase(Locale.ROOT));
        }

        List<String> lines = run("git", "log", "--all",
                "--format=" + COMMIT_MARKER + "%H%x09%ct%n%B%n" + END_MARKER);
        List<FixCommit> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String currentHash = null;
        Instant currentTime = null;
        StringBuilder message = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith(COMMIT_MARKER)) {
                String[] fields = line.substring(COMMIT_MARKER.length()).split("\\t", 2);
                currentHash = fields[0].trim();
                currentTime = Instant.ofEpochSecond(Long.parseLong(fields[1].trim()));
                message.setLength(0);
            } else if (line.equals(END_MARKER)) {
                if (currentHash != null) {
                    Matcher matcher = TICKET_KEY.matcher(message);
                    while (matcher.find()) {
                        String ticket = matcher.group().toUpperCase(Locale.ROOT);
                        if (known.contains(ticket) && seen.add(ticket + "#" + currentHash)) {
                            result.add(new FixCommit(ticket, currentHash, currentTime));
                        }
                    }
                }
                currentHash = null;
            } else if (currentHash != null) {
                message.append(line).append('\n');
            }
        }
        return result;
    }

    public List<SzzClass> runBasicSzz(String fixCommit) throws IOException, InterruptedException {
        String parent = firstParent(fixCommit);
        if (parent == null) {
            return List.of();
        }

        List<String> diff = run("git", "diff", "--unified=0", "--find-renames", parent, fixCommit, "--");
        Map<String, List<LineRange>> removedRanges = parseRemovedRanges(diff);
        List<SzzClass> result = new ArrayList<>();

        for (Map.Entry<String, List<LineRange>> entry : removedRanges.entrySet()) {
            Set<String> introducing = new LinkedHashSet<>();
            for (LineRange range : entry.getValue()) {
                List<String> blame;
                try {
                    blame = run("git", "blame", "--line-porcelain", "-L",
                            range.start() + "," + range.end(), parent, "--", entry.getKey());
                } catch (IOException e) {
                    continue;
                }
                for (String line : blame) {
                    if (BLAME_HASH.matcher(line).find()) {
                        introducing.add(line.substring(0, 40));
                    }
                }
            }
            if (!introducing.isEmpty()) {
                result.add(new SzzClass(entry.getKey(), introducing));
            }
        }
        return result;
    }

    public List<Touch> fileHistory(String releaseCommit, String classPath)
            throws IOException, InterruptedException {
        List<String> lines = run("git", "log", "--follow", "--no-merges", "--numstat",
                "--format=" + HISTORY_MARKER + "%H%x09%ct%x09%aE", releaseCommit, "--", classPath);
        List<Touch> touches = new ArrayList<>();
        String hash = null;
        Instant time = null;
        String author = "";
        for (String line : lines) {
            if (line.startsWith(HISTORY_MARKER)) {
                String[] fields = line.substring(HISTORY_MARKER.length()).split("\\t", 3);
                hash = fields[0];
                time = Instant.ofEpochSecond(Long.parseLong(fields[1]));
                author = fields.length > 2 ? fields[2].trim().toLowerCase(Locale.ROOT) : "";
            } else if (hash != null && !line.isBlank()) {
                String[] fields = line.split("\\t", 3);
                if (fields.length == 3) {
                    touches.add(new Touch(hash, time, author,
                            parseNumstat(fields[0]), parseNumstat(fields[1])));
                    hash = null;
                }
            }
        }
        return touches;
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
        List<String> command = new ArrayList<>(List.of("git", "rev-list", "--no-merges", currentCommit));
        if (boundaryExclusive != null) {
            command.add("^" + boundaryExclusive);
        }
        return new LinkedHashSet<>(run(command.toArray(String[]::new)));
    }

    public Map<String, CommitStats> commitStats(Collection<String> releaseCommits)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "log", "--no-merges",
                "--format=" + COMMIT_MARKER + "%H", "--numstat"));
        command.addAll(releaseCommits);
        List<String> lines = run(command.toArray(String[]::new));
        Map<String, CommitStats> result = new HashMap<>();
        String current = null;
        Map<String, Integer> touchedByPath = new HashMap<>();
        for (String line : lines) {
            if (line.startsWith(COMMIT_MARKER)) {
                if (current != null) {
                    result.put(current, toCommitStats(touchedByPath));
                }
                current = line.substring(COMMIT_MARKER.length()).trim();
                touchedByPath = new HashMap<>();
            } else if (!line.isBlank()) {
                String[] fields = line.split("\\t", 3);
                if (fields.length == 3) {
                    int touched = parseNumstat(fields[0]) + parseNumstat(fields[1]);
                    touchedByPath.merge(normalizePath(fields[2]), touched, Integer::sum);
                }
            }
        }
        if (current != null) {
            result.put(current, toCommitStats(touchedByPath));
        }
        return result;
    }

    private static CommitStats toCommitStats(Map<String, Integer> touchedByPath) {
        int changedFiles = touchedByPath.size();
        int totalTouched = touchedByPath.values().stream().mapToInt(Integer::intValue).sum();
        if (changedFiles <= 1 || totalTouched == 0) {
            return new CommitStats(changedFiles, 0.0);
        }
        double entropy = 0.0;
        for (int touched : touchedByPath.values()) {
            if (touched <= 0) {
                continue;
            }
            double proportion = touched / (double) totalTouched;
            entropy -= proportion * Math.log(proportion);
        }
        return new CommitStats(changedFiles, entropy / Math.log(changedFiles));
    }

    public Path addWorktree(Path worktree, String commitHash) throws IOException, InterruptedException {
        Files.createDirectories(worktree.toAbsolutePath().getParent());
        run("git", "worktree", "add", "--detach", "--force", worktree.toAbsolutePath().toString(), commitHash);
        return worktree.toAbsolutePath();
    }

    public void removeWorktree(Path worktree) throws IOException, InterruptedException {
        run("git", "worktree", "remove", "--force", worktree.toAbsolutePath().toString());
    }

    private String firstParent(String commitHash) throws IOException, InterruptedException {
        List<String> lines = run("git", "rev-list", "--parents", "-n", "1", commitHash);
        if (lines.isEmpty()) {
            return null;
        }
        String[] fields = lines.get(0).trim().split("\\s+");
        return fields.length >= 2 ? fields[1] : null;
    }

    private Map<String, List<LineRange>> parseRemovedRanges(List<String> lines) {
        Map<String, List<LineRange>> result = new LinkedHashMap<>();
        String oldPath = null;
        boolean production = false;
        for (String line : lines) {
            if (line.startsWith("--- ")) {
                oldPath = stripDiffPrefix(line.substring(4).trim());
                production = oldPath != null && isProductionJava(oldPath);
            } else if (production && line.startsWith("@@")) {
                Matcher matcher = HUNK.matcher(line);
                if (matcher.matches()) {
                    int start = Integer.parseInt(matcher.group("start"));
                    int count = matcher.group("count") == null ? 1 : Integer.parseInt(matcher.group("count"));
                    if (count > 0) {
                        result.computeIfAbsent(oldPath, ignored -> new ArrayList<>())
                                .add(new LineRange(start, start + count - 1));
                    }
                }
            }
        }
        return result;
    }

    private static String stripDiffPrefix(String value) {
        if (value.equals("/dev/null")) {
            return null;
        }
        return normalizePath(value.startsWith("a/") || value.startsWith("b/") ? value.substring(2) : value);
    }

    static boolean isProductionJava(String path) {
        String normalized = normalizePath(path).toLowerCase(Locale.ROOT);
        return normalized.endsWith(".java")
                && (normalized.startsWith("src/main/java/") || normalized.contains("/src/main/java/"))
                && !normalized.contains("/src/test/")
                && !normalized.contains("/target/")
                && !normalized.contains("/generated/");
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/').trim();
    }

    private int parseNumstat(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<String> run(String... command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repository.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        List<String> lines;
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            lines = reader.lines().toList();
        }
        int exit = process.waitFor();
        if (exit != 0) {
            String detail = String.join("\n", lines.subList(0, Math.min(lines.size(), 20)));
            throw new IOException("Comando Git fallito (" + exit + "): "
                    + String.join(" ", command) + (detail.isBlank() ? "" : "\n" + detail));
        }
        return lines;
    }

    private record LineRange(int start, int end) {
    }
}
