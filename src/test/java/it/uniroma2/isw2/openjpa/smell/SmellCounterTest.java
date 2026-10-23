package it.uniroma2.isw2.openjpa.smell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmellCounterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void usesOnePmdWorkerToAvoidIntermittentTypeResolutionFailures() {
        assertEquals(1, SmellCounter.configuration().getThreads());
    }

    @Test
    void handlesRawMapAndStillReportsEnumCollections() throws Exception {
        String relative = "src/main/java/example/ClassTableJDBCSeq.java";
        Path source = tempDirectory.resolve(relative);
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;

                import java.util.HashMap;
                import java.util.Map;

                public class ClassTableJDBCSeq {
                    private final Map stats = new HashMap();
                    private final Map<Key, String> values = new HashMap<>();

                    private enum Key {
                        FIRST
                    }
                }
                """);

        Map<String, Integer> smells = new SmellCounter().count(tempDirectory, List.of(relative));

        assertEquals(3, smells.get(relative));
    }
}
