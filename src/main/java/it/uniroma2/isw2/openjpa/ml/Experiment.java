package it.uniroma2.isw2.openjpa.ml;

import weka.classifiers.Classifier;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.lazy.IBk;
import weka.classifiers.trees.RandomForest;

record Experiment(ClassifierType classifier, PipelineVariant pipeline) {

    Classifier newClassifier(int seed, int randomForestThreads) {
        return classifier.create(seed, randomForestThreads);
    }
}

enum ClassifierType {
    RANDOM_FOREST("RandomForest") {
        @Override
        Classifier create(int seed, int randomForestThreads) {
            RandomForest classifier = new RandomForest();
            classifier.setSeed(seed);
            classifier.setNumExecutionSlots(randomForestThreads);
            return classifier;
        }
    },
    NAIVE_BAYES("NaiveBayes") {
        @Override
        Classifier create(int seed, int randomForestThreads) {
            return new NaiveBayes();
        }
    },
    IBK("IBk") {
        @Override
        Classifier create(int seed, int randomForestThreads) {
            return new IBk();
        }
    };

    private final String label;

    ClassifierType(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    abstract Classifier create(int seed, int randomForestThreads);
}

enum PipelineVariant {
    BASELINE("Baseline", false, false),
    FEATURE_SELECTION("FeatureSelection", true, false),
    BALANCING("Balancing", false, true),
    FEATURE_SELECTION_AND_BALANCING("FeatureSelection+Balancing", true, true);

    private final String label;
    private final boolean featureSelection;
    private final boolean balancing;

    PipelineVariant(String label, boolean featureSelection, boolean balancing) {
        this.label = label;
        this.featureSelection = featureSelection;
        this.balancing = balancing;
    }

    String label() {
        return label;
    }

    boolean usesFeatureSelection() {
        return featureSelection;
    }

    boolean usesBalancing() {
        return balancing;
    }
}
