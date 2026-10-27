package it.uniroma2.isw2.openjpa.ml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record MlConfig(Path inputCsv, Path outputDirectory, int repeats, int folds,
                       int randomSeed, int randomForestThreads,
                       int featureSelectionCount) {

    public static MlConfig load() throws IOException {
        Path configPath = Path.of(System.getProperty("config.file", "config.properties"))
                .toAbsolutePath().normalize();
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configPath)) {
            properties.load(input);
        }

        Path base = configPath.getParent();
        MlConfig config = new MlConfig(
                resolve(base, required(properties, "ml.input.csv")),
                resolve(base, required(properties, "ml.output.directory")),
                Integer.parseInt(required(properties, "ml.cv.repeats")),
                Integer.parseInt(required(properties, "ml.cv.folds")),
                Integer.parseInt(required(properties, "ml.random.seed")),
                Integer.parseInt(required(properties, "ml.random.forest.threads")),
                Integer.parseInt(required(properties, "ml.feature.selection.count"))
        );
        if (config.repeats() < 1 || config.folds() < 2 || config.randomForestThreads() < 1
                || config.featureSelectionCount() < 1) {
            throw new IllegalArgumentException(
                    "repeats, random.forest.threads e feature.selection.count devono essere positivi; "
                            + "folds almeno 2");
        }
        return config;
    }

    private static Path resolve(Path base, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : base.resolve(path)).normalize();
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Proprieta' obbligatoria mancante: " + key);
        }
        return value.trim();
    }
}
