package it.uniroma2.isw2.openjpa.whatif;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

record WhatIfConfig(Path inputCsv, Path modelComparisonCsv, Path outputDirectory,
                    int featureSelectionCount, int randomSeed, int randomForestThreads) {

    static WhatIfConfig load() throws IOException {
        Path configPath = Path.of(System.getProperty("config.file", "config.properties"))
                .toAbsolutePath().normalize();
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configPath)) {
            properties.load(input);
        }

        Path base = configPath.getParent();
        Path mlOutput = resolve(base, required(properties, "ml.output.directory"));
        WhatIfConfig config = new WhatIfConfig(
                resolve(base, required(properties, "ml.input.csv")),
                mlOutput.resolve("classifier_comparison.csv"),
                resolve(base, required(properties, "milestone3.output.directory")),
                Integer.parseInt(required(properties, "ml.feature.selection.count")),
                Integer.parseInt(required(properties, "ml.random.seed")),
                Integer.parseInt(required(properties, "ml.random.forest.threads"))
        );
        if (config.featureSelectionCount() < 1 || config.randomForestThreads() < 1) {
            throw new IllegalArgumentException(
                    "feature.selection.count e random.forest.threads devono essere positivi");
        }
        return config;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Proprieta' obbligatoria mancante: " + key);
        }
        return value.trim();
    }

    private static Path resolve(Path base, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : base.resolve(path)).normalize();
    }
}
