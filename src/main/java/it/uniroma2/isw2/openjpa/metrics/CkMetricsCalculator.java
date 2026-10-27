package it.uniroma2.isw2.openjpa.metrics;

import com.github.mauricioaniche.ck.CK;
import com.github.mauricioaniche.ck.CKClassResult;
import com.github.mauricioaniche.ck.CKNotifier;
import it.uniroma2.isw2.openjpa.model.StaticMetrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CkMetricsCalculator {

    public Map<String, StaticMetrics> compute(Path worktree, List<String> classPaths) throws IOException {
        Path root = worktree.toAbsolutePath().normalize();
        Map<String, List<CKClassResult>> resultsByFile = new ConcurrentHashMap<>();
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        Path[] inputs = classPaths.stream().map(root::resolve).toArray(Path[]::new);
        new CK(false, 0, true).calculate(root, new CKNotifier() {
            @Override
            public void notify(CKClassResult result) {
                Path file = Path.of(result.getFile()).toAbsolutePath().normalize();
                if (!file.startsWith(root)) {
                    return;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                resultsByFile.computeIfAbsent(relative, ignored ->
                        java.util.Collections.synchronizedList(new ArrayList<>())).add(result);
            }

            @Override
            public void notifyError(String sourceFilePath, Exception exception) {
                errors.add(sourceFilePath + ": " + exception.getMessage());
            }
        }, inputs);

        List<String> missing = new ArrayList<>();
        Map<String, StaticMetrics> metrics = collectMetrics(
                root, classPaths, resultsByFile, missing);

        if (!missing.isEmpty() || !errors.isEmpty()) {
            String detail = errors.isEmpty() ? "nessun risultato CK per " + missing.get(0)
                    : errors.get(0);
            throw new IOException("CK non ha analizzato tutte le classi: " + missing.size()
                    + " mancanti, " + errors.size() + " errori. Primo problema: " + detail);
        }
        return Map.copyOf(metrics);
    }

    private static Map<String, StaticMetrics> collectMetrics(
            Path root, List<String> classPaths,
            Map<String, List<CKClassResult>> resultsByFile,
            List<String> missing) throws IOException {
        Map<String, StaticMetrics> metrics = new java.util.LinkedHashMap<>();
        for (String classPath : classPaths) {
            List<CKClassResult> candidates = resultsByFile.getOrDefault(classPath, List.of());
            CKClassResult selected = selectTopLevelClass(classPath, candidates);
            if (selected == null) {
                if (!candidates.isEmpty() || !isAnnotationType(root.resolve(classPath))) {
                    missing.add(classPath);
                }
            } else {
                double lcom = selected.getLcomNormalized();
                if (!Double.isFinite(lcom)) {
                    lcom = 0.0;
                }
                metrics.put(classPath, new StaticMetrics(selected.getLoc(), selected.getWmc(),
                        selected.getCbo(), selected.getRfc(), Math.max(0.0, Math.min(1.0, lcom))));
            }
        }
        return metrics;
    }

    private static boolean isAnnotationType(Path source) throws IOException {
        return java.util.regex.Pattern.compile(
                        "(?m)^\\s*(?:public\\s+)?@interface\\s+[A-Za-z_$][A-Za-z0-9_$]*")
                .matcher(Files.readString(source))
                .find();
    }

    private static CKClassResult selectTopLevelClass(String classPath, List<CKClassResult> candidates) {
        String fileName = Path.of(classPath).getFileName().toString();
        String expected = fileName.substring(0, fileName.length() - ".java".length());
        for (CKClassResult candidate : candidates) {
            String className = candidate.getClassName();
            int separator = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
            String simpleName = separator < 0 ? className : className.substring(separator + 1);
            if (simpleName.equals(expected)) {
                return candidate;
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }
}
