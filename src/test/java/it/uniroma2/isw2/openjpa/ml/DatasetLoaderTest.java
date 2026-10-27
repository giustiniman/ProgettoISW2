package it.uniroma2.isw2.openjpa.ml;

import it.uniroma2.isw2.openjpa.ml.DataPreprocessor.PreparedData;
import it.uniroma2.isw2.openjpa.ml.DatasetLoader.Dataset;
import it.uniroma2.isw2.openjpa.ml.DatasetLoader.ReleaseFold;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetLoaderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void walkForwardUsesOnlyEarlierReleases() throws Exception {
        Dataset dataset = new DatasetLoader().load(writeDataset());

        List<ReleaseFold> folds = dataset.walkForwardFolds();
        assertEquals(2, folds.size());
        assertEquals("1.0.1", folds.get(0).testRelease());
        assertEquals(4, folds.get(0).training().numInstances());
        assertEquals("1.0.2", folds.get(1).testRelease());
        assertEquals(6, folds.get(1).training().numInstances());
    }

    @Test
    void preprocessingRemovesIdentifiersAndBalancesOnlyTraining() throws Exception {
        Dataset dataset = new DatasetLoader().load(writeDataset());
        ReleaseFold fold = dataset.walkForwardFolds().get(0);

        PreparedData prepared = new DataPreprocessor(2).prepare(
                fold.training(), fold.test(), PipelineVariant.BALANCING, 42);

        assertNull(prepared.training().attribute("Project"));
        assertNull(prepared.training().attribute("Release"));
        assertNull(prepared.training().attribute("Class"));
        assertEquals(fold.test().numInstances(), prepared.test().numInstances());
        assertTrue(prepared.training().numInstances() > prepared.unbalancedTrainingSize());
        assertEquals("Buggy", prepared.training().classAttribute().name());

        PreparedData selected = new DataPreprocessor(2).prepare(
                fold.training(), fold.test(), PipelineVariant.FEATURE_SELECTION, 42);
        assertEquals(3, selected.training().numAttributes()); // due feature + target
    }

    private Path writeDataset() throws Exception {
        Path path = tempDirectory.resolve("dataset.csv");
        Files.writeString(path, """
                Project,Release,Class,LOC,Metric,NSmells,Buggy
                OPENJPA,1.0.0,A,10,1,0,No
                OPENJPA,1.0.0,B,20,2,0,No
                OPENJPA,1.0.0,C,30,3,1,No
                OPENJPA,1.0.0,D,40,9,2,Yes
                OPENJPA,1.0.1,E,15,2,0,No
                OPENJPA,1.0.1,F,25,8,1,Yes
                OPENJPA,1.0.2,G,35,7,2,Yes
                OPENJPA,1.0.2,H,45,1,0,No
                """);
        return path;
    }
}
