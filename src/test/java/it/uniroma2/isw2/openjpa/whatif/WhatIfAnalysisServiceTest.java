package it.uniroma2.isw2.openjpa.whatif;

import it.uniroma2.isw2.openjpa.ml.DatasetLoader;
import it.uniroma2.isw2.openjpa.ml.TrainedModel.Prediction;
import it.uniroma2.isw2.openjpa.whatif.WhatIfAnalysisService.Datasets;
import it.uniroma2.isw2.openjpa.whatif.WhatIfAnalysisService.PreventionResult;
import it.uniroma2.isw2.openjpa.whatif.WhatIfAnalysisService.SelectedModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import weka.core.Instance;
import weka.core.Instances;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhatIfAnalysisServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void createsBPlusBAndCWithoutChangingA() throws Exception {
        Instances a = new DatasetLoader().loadInstances(writeDataset());

        Datasets datasets = WhatIfAnalysisService.split(a);

        assertEquals(4, datasets.a().numInstances());
        assertEquals(2, datasets.bPlus().numInstances());
        assertEquals(2, datasets.b().numInstances());
        assertEquals(2, datasets.c().numInstances());
        assertEquals(2.0, datasets.bPlus().instance(1)
                .value(datasets.bPlus().attribute("NSmells")));
        for (Instance instance : datasets.b()) {
            assertEquals(0.0, instance.value(datasets.b().attribute("NSmells")));
            assertTrue(instance.classIsMissing());
        }
        assertEquals(1.0, datasets.a().instance(1).value(datasets.a().attribute("NSmells")));
    }

    @Test
    void readsTheSingleModelSelectedByMilestone2() throws Exception {
        Path comparison = tempDirectory.resolve("classifier_comparison.csv");
        Files.writeString(comparison, """
                Classifier,Pipeline,CvPrecision,CvRecall,CvF1,CvAUC,CvKappa,CvNPofB20,SelectionScore,Selected
                RandomForest,Baseline,0.7,0.6,0.65,0.9,0.5,0.4,0.6,No
                RandomForest,Balancing,0.6,0.8,0.68,0.91,0.6,0.5,0.67,Yes
                """);

        SelectedModel selected = WhatIfAnalysisService.readSelectedModel(comparison);

        assertEquals("RandomForest", selected.classifier());
        assertEquals("Balancing", selected.pipeline());
        assertEquals(0.67, selected.selectionScore());
    }

    @Test
    void computesOfficialAndPairedPreventionMeasuresSeparately() {
        Prediction bPlus = prediction(true, true, false, true);
        Prediction b = prediction(false, true, true, false);

        PreventionResult result = WhatIfAnalysisService.prevention(5, 3, bPlus, b);

        assertEquals(1, result.preventedTotal());
        assertEquals(0.2, result.preventedProportion());
        assertEquals(1.0 / 3.0, result.preventedAmongSmellyBuggy());
        assertEquals(2, result.pairedPredictedPrevented());
        assertEquals(1, result.pairedPredictedWorsened());
        assertEquals(1, result.netEstimatedReduction());
    }

    private static Prediction prediction(Boolean... values) {
        return new Prediction(List.of(values),
                java.util.Collections.nCopies(values.length, 0.5));
    }

    private Path writeDataset() throws Exception {
        Path path = tempDirectory.resolve("dataset.csv");
        Files.writeString(path, """
                Project,Release,Class,LOC,NSmells,Buggy
                OPENJPA,1.0.0,A,10,0,No
                OPENJPA,1.0.0,B,20,1,Yes
                OPENJPA,1.0.1,C,30,0,No
                OPENJPA,1.0.1,D,40,2,Yes
                """);
        return path;
    }
}
