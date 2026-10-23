package it.uniroma2.isw2.openjpa.release;

import it.uniroma2.isw2.openjpa.git.GitClient;
import it.uniroma2.isw2.openjpa.model.RawRelease;
import it.uniroma2.isw2.openjpa.model.Release;
import it.uniroma2.isw2.openjpa.model.ReleaseRange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReleaseService {

    private ReleaseService() {
    }

    public static List<Release> resolveSnapshots(List<RawRelease> jiraReleases,
                                                 List<String> tagPatterns,
                                                 String dateFallbackRef,
                                                 GitClient git) {
        List<RawRelease> sorted = jiraReleases.stream()
                .sorted(Comparator.comparing(RawRelease::releaseDate)
                        .thenComparing(RawRelease::version, ReleaseService::compareVersions))
                .toList();
        List<Release> result = new ArrayList<>();
        Set<String> seenVersions = new HashSet<>();

        for (RawRelease raw : sorted) {
            if (!seenVersions.add(raw.version())) {
                continue;
            }
            String commit = null;
            String snapshotRef = null;
            for (String pattern : tagPatterns) {
                String tag = pattern.replace("${version}", raw.version());
                commit = git.resolveTag(tag);
                if (commit != null) {
                    snapshotRef = "TAG:" + tag;
                    break;
                }
            }
            if (commit == null) {
                commit = git.resolveLastCommitBefore(dateFallbackRef, raw.releaseDate());
                if (commit != null) {
                    snapshotRef = "DATE_FALLBACK:" + dateFallbackRef;
                }
            }
            if (commit != null) {
                result.add(new Release(result.size() + 1, raw.jiraId(), raw.version(),
                        raw.releaseDate(), snapshotRef, commit));
            }
        }
        return List.copyOf(result);
    }

    public static List<Release> selectFirstFraction(List<Release> eligible, double fraction) {
        if (eligible.isEmpty()) {
            throw new IllegalArgumentException("Nessuna release Jira valida possiede uno snapshot Git risolvibile");
        }
        int count = Math.max(1, (int) Math.ceil(eligible.size() * fraction));
        return List.copyOf(eligible.subList(0, Math.min(count, eligible.size())));
    }

    public static List<ReleaseRange> resolveHistoryRanges(List<Release> releases, GitClient git)
            throws java.io.IOException, InterruptedException {
        List<ReleaseRange> result = new ArrayList<>();
        for (int currentIndex = 0; currentIndex < releases.size(); currentIndex++) {
            Release current = releases.get(currentIndex);
            if (currentIndex == 0) {
                result.add(new ReleaseRange(current, null, null,
                        ReleaseRange.BoundaryType.REPOSITORY_ORIGIN));
                continue;
            }

            Release bestPredecessor = null;
            String bestBoundary = null;
            int bestDistance = Integer.MAX_VALUE;
            for (int candidateIndex = 0; candidateIndex < currentIndex; candidateIndex++) {
                Release candidate = releases.get(candidateIndex);
                String boundary = git.mergeBase(candidate.commitHash(), current.commitHash());
                int distance = git.countCommits(boundary, current.commitHash());
                if (distance < bestDistance || distance == bestDistance
                        && (bestPredecessor == null || candidate.index() > bestPredecessor.index())) {
                    bestPredecessor = candidate;
                    bestBoundary = boundary;
                    bestDistance = distance;
                }
            }

            ReleaseRange.BoundaryType type = bestBoundary.equals(bestPredecessor.commitHash())
                    ? ReleaseRange.BoundaryType.PREDECESSOR
                    : ReleaseRange.BoundaryType.MERGE_BASE;
            result.add(new ReleaseRange(current, bestPredecessor, bestBoundary, type));
        }
        return List.copyOf(result);
    }

    static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < 3; i++) {
            int comparison = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }
}
