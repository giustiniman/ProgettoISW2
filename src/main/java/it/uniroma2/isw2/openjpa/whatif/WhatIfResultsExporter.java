package it.uniroma2.isw2.openjpa.whatif;

import it.uniroma2.isw2.openjpa.whatif.WhatIfAnalysisService.AnalysisResult;
import it.uniroma2.isw2.openjpa.whatif.WhatIfAnalysisService.DatasetResult;
import it.uniroma2.isw2.openjpa.whatif.WhatIfAnalysisService.PreventionResult;
import it.uniroma2.isw2.openjpa.whatif.WhatIfAnalysisService.SelectedModel;
import weka.core.Instances;
import weka.core.converters.CSVSaver;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class WhatIfResultsExporter {

    void write(Path outputDirectory, AnalysisResult result) throws IOException {
        Files.createDirectories(outputDirectory);
        Path datasets = outputDirectory.resolve("datasets");
        Files.createDirectories(datasets);
        writeInstances(datasets.resolve("dataset_B_plus.csv"), result.datasets().bPlus());
        writeInstances(datasets.resolve("dataset_B.csv"), result.datasets().b());
        writeInstances(datasets.resolve("dataset_C.csv"), result.datasets().c());
        writeTable(outputDirectory.resolve("what_if_table.csv"), result.table());
        writePrevention(outputDirectory.resolve("prevention_summary.csv"), result.prevention());
        writeModel(outputDirectory.resolve("model_used.csv"), result);
    }

    private static void writeInstances(Path path, Instances instances) throws IOException {
        CSVSaver saver = new CSVSaver();
        saver.setInstances(instances);
        saver.setFile(path.toFile());
        saver.writeBatch();
    }

    private static void writeTable(Path path, List<DatasetResult> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                path, StandardCharsets.UTF_8)) {
            writer.write("Dataset,Instances,ActualBuggy,EstimatedBuggy");
            writer.newLine();
            for (DatasetResult row : rows) {
                writer.write(String.join(",", row.dataset(), Integer.toString(row.instances()),
                        row.actualBuggy() == null ? "" : row.actualBuggy().toString(),
                        Integer.toString(row.estimatedBuggy())));
                writer.newLine();
            }
        }
    }

    private static void writePrevention(Path path, PreventionResult result) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                path, StandardCharsets.UTF_8)) {
            writer.write("Metric,Value,Definition");
            writer.newLine();
            writeMetric(writer, "PreventedTotal", Integer.toString(result.preventedTotal()),
                    "ActualBuggy(B+) - EstimatedBuggy(B)");
            writeMetric(writer, "PreventedProportion",
                    decimal(result.preventedProportion()),
                    "PreventedTotal / ActualBuggy(A)");
            writeMetric(writer, "PreventedAmongSmellyBuggy",
                    decimal(result.preventedAmongSmellyBuggy()),
                    "PreventedTotal / ActualBuggy(B+)");
            writeMetric(writer, "PairedPredictedPrevented",
                    Integer.toString(result.pairedPredictedPrevented()),
                    "Predicted Yes on B+ and No on B");
            writeMetric(writer, "PairedPredictedWorsened",
                    Integer.toString(result.pairedPredictedWorsened()),
                    "Predicted No on B+ and Yes on B");
            writeMetric(writer, "NetEstimatedReduction",
                    Integer.toString(result.netEstimatedReduction()),
                    "EstimatedBuggy(B+) - EstimatedBuggy(B)");
        }
    }

    private static void writeModel(Path path, AnalysisResult result) throws IOException {
        SelectedModel model = result.selectedModel();
        try (BufferedWriter writer = Files.newBufferedWriter(
                path, StandardCharsets.UTF_8)) {
            writer.write("Classifier,Pipeline,CvPrecision,CvRecall,CvF1,CvAUC,CvKappa,"
                    + "CvNPofB20,SelectionScore,TrainingInstances,TrainingAfterBalancing,FeatureCount");
            writer.newLine();
            writer.write(String.join(",", model.classifier(), model.pipeline(),
                    decimal(model.cvPrecision()), decimal(model.cvRecall()),
                    decimal(model.cvF1()), decimal(model.cvAuc()), decimal(model.cvKappa()),
                    decimal(model.cvNPofB20()), decimal(model.selectionScore()),
                    Integer.toString(result.trainingInfo().instances()),
                    Integer.toString(result.trainingInfo().instancesAfterBalancing()),
                    Integer.toString(result.trainingInfo().featureCount())));
            writer.newLine();
        }
    }

    private static void writeMetric(BufferedWriter writer, String metric,
                                    String value, String definition) throws IOException {
        writer.write(metric + "," + value + "," + csv(definition));
        writer.newLine();
    }

    private static String csv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
