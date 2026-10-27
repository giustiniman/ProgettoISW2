package it.uniroma2.isw2.openjpa.app;

import it.uniroma2.isw2.openjpa.config.Config;
import it.uniroma2.isw2.openjpa.export.CsvExporter;
import it.uniroma2.isw2.openjpa.git.GitClient;
import it.uniroma2.isw2.openjpa.jira.JiraClient;
import it.uniroma2.isw2.openjpa.labeling.LabelingService;
import it.uniroma2.isw2.openjpa.metrics.CkMetricsCalculator;
import it.uniroma2.isw2.openjpa.metrics.MetricsService;
import it.uniroma2.isw2.openjpa.model.CommitStats;
import it.uniroma2.isw2.openjpa.model.DatasetRow;
import it.uniroma2.isw2.openjpa.model.LabelingResult;
import it.uniroma2.isw2.openjpa.model.RawRelease;
import it.uniroma2.isw2.openjpa.model.Release;
import it.uniroma2.isw2.openjpa.model.ReleaseRange;
import it.uniroma2.isw2.openjpa.model.StaticMetrics;
import it.uniroma2.isw2.openjpa.model.Ticket;
import it.uniroma2.isw2.openjpa.model.TicketWindow;
import it.uniroma2.isw2.openjpa.release.ReleaseService;
import it.uniroma2.isw2.openjpa.smell.SmellCounter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Main {

    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Instant started = Instant.now();
        try {
            Config config = Config.load();
            Files.createDirectories(config.workDirectory());
            GitClient git = new GitClient(config.repository(), config.repositoryUrl(),
                    config.excludedCommits());
            JiraClient jira = new JiraClient(config.jiraBaseUrl(), config.jiraProject());

            LOGGER.info("Recupero release e ticket Jira di OPENJPA...");
            List<RawRelease> jiraReleases = jira.fetchReleasedSemanticVersions();
            List<Ticket> tickets = jira.fetchFixedBugs();

            List<Release> eligible = ReleaseService.resolveSnapshots(
                    jiraReleases, config.tagPatterns(), config.dateFallbackRef(), git);
            List<Release> selected = ReleaseService.selectFirstFraction(eligible, config.releaseFraction());
            List<ReleaseRange> ranges = ReleaseService.resolveHistoryRanges(selected, git);
            printReleasePlan(eligible.size(), selected.size(), ranges);

            Map<Integer, ReleaseData> releaseData = analyzeReleases(selected, config, git);
            Map<Integer, List<String>> inventory = inventoryOf(releaseData);

            LOGGER.info("Link Jira/Git, SZZ e Proportion Total...");
            LabelingResult labeling = new LabelingService().label(
                    eligible, selected, tickets, inventory, git);
            long buggyLabels = labeling.labels().values().stream().filter(Boolean::booleanValue).count();
            long estimatedWindows = labeling.windows().stream().filter(TicketWindow::estimated).count();
            double totalProportion = labeling.windows().get(0).proportionUsed();
            LOGGER.info(() -> String.format(Locale.ROOT,
                    "Proportion Total: %.6f; ticket con IV nota: %d; IV stimate: %d",
                    totalProportion, labeling.windows().size() - estimatedWindows, estimatedWindows));
            LOGGER.info(() -> String.format(Locale.ROOT,
                    "Fix commit collegati: %d; classi SZZ: %d; etichette Buggy: %d",
                    labeling.linkedFixCommits(), labeling.szzClasses(), buggyLabels));

            Map<Integer, Set<String>> releaseCommits = new LinkedHashMap<>();
            for (ReleaseRange range : ranges) {
                releaseCommits.put(range.current().index(), git.commitsInRange(
                        range.current().commitHash(), range.boundaryCommit()));
            }
            LOGGER.info(() -> "Commit amministrativi/meccanici esclusi dalle metriche: "
                    + git.excludedCommitCount());
            Map<String, CommitStats> commitStats = git.commitStats(
                    selected.stream().map(Release::commitHash).toList());
            MetricsService metrics = new MetricsService(git);
            List<DatasetRow> rows = new ArrayList<>();

            for (Release release : selected) {
                LOGGER.info(() -> "Metriche release " + release.version() + "...");
                ReleaseData data = releaseData.get(release.index());
                MetricsService.ReleaseMetricsContext context = new MetricsService.ReleaseMetricsContext(
                        releaseCommits.get(release.index()), commitStats,
                        labeling.fixResolutionByCommit().keySet(), labeling.labels());
                rows.addAll(metrics.computeRelease(release, data.classes(), data.staticMetrics(),
                        data.smells(), context));
            }

            new CsvExporter().write(config.outputCsv(), rows);
            long elapsedSeconds = Duration.between(started, Instant.now()).toSeconds();
            LOGGER.info(() -> String.format(Locale.ROOT, "Dataset scritto: %s (%d righe, %d s)",
                    config.outputCsv(), rows.size(), elapsedSeconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.SEVERE, e, () -> "Milestone 1 interrotta: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e, () -> "Milestone 1 interrotta: " + e.getMessage());
            throw e;
        }
    }

    private static void printReleasePlan(int eligibleCount, int selectedCount,
                                         List<ReleaseRange> ranges) {
        LOGGER.info(() -> String.format(Locale.ROOT,
                "Release Jira valide con snapshot: %d; selezionate: %d",
                eligibleCount, selectedCount));
        for (ReleaseRange range : ranges) {
            Release release = range.current();
            String predecessor = range.predecessor() == null
                    ? "origine" : range.predecessor().version();
            LOGGER.info(() -> String.format(Locale.ROOT, "  %d  %s  %s  %s  intervallo=%s:%s",
                    release.index(), release.version(), release.releaseDate(), release.snapshotRef(),
                    range.boundaryType(), predecessor));
        }
    }

    private static Map<Integer, ReleaseData> analyzeReleases(List<Release> releases,
                                                              Config config,
                                                              GitClient git)
            throws IOException, InterruptedException {
        Map<Integer, ReleaseData> result = new LinkedHashMap<>();
        CkMetricsCalculator ckMetrics = new CkMetricsCalculator();
        SmellCounter smellCounter = new SmellCounter();
        for (Release release : releases) {
            result.put(release.index(), analyzeRelease(
                    release, config.workDirectory(), git, ckMetrics, smellCounter));
        }
        return result;
    }

    private static ReleaseData analyzeRelease(Release release, Path workDirectory,
                                              GitClient git,
                                              CkMetricsCalculator ckMetrics,
                                              SmellCounter smellCounter)
            throws IOException, InterruptedException {
        List<String> javaFiles = git.listProductionClasses(release.commitHash());
        Path worktree = workDirectory.resolve(
                "release-" + release.index() + "-" + System.nanoTime());
        git.addWorktree(worktree, release.commitHash());
        try {
            Map<String, StaticMetrics> staticMetrics = ckMetrics.compute(worktree, javaFiles);
            List<String> classes = javaFiles.stream()
                    .filter(staticMetrics::containsKey)
                    .toList();
            Map<String, Integer> smells = smellCounter.count(worktree, classes);
            LOGGER.info(() -> String.format(Locale.ROOT,
                    "Inventario %s: %d classi Java di produzione"
                            + " (%d annotation type esclusi)",
                    release.version(), classes.size(), javaFiles.size() - classes.size()));
            return new ReleaseData(classes, staticMetrics, smells);
        } finally {
            git.removeWorktree(worktree);
        }
    }

    private static Map<Integer, List<String>> inventoryOf(
            Map<Integer, ReleaseData> releaseData) {
        Map<Integer, List<String>> inventory = new LinkedHashMap<>();
        releaseData.forEach((index, data) -> inventory.put(index, data.classes()));
        return inventory;
    }

    private record ReleaseData(List<String> classes,
                               Map<String, StaticMetrics> staticMetrics,
                               Map<String, Integer> smells) {
    }
}
