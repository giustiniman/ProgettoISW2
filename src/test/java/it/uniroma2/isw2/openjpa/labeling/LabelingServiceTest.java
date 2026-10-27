package it.uniroma2.isw2.openjpa.labeling;

import it.uniroma2.isw2.openjpa.model.Release;
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
        Ticket earlierMissing = ticket("OPENJPA-1", 3, 4, List.of(), List.of("1.0.5"));
        Ticket laterKnown = ticket("OPENJPA-2", 6, 7, List.of("1.0.1"), List.of("1.0.8"));
        Ticket sameOpeningAndFix = ticket("OPENJPA-5", 5, 5, List.of(), List.of("1.0.5"));

        Map<String, TicketWindow> windows = LabelingService.resolveWindows(
                        List.of(earlierMissing, laterKnown, sameOpeningAndFix), releases).stream()
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
        Ticket ticket = ticket("OPENJPA-3", 4, 5,
                List.of("1.0.4", "1.0.2", "not-semver"), List.of("1.0.6"));

        TicketWindow window = LabelingService.resolveWindows(List.of(ticket), releases).get(0);

        assertEquals(2, window.injectedVersion());
        assertEquals(4, window.openingVersion());
        assertEquals(6, window.fixedVersion());
    }

    @Test
    void proportionTotalHasNoColdStartWhenNoKnownIvExists() {
        List<Release> releases = releases(6);
        Ticket missing = ticket("OPENJPA-4", 3, 4, List.of(), List.of("1.0.5"));
        List<Ticket> tickets = List.of(missing);

        assertThrows(IllegalStateException.class,
                () -> LabelingService.resolveWindows(tickets, releases));
    }

    private static Ticket ticket(String key, int creationDay, int resolutionDay,
                                 List<String> affected, List<String> fixed) {
        return new Ticket(key,
                LocalDate.of(2020, 1, creationDay).atStartOfDay().toInstant(ZoneOffset.UTC),
                LocalDate.of(2020, 1, resolutionDay).atStartOfDay().toInstant(ZoneOffset.UTC),
                affected, fixed);
    }

    private static List<Release> releases(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> new Release(index, "j" + index, "1.0." + index,
                        LocalDate.of(2020, 1, index), "tag", "hash"))
                .toList();
    }
}
