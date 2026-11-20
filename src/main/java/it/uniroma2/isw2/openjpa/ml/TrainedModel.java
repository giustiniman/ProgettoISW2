package it.uniroma2.isw2.openjpa.ml;

import it.uniroma2.isw2.openjpa.ml.DataPreprocessor.FittedPreprocessor;
import weka.classifiers.Classifier;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Modello finale addestrato sull'intero dataset A. Conserva il preprocessing
 * appreso su A e lo riusa senza rifit sui dataset controfattuali.
 */
public final class TrainedModel {

    private final Classifier classifier;
    private final FittedPreprocessor preprocessing;
    private final int positiveClass;
    private final TrainingInfo trainingInfo;

    private TrainedModel(Classifier classifier, FittedPreprocessor preprocessing,
                         int positiveClass, TrainingInfo trainingInfo) {
        this.classifier = classifier;
        this.preprocessing = preprocessing;
        this.positiveClass = positiveClass;
        this.trainingInfo = trainingInfo;
    }

    public static TrainedModel train(Instances rawTraining, String classifierLabel,
                                     String pipelineLabel, int featureSelectionCount,
                                     int seed, int randomForestThreads) throws Exception {
        ClassifierType classifierType = classifierType(classifierLabel);
        PipelineVariant pipeline = pipeline(pipelineLabel);
        DataPreprocessor preprocessor = new DataPreprocessor(featureSelectionCount);
        FittedPreprocessor fitted = preprocessor.fit(rawTraining, pipeline);
        Instances training = fitted.trainingForClassifier(seed);
        Classifier classifier = new Experiment(classifierType, pipeline)
                .newClassifier(seed, randomForestThreads);
        classifier.buildClassifier(training);

        int positiveClass = training.classAttribute().indexOfValue("Yes");
        if (positiveClass < 0) {
            throw new IllegalArgumentException("La classe positiva Buggy=Yes non e' presente");
        }
        TrainingInfo info = new TrainingInfo(fitted.unbalancedTrainingSize(),
                training.numInstances(), fitted.featureCount());
        return new TrainedModel(classifier, fitted, positiveClass, info);
    }

    public Prediction predict(Instances rawData) throws Exception {
        Instances data = preprocessing.transform(rawData);
        List<Boolean> buggy = new ArrayList<>(data.numInstances());
        List<Double> probabilities = new ArrayList<>(data.numInstances());
        for (Instance instance : data) {
            double[] distribution = classifier.distributionForInstance(instance);
            buggy.add(Utils.maxIndex(distribution) == positiveClass);
            probabilities.add(distribution[positiveClass]);
        }
        return new Prediction(buggy, probabilities);
    }

    public TrainingInfo trainingInfo() {
        return trainingInfo;
    }

    private static ClassifierType classifierType(String label) {
        for (ClassifierType value : ClassifierType.values()) {
            if (value.label().equals(label)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Classificatore non supportato: " + label);
    }

    private static PipelineVariant pipeline(String label) {
        for (PipelineVariant value : PipelineVariant.values()) {
            if (value.label().equals(label)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Pipeline non supportata: " + label);
    }

    public record Prediction(List<Boolean> buggy, List<Double> probabilities) {

        public Prediction {
            buggy = List.copyOf(buggy);
            probabilities = List.copyOf(probabilities);
            if (buggy.size() != probabilities.size()) {
                throw new IllegalArgumentException("Predizioni e probabilita' non allineate");
            }
        }

        public int size() {
            return buggy.size();
        }

        public int predictedBuggy() {
            return (int) buggy.stream().filter(Boolean::booleanValue).count();
        }
    }

    public record TrainingInfo(int instances, int instancesAfterBalancing, int featureCount) {
    }
}
