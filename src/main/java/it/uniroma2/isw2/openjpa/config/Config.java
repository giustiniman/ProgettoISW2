package it.uniroma2.isw2.openjpa.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public record Config(Path repository, String repositoryUrl, String jiraBaseUrl, String jiraProject,
                     List<String> tagPatterns, String dateFallbackRef, double releaseFraction,
                     Set<String> excludedCommits, Path outputCsv, Path workDirectory) {

    public static Config load() throws IOException {
        Path configPath = Path.of(System.getProperty("config.file", "config.properties"))
                .toAbsolutePath().normalize();
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configPath)) {
            properties.load(input);
        }

        Path base = configPath.getParent();
        Path repository = resolve(base, required(properties, "repository.path"));
        Path output = resolve(base, required(properties, "output.csv"));
        Path work = resolve(base, required(properties, "work.directory"));
        List<String> patterns = Arrays.stream(required(properties, "git.tag.patterns").split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        Set<String> excludedCommits = parseCommitHashes(
                properties.getProperty("git.excluded.commits", ""));

        double fraction = Double.parseDouble(required(properties, "release.fraction"));
        if (!(fraction > 0.0 && fraction <= 1.0)) {
            throw new IllegalArgumentException("release.fraction deve essere nell'intervallo (0, 1]");
        }
        return new Config(
                repository,
                required(properties, "repository.url"),
                required(properties, "jira.base.url").replaceAll("/+$", ""),
                required(properties, "jira.project"),
                patterns,
                required(properties, "git.date.fallback.ref"),
                fraction,
                excludedCommits,
                output,
                work
        );
    }

    private static Set<String> parseCommitHashes(String value) {
        Set<String> hashes = new LinkedHashSet<>();
        for (String candidate : value.split(",")) {
            String hash = candidate.trim().toLowerCase(java.util.Locale.ROOT);
            if (hash.isEmpty()) {
                continue;
            }
            if (!hash.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException(
                        "git.excluded.commits contiene un hash Git non valido: " + candidate.trim());
            }
            hashes.add(hash);
        }
        return Set.copyOf(hashes);
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
