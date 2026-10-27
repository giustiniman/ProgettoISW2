package it.uniroma2.isw2.openjpa.ml;

import weka.attributeSelection.AttributeSelection;
import weka.attributeSelection.InfoGainAttributeEval;
import weka.attributeSelection.Ranker;
import weka.core.Instances;
import weka.filters.Filter;
import weka.filters.supervised.instance.Resample;
import weka.filters.unsupervised.attribute.Remove;
import weka.filters.unsupervised.attribute.Standardize;

import java.util.Arrays;
import java.util.List;

final class DataPreprocessor {

    private static final List<String> IDENTIFIERS = List.of("Project", "Release", "Class");
    private final int featureSelectionCount;

    DataPreprocessor(int featureSelectionCount) {
        this.featureSelectionCount = featureSelectionCount;
    }

    PreparedData prepare(Instances rawTraining, Instances rawTest,
                         PipelineVariant variant, int seed) throws Exception {
        Instances training = removeIdentifiers(rawTraining);
        Instances test = removeIdentifiers(rawTest);

        if (variant.usesFeatureSelection()) {
            AttributeSelection selection = new AttributeSelection();
            InfoGainAttributeEval evaluator = new InfoGainAttributeEval();
            Ranker ranker = new Ranker();
            ranker.setNumToSelect(Math.min(featureSelectionCount, training.numAttributes() - 1));
            selection.setEvaluator(evaluator);
            selection.setSearch(ranker);
            selection.SelectAttributes(training);
            training = selection.reduceDimensionality(training);
            test = selection.reduceDimensionality(test);
        }

        Standardize standardize = new Standardize();
        standardize.setInputFormat(training);
        training = Filter.useFilter(training, standardize);
        test = Filter.useFilter(test, standardize);

        int unbalancedTrainingSize = training.numInstances();
        if (variant.usesBalancing()) {
            training = balance(training, seed);
        }
        return new PreparedData(training, test, unbalancedTrainingSize);
    }

    private static Instances removeIdentifiers(Instances source) throws Exception {
        int[] indices = IDENTIFIERS.stream()
                .map(source::attribute)
                .mapToInt(attribute -> {
                    if (attribute == null) {
                        throw new IllegalArgumentException("Identificatore assente: " + IDENTIFIERS);
                    }
                    return attribute.index();
                })
                .sorted()
                .toArray();
        Remove remove = new Remove();
        remove.setAttributeIndicesArray(indices);
        remove.setInputFormat(source);
        Instances result = Filter.useFilter(source, remove);
        result.setClass(result.attribute("Buggy"));
        return result;
    }

    private static Instances balance(Instances training, int seed) throws Exception {
        int[] counts = training.attributeStats(training.classIndex()).nominalCounts;
        int majority = Arrays.stream(counts).max().orElse(0);
        int minority = Arrays.stream(counts).min().orElse(0);
        if (minority == 0 || majority == minority) {
            return training;
        }

        Resample resample = new Resample();
        resample.setBiasToUniformClass(1.0);
        resample.setSampleSizePercent(200.0 * majority / training.numInstances());
        resample.setRandomSeed(seed);
        resample.setInputFormat(training);
        Instances balanced = Filter.useFilter(training, resample);
        balanced.setClassIndex(training.classIndex());
        return balanced;
    }

    record PreparedData(Instances training, Instances test, int unbalancedTrainingSize) {
    }
}
