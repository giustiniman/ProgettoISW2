package it.uniroma2.isw2.openjpa.ml;

import it.uniroma2.isw2.openjpa.ml.DatasetLoader.Dataset;
import it.uniroma2.isw2.openjpa.ml.ModelEvaluator.SummaryResult;
import it.uniroma2.isw2.openjpa.ml.ModelEvaluator.WalkForwardResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

public final class Milestone2Main {

    private static final Logger LOGGER = Logger.getLogger(Milestone2Main.class.getName());

    private Milestone2Main() {
    }

    public static void main(String[] args) throws Exception {
        try {
            MlConfig config = MlConfig.load();
            Dataset dataset = new DatasetLoader().load(config.inputCsv());
            List<Experiment> experiments = experiments();
            ModelEvaluator evaluator = new ModelEvaluator(
                    config.featureSelectionCount(), config.randomSeed(),
                    config.randomForestThreads());

            LOGGER.info(() -> String.format(Locale.ROOT,
                    "Milestone 2: %d istanze, %d release, %d configurazioni",
                    dataset.all().numInstances(), dataset.byRelease().size(), experiments.size()));
            LOGGER.info("Validazione walk-forward...");
            WalkForwardResult walkForward = evaluator.walkForward(dataset, experiments);

            LOGGER.info(() -> String.format(Locale.ROOT, "Validazione %d-times %d-fold...",
                    config.repeats(), config.folds()));
            List<SummaryResult> crossValidation = evaluator.repeatedCrossValidation(
                    dataset, experiments, config.repeats(), config.folds());

            MlResultsExporter exporter = new MlResultsExporter();
            exporter.writeWalkForwardDetails(config.outputDirectory(), walkForward.details());
            exporter.writeCrossValidation(config.outputDirectory(), crossValidation);
            SummaryResult selected = exporter.writeComparison(config.outputDirectory(),
                    walkForward.summaries(), crossValidation);

            LOGGER.info(() -> String.format(Locale.ROOT,
                    "BClassifier: %s / %s (score 10x10 CV %.6f)",
                    selected.experiment().classifier().label(),
                    selected.experiment().pipeline().label(), selected.selectionScore()));
            LOGGER.info(() -> "Risultati scritti in: " + config.outputDirectory());
        } catch (Exception exception) {
            throw new IllegalStateException("Milestone 2 interrotta", exception);
        }
    }

    private static List<Experiment> experiments() {
        List<Experiment> experiments = new ArrayList<>();
        for (ClassifierType classifier : ClassifierType.values()) {
            for (PipelineVariant pipeline : PipelineVariant.values()) {
                experiments.add(new Experiment(classifier, pipeline));
            }
        }
        return List.copyOf(experiments);
    }
}
