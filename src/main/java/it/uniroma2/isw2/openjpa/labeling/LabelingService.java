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

public final class LabelingService {

    public LabelingResult label(List<Release> allReleases,
                                List<Release> selectedReleases,
                                List<Ticket> tickets,
                                Map<Integer, List<String>> inventory,
                                GitClient git) throws IOException, InterruptedException {
        List<TicketWindow> windows = resolveWindows(tickets, allReleases);
        Map<String, TicketWindow> windowByTicket = new HashMap<>();
        for (TicketWindow window : windows) {
            windowByTicket.put(window.ticketKey(), window);
        }

        Map<String, Ticket> ticketByKey = new HashMap<>();
        for (Ticket ticket : tickets) {
            ticketByKey.put(ticket.key(), ticket);
        }

        List<FixCommit> fixCommits = git.findFixCommits(ticketByKey.keySet());
        Map<String, Set<String>> classesByTicket = new HashMap<>();
        Map<String, Instant> fixResolutionByCommit = new HashMap<>();
        int szzClassCount = 0;

        for (FixCommit fix : fixCommits) {
            Ticket ticket = ticketByKey.get(fix.ticketKey());
            if (ticket == null) {
                continue;
            }
            fixResolutionByCommit.merge(fix.commitHash(), ticket.resolvedAt(),
                    (left, right) -> left.isBefore(right) ? left : right);
            try {
                List<SzzClass> classes = git.runBasicSzz(fix.commitHash());
                for (SzzClass szzClass : classes) {
                    classesByTicket.computeIfAbsent(fix.ticketKey(), ignored -> new LinkedHashSet<>())
                            .add(szzClass.classPath());
                    szzClassCount++;
                }
            } catch (IOException e) {
                System.err.println("SZZ ignorato per commit " + fix.commitHash() + ": " + e.getMessage());
            }
        }

        Map<String, Boolean> labels = new LinkedHashMap<>();
        Set<Integer> selectedIds = new HashSet<>();
        for (Release release : selectedReleases) {
            selectedIds.add(release.index());
            for (String path : inventory.getOrDefault(release.index(), List.of())) {
                labels.put(key(release.index(), path), false);
            }
        }

        for (Map.Entry<String, Set<String>> entry : classesByTicket.entrySet()) {
            TicketWindow window = windowByTicket.get(entry.getKey());
            if (window == null) {
                continue;
            }
            for (int releaseIndex = window.injectedVersion(); releaseIndex < window.fixedVersion(); releaseIndex++) {
                if (!selectedIds.contains(releaseIndex)) {
                    continue;
                }
                for (String path : entry.getValue()) {
                    labels.computeIfPresent(key(releaseIndex, path), (ignored, value) -> true);
                }
            }
        }

        return new LabelingResult(labels, fixResolutionByCommit, windows,
                fixCommits.size(), szzClassCount);
    }

    /**
     * Proportion Total: calcola una sola media sui ticket con IV nota e la usa per
     * stimare tutti i ticket senza una affected version valida.
     */
    public static List<TicketWindow> resolveWindows(List<Ticket> tickets,
                                                     List<Release> releases) {
        Map<String, Release> byVersion = new HashMap<>();
        for (Release release : releases) {
            byVersion.put(release.version(), release);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (Ticket ticket : tickets) {
            Integer ov = openingVersion(ticket, releases);
            Integer fv = fixedVersion(ticket, releases, byVersion);
            if (ov == null || fv == null || fv < 1) {
                continue;
            }
            Integer knownIv = affectedVersion(ticket, byVersion, ov, fv);
            candidates.add(new Candidate(ticket, ov, fv, knownIv));
        }
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

        double totalProportion = proportionSum / proportionCount;
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
}
