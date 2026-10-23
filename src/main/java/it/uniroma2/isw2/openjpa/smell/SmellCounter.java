package it.uniroma2.isw2.openjpa.smell;

import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.reporting.Report;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SmellCounter {

    public Map<String, Integer> count(Path worktree, List<String> classPaths) throws IOException {
        PMDConfiguration configuration = configuration();
        for (String classPath : classPaths) {
            configuration.addInputPath(worktree.resolve(classPath));
        }

        Map<String, Integer> result = new HashMap<>();
        try (PmdAnalysis analysis = PmdAnalysis.create(configuration)) {
            Report report = analysis.performAnalysisAndCollectReport();
            if (!report.getProcessingErrors().isEmpty()) {
                Report.ProcessingError first = report.getProcessingErrors().get(0);
                throw new IOException("PMD non ha analizzato " + first.getFileId()
                        + ": " + first.getMsg(), first.getError());
            }
            report.getViolations().forEach(violation -> {
                Path absolute = Path.of(violation.getFileId().getAbsolutePath()).normalize();
                String relative = worktree.toAbsolutePath().normalize().relativize(absolute)
                        .toString().replace('\\', '/');
                result.merge(relative, 1, Integer::sum);
            });
        }
        return result;
    }

    static PMDConfiguration configuration() {
        PMDConfiguration configuration = new PMDConfiguration();
        // PMD 7.7 defaults to one worker per CPU. Its Java type resolution may fail
        // intermittently on these old, incomplete snapshots when files run in parallel.
        configuration.setThreads(1);
        configuration.setDefaultLanguageVersion(
                LanguageRegistry.PMD.getLanguageVersionById("java", "17"));
        configuration.addRuleSet("rulesets/openjpa-smells.xml");
        return configuration;
    }
}
