package it.uniroma2.isw2.openjpa.ml;

import it.uniroma2.isw2.openjpa.ml.ModelEvaluator.DetailResult;
import it.uniroma2.isw2.openjpa.ml.ModelEvaluator.SummaryResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class MlResultsExporter {

    void writeWalkForwardDetails(Path directory, List<DetailResult> rows) throws IOException {
        Files.createDirectories(directory);
        try (BufferedWriter writer = writer(directory.resolve("walk_forward_details.csv"))) {
            writer.write("Classifier,Pipeline,Run,TestRelease,TrainingSize,TrainingAfterBalancing,"
                    + "TestSize,FeatureCount,TP,FP,TN,FN,Precision,Recall,F1,AUC,Kappa,NPofB20");
            writer.newLine();
            for (DetailResult row : rows) {
                PerformanceMetrics m = row.metrics();
                writer.write(String.join(",",
                        row.experiment().classifier().label(), row.experiment().pipeline().label(),
                        Integer.toString(row.run()), row.testRelease(),
                        Integer.toString(row.trainingSize()),
                        Integer.toString(row.trainingAfterBalancing()),
                        Integer.toString(row.testSize()), Integer.toString(row.featureCount()),
                        counts(m), decimals(m)));
                writer.newLine();
            }
        }
    }

    void writeCrossValidation(Path directory, List<SummaryResult> rows) throws IOException {
        Files.createDirectories(directory);
        try (BufferedWriter writer = writer(directory.resolve("cross_validation_summary.csv"))) {
            writer.write(summaryHeader());
            writer.newLine();
            for (SummaryResult row : rows) {
                writer.write(summaryRow(row));
                writer.newLine();
            }
        }
    }

    SummaryResult writeComparison(Path directory, List<SummaryResult> walkForward,
                                  List<SummaryResult> crossValidation) throws IOException {
        SummaryResult selected = crossValidation.stream()
                .max(Comparator.comparingDouble(SummaryResult::selectionScore))
                .orElseThrow();
        try (BufferedWriter writer = writer(directory.resolve("classifier_comparison.csv"))) {
            writer.write("Classifier,Pipeline,WalkPrecision,WalkRecall,WalkF1,WalkAUC,WalkKappa,"
                    + "WalkNPofB20,CvPrecision,CvRecall,CvF1,CvAUC,CvKappa,CvNPofB20,"
                    + "SelectionScore,Selected");
            writer.newLine();
            for (SummaryResult walk : walkForward) {
                SummaryResult cv = crossValidation.stream()
                        .filter(candidate -> candidate.experiment().equals(walk.experiment()))
                        .findFirst().orElseThrow();
                writer.write(String.join(",",
                        walk.experiment().classifier().label(), walk.experiment().pipeline().label(),
                        metricDecimals(walk.metrics()), metricDecimals(cv.metrics()),
                        decimal(cv.selectionScore()), cv.equals(selected) ? "Yes" : "No"));
                writer.newLine();
            }
        }
        return selected;
    }

    private static BufferedWriter writer(Path path) throws IOException {
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    private static String summaryHeader() {
        return "Classifier,Pipeline,Validation,Runs,MeanTP,MeanFP,MeanTN,MeanFN,"
                + "Precision,Recall,F1,AUC,Kappa,NPofB20";
    }

    private static String summaryRow(SummaryResult row) {
        PerformanceMetrics m = row.metrics();
        return String.join(",", row.experiment().classifier().label(),
                row.experiment().pipeline().label(), row.validation(), Integer.toString(row.runs()),
                counts(m), decimals(m));
    }

    private static String counts(PerformanceMetrics metrics) {
        return String.join(",", Long.toString(metrics.truePositive()),
                Long.toString(metrics.falsePositive()), Long.toString(metrics.trueNegative()),
                Long.toString(metrics.falseNegative()));
    }

    private static String decimals(PerformanceMetrics metrics) {
        return String.join(",", decimal(metrics.precision()), decimal(metrics.recall()),
                decimal(metrics.f1()), decimal(metrics.auc()), decimal(metrics.kappa()),
                decimal(metrics.npofb20()));
    }

    private static String metricDecimals(PerformanceMetrics metrics) {
        return String.join(",", decimal(metrics.precision()), decimal(metrics.recall()),
                decimal(metrics.f1()), decimal(metrics.auc()), decimal(metrics.kappa()),
                decimal(metrics.npofb20()));
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
