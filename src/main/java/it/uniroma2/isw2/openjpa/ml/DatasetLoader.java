package it.uniroma2.isw2.openjpa.ml;

import weka.core.Attribute;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.CSVLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DatasetLoader {

    private static final List<String> REQUIRED = List.of(
            "Project", "Release", "Class", "LOC", "NSmells", "Buggy");

    Dataset load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Dataset Milestone 1 non trovato: " + path);
        }
        CSVLoader loader = new CSVLoader();
        loader.setSource(path.toFile());
        Instances instances = loader.getDataSet();
        for (String name : REQUIRED) {
            if (instances.attribute(name) == null) {
                throw new IOException("Colonna obbligatoria assente dal dataset: " + name);
            }
        }
        instances.setClass(instances.attribute("Buggy"));
        if (!instances.classAttribute().isNominal()
                || instances.classAttribute().numValues() != 2
                || instances.classAttribute().indexOfValue("Yes") < 0
                || instances.classAttribute().indexOfValue("No") < 0) {
            throw new IOException("Buggy deve essere nominale e contenere esattamente Yes/No");
        }
        if (!instances.attribute("LOC").isNumeric()) {
            throw new IOException("LOC deve essere una colonna numerica");
        }
        return new Dataset(instances, splitByRelease(instances));
    }

    private static LinkedHashMap<String, Instances> splitByRelease(Instances data) throws IOException {
        Attribute release = data.attribute("Release");
        if (!release.isNominal() && !release.isString()) {
            throw new IOException("Release deve contenere versioni nominali X.Y.Z");
        }
        LinkedHashMap<String, Instances> result = new LinkedHashMap<>();
        for (Instance instance : data) {
            String name = instance.stringValue(release);
            result.computeIfAbsent(name, ignored -> new Instances(data, 0)).add(instance);
        }
        if (result.size() < 2) {
            throw new IOException("Servono almeno due release per la validazione walk-forward");
        }
        return result;
    }

    record Dataset(Instances all, LinkedHashMap<String, Instances> byRelease) {

        List<ReleaseFold> walkForwardFolds() {
            List<Map.Entry<String, Instances>> releases = new ArrayList<>(byRelease.entrySet());
            List<ReleaseFold> folds = new ArrayList<>();
            Instances training = new Instances(all, 0);
            training.addAll(releases.get(0).getValue());
            for (int index = 1; index < releases.size(); index++) {
                Map.Entry<String, Instances> test = releases.get(index);
                folds.add(new ReleaseFold(index, test.getKey(), new Instances(training),
                        new Instances(test.getValue())));
                training.addAll(test.getValue());
            }
            return List.copyOf(folds);
        }
    }

    record ReleaseFold(int index, String testRelease, Instances training, Instances test) {
    }
}
