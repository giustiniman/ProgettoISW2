package it.uniroma2.isw2.openjpa.export;

import it.uniroma2.isw2.openjpa.model.DatasetRow;
import it.uniroma2.isw2.openjpa.model.ProcessMetrics;
import it.uniroma2.isw2.openjpa.model.StaticMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvExporterTest {

    @Test
    void writesAllTwentyTwoFeaturesPlusSmellsAndTarget() {
        DatasetRow row = new DatasetRow("OPENJPA", "1.0.0", "a,b/A.java",
                new StaticMetrics(1, 2, 3, 4, 5),
                new ProcessMetrics(6, 7, 8, 9, 10, 11, 12.0,
                        13, 14, 15.0, 16, 17, 18.0, 19, 20.0, 0.5, 0.8),
                2, true);

        String csv = new CsvExporter().toCsv(row);

        assertEquals(27, CsvExporter.HEADER.split(",").length);
        assertEquals(27, csv.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1).length);
    }
}
