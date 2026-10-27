package it.uniroma2.isw2.openjpa.metrics;

import it.uniroma2.isw2.openjpa.git.GitClient;
import it.uniroma2.isw2.openjpa.labeling.LabelingService;
import it.uniroma2.isw2.openjpa.model.CommitStats;
import it.uniroma2.isw2.openjpa.model.DatasetRow;
import it.uniroma2.isw2.openjpa.model.ProcessMetrics;
import it.uniroma2.isw2.openjpa.model.Release;
import it.uniroma2.isw2.openjpa.model.StaticMetrics;
import it.uniroma2.isw2.openjpa.model.Touch;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class MetricsService {

    private static final double SECONDS_PER_WEEK = 7.0 * 24.0 * 60.0 * 60.0;
    private static final Logger LOGGER = Logger.getLogger(MetricsService.class.getName());

    private final GitClient git;

    public MetricsService(GitClient git) {
        this.git = git;
    }

    public List<DatasetRow> computeRelease(Release release, List<String> classPaths,
                                           Map<String, StaticMetrics> staticMetricsByClass,
                                           Map<String, Integer> smells,
                                           ReleaseMetricsContext context)
            throws IOException, InterruptedException {
        List<DatasetRow> rows = new ArrayList<>();
        Instant releaseTime = release.releaseDate().atStartOfDay().toInstant(ZoneOffset.UTC);

        int completed = 0;
        for (String classPath : classPaths) {
            StaticMetrics staticMetrics = staticMetricsByClass.get(classPath);
            if (staticMetrics == null) {
                throw new IOException("Metriche CK mancanti per " + classPath
                        + " nella release " + release.version());
            }

            List<Touch> history = git.fileHistory(release.commitHash(), classPath);
            ProcessMetrics processMetrics = computeProcessMetrics(history, context.releaseCommits(),
                    context.commitStats(), context.fixCommits(), releaseTime);
            boolean buggy = context.labels().getOrDefault(
                    LabelingService.key(release.index(), classPath), false);
            rows.add(new DatasetRow("OPENJPA", release.version(), classPath,
                    staticMetrics, processMetrics, smells.getOrDefault(classPath, 0), buggy));

            completed++;
            if (completed % 250 == 0) {
                int processed = completed;
                LOGGER.info(() -> String.format(Locale.ROOT, "  metriche %s: %d/%d classi",
                        release.version(), processed, classPaths.size()));
            }
        }
        return rows;
    }

    static ProcessMetrics computeProcessMetrics(List<Touch> cumulativeHistory,
                                                Set<String> releaseCommits,
                                                Map<String, CommitStats> commitStats,
                                                Set<String> fixCommits,
                                                Instant releaseTime) {
        List<Touch> releaseTouches = cumulativeHistory.stream()
                .filter(touch -> releaseCommits.contains(touch.commitHash()))
                .toList();
        int nr = releaseTouches.size();

        Instant birth = cumulativeHistory.stream()
                .map(Touch::time)
                .min(Instant::compareTo)
                .orElse(releaseTime);
        double ageWeeks = Math.max(0.0,
                Duration.between(birth, releaseTime).toSeconds() / SECONDS_PER_WEEK);

        Map<String, Integer> commitsByAuthor = new HashMap<>();
        for (Touch touch : cumulativeHistory) {
            commitsByAuthor.merge(touch.author(), 1, Integer::sum);
        }
        double ownership = cumulativeHistory.isEmpty() ? 0.0
                : commitsByAuthor.values().stream().mapToInt(Integer::intValue).max().orElse(0)
                / (double) cumulativeHistory.size();

        if (nr == 0) {
            return new ProcessMetrics(0, 0, 0, 0, 0, 0, 0.0,
                    0, 0, 0.0, 0, 0, 0.0, ageWeeks, 0.0, 0.0, ownership);
        }

        int locTouched = 0;
        int locAdded = 0;
        int maxLocAdded = 0;
        int churn = 0;
        int maxChurn = Integer.MIN_VALUE;
        int changeSetTotal = 0;
        int maxChangeSet = 0;
        int nFix = 0;
        Set<String> releaseAuthors = new HashSet<>();
        double weightedAgeNumerator = 0.0;
        long weightTotal = 0L;
        double entropyTotal = 0.0;

        for (Touch touch : releaseTouches) {
            int touched = touch.locTouched();
            int touchChurn = touch.churn();
            CommitStats stats = commitStats.getOrDefault(touch.commitHash(), new CommitStats(0, 0.0));
            double ageAtTouch = Math.max(0.0,
                    Duration.between(birth, touch.time()).toSeconds() / SECONDS_PER_WEEK);

            locTouched += touched;
            locAdded += touch.added();
            maxLocAdded = Math.max(maxLocAdded, touch.added());
            churn += touchChurn;
            maxChurn = Math.max(maxChurn, touchChurn);
            changeSetTotal += stats.changedFiles();
            maxChangeSet = Math.max(maxChangeSet, stats.changedFiles());
            entropyTotal += stats.normalizedEntropy();
            releaseAuthors.add(touch.author());
            weightedAgeNumerator += ageAtTouch * touched;
            weightTotal += touched;
            if (fixCommits.contains(touch.commitHash())) {
                nFix++;
            }
        }

        double weightedAge = weightTotal == 0 ? 0.0 : weightedAgeNumerator / weightTotal;
        return new ProcessMetrics(
                locTouched,
                nr,
                nFix,
                releaseAuthors.size(),
                locAdded,
                maxLocAdded,
                locAdded / (double) nr,
                churn,
                maxChurn,
                churn / (double) nr,
                changeSetTotal,
                maxChangeSet,
                changeSetTotal / (double) nr,
                ageWeeks,
                weightedAge,
                entropyTotal / nr,
                ownership
        );
    }

    public record ReleaseMetricsContext(Set<String> releaseCommits,
                                        Map<String, CommitStats> commitStats,
                                        Set<String> fixCommits,
                                        Map<String, Boolean> labels) {
    }
}
