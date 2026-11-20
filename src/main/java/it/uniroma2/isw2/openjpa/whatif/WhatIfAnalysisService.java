package it.uniroma2.isw2.openjpa.whatif;

import it.uniroma2.isw2.openjpa.ml.DatasetLoader;
import it.uniroma2.isw2.openjpa.ml.TrainedModel;
import it.uniroma2.isw2.openjpa.ml.TrainedModel.Prediction;
import weka.core.Attribute;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.CSVLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class WhatIfAnalysisService {

    AnalysisResult analyze(WhatIfConfig config) throws Exception {
        Instances a = new DatasetLoader().loadInstances(config.inputCsv());
        Datasets datasets = split(a);
        SelectedModel selected = readSelectedModel(config.modelComparisonCsv());
        TrainedModel model = TrainedModel.train(datasets.a(), selected.classifier(),
                selected.pipeline(), config.featureSelectionCount(), config.randomSeed(),
                config.randomForestThreads());

        Predictions predictions = new Predictions(
                model.predict(datasets.a()),
                model.predict(datasets.bPlus()),
                model.predict(datasets.b()),
                model.predict(datasets.c()));
        validatePredictionSizes(datasets, predictions);

        int actualA = countActualBuggy(datasets.a());
        int actualBPlus = countActualBuggy(datasets.bPlus());
        int actualC = countActualBuggy(datasets.c());
        List<DatasetResult> table = List.of(
                new DatasetResult("A", datasets.a().numInstances(), actualA,
                        predictions.a().predictedBuggy()),
                new DatasetResult("B+", datasets.bPlus().numInstances(), actualBPlus,
                        predictions.bPlus().predictedBuggy()),
                new DatasetResult("B", datasets.b().numInstances(), null,
                        predictions.b().predictedBuggy()),
                new DatasetResult("C", datasets.c().numInstances(), actualC,
                        predictions.c().predictedBuggy()));
        PreventionResult prevention = prevention(
                actualA, actualBPlus, predictions.bPlus(), predictions.b());
        return new AnalysisResult(datasets, selected, model.trainingInfo(), table, prevention);
    }

    static Datasets split(Instances source) {
        Instances a = new Instances(source);
        Attribute smells = a.attribute("NSmells");
        if (smells == null || !smells.isNumeric()) {
            throw new IllegalArgumentException("NSmells deve essere una feature numerica");
        }

        Instances bPlus = new Instances(a, 0);
        Instances c = new Instances(a, 0);
        for (Instance instance : a) {
            if (instance.value(smells) > 0.0) {
                bPlus.add(instance);
            } else {
                c.add(instance);
            }
        }

        Instances b = new Instances(bPlus);
        Attribute bSmells = b.attribute("NSmells");
        for (Instance instance : b) {
            instance.setValue(bSmells, 0.0);
            instance.setClassMissing();
        }
        return new Datasets(a, bPlus, b, c);
    }

    static SelectedModel readSelectedModel(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Confronto Milestone 2 non trovato: " + path);
        }
        CSVLoader loader = new CSVLoader();
        loader.setSource(path.toFile());
        Instances comparison = loader.getDataSet();
        requireAttributes(comparison, "Classifier", "Pipeline", "CvPrecision", "CvRecall",
                "CvF1", "CvAUC", "CvKappa", "CvNPofB20", "SelectionScore", "Selected");

        SelectedModel selected = null;
        Attribute selectedAttribute = comparison.attribute("Selected");
        for (Instance row : comparison) {
            if ("Yes".equals(row.stringValue(selectedAttribute))) {
                if (selected != null) {
                    throw new IOException("Piu' modelli hanno Selected=Yes nella Milestone 2");
                }
                selected = new SelectedModel(
                        row.stringValue(comparison.attribute("Classifier")),
                        row.stringValue(comparison.attribute("Pipeline")),
                        row.value(comparison.attribute("CvPrecision")),
                        row.value(comparison.attribute("CvRecall")),
                        row.value(comparison.attribute("CvF1")),
                        row.value(comparison.attribute("CvAUC")),
                        row.value(comparison.attribute("CvKappa")),
                        row.value(comparison.attribute("CvNPofB20")),
                        row.value(comparison.attribute("SelectionScore")));
            }
        }
        if (selected == null) {
            throw new IOException("Nessun modello Selected=Yes nella Milestone 2");
        }
        return selected;
    }

    private static void requireAttributes(Instances data, String... names) throws IOException {
        for (String name : names) {
            if (data.attribute(name) == null) {
                throw new IOException("Colonna obbligatoria assente dal confronto M2: " + name);
            }
        }
    }

    static PreventionResult prevention(int actualA, int actualBPlus,
                                       Prediction bPlus, Prediction b) {
        if (bPlus.size() != b.size()) {
            throw new IllegalArgumentException("B+ e B non sono allineati");
        }
        int pairedPrevented = 0;
        int pairedWorsened = 0;
        for (int index = 0; index < bPlus.size(); index++) {
            boolean before = bPlus.buggy().get(index);
            boolean after = b.buggy().get(index);
            if (before && !after) {
                pairedPrevented++;
            } else if (!before && after) {
                pairedWorsened++;
            }
        }

        int preventedTotal = actualBPlus - b.predictedBuggy();
        double proportion = ratio(preventedTotal, actualA);
        double amongSmellyBuggy = ratio(preventedTotal, actualBPlus);
        int netEstimatedReduction = bPlus.predictedBuggy() - b.predictedBuggy();
        if (netEstimatedReduction != pairedPrevented - pairedWorsened) {
            throw new IllegalStateException("Transizioni controfattuali non coerenti");
        }
        return new PreventionResult(preventedTotal, proportion, amongSmellyBuggy,
                pairedPrevented, pairedWorsened, netEstimatedReduction);
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator / (double) denominator;
    }

    private static int countActualBuggy(Instances data) {
        int positive = data.classAttribute().indexOfValue("Yes");
        int count = 0;
        for (Instance instance : data) {
            if (!instance.classIsMissing() && (int) instance.classValue() == positive) {
                count++;
            }
        }
        return count;
    }

    private static void validatePredictionSizes(Datasets datasets, Predictions predictions) {
        if (predictions.a().size() != datasets.a().numInstances()
                || predictions.bPlus().size() != datasets.bPlus().numInstances()
                || predictions.b().size() != datasets.b().numInstances()
                || predictions.c().size() != datasets.c().numInstances()) {
            throw new IllegalStateException("Numero di predizioni non coerente con i dataset");
        }
    }

    record Datasets(Instances a, Instances bPlus, Instances b, Instances c) {
    }

    record Predictions(Prediction a, Prediction bPlus, Prediction b, Prediction c) {
    }

    record DatasetResult(String dataset, int instances, Integer actualBuggy,
                         int estimatedBuggy) {
    }

    record PreventionResult(int preventedTotal, double preventedProportion,
                            double preventedAmongSmellyBuggy,
                            int pairedPredictedPrevented, int pairedPredictedWorsened,
                            int netEstimatedReduction) {
    }

    record SelectedModel(String classifier, String pipeline,
                         double cvPrecision, double cvRecall, double cvF1,
                         double cvAuc, double cvKappa, double cvNPofB20,
                         double selectionScore) {
    }

    record AnalysisResult(Datasets datasets, SelectedModel selectedModel,
                          TrainedModel.TrainingInfo trainingInfo,
                          List<DatasetResult> table,
                          PreventionResult prevention) {
    }
}
