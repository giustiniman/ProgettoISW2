package it.uniroma2.isw2.openjpa.git;

import it.uniroma2.isw2.openjpa.model.SzzClass;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BasicSzz {

    private static final Pattern HUNK =
            Pattern.compile("^@@ -(?<start>\\d+)(?:,(?<count>\\d+))? \\+.*$");
    private static final Pattern BLAME_HASH = Pattern.compile("^[0-9a-f]{40}\\s");

    private BasicSzz() {
    }

    static List<SzzClass> analyze(GitClient git, String fixCommit)
            throws IOException, InterruptedException {
        if (git.isExcludedCommit(fixCommit)) {
            return List.of();
        }
        String parent = git.firstParent(fixCommit);
        if (parent == null) {
            return List.of();
        }

        List<String> diff = git.run("git", "diff", "--unified=0", "--find-renames",
                "--ignore-space-at-eol", "--ignore-blank-lines", parent, fixCommit, "--");
        List<SzzClass> result = new ArrayList<>();
        for (Map.Entry<String, List<LineRange>> entry : parseRemovedRanges(diff).entrySet()) {
            Set<String> introducing = blame(git, parent, entry.getKey(), entry.getValue());
            if (!introducing.isEmpty()) {
                result.add(new SzzClass(entry.getKey(), introducing));
            }
        }
        return result;
    }

    private static Set<String> blame(GitClient git, String parent, String path,
                                     List<LineRange> ranges) throws InterruptedException {
        Set<String> introducing = new LinkedHashSet<>();
        for (LineRange range : ranges) {
            try {
                List<String> lines = git.run("git", "blame", "--line-porcelain", "-L",
                        range.start() + "," + range.end(), parent, "--", path);
                for (String line : lines) {
                    if (BLAME_HASH.matcher(line).find()) {
                        introducing.add(line.substring(0, 40));
                    }
                }
            } catch (IOException ignored) {
                // Il file potrebbe non essere raggiungibile attraverso rename complessi.
            }
        }
        return introducing;
    }

    private static Map<String, List<LineRange>> parseRemovedRanges(List<String> lines) {
        Map<String, List<LineRange>> result = new LinkedHashMap<>();
        String oldPath = null;
        boolean production = false;
        for (String line : lines) {
            if (line.startsWith("--- ")) {
                oldPath = stripDiffPrefix(line.substring(4).trim());
                production = oldPath != null && GitClient.isProductionJava(oldPath);
            } else if (production && line.startsWith("@@")) {
                Matcher matcher = HUNK.matcher(line);
                if (matcher.matches()) {
                    int start = Integer.parseInt(matcher.group("start"));
                    int count = matcher.group("count") == null
                            ? 1 : Integer.parseInt(matcher.group("count"));
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
        return GitClient.normalizePath(
                value.startsWith("a/") || value.startsWith("b/") ? value.substring(2) : value);
    }

    private record LineRange(int start, int end) {
    }
}
