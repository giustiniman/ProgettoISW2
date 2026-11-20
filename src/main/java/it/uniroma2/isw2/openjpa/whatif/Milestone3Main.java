package it.uniroma2.isw2.openjpa.whatif;

import it.uniroma2.isw2.openjpa.whatif.WhatIfAnalysisService.AnalysisResult;
import it.uniroma2.isw2.openjpa.whatif.WhatIfAnalysisService.DatasetResult;
import it.uniroma2.isw2.openjpa.whatif.WhatIfAnalysisService.PreventionResult;

import java.util.Locale;
import java.util.logging.Logger;

public final class Milestone3Main {

    private static final Logger LOGGER = Logger.getLogger(Milestone3Main.class.getName());

    private Milestone3Main() {
    }

    public static void main(String[] args) {
        try {
            WhatIfConfig config = WhatIfConfig.load();
            AnalysisResult result = new WhatIfAnalysisService().analyze(config);
            new WhatIfResultsExporter().write(config.outputDirectory(), result);

            LOGGER.info(() -> String.format(Locale.ROOT,
                    "BClassifierA: %s / %s (score CV %.6f)",
                    result.selectedModel().classifier(), result.selectedModel().pipeline(),
                    result.selectedModel().selectionScore()));
            for (DatasetResult row : result.table()) {
                LOGGER.info(() -> String.format(Locale.ROOT,
                        "  %s: istanze=%d, actual=%s, estimated=%d",
                        row.dataset(), row.instances(),
                        row.actualBuggy() == null ? "n.a." : row.actualBuggy().toString(),
                        row.estimatedBuggy()));
            }
            PreventionResult prevention = result.prevention();
            LOGGER.info(() -> String.format(Locale.ROOT,
                    "Buggy prevenute: %d; proporzione su A: %.6f; tra le buggy con smell: %.6f",
                    prevention.preventedTotal(), prevention.preventedProportion(),
                    prevention.preventedAmongSmellyBuggy()));
            LOGGER.info(() -> "Risultati Milestone 3 scritti in: " + config.outputDirectory());
        } catch (Exception exception) {
            throw new IllegalStateException("Milestone 3 interrotta", exception);
        }
    }
}
