package it.uniroma2.isw2.openjpa.ml;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerformanceMetricsTest {

    @Test
    void computesPositiveClassMetricsAndLocAwareNpofb20() {
        PerformanceMetrics metrics = PerformanceMetrics.from(List.of(
                new ScoredPrediction(0.9, true, true, 2),
                new ScoredPrediction(0.8, false, true, 3),
                new ScoredPrediction(0.3, true, false, 5),
                new ScoredPrediction(0.1, false, false, 30)
        ));

        assertEquals(1, metrics.truePositive());
        assertEquals(1, metrics.falsePositive());
        assertEquals(1, metrics.trueNegative());
        assertEquals(1, metrics.falseNegative());
        assertEquals(0.5, metrics.precision(), 1.0e-12);
        assertEquals(0.5, metrics.recall(), 1.0e-12);
        assertEquals(0.5, metrics.f1(), 1.0e-12);
        assertEquals(0.75, metrics.auc(), 1.0e-12);
        assertEquals(0.0, metrics.kappa(), 1.0e-12);
        assertEquals(0.5, metrics.npofb20(), 1.0e-12);
    }

    @Test
    void aucHandlesTiedScores() {
        PerformanceMetrics metrics = PerformanceMetrics.from(List.of(
                new ScoredPrediction(0.5, true, true, 1),
                new ScoredPrediction(0.5, false, false, 1)
        ));

        assertEquals(0.5, metrics.auc(), 1.0e-12);
    }
}
