package it.uniroma2.isw2.openjpa.metrics;

import it.uniroma2.isw2.openjpa.model.StaticMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CkMetricsCalculatorTest {

    @TempDir
    Path project;

    @Test
    void selectsTheTopLevelClassAndReturnsNormalizedLcom() throws Exception {
        String relative = "src/main/java/sample/Example.java";
        Path source = project.resolve(relative);
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package sample;
                class Example {
                    private int value;
                    void first() { value++; second(); }
                    void second() { if (value > 0) { value--; } }
                    class Inner { void ignored() { } }
                }
                """);

        Map<String, StaticMetrics> result = new CkMetricsCalculator()
                .compute(project, List.of(relative));

        StaticMetrics metrics = result.get(relative);
        assertEquals(1, result.size());
        assertTrue(metrics.loc() > 0);
        assertTrue(metrics.wmc() > 0);
        assertTrue(metrics.rfc() > 0);
        assertTrue(metrics.lcom() >= 0.0 && metrics.lcom() <= 1.0);
    }

    @Test
    void excludesAnnotationTypesThatCkDoesNotModelAsClasses() throws Exception {
        String relative = "src/main/java/sample/Marker.java";
        Path source = project.resolve(relative);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package sample;\npublic @interface Marker { }\n");

        Map<String, StaticMetrics> result = new CkMetricsCalculator()
                .compute(project, List.of(relative));

        assertTrue(result.isEmpty());
    }
}
