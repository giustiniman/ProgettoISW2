package it.uniroma2.isw2.openjpa.ml;

import it.uniroma2.isw2.openjpa.ml.DatasetLoader.Dataset;
import it.uniroma2.isw2.openjpa.ml.ModelEvaluator.SummaryResult;
import it.uniroma2.isw2.openjpa.ml.ModelEvaluator.WalkForwardResult;

import java.util.ArrayList;
import java.util.List;

public final class Milestone2Main {

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

            System.out.printf("Milestone 2: %d istanze, %d release, %d configurazioni%n",
                    dataset.all().numInstances(), dataset.byRelease().size(), experiments.size());
            System.out.println("Validazione walk-forward...");
            WalkForwardResult walkForward = evaluator.walkForward(dataset, experiments);

            System.out.printf("Validazione %d-times %d-fold...%n", config.repeats(), config.folds());
            List<SummaryResult> crossValidation = evaluator.repeatedCrossValidation(
                    dataset, experiments, config.repeats(), config.folds());

            MlResultsExporter exporter = new MlResultsExporter();
            exporter.writeWalkForwardDetails(config.outputDirectory(), walkForward.details());
            exporter.writeCrossValidation(config.outputDirectory(), crossValidation);
            SummaryResult selected = exporter.writeComparison(config.outputDirectory(),
                    walkForward.summaries(), crossValidation);

            System.out.printf("BClassifier: %s / %s (score 10x10 CV %.6f)%n",
                    selected.experiment().classifier().label(),
                    selected.experiment().pipeline().label(), selected.selectionScore());
            System.out.println("Risultati scritti in: " + config.outputDirectory());
        } catch (Exception exception) {
            System.err.println("Milestone 2 interrotta: " + exception.getMessage());
            throw exception;
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
