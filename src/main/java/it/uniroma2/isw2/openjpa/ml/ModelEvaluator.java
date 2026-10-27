package it.uniroma2.isw2.openjpa.ml;

import it.uniroma2.isw2.openjpa.ml.DataPreprocessor.PreparedData;
import it.uniroma2.isw2.openjpa.ml.DatasetLoader.Dataset;
import it.uniroma2.isw2.openjpa.ml.DatasetLoader.ReleaseFold;
import weka.classifiers.Classifier;
import weka.core.Attribute;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.logging.Logger;

final class ModelEvaluator {

    private static final Logger LOGGER = Logger.getLogger(ModelEvaluator.class.getName());

    private final DataPreprocessor preprocessor;
    private final int baseSeed;
    private final int randomForestThreads;

    ModelEvaluator(int featureSelectionCount, int baseSeed, int randomForestThreads) {
        this.preprocessor = new DataPreprocessor(featureSelectionCount);
        this.baseSeed = baseSeed;
        this.randomForestThreads = randomForestThreads;
    }

    WalkForwardResult walkForward(Dataset dataset, List<Experiment> experiments) throws Exception {
        List<DetailResult> details = new ArrayList<>();
        List<SummaryResult> summaries = new ArrayList<>();
        List<ReleaseFold> folds = dataset.walkForwardFolds();

        for (Experiment experiment : experiments) {
            LOGGER.info(() -> String.format(Locale.ROOT, "  walk-forward: %s / %s",
                    experiment.classifier().label(), experiment.pipeline().label()));
            List<ScoredPrediction> allPredictions = new ArrayList<>();
            for (ReleaseFold fold : folds) {
                int seed = baseSeed + fold.index();
                PredictionBatch batch = evaluate(experiment, fold.training(), fold.test(), seed);
                allPredictions.addAll(batch.predictions());
                details.add(new DetailResult(experiment, fold.index(), fold.testRelease(),
                        fold.training().numInstances(), batch.trainingAfterBalancing(),
                        fold.test().numInstances(), batch.featureCount(),
                        PerformanceMetrics.from(batch.predictions())));
            }
            PerformanceMetrics metrics = PerformanceMetrics.from(allPredictions);
            summaries.add(new SummaryResult(experiment, "WalkForward", folds.size(), metrics));
        }
        return new WalkForwardResult(List.copyOf(details), List.copyOf(summaries));
    }

    List<SummaryResult> repeatedCrossValidation(Dataset dataset, List<Experiment> experiments,
                                                 int repeats, int folds) throws Exception {
        if (dataset.all().numInstances() < folds) {
            throw new IllegalArgumentException("Il numero di fold supera il numero di istanze");
        }
        List<SummaryResult> summaries = new ArrayList<>();
        for (Experiment experiment : experiments) {
            LOGGER.info(() -> String.format(Locale.ROOT, "  cross-validation: %s / %s",
                    experiment.classifier().label(), experiment.pipeline().label()));
            List<PerformanceMetrics> repetitions = new ArrayList<>();
            for (int repeat = 0; repeat < repeats; repeat++) {
                Instances randomized = new Instances(dataset.all());
                Random random = new Random((long) baseSeed + repeat);
                randomized.randomize(random);
                randomized.stratify(folds);
                List<ScoredPrediction> predictions = new ArrayList<>();
                for (int fold = 0; fold < folds; fold++) {
                    Instances training = randomized.trainCV(folds, fold);
                    Instances test = randomized.testCV(folds, fold);
                    PredictionBatch batch = evaluate(experiment, training, test,
                            baseSeed + repeat * folds + fold);
                    predictions.addAll(batch.predictions());
                }
                repetitions.add(PerformanceMetrics.from(predictions));
            }
            summaries.add(new SummaryResult(experiment,
                    repeats + "x" + folds + "CrossValidation", repeats,
                    PerformanceMetrics.average(repetitions)));
        }
        return List.copyOf(summaries);
    }

    private PredictionBatch evaluate(Experiment experiment, Instances rawTraining,
                                     Instances rawTest, int seed) throws Exception {
        PreparedData prepared = preprocessor.prepare(rawTraining, rawTest, experiment.pipeline(), seed);
        Classifier classifier = experiment.newClassifier(seed, randomForestThreads);
        classifier.buildClassifier(prepared.training());

        Attribute rawLoc = rawTest.attribute("LOC");
        int positiveClass = prepared.test().classAttribute().indexOfValue("Yes");
        List<ScoredPrediction> predictions = new ArrayList<>();
        for (int index = 0; index < prepared.test().numInstances(); index++) {
            Instance instance = prepared.test().instance(index);
            double[] distribution = classifier.distributionForInstance(instance);
            int predictedClass = Utils.maxIndex(distribution);
            predictions.add(new ScoredPrediction(
                    distribution[positiveClass],
                    (int) instance.classValue() == positiveClass,
                    predictedClass == positiveClass,
                    (int) Math.round(rawTest.instance(index).value(rawLoc))));
        }
        return new PredictionBatch(List.copyOf(predictions), prepared.training().numInstances(),
                prepared.training().numAttributes() - 1);
    }

    record WalkForwardResult(List<DetailResult> details, List<SummaryResult> summaries) {
    }

    record DetailResult(Experiment experiment, int run, String testRelease,
                        int trainingSize, int trainingAfterBalancing, int testSize,
                        int featureCount, PerformanceMetrics metrics) {
    }

    record SummaryResult(Experiment experiment, String validation, int runs,
                         PerformanceMetrics metrics) {
        double selectionScore() {
            return (metrics.auc() + metrics.kappa() + metrics.npofb20()) / 3.0;
        }
    }

    private record PredictionBatch(List<ScoredPrediction> predictions,
                                   int trainingAfterBalancing, int featureCount) {
    }
}
