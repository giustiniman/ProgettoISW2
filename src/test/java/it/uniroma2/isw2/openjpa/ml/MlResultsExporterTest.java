package it.uniroma2.isw2.openjpa.ml;

import it.uniroma2.isw2.openjpa.ml.ModelEvaluator.SummaryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MlResultsExporterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void selectsBClassifierFromOfficialCrossValidation() throws Exception {
        Experiment baseline = new Experiment(ClassifierType.RANDOM_FOREST, PipelineVariant.BASELINE);
        Experiment balanced = new Experiment(ClassifierType.RANDOM_FOREST, PipelineVariant.BALANCING);
        SummaryResult walkBaseline = summary(baseline, "WalkForward", 0.30);
        SummaryResult walkBalanced = summary(balanced, "WalkForward", 0.90);
        SummaryResult cvBaseline = summary(baseline, "10x10CrossValidation", 0.80);
        SummaryResult cvBalanced = summary(balanced, "10x10CrossValidation", 0.40);

        SummaryResult selected = new MlResultsExporter().writeComparison(tempDirectory,
                List.of(walkBaseline, walkBalanced), List.of(cvBaseline, cvBalanced));

        assertEquals(baseline, selected.experiment());
        List<String> rows = Files.readAllLines(tempDirectory.resolve("classifier_comparison.csv"));
        assertTrue(rows.get(1).endsWith(",0.800000,Yes"));
        assertTrue(rows.get(2).endsWith(",0.400000,No"));
    }

    private static SummaryResult summary(Experiment experiment, String validation, double score) {
        PerformanceMetrics metrics = new PerformanceMetrics(
                1, 0, 1, 0, 1.0, 1.0, 1.0, score, score, score);
        return new SummaryResult(experiment, validation, 1, metrics);
    }
}
