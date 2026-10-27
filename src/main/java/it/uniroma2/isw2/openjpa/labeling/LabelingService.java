package it.uniroma2.isw2.openjpa.labeling;

import it.uniroma2.isw2.openjpa.git.GitClient;
import it.uniroma2.isw2.openjpa.model.FixCommit;
import it.uniroma2.isw2.openjpa.model.LabelingResult;
import it.uniroma2.isw2.openjpa.model.Release;
import it.uniroma2.isw2.openjpa.model.SzzClass;
import it.uniroma2.isw2.openjpa.model.Ticket;
import it.uniroma2.isw2.openjpa.model.TicketWindow;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LabelingService {

    private static final Logger LOGGER = Logger.getLogger(LabelingService.class.getName());

    public LabelingResult label(List<Release> allReleases,
                                List<Release> selectedReleases,
                                List<Ticket> tickets,
                                Map<Integer, List<String>> inventory,
                                GitClient git) throws IOException, InterruptedException {
        List<TicketWindow> windows = resolveWindows(tickets, allReleases);
        Map<String, TicketWindow> windowByTicket = indexWindows(windows);
        Map<String, Ticket> ticketByKey = indexTickets(tickets);
        FixLinks fixLinks = linkFixCommits(git, ticketByKey);
        Map<String, Boolean> labels = initializeLabels(selectedReleases, inventory);
        applyBuggyLabels(labels, fixLinks.classesByTicket(), windowByTicket,
                selectedReleases);

        return new LabelingResult(labels, fixLinks.resolutionByCommit(), windows,
                fixLinks.linkedCommitCount(), fixLinks.szzClassCount());
    }

    private static Map<String, TicketWindow> indexWindows(List<TicketWindow> windows) {
        Map<String, TicketWindow> result = new HashMap<>();
        for (TicketWindow window : windows) {
            result.put(window.ticketKey(), window);
        }
        return result;
    }

    private static Map<String, Ticket> indexTickets(List<Ticket> tickets) {
        Map<String, Ticket> result = new HashMap<>();
        for (Ticket ticket : tickets) {
            result.put(ticket.key(), ticket);
        }
        return result;
    }

    private static FixLinks linkFixCommits(GitClient git, Map<String, Ticket> ticketByKey)
            throws IOException, InterruptedException {
        List<FixCommit> fixCommits = git.findFixCommits(ticketByKey.keySet());
        Map<String, Set<String>> classesByTicket = new HashMap<>();
        Map<String, Instant> fixResolutionByCommit = new HashMap<>();
        int szzClassCount = 0;
        for (FixCommit fix : fixCommits) {
            Ticket ticket = ticketByKey.get(fix.ticketKey());
            if (ticket != null) {
                szzClassCount += linkFix(git, fix, ticket, classesByTicket,
                        fixResolutionByCommit);
            }
        }
        return new FixLinks(classesByTicket, fixResolutionByCommit,
                fixCommits.size(), szzClassCount);
    }

    private static int linkFix(GitClient git, FixCommit fix, Ticket ticket,
                               Map<String, Set<String>> classesByTicket,
                               Map<String, Instant> fixResolutionByCommit)
            throws InterruptedException {
        fixResolutionByCommit.merge(fix.commitHash(), ticket.resolvedAt(),
                (left, right) -> left.isBefore(right) ? left : right);
        try {
            List<SzzClass> classes = git.runBasicSzz(fix.commitHash());
            for (SzzClass szzClass : classes) {
                classesByTicket.computeIfAbsent(fix.ticketKey(), ignored -> new LinkedHashSet<>())
                        .add(szzClass.classPath());
            }
            return classes.size();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e, () -> "SZZ ignorato per commit "
                    + fix.commitHash() + ": " + e.getMessage());
            return 0;
        }
    }

    private static Map<String, Boolean> initializeLabels(List<Release> selectedReleases,
                                                          Map<Integer, List<String>> inventory) {
        Map<String, Boolean> labels = new LinkedHashMap<>();
        for (Release release : selectedReleases) {
            for (String path : inventory.getOrDefault(release.index(), List.of())) {
                labels.put(key(release.index(), path), false);
            }
        }
        return labels;
    }

    private static void applyBuggyLabels(Map<String, Boolean> labels,
                                         Map<String, Set<String>> classesByTicket,
                                         Map<String, TicketWindow> windowByTicket,
                                         List<Release> selectedReleases) {
        Set<Integer> selectedIds = new HashSet<>();
        for (Release release : selectedReleases) {
            selectedIds.add(release.index());
        }
        classesByTicket.forEach((ticketKey, paths) -> {
            TicketWindow window = windowByTicket.get(ticketKey);
            if (window != null) {
                applyWindow(labels, paths, window, selectedIds);
            }
        });
    }

    private static void applyWindow(Map<String, Boolean> labels, Set<String> paths,
                                    TicketWindow window, Set<Integer> selectedIds) {
        for (int releaseIndex = window.injectedVersion();
             releaseIndex < window.fixedVersion(); releaseIndex++) {
            if (selectedIds.contains(releaseIndex)) {
                for (String path : paths) {
                    labels.computeIfPresent(key(releaseIndex, path), (ignored, value) -> true);
                }
            }
        }
    }

    /**
     * Proportion Total: calcola una sola media sui ticket con IV nota e la usa per
     * stimare tutti i ticket senza una affected version valida.
     */
    public static List<TicketWindow> resolveWindows(List<Ticket> tickets,
                                                     List<Release> releases) {
        Map<String, Release> byVersion = indexReleases(releases);
        List<Candidate> candidates = buildCandidates(tickets, releases, byVersion);
        double totalProportion = totalProportion(candidates);
        return buildWindows(candidates, totalProportion);
    }

    private static Map<String, Release> indexReleases(List<Release> releases) {
        Map<String, Release> byVersion = new HashMap<>();
        for (Release release : releases) {
            byVersion.put(release.version(), release);
        }
        return byVersion;
    }

    private static List<Candidate> buildCandidates(List<Ticket> tickets,
                                                    List<Release> releases,
                                                    Map<String, Release> byVersion) {
        List<Candidate> candidates = new ArrayList<>();
        for (Ticket ticket : tickets) {
            Integer ov = openingVersion(ticket, releases);
            Integer fv = fixedVersion(ticket, releases, byVersion);
            if (ov != null && fv != null && fv >= 1) {
                candidates.add(new Candidate(ticket, ov, fv,
                        affectedVersion(ticket, byVersion, ov, fv)));
            }
        }
        return candidates;
    }

    private static double totalProportion(List<Candidate> candidates) {
        double proportionSum = 0.0;
        int proportionCount = 0;
        for (Candidate candidate : candidates) {
            if (candidate.knownIv() != null && candidate.fv() != candidate.ov()) {
                double p = (double) (candidate.fv() - candidate.knownIv())
                        / (candidate.fv() - candidate.ov());
                if (Double.isFinite(p)) {
                    proportionSum += p;
                    proportionCount++;
                }
            }
        }

        if (proportionCount == 0) {
            throw new IllegalStateException("Nessun ticket con IV nota disponibile per Proportion Total");
        }
        return proportionSum / proportionCount;
    }

    private static List<TicketWindow> buildWindows(List<Candidate> candidates,
                                                   double totalProportion) {
        List<TicketWindow> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            boolean estimated = candidate.knownIv() == null;
            int iv = estimated
                    ? estimateInjectedVersion(candidate.ov(), candidate.fv(), totalProportion)
                    : candidate.knownIv();
            if (iv <= candidate.fv()) {
                result.add(new TicketWindow(candidate.ticket().key(), iv, candidate.ov(),
                        candidate.fv(), estimated, totalProportion));
            }
        }
        return result;
    }

    private static int estimateInjectedVersion(int ov, int fv, double proportion) {
        int estimated = (int) Math.round(fv - (fv - ov) * proportion);
        return Math.max(1, Math.min(estimated, ov));
    }

    private static Integer openingVersion(Ticket ticket, List<Release> releases) {
        var openingDate = ticket.createdAt().atZone(ZoneOffset.UTC).toLocalDate();
        Integer result = null;
        for (Release release : releases) {
            if (!release.releaseDate().isAfter(openingDate)) {
                result = release.index();
            } else {
                break;
            }
        }
        return result;
    }

    private static Integer fixedVersion(Ticket ticket, List<Release> releases,
                                        Map<String, Release> byVersion) {
        Integer explicit = ticket.fixVersions().stream()
                .map(byVersion::get)
                .filter(java.util.Objects::nonNull)
                .map(Release::index)
                .min(Integer::compareTo)
                .orElse(null);
        if (explicit != null) {
            return explicit;
        }
        var resolutionDate = ticket.resolvedAt().atZone(ZoneOffset.UTC).toLocalDate();
        return releases.stream()
                .filter(release -> !release.releaseDate().isBefore(resolutionDate))
                .map(Release::index)
                .findFirst()
                .orElse(null);
    }

    private static Integer affectedVersion(Ticket ticket, Map<String, Release> byVersion,
                                           int ov, int fv) {
        return ticket.affectedVersions().stream()
                .map(byVersion::get)
                .filter(java.util.Objects::nonNull)
                .map(Release::index)
                .filter(index -> index <= ov && index < fv)
                .min(Integer::compareTo)
                .orElse(null);
    }

    public static String key(int releaseIndex, String classPath) {
        return releaseIndex + "#" + classPath;
    }

    private record Candidate(Ticket ticket, int ov, int fv, Integer knownIv) {
    }

    private record FixLinks(Map<String, Set<String>> classesByTicket,
                            Map<String, Instant> resolutionByCommit,
                            int linkedCommitCount, int szzClassCount) {
    }
}
