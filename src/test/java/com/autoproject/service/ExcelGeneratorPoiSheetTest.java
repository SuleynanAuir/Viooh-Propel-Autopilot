package com.autoproject.service;

import com.autoproject.model.Brief;
import com.autoproject.model.FrameData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcelGeneratorPoiSheetTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteAddressCountryAsFirstColumnOnPoiSheet() throws Exception {
        FrameData de = frame("UK_DE", "DEU", 100d);
        FrameData uk = frame("UK_DE", "GBR", 200d);
        FrameData paris = frame("Paris", "FRA", 300d);

        Path out = tempDir.resolve("poi-sheet.xlsx");
        Brief brief = new Brief();
        brief.setLocation("Europe");
        brief.setBudget(1000);
        new ExcelGenerator().generate(List.of(de, uk, paris), out.toString(), brief);

        try (InputStream in = Files.newInputStream(out);
             Workbook workbook = new XSSFWorkbook(in)) {
            Sheet poiSheet = workbook.getSheet("POI");
            assertEquals("ADDRESS_COUNTRY", poiSheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Location", poiSheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("POI", poiSheet.getRow(0).getCell(2).getStringCellValue());

            assertEquals("DEU/GBR", poiSheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Europe", poiSheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("UK_DE", poiSheet.getRow(1).getCell(2).getStringCellValue());

            assertEquals("FRA", poiSheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals("Paris", poiSheet.getRow(2).getCell(2).getStringCellValue());
        }
    }

    @Test
    void shouldWriteNullSentinelWhenPoiHasNoIso3Countries() throws Exception {
        FrameData frame = frame("Mystery", null, 100d);

        Path out = tempDir.resolve("poi-sheet-empty-country.xlsx");
        new ExcelGenerator().generate(List.of(frame), out.toString());

        try (InputStream in = Files.newInputStream(out);
             Workbook workbook = new XSSFWorkbook(in)) {
            Sheet poiSheet = workbook.getSheet("POI");
            Row row = poiSheet.getRow(1);
            assertEquals("\\N", row.getCell(0).getStringCellValue());
            assertEquals("Mystery", row.getCell(2).getStringCellValue());
        }
    }

    private static FrameData frame(String poi, String iso3, Double impressions) {
        FrameData data = new FrameData();
        data.setClosestPoi(poi);
        data.setAddressIso3CountryCode(iso3);
        data.setImpressions(impressions);
        data.setMarket("MKT");
        data.setAssetUuid(poi + "-" + (iso3 == null ? "X" : iso3));
        return data;
    }
}
