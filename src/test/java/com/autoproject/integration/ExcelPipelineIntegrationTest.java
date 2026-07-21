package com.autoproject.integration;

import com.autoproject.model.FrameData;
import com.autoproject.service.CsvReader;
import com.autoproject.service.DataMerger;
import com.autoproject.service.ExcelGenerator;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelPipelineIntegrationTest {

    @Test
    void shouldMergeFourProvidedFilesAndGenerateFilteredSheet() throws Exception {
        List<Path> inputs = resolveInputPaths();
        Path output = Path.of("merged_output.xlsx");

        DataMerger merger = new DataMerger();
        List<FrameData> merged = merger.merge(inputs.stream().map(Path::toString).toArray(String[]::new));

       // 1. check if the merged data is not empty
        assertFalse(merged.isEmpty(), "result should not be empty");

        ExcelGenerator generator = new ExcelGenerator();
        generator.generate(merged, output.toString());

        try (InputStream in = Files.newInputStream(output);
             Workbook workbook = new XSSFWorkbook(in)) {

            Sheet dataSheet = workbook.getSheet("UnfilteredFrames");
            Sheet filteredSheet = workbook.getSheet("FilteredFrames");
            Sheet summarySheet = workbook.getSheet("Proposal");

            // 2. check if the data sheets are existing
            assertNotNull(dataSheet, "must have UnfilteredFrames sheet");
            assertNotNull(filteredSheet, "must have FilteredFrames sheet");
            assertNotNull(summarySheet, "must have Proposal sheet");

            int dataRows = dataSheet.getLastRowNum(); // exclude header
            int filteredRows = filteredSheet.getLastRowNum(); // exclude header

            // 3. check if the data sheet and filtered sheet are not null
            assertEquals(merged.size(), dataRows, "UnfilteredFrames sheet should have the same number of rows as the merged data");

            long filteredExportRows = ExcelGenerator.countFilteredExportRows(merged);
            assertEquals(filteredExportRows, filteredRows,
                    "FilteredFrames sheet should match non-zero impressions rows after market|assetUuid dedupe (same as export loop)");
        }
    }

    @Test
    void shouldPreferCsvPoiAndUseFilenamePoiOnlyAsFallback() throws Exception {
        Path tempDir = Files.createTempDirectory("poi-fill-test");
        Path csv = tempDir.resolve("BeijingGuomao_3km.csv");
        String content = String.join("\n",
                "CLOSEST_POI,DISTANCE_TO_CLOSEST_POI,IMPRESSIONS",
                ",,100",
                "ExistingPOI,,200",
                ",8km,300",
                "ExistingPOI,9km,400",
                "\\N,-,500"
        );
        Files.writeString(csv, content);

        CsvReader reader = new CsvReader();
        List<FrameData> rows = reader.readCsv(csv.toString());

        assertEquals(5, rows.size(), "should read all rows");
        assertEquals("BeijingGuomao", rows.get(0).getClosestPoi());
        assertEquals("3km", rows.get(0).getDistanceToClosestPoi());
        assertEquals("ExistingPOI", rows.get(1).getClosestPoi(),
                "CLOSEST_POI from CSV should take priority over the file name");
        assertNull(rows.get(1).getDistanceToClosestPoi());
        assertEquals("BeijingGuomao", rows.get(2).getClosestPoi());
        assertEquals("3km", rows.get(2).getDistanceToClosestPoi());
        assertEquals("ExistingPOI", rows.get(3).getClosestPoi());
        assertEquals("9km", rows.get(3).getDistanceToClosestPoi(),
                "CSV distance should stay paired with the preferred CSV POI");
        assertEquals("BeijingGuomao", rows.get(4).getClosestPoi());
        assertEquals("3km", rows.get(4).getDistanceToClosestPoi());

        assertTrue(Files.exists(csv), "test csv should exist during assertion");
    }

    @Test
    void shouldUseCsvClosestPoiWhenFilenameHasNoValidPoi() throws Exception {
        Path tempDir = Files.createTempDirectory("poi-csv-fallback-test");
        Path csv = tempDir.resolve("legacy_input.csv");
        String content = String.join("\n",
                "CLOSEST_POI,DISTANCE_TO_CLOSEST_POI,IMPRESSIONS",
                "ColumnPoi,5km,100",
                ",,200"
        );
        Files.writeString(csv, content);

        CsvReader reader = new CsvReader();
        List<FrameData> rows = reader.readCsv(csv.toString());

        assertEquals("ColumnPoi", rows.get(0).getClosestPoi());
        assertEquals("5km", rows.get(0).getDistanceToClosestPoi());
        assertNull(rows.get(1).getClosestPoi());
        assertNull(rows.get(1).getDistanceToClosestPoi());
    }

    @Test
    void shouldMergeExcelFrameListInputLikeCsv() throws Exception {
        Path tempDir = Files.createTempDirectory("excel-frame-list-test");
        Path xlsx = tempDir.resolve("ExcelFallback_2km.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Frames");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("MARKET");
            header.createCell(1).setCellValue("ASSETUUID");
            header.createCell(2).setCellValue("IMPRESSIONS");
            header.createCell(3).setCellValue("CLOSEST_POI");
            header.createCell(4).setCellValue("DISTANCE_TO_CLOSEST_POI");
            header.createCell(5).setCellValue("SCORE_P");

            org.apache.poi.ss.usermodel.Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("MKT");
            row1.createCell(1).setCellValue("asset-1");
            row1.createCell(2).setCellValue(100d);
            row1.createCell(3).setCellValue("ExcelPOI");
            row1.createCell(4).setCellValue("7km");
            row1.createCell(5).setCellValue(91.5d);

            org.apache.poi.ss.usermodel.Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("MKT");
            row2.createCell(1).setCellValue("asset-2");
            row2.createCell(2).setCellValue(200d);

            try (OutputStream out = Files.newOutputStream(xlsx)) {
                workbook.write(out);
            }
        }

        DataMerger merger = new DataMerger();
        List<FrameData> rows = merger.merge(xlsx.toString());

        assertEquals(2, rows.size(), "Excel frame-list rows should be read");
        assertEquals("ExcelPOI", rows.get(0).getClosestPoi(),
                "Excel CLOSEST_POI should take priority over the file name");
        assertEquals("7km", rows.get(0).getDistanceToClosestPoi());
        assertEquals(91.5d, rows.get(0).getScoreP(), 1e-6);
        assertEquals("ExcelFallback", rows.get(1).getClosestPoi(),
                "Excel rows without CLOSEST_POI should use file-name fallback");
        assertEquals("2km", rows.get(1).getDistanceToClosestPoi());
    }

    @Test
    void shouldFillPoiAndDistanceFromDashSeparatedFileName() throws Exception {
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

        assertTrue(Files.exists(csv), "test csv should exist during assertion");
    }

    @Test
    void shouldReadScorePWhenPresentAndExportClosestPoiAliasAsLastColumn() throws Exception {
        Path tempDir = Files.createTempDirectory("score-p-test");
        Path withScore = tempDir.resolve("FallbackPoi_3km.csv");
        Files.writeString(withScore, String.join("\n",
                "IMPRESSIONS,MARKET,ASSETUUID,SCORE_P,CLOSEST_POI",
                "100,MKT,uuid-1,97.5,CsvPOI",
                "200,MKT,uuid-2,\\N,"
        ));
        Path withoutScore = tempDir.resolve("without_score.csv");
        Files.writeString(withoutScore, String.join("\n",
                "IMPRESSIONS,MARKET,ASSETUUID,IATA",
                "100,MKT,uuid-3,\\N"
        ));

        CsvReader reader = new CsvReader();
        List<FrameData> withRows = reader.readCsv(withScore.toString());
        assertEquals(97.5d, withRows.get(0).getScoreP(), 1e-6);
        assertNull(withRows.get(1).getScoreP());

        List<FrameData> withoutRows = reader.readCsv(withoutScore.toString());
        assertNull(withoutRows.get(0).getScoreP());

        Path out = tempDir.resolve("out.xlsx");
        ExcelGenerator generator = new ExcelGenerator();
        generator.generate(withRows, out.toString());

        try (InputStream in = Files.newInputStream(out);
             Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheet("UnfilteredFrames");
            org.apache.poi.ss.usermodel.Row header = sheet.getRow(0);
            int lastCol = header.getLastCellNum() - 1;
            int scoreCol = lastCol - 1;
            assertEquals("SCORE_P", header.getCell(scoreCol).getStringCellValue());
            assertEquals("CLOEST_POI", header.getCell(lastCol).getStringCellValue());
            assertEquals(97.5d, sheet.getRow(1).getCell(scoreCol).getNumericCellValue(), 1e-6);
            assertEquals("\\N", sheet.getRow(2).getCell(scoreCol).getStringCellValue());
            assertEquals("CsvPOI", sheet.getRow(1).getCell(lastCol).getStringCellValue(),
                    "CSV CLOSEST_POI should take priority in the exported CLOEST_POI column");
            assertEquals("FallbackPoi", sheet.getRow(2).getCell(lastCol).getStringCellValue(),
                    "filename POI should be exported only when CSV CLOSEST_POI is empty");
        }
    }

    @Test
    void shouldFillPoiFromLegacyInputFileNameWithoutDistance() throws Exception {
        Path tempDir = Files.createTempDirectory("poi-no-fill-test");
        Path csv = tempDir.resolve("legacy_input_file.csv");
        String content = String.join("\n",
                "CLOSEST_POI,DISTANCE_TO_CLOSEST_POI,IMPRESSIONS",
                ",,100",
                "\\N,-,200"
        );
        Files.writeString(csv, content);

        CsvReader reader = new CsvReader();
        List<FrameData> rows = reader.readCsv(csv.toString());

        assertEquals(2, rows.size(), "should read all rows");
        assertEquals("legacy_input_file", rows.get(0).getClosestPoi());
        assertNull(rows.get(0).getDistanceToClosestPoi());
        assertEquals("legacy_input_file", rows.get(1).getClosestPoi());
        assertNull(rows.get(1).getDistanceToClosestPoi());
    }

    @Test
    void shouldBackfillCountryAndIso3AfterMergeWithAdjacentAndPoiFallbacks() throws Exception {
        Path tempDir = Files.createTempDirectory("country-map-fill-test");
        Path csv = tempDir.resolve("country_fill.csv");
        String content = String.join("\n",
                "ADDRESS_COUNTRY,ADDRESS_ISO3_COUNTRY_CODE,CLOSEST_POI,IMPRESSIONS",
                "Germany,,Berlin Brandenburg,100",
                "Germany,DEU,Berlin Brandenburg,200",
                ",DEU,Berlin Brandenburg,300",
                "France,FRA,Paris CDG,400",
                ",FRA,Paris CDG,500",
                "Congo,COG,Kinshasa Center,600",
                "Congo,COD,Brazzaville Center,700",
                "Congo,,Kinshasa Center,800",
                ",,Paris CDG,900"
        );
        Files.writeString(csv, content);

        DataMerger merger = new DataMerger();
        List<FrameData> merged = merger.merge(csv.toString());

        assertEquals(9, merged.size(), "should keep all rows after merge");
        assertEquals("DEU", merged.get(0).getAddressIso3CountryCode(), "should fill missing iso3 from same country mapping");
        assertEquals("Germany", merged.get(2).getAddressCountry(), "should fill missing country from same iso3 mapping");
        assertEquals("France", merged.get(4).getAddressCountry(), "should fill another unique iso3->country mapping");
        assertEquals("COD", merged.get(7).getAddressIso3CountryCode(), "adjacent row should fill missing iso3 even when global mapping is ambiguous");
        assertEquals("France", merged.get(8).getAddressCountry(), "both-missing row should be filled from unique POI mapping");
        assertEquals("FRA", merged.get(8).getAddressIso3CountryCode(), "both-missing row should recover iso3 from unique POI mapping");
    }

    @Test
    void shouldBackfillUsingStandardIsoCountryMapWhenNoFileMappingExists() throws Exception {
        Path tempDir = Files.createTempDirectory("country-standard-map-fill-test");
        Path csv = tempDir.resolve("country_standard_fill.csv");
        String content = String.join("\n",
                "ADDRESS_COUNTRY,ADDRESS_ISO3_COUNTRY_CODE,IMPRESSIONS",
                "Japan,,100",
                ",ESP,200"
        );
        Files.writeString(csv, content);

        DataMerger merger = new DataMerger();
        List<FrameData> merged = merger.merge(csv.toString());

        assertEquals(2, merged.size(), "should keep all rows after merge");
        assertEquals("JPN", merged.get(0).getAddressIso3CountryCode(), "should fill iso3 from standard country map");
        assertEquals("Spain", merged.get(1).getAddressCountry(), "should fill country from standard iso3 map");
    }

    @Test
    void shouldDeduplicateFilteredFramesByMarketAndAssetUuid() throws Exception {
        Path tempDir = Files.createTempDirectory("filtered-dedup-test");
        Path csv = tempDir.resolve("filtered_dedup.csv");
        String content = String.join("\n",
                "MARKET,ASSETUUID,IMPRESSIONS",
                "DE,asset-1,100",
                "DE,asset-1,200",
                "DE,asset-2,300",
                "FR,asset-1,0"
        );
        Files.writeString(csv, content);

        DataMerger merger = new DataMerger();
        List<FrameData> merged = merger.merge(csv.toString());
        Path output = tempDir.resolve("dedup_result.xlsx");

        ExcelGenerator generator = new ExcelGenerator();
        generator.generate(merged, output.toString());

        try (InputStream in = Files.newInputStream(output);
             Workbook workbook = new XSSFWorkbook(in)) {
            Sheet filteredSheet = workbook.getSheet("FilteredFrames");
            assertNotNull(filteredSheet, "must have FilteredFrames sheet");
            assertEquals(2, filteredSheet.getLastRowNum(), "filtered rows should keep one row per MARKET+ASSETUUID with valid impressions");
        }
    }

    // tool method: check if the file exists
    private Path requirePathProperty(String propertyName) {
        String raw = System.getProperty(propertyName);
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "lack of JVM property: -" + "D" + propertyName + "=<file-path>"
            );
        }
        try {
            Path path = Path.of(raw.trim());
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("file not found: " + path);
            }
            return path;
        } catch (Exception e) {
            throw new IllegalStateException("invalid path for " + propertyName + ": " + raw, e);
        }
    }

    // tool method: 2 method for test input (manual or auto)
    private List<Path> resolveInputPaths() throws Exception {
        String i1 = System.getProperty("test.input1");
        String i2 = System.getProperty("test.input2");
        String i3 = System.getProperty("test.input3");
        String i4 = System.getProperty("test.input4");
        boolean allProvided = isNotBlank(i1) && isNotBlank(i2) && isNotBlank(i3) && isNotBlank(i4);
        if (allProvided) {
            return List.of(
                    requirePathProperty("test.input1"),
                    requirePathProperty("test.input2"),
                    requirePathProperty("test.input3"),
                    requirePathProperty("test.input4")
            );
        }

        Path integrationDir = Path.of("src", "test", "resources", "integration");
        if (!Files.isDirectory(integrationDir)) {
            throw new IllegalStateException("catalog not found: " + integrationDir.toAbsolutePath());
        }
        try (Stream<Path> stream = Files.list(integrationDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".csv") || n.endsWith(".tsv") || n.endsWith(".xlsx") || n.endsWith(".xls");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
            if (files.size() != 4) {
                throw new IllegalStateException(
                        "integration test input files count should be 4, but got " + files.size()
                );
            }
            return files;
        }
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
