package it.uniroma2.isw2.openjpa.labeling;

import it.uniroma2.isw2.openjpa.model.Release;
import it.uniroma2.isw2.openjpa.model.FixCommit;
import it.uniroma2.isw2.openjpa.model.Ticket;
import it.uniroma2.isw2.openjpa.model.TicketWindow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelingServiceTest {

    @Test
    void proportionTotalUsesAllKnownTicketsForEveryEstimate() {
        List<Release> releases = releases(8);
        Ticket earlierMissing = ticket("OPENJPA-1", 3, List.of());
        Ticket laterKnown = ticket("OPENJPA-2", 6, List.of("1.0.1"));
        Ticket sameOpeningAndFix = ticket("OPENJPA-5", 5, List.of());
        List<FixCommit> fixes = List.of(
                fix("OPENJPA-1", 5), fix("OPENJPA-2", 8), fix("OPENJPA-5", 5));

        Map<String, TicketWindow> windows = LabelingService.resolveWindows(
                        List.of(earlierMissing, laterKnown, sameOpeningAndFix), releases, fixes).stream()
                .collect(Collectors.toMap(TicketWindow::ticketKey, Function.identity()));

        assertEquals(1, windows.get("OPENJPA-1").injectedVersion(),
                "Proportion Total deve usare anche il ticket noto risolto successivamente");
        assertTrue(windows.get("OPENJPA-1").estimated());
        assertEquals(7.0 / 2.0, windows.get("OPENJPA-1").proportionUsed());
        assertEquals(1, windows.get("OPENJPA-2").injectedVersion());
        assertEquals(5, windows.get("OPENJPA-5").injectedVersion(),
                "con FV uguale a OV la formula Total deve restituire IV uguale a OV");
    }

    @Test
    void earliestReliableAffectedVersionWins() {
        List<Release> releases = releases(6);
        Ticket ticket = ticket("OPENJPA-3", 4,
                List.of("1.0.4", "1.0.2", "not-semver"));

        TicketWindow window = LabelingService.resolveWindows(
                List.of(ticket), releases, List.of(fix("OPENJPA-3", 6))).get(0);

        assertEquals(2, window.injectedVersion());
        assertEquals(4, window.openingVersion());
        assertEquals(6, window.fixedVersion());
    }

    @Test
    void proportionTotalHasNoColdStartWhenNoKnownIvExists() {
        List<Release> releases = releases(6);
        Ticket missing = ticket("OPENJPA-4", 3, List.of());
        List<Ticket> tickets = List.of(missing);

        assertThrows(IllegalStateException.class,
                () -> LabelingService.resolveWindows(
                        tickets, releases, List.of(fix("OPENJPA-4", 5))));
    }

    @Test
    void fixedVersionComesFromFixCommitInsteadOfJiraResolutionDate() {
        List<Release> releases = releases(8);
        Ticket ticket = ticket("OPENJPA-6", 2, List.of("1.0.1"));

        TicketWindow window = LabelingService.resolveWindows(
                List.of(ticket), releases, List.of(fix("OPENJPA-6", 7))).get(0);

        assertEquals(7, window.fixedVersion());
    }

    @Test
    void firstFixCommitDeterminesFixedVersionWhenTicketHasBackports() {
        List<Release> releases = releases(8);
        Ticket ticket = ticket("OPENJPA-7", 2, List.of("1.0.1"));

        TicketWindow window = LabelingService.resolveWindows(
                List.of(ticket), releases,
                List.of(fix("OPENJPA-7", 8), fix("OPENJPA-7", 5))).get(0);

        assertEquals(5, window.fixedVersion());
    }

    private static Ticket ticket(String key, int creationDay, List<String> affected) {
        return new Ticket(key,
                LocalDate.of(2020, 1, creationDay).atStartOfDay().toInstant(ZoneOffset.UTC),
                affected);
    }

    private static FixCommit fix(String ticketKey, int commitDay) {
        return new FixCommit(ticketKey, "hash-" + ticketKey + "-" + commitDay,
                LocalDate.of(2020, 1, commitDay).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private static List<Release> releases(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> new Release(index, "j" + index, "1.0." + index,
                        LocalDate.of(2020, 1, index), "tag", "hash"))
                .toList();
    }
}
