package com.autoproject.unit;

import com.autoproject.model.FrameData;
import com.autoproject.service.CsvReader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CsvReaderHeaderAliasTest {

    @Test
    void shouldReadColumnWhenUnderscoreRemovedFromHeader() throws Exception {
        Path csv = Files.createTempDirectory("header-alias-test").resolve("frames.csv");
        Files.writeString(csv, String.join("\n",
                "VIOOHID,ASSETUUID,FLOORCPM,CURRENCY,VIOOHSELECTOPTIN,IMPRESSIONS",
                "uuid-1,FRAME-A,10.5,USD,Yes,100"
        ));

        List<FrameData> rows = new CsvReader().readCsv(csv.toString());

        assertEquals(1, rows.size());
        assertEquals("uuid-1", rows.get(0).getVioohId());
        assertEquals("FRAME-A", rows.get(0).getAssetUuid());
        assertEquals(10.5d, rows.get(0).getFloorCpm(), 1e-6);
        assertEquals("USD", rows.get(0).getMediaOwnerCurrency());
        assertEquals("Yes", rows.get(0).getVioohSelectOptin());
    }

    @Test
    void shouldReadColumnWhenUnderscoreReplacedWithSpaceInHeader() throws Exception {
        Path csv = Files.createTempDirectory("header-space-alias-test").resolve("frames.csv");
        Files.writeString(csv, String.join("\n",
                "VIOOH ID,ASSET UUID,FLOOR CPM,CURRENCY,VIOOH SELECT OPTIN,IMPRESSIONS",
                "uuid-2,FRAME-B,8,JPY,No,200"
        ));

        List<FrameData> rows = new CsvReader().readCsv(csv.toString());

        assertEquals(1, rows.size());
        assertEquals("uuid-2", rows.get(0).getVioohId());
        assertEquals("FRAME-B", rows.get(0).getAssetUuid());
        assertEquals(8d, rows.get(0).getFloorCpm(), 1e-6);
        assertEquals("JPY", rows.get(0).getMediaOwnerCurrency());
        assertEquals("No", rows.get(0).getVioohSelectOptin());
    }

    @Test
    void shouldReadClosestPoiWhenHeaderUsesSpaceOrNoUnderscore() throws Exception {
        Path noUnderscore = Files.createTempDirectory("closest-poi-no-underscore").resolve("frames.csv");
        Files.writeString(noUnderscore, String.join("\n",
                "CLOSESTPOI,DISTANCE_TO_CLOSEST_POI,IMPRESSIONS",
                "Poi-A,3km,100"
        ));
        List<FrameData> rowsNoUnderscore = new CsvReader().readCsv(noUnderscore.toString());
        assertEquals("Poi-A", rowsNoUnderscore.get(0).getClosestPoi());

        Path withSpace = Files.createTempDirectory("closest-poi-space").resolve("frames.csv");
        Files.writeString(withSpace, String.join("\n",
                "CLOSEST POI,DISTANCE TO CLOSEST POI,IMPRESSIONS",
                "Poi-B,5km,200"
        ));
        List<FrameData> rowsWithSpace = new CsvReader().readCsv(withSpace.toString());
        assertEquals("Poi-B", rowsWithSpace.get(0).getClosestPoi());
        assertEquals("5km", rowsWithSpace.get(0).getDistanceToClosestPoi());
    }

    @Test
    void shouldStillPreferExplicitAliasesForFloorCpmAndCurrency() throws Exception {
        Path csv = Files.createTempDirectory("header-explicit-alias-test").resolve("frames.csv");
        Files.writeString(csv, String.join("\n",
                "MARKET,IMPRESSIONS",
                "MKT,100"
        ));

        List<FrameData> rows = new CsvReader().readCsv(csv.toString());

        assertEquals("MKT", rows.get(0).getMarket());
        assertNull(rows.get(0).getFloorCpm());
    }
}
