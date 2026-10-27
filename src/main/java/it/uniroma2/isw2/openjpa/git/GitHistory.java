package it.uniroma2.isw2.openjpa.git;

import it.uniroma2.isw2.openjpa.model.CommitStats;
import it.uniroma2.isw2.openjpa.model.FixCommit;
import it.uniroma2.isw2.openjpa.model.Touch;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GitHistory {

    private static final Pattern TICKET_KEY = Pattern.compile("(?i)\\bOPENJPA-\\d+\\b");
    private static final String COMMIT_MARKER = "__OPENJPA_COMMIT__";
    private static final String END_MARKER = "__OPENJPA_END__";
    private static final String HISTORY_MARKER = "__OPENJPA_HISTORY__";
    private static final String FORMAT_OPTION = "--format=";
    private static final String NO_MERGES = "--no-merges";
    private static final int METADATA_BATCH_SIZE = 256;

    private final GitClient git;
    private final Set<String> explicitlyExcluded;
    private final Set<String> automaticallyExcluded = new HashSet<>();
    private final Set<String> inspected = new HashSet<>();
    private final Set<String> encounteredExcluded = new HashSet<>();

    GitHistory(GitClient git, Collection<String> excludedCommits) {
        this.git = git;
        this.explicitlyExcluded = excludedCommits.stream()
                .map(hash -> hash.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    List<FixCommit> findFixCommits(Collection<String> knownTickets)
            throws IOException, InterruptedException {
        Set<String> known = knownTickets.stream()
                .map(ticket -> ticket.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        List<String> lines = git.run(GitClient.GIT_EXECUTABLE, "log", "--all",
                FORMAT_OPTION + COMMIT_MARKER + "%H%x09%ct%n%B%n" + END_MARKER);
        List<FixCommit> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LoggedCommit commit : parseFixLog(lines)) {
            registerAdministrativeCommit(commit.hash(), commit.message());
            if (!isExcluded(commit.hash())) {
                addMatchingFixes(commit, known, seen, result);
            }
        }
        return result;
    }

    private static List<LoggedCommit> parseFixLog(List<String> lines) {
        List<LoggedCommit> commits = new ArrayList<>();
        String hash = null;
        Instant time = null;
        StringBuilder message = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith(COMMIT_MARKER)) {
                String[] fields = line.substring(COMMIT_MARKER.length()).split("\\t", 2);
                hash = fields[0].trim();
                time = Instant.ofEpochSecond(Long.parseLong(fields[1].trim()));
                message.setLength(0);
            } else if (line.equals(END_MARKER)) {
                if (hash != null) {
                    commits.add(new LoggedCommit(hash, time, message.toString()));
                }
                hash = null;
            } else if (hash != null) {
                message.append(line).append('\n');
            }
        }
        return commits;
    }

    private static void addMatchingFixes(LoggedCommit commit, Set<String> known,
                                         Set<String> seen, List<FixCommit> result) {
        Matcher matcher = TICKET_KEY.matcher(commit.message());
        while (matcher.find()) {
            String ticket = matcher.group().toUpperCase(Locale.ROOT);
            if (known.contains(ticket) && seen.add(ticket + "#" + commit.hash())) {
                result.add(new FixCommit(ticket, commit.hash(), commit.time()));
            }
        }
    }

    List<Touch> fileHistory(String releaseCommit, String classPath)
            throws IOException, InterruptedException {
        List<String> lines = git.run(GitClient.GIT_EXECUTABLE, "log", "--follow", NO_MERGES,
                "--ignore-space-at-eol", "--ignore-blank-lines", "--numstat",
                FORMAT_OPTION + HISTORY_MARKER + "%H%x09%ct%x09%aE",
                releaseCommit, "--", classPath);
        List<Touch> touches = new ArrayList<>();
        HistoryPoint current = null;

        for (String line : lines) {
            if (line.startsWith(HISTORY_MARKER)) {
                current = parseHistoryPoint(line);
            } else if (current != null && !line.isBlank()) {
                Touch touch = parseTouch(line, current);
                if (touch != null) {
                    touches.add(touch);
                }
                current = null;
            }
        }
        return touches;
    }

    private static HistoryPoint parseHistoryPoint(String line) {
        String[] fields = line.substring(HISTORY_MARKER.length()).split("\\t", 3);
        String author = fields.length > 2 ? fields[2].trim().toLowerCase(Locale.ROOT) : "";
        return new HistoryPoint(fields[0],
                Instant.ofEpochSecond(Long.parseLong(fields[1])), author);
    }

    private Touch parseTouch(String line, HistoryPoint current) {
        String[] fields = line.split("\\t", 3);
        if (fields.length != 3) {
            return null;
        }
        int added = parseNumstat(fields[0]);
        int deleted = parseNumstat(fields[1]);
        if (added + deleted == 0 || isExcluded(current.hash())) {
            return null;
        }
        return new Touch(current.hash(), current.time(), current.author(), added, deleted);
    }

    Set<String> commitsInRange(String currentCommit, String boundaryExclusive)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                GitClient.GIT_EXECUTABLE, "rev-list", NO_MERGES, currentCommit));
        if (boundaryExclusive != null) {
            command.add("^" + boundaryExclusive);
        }

        Set<String> candidates = new LinkedHashSet<>(git.run(command.toArray(String[]::new)));
        discoverAdministrativeCommits(candidates);
        Set<String> result = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (isExcluded(candidate)) {
                encounteredExcluded.add(candidate);
            } else {
                result.add(candidate);
            }
        }
        return result;
    }

    int excludedCommitCount() {
        return encounteredExcluded.size();
    }

    Map<String, CommitStats> commitStats(Collection<String> releaseCommits)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(GitClient.GIT_EXECUTABLE, "log", NO_MERGES,
                "--ignore-space-at-eol", "--ignore-blank-lines",
                FORMAT_OPTION + COMMIT_MARKER + "%H", "--numstat"));
        command.addAll(releaseCommits);

        Map<String, CommitStats> result = new HashMap<>();
        String hash = null;
        Map<String, Integer> touchedByPath = new HashMap<>();
        for (String line : git.run(command.toArray(String[]::new))) {
            if (line.startsWith(COMMIT_MARKER)) {
                addStats(result, hash, touchedByPath);
                hash = line.substring(COMMIT_MARKER.length()).trim();
                touchedByPath = new HashMap<>();
            } else if (!line.isBlank()) {
                String[] fields = line.split("\\t", 3);
                if (fields.length == 3) {
                    int touched = parseNumstat(fields[0]) + parseNumstat(fields[1]);
                    touchedByPath.merge(GitClient.normalizePath(fields[2]), touched, Integer::sum);
                }
            }
        }
        addStats(result, hash, touchedByPath);
        return result;
    }

    boolean isExcluded(String hash) {
        String normalized = hash.toLowerCase(Locale.ROOT);
        return explicitlyExcluded.contains(normalized) || automaticallyExcluded.contains(normalized);
    }

    private void addStats(Map<String, CommitStats> result, String hash,
                          Map<String, Integer> touchedByPath) {
        if (hash != null && !isExcluded(hash)) {
            result.put(hash, toCommitStats(touchedByPath));
        }
    }

    private static CommitStats toCommitStats(Map<String, Integer> touchedByPath) {
        List<Integer> meaningfulTouches = touchedByPath.values().stream()
                .filter(touched -> touched > 0)
                .toList();
        int changedFiles = meaningfulTouches.size();
        int totalTouched = meaningfulTouches.stream().mapToInt(Integer::intValue).sum();
        if (changedFiles <= 1 || totalTouched == 0) {
            return new CommitStats(changedFiles, 0.0);
        }

        double entropy = 0.0;
        for (int touched : meaningfulTouches) {
            double proportion = touched / (double) totalTouched;
            entropy -= proportion * Math.log(proportion);
        }
        return new CommitStats(changedFiles, entropy / Math.log(changedFiles));
    }

    private void discoverAdministrativeCommits(Collection<String> candidates)
            throws IOException, InterruptedException {
        List<String> unknown = candidates.stream()
                .filter(hash -> !inspected.contains(hash))
                .toList();
        for (int start = 0; start < unknown.size(); start += METADATA_BATCH_SIZE) {
            int end = Math.min(start + METADATA_BATCH_SIZE, unknown.size());
            List<String> batch = unknown.subList(start, end);
            List<String> command = new ArrayList<>(List.of(GitClient.GIT_EXECUTABLE, "show", "-s",
                    FORMAT_OPTION + COMMIT_MARKER + "%H%n%B%n" + END_MARKER));
            command.addAll(batch);
            inspectMessages(git.run(command.toArray(String[]::new)));
            inspected.addAll(batch);
        }
    }

    private void inspectMessages(List<String> lines) {
        String hash = null;
        StringBuilder message = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith(COMMIT_MARKER)) {
                hash = line.substring(COMMIT_MARKER.length()).trim();
                message.setLength(0);
            } else if (line.equals(END_MARKER)) {
                if (hash != null) {
                    registerAdministrativeCommit(hash, message.toString());
                }
                hash = null;
            } else if (hash != null) {
                message.append(line).append('\n');
            }
        }
    }

    private void registerAdministrativeCommit(String hash, String message) {
        if (isSvnTagAdministrativeMessage(message)) {
            automaticallyExcluded.add(hash);
        }
    }

    static boolean isSvnTagAdministrativeMessage(String message) {
        return message.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("git-svn-id:"))
                .anyMatch(GitHistory::isSvnTagLine);
    }

    private static boolean isSvnTagLine(String line) {
        String metadata = line.substring("git-svn-id:".length()).trim();
        int separator = metadata.indexOf(' ');
        if (separator < 0) {
            return false;
        }
        String source = metadata.substring(0, separator);
        return source.contains("/tags/") || source.contains("/tags@");
    }

    private static int parseNumstat(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record LoggedCommit(String hash, Instant time, String message) {
    }

    private record HistoryPoint(String hash, Instant time, String author) {
    }
}
