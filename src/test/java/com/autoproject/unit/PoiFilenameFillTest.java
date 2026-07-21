package com.autoproject.unit;

import com.autoproject.model.FrameData;
import com.autoproject.service.CsvReader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PoiFilenameFillTest {

    @Test
    void shouldFillPoiAndDistanceWhenFilenameUsesDashSeparator() throws Exception {
        Path tempDir = Files.createTempDirectory("poi-fill-dash-test");
        Path csv = tempDir.resolve("BeijingGuomao-3km.csv");

        String content = String.join("\n",
                "CLOSEST_POI,DISTANCE_TO_CLOSEST_POI,IMPRESSIONS",
                ",,100",
                "\\N,-,200"
        );
        Files.writeString(csv, content);

        CsvReader reader = new CsvReader();
        List<FrameData> rows = reader.readCsv(csv.toString());

        assertEquals(2, rows.size(), "should read all rows");
        assertEquals("BeijingGuomao", rows.get(0).getClosestPoi());
        assertEquals("3km", rows.get(0).getDistanceToClosestPoi());
        assertEquals("BeijingGuomao", rows.get(1).getClosestPoi());
        assertEquals("3km", rows.get(1).getDistanceToClosestPoi());
    }

    @Test
    void shouldFillPoiOnlyWhenFilenameHasNoDistance() throws Exception {
        Path tempDir = Files.createTempDirectory("poi-fill-name-only-test");
        Path csv = tempDir.resolve("Louisville International Airport.csv");

        String content = String.join("\n",
                "CLOSEST_POI,DISTANCE_TO_CLOSEST_POI,IMPRESSIONS",
                ",,100",
                "ExistingPOI,9km,200",
                "\\N,-,300"
        );
        Files.writeString(csv, content);

        CsvReader reader = new CsvReader();
        List<FrameData> rows = reader.readCsv(csv.toString());

        assertEquals(3, rows.size());
        assertEquals("Louisville International Airport", rows.get(0).getClosestPoi());
        assertNull(rows.get(0).getDistanceToClosestPoi());
        assertEquals("ExistingPOI", rows.get(1).getClosestPoi());
        assertEquals("9km", rows.get(1).getDistanceToClosestPoi());
        assertEquals("Louisville International Airport", rows.get(2).getClosestPoi());
        assertNull(rows.get(2).getDistanceToClosestPoi());
    }

    @Test
    void shouldFillPoiOnlyFromFramesListSuffixWithoutDistance() throws Exception {
        Path tempDir = Files.createTempDirectory("poi-fill-frameslist-only-test");
        Path csv = tempDir.resolve("BeijingGuomao_FramesList.csv");

        String content = String.join("\n",
                "CLOSEST_POI,DISTANCE_TO_CLOSEST_POI,IMPRESSIONS",
                ",,100"
        );
        Files.writeString(csv, content);

        CsvReader reader = new CsvReader();
        List<FrameData> rows = reader.readCsv(csv.toString());

        assertEquals(1, rows.size());
        assertEquals("BeijingGuomao", rows.get(0).getClosestPoi());
        assertNull(rows.get(0).getDistanceToClosestPoi());
    }

    @Test
    void shouldUseCsvClosestPoiWhenFilenameIsGenericWithoutValidPoi() throws Exception {
        Path tempDir = Files.createTempDirectory("generic-filename-poi-test");
        Path csv = tempDir.resolve("frames.csv");

        String content = String.join("\n",
                "CLOSEST_POI,DISTANCE_TO_CLOSEST_POI,IMPRESSIONS",
                "ColumnPoi,3km,100"
        );
        Files.writeString(csv, content);

        List<FrameData> rows = new CsvReader().readCsv(csv.toString());

        assertEquals(1, rows.size());
        assertEquals("ColumnPoi", rows.get(0).getClosestPoi());
        assertEquals("3km", rows.get(0).getDistanceToClosestPoi());
    }

    @Test
    void shouldStripAllSuffixForPoiOnlyFilename() throws Exception {
        Path tempDir = Files.createTempDirectory("poi-fill-all-suffix-test");
        Path csv = tempDir.resolve("UK_DE_all.csv");

        String content = String.join("\n",
                "CLOSEST_POI,DISTANCE_TO_CLOSEST_POI,IMPRESSIONS",
                ",,100",
                "ColumnPoi,5km,200"
        );
        Files.writeString(csv, content);

        CsvReader reader = new CsvReader();
        List<FrameData> rows = reader.readCsv(csv.toString());

        assertEquals(2, rows.size());
        assertEquals("UK_DE", rows.get(0).getClosestPoi());
        assertNull(rows.get(0).getDistanceToClosestPoi());
        assertEquals("ColumnPoi", rows.get(1).getClosestPoi());
        assertEquals("5km", rows.get(1).getDistanceToClosestPoi());
    }
}
