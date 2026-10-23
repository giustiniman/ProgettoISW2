package it.uniroma2.isw2.openjpa.export;

import it.uniroma2.isw2.openjpa.model.DatasetRow;
import it.uniroma2.isw2.openjpa.model.ProcessMetrics;
import it.uniroma2.isw2.openjpa.model.StaticMetrics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class CsvExporter {

    public static final String HEADER = "Project,Release,Class,LOC,LOC_Touched,NR,NFix,NAuth,"
            + "LOC_Added,MAX_LOC_Added,AVG_LOC_Added,Churn,MAX_Churn,AVG_Churn,"
            + "ChgSetSize,MAX_ChgSet,AVG_ChgSet,Age,WeightedAge,WMC,CBO,RFC,LCOM,"
            + "ChangeEntropy,Ownership,NSmells,Buggy";

    public void write(Path output, List<DatasetRow> rows) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.newLine();
            for (DatasetRow row : rows) {
                writer.write(toCsv(row));
                writer.newLine();
            }
        }
    }

    String toCsv(DatasetRow row) {
        StaticMetrics sm = row.staticMetrics();
        ProcessMetrics pm = row.processMetrics();
        return String.join(",",
                escape(row.project()),
                escape(row.release()),
                escape(row.className()),
                Integer.toString(sm.loc()),
                Integer.toString(pm.locTouched()),
                Integer.toString(pm.nr()),
                Integer.toString(pm.nFix()),
                Integer.toString(pm.nAuth()),
                Integer.toString(pm.locAdded()),
                Integer.toString(pm.maxLocAdded()),
                decimal(pm.avgLocAdded()),
                Integer.toString(pm.churn()),
                Integer.toString(pm.maxChurn()),
                decimal(pm.avgChurn()),
                Integer.toString(pm.changeSetSize()),
                Integer.toString(pm.maxChangeSet()),
                decimal(pm.avgChangeSet()),
                decimal(pm.ageWeeks()),
                decimal(pm.weightedAgeWeeks()),
                Integer.toString(sm.wmc()),
                Integer.toString(sm.cbo()),
                Integer.toString(sm.rfc()),
                decimal(sm.lcom()),
                decimal(pm.changeEntropy()),
                decimal(pm.ownership()),
                Integer.toString(row.nSmells()),
                row.buggy() ? "Yes" : "No"
        );
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String escape(String value) {
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
