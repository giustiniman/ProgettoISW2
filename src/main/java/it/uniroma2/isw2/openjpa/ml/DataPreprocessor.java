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
        FittedPreprocessor fitted = fit(rawTraining, variant);
        Instances training = fitted.trainingForClassifier(seed);
        Instances test = fitted.transform(rawTest);
        return new PreparedData(training, test, fitted.unbalancedTrainingSize());
    }

    FittedPreprocessor fit(Instances rawTraining, PipelineVariant variant) throws Exception {
        Instances training = removeIdentifiers(rawTraining);
        AttributeSelection selection = null;

        if (variant.usesFeatureSelection()) {
            selection = new AttributeSelection();
            InfoGainAttributeEval evaluator = new InfoGainAttributeEval();
            Ranker ranker = new Ranker();
            ranker.setNumToSelect(Math.min(featureSelectionCount, training.numAttributes() - 1));
            selection.setEvaluator(evaluator);
            selection.setSearch(ranker);
            selection.SelectAttributes(training);
            training = selection.reduceDimensionality(training);
        }

        Standardize standardize = new Standardize();
        standardize.setInputFormat(training);
        training = Filter.useFilter(training, standardize);
        return new FittedPreprocessor(training, selection, standardize, variant);
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

    record PreparedData(Instances training, Instances test, int unbalancedTrainingSize) {
    }

    record FittedPreprocessor(Instances standardizedTraining,
                              AttributeSelection selection,
                              Standardize standardize,
                              PipelineVariant variant) {

        Instances trainingForClassifier(int seed) throws Exception {
            Instances training = new Instances(standardizedTraining);
            return variant.usesBalancing() ? balance(training, seed) : training;
        }

        Instances transform(Instances rawData) throws Exception {
            Instances transformed = removeIdentifiers(rawData);
            if (selection != null) {
                transformed = selection.reduceDimensionality(transformed);
            }
            Standardize fittedCopy = (Standardize) Filter.makeCopy(standardize);
            return Filter.useFilter(transformed, fittedCopy);
        }

        int unbalancedTrainingSize() {
            return standardizedTraining.numInstances();
        }

        int featureCount() {
            return standardizedTraining.numAttributes() - 1;
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
    }
}
