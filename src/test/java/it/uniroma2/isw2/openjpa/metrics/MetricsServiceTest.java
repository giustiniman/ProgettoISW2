package it.uniroma2.isw2.openjpa.metrics;

import it.uniroma2.isw2.openjpa.model.ProcessMetrics;
import it.uniroma2.isw2.openjpa.model.CommitStats;
import it.uniroma2.isw2.openjpa.model.Touch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricsServiceTest {

    @Test
    void computesReleaseMetricsAndHistoricalAgeAndOwnership() {
        Instant snapshot = Instant.parse("2024-03-11T00:00:00Z");
        List<Touch> touches = List.of(
                new Touch("old", snapshot.minus(70, ChronoUnit.DAYS), "alice", 3, 0),
                new Touch("a", snapshot.minus(14, ChronoUnit.DAYS), "alice", 10, 2),
                new Touch("b", snapshot.minus(7, ChronoUnit.DAYS), "bob", 2, 5)
        );

        ProcessMetrics metrics = MetricsService.computeProcessMetrics(
                touches,
                java.util.Set.of("a", "b"),
                Map.of("a", new CommitStats(3, 0.5), "b", new CommitStats(5, 1.0)),
                java.util.Set.of("a"),
                snapshot);

        assertEquals(19, metrics.locTouched());
        assertEquals(2, metrics.nr());
        assertEquals(1, metrics.nFix());
        assertEquals(2, metrics.nAuth());
        assertEquals(12, metrics.locAdded());
        assertEquals(5, metrics.churn());
        assertEquals(8, metrics.changeSetSize());
        assertEquals(10.0, metrics.ageWeeks(), 1.0e-9);
        assertEquals((8.0 * 12.0 + 9.0 * 7.0) / 19.0,
                metrics.weightedAgeWeeks(), 1.0e-9);
        assertEquals(0.75, metrics.changeEntropy(), 1.0e-9);
        assertEquals(2.0 / 3.0, metrics.ownership(), 1.0e-9);
    }

    @Test
    void unchangedClassKeepsAgeAndOwnershipButHasZeroReleaseMetrics() {
        Instant release = Instant.parse("2024-03-11T00:00:00Z");
        ProcessMetrics metrics = MetricsService.computeProcessMetrics(
                List.of(new Touch("old", release.minus(14, ChronoUnit.DAYS), "alice", 5, 0)),
                java.util.Set.of(), Map.of(), java.util.Set.of(), release);

        assertEquals(0, metrics.nr());
        assertEquals(0, metrics.locTouched());
        assertEquals(2.0, metrics.ageWeeks(), 1.0e-9);
        assertEquals(0.0, metrics.weightedAgeWeeks(), 1.0e-9);
        assertEquals(1.0, metrics.ownership(), 1.0e-9);
    }
}
