package com.autoproject.web;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebExportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void usesLatestPicsPipelineWithoutFailingWhenRemoteDownloadsAreDisabled() throws Exception {
        Path taskRoot = tempDir.resolve("task");
        Files.createDirectories(taskRoot);
        Path supplyMatrix = createSupplyMatrix();
        String csv = String.join("\n",
                "MARKET,ASSETUUID,ADDRESS_COUNTRY,ADDRESS_ISO3_COUNTRY_CODE,"
                        + "VENUE_TAXONOMY_VALUE,IMPRESSIONS,FLOORCPM,MEDIAOWNERCURRENCY,VIOOHSELECTOPTIN",
                "Web Test Market,asset-1,France,FRA,outdoor.billboards.roadside,1000,5,EUR,Yes",
                "Web Test Market,asset-2,United Kingdom,GBR,Shopping Center,1000,5,EUR,Yes");
        MultipartFormData.Form form = parseForm(taskRoot, csv);

        String oldSupplyMatrixPath = System.getProperty("propel.supplyMatrixPath");
        WebExportService.ExportResult result;
        try {
            System.setProperty("propel.supplyMatrixPath", supplyMatrix.toString());
            result = new WebExportService(false).export(form, taskRoot);
        } finally {
            restoreProperty("propel.supplyMatrixPath", oldSupplyMatrixPath);
        }

        assertTrue(Files.isRegularFile(result.path()));
        assertEquals(2, result.mergedRows());
        assertEquals(2, result.filteredRows());
        try (InputStream in = Files.newInputStream(result.path());
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            Sheet pics = workbook.getSheet("PICS");
            assertNotNull(pics);
            assertEquals("Web Test Market", pics.getRow(1).getCell(0).getStringCellValue());
            assertEquals("FRA", pics.getRow(1).getCell(1).getStringCellValue());
            assertEquals("Billboards", pics.getRow(1).getCell(2).getStringCellValue());
            assertEquals("https://my.feishu.cn/drive/folder/web-test",
                    pics.getRow(1).getCell(4).getHyperlink().getAddress(),
                    "the offline web flow must preserve the curated supply-matrix link");
            assertEquals("UNKNOWN", pics.getRow(2).getCell(2).getStringCellValue(),
                    "the web export must use the current venue dictionary and UNKNOWN fallback");
            assertEquals(1, workbook.getAllPictures().size(),
                    "the current offline PICS flow must insert one placeholder for the unmatched request");
        }

        String missingVenueAudit = Files.readString(taskRoot.resolve("meta").resolve("missing_venue_type.json"));
        assertTrue(missingVenueAudit.contains("Shopping Center"));
        assertTrue(Files.isRegularFile(taskRoot.resolve("meta").resolve("image_mapping.json")));
    }

    @Test
    void convertsEveryConfiguredSourceCurrencyAndCalculatesPackageInTargetCurrency() throws Exception {
        Path taskRoot = tempDir.resolve("multi-currency-task");
        Files.createDirectories(taskRoot);
        Path supplyMatrix = createSupplyMatrix();
        String csv = String.join("\n",
                "MARKET,ASSETUUID,ADDRESS_COUNTRY,ADDRESS_ISO3_COUNTRY_CODE,"
                        + "VENUE_TAXONOMY_VALUE,IMPRESSIONS,FLOORCPM,MEDIAOWNERCURRENCY,VIOOHSELECTOPTIN",
                "EUR Market,asset-eur,France,FRA,outdoor.billboards,100000,5,EUR,Yes",
                "USD Market,asset-usd,United States,USA,outdoor.billboards,100000,5,USD,Yes",
                "SGD Market,asset-sgd,Singapore,SGP,outdoor.billboards,100000,5,SGD,Yes");
        MultipartFormData.Form form = parseMultiCurrencyForm(
                taskRoot,
                csv,
                "USD",
                "EUR=1.08,USD=1,SGD=0.74");

        String oldSupplyMatrixPath = System.getProperty("propel.supplyMatrixPath");
        WebExportService.ExportResult result;
        try {
            System.setProperty("propel.supplyMatrixPath", supplyMatrix.toString());
            result = new WebExportService(false).export(form, taskRoot);
        } finally {
            restoreProperty("propel.supplyMatrixPath", oldSupplyMatrixPath);
        }

        try (InputStream in = Files.newInputStream(result.path());
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            Sheet proposal = workbook.getSheet("Proposal");
            assertNotNull(proposal);
            assertEquals("Floor price 2026 CPM (USD)",
                    proposal.getRow(0).getCell(8).getStringCellValue());
            assertEquals("Media Budget (USD)",
                    proposal.getRow(0).getCell(17).getStringCellValue());

            Map<String, Double> convertedCpmByCurrency = new LinkedHashMap<>();
            for (int rowIndex = 1; rowIndex <= 3; rowIndex++) {
                var row = proposal.getRow(rowIndex);
                convertedCpmByCurrency.put(
                        row.getCell(7).getStringCellValue(),
                        row.getCell(8).getNumericCellValue());
                assertTrue(row.getCell(17).getCellFormula().contains("I" + (rowIndex + 1)),
                        "package budget must reference the target-currency CPM column");
            }
            // VIOOH Select adds 20% before each source-specific exchange rate is applied.
            assertEquals(6.48d, convertedCpmByCurrency.get("EUR"), 0.0001d);
            assertEquals(6d, convertedCpmByCurrency.get("USD"), 0.0001d);
            assertEquals(4.44d, convertedCpmByCurrency.get("SGD"), 0.0001d);
        }
    }

    @Test
    void rejectsAFrameCurrencyThatHasNoTargetRate() throws Exception {
        Path taskRoot = tempDir.resolve("missing-rate-task");
        Files.createDirectories(taskRoot);
        String csv = String.join("\n",
                "MARKET,ASSETUUID,ADDRESS_ISO3_COUNTRY_CODE,"
                        + "VENUE_TAXONOMY_VALUE,IMPRESSIONS,FLOORCPM,MEDIAOWNERCURRENCY,VIOOHSELECTOPTIN",
                "EUR Market,asset-eur,FRA,outdoor.billboards,1000,5,EUR,Yes",
                "SGD Market,asset-sgd,SGP,outdoor.billboards,1000,5,SGD,Yes");
        MultipartFormData.Form form = parseMultiCurrencyForm(
                taskRoot,
                csv,
                "USD",
                "EUR=1.08,USD=1");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new WebExportService(false).export(form, taskRoot));

        assertTrue(error.getMessage().contains("Missing exchange rate to USD"));
        assertTrue(error.getMessage().contains("SGD"));
    }

    private Path createSupplyMatrix() throws Exception {
        Path matrix = tempDir.resolve("supply-matrix.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Supply");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Media Owner");
            header.createCell(1).setCellValue("Country Code");
            header.createCell(2).setCellValue("Venue Type");
            header.createCell(3).setCellValue("飞书图片link");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("Web Test Market");
            row.createCell(1).setCellValue("FRA");
            row.createCell(2).setCellValue("Billboards");
            row.createCell(3).setCellValue("https://my.feishu.cn/drive/folder/web-test");
            try (OutputStream out = Files.newOutputStream(matrix)) {
                workbook.write(out);
            }
        }
        return matrix;
    }

    private static MultipartFormData.Form parseForm(Path taskRoot, String csv) throws Exception {
        String boundary = "propel-web-test-boundary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "budget", "10000");
        writeField(body, boundary, "campaignDays", "7");
        writeField(body, boundary, "location", "Paris");
        writeField(body, boundary, "sourceCurrency", "EUR");
        writeField(body, boundary, "targetCurrency", "EUR");
        writeField(body, boundary, "exchangeRate", "1");
        writeField(body, boundary, "photographyMode", "none");
        writeField(body, boundary, "fetchPicsFromLinks", "true");
        writeField(body, boundary, "outputName", "web-latest-pics.xlsx");
        writeFile(body, boundary, "inputFiles", "web-test.csv", "text/csv", csv);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] request = body.toByteArray();
        return MultipartFormData.parse(
                new ByteArrayInputStream(request),
                "multipart/form-data; boundary=" + boundary,
                request.length + 1024L,
                taskRoot);
    }

    private static MultipartFormData.Form parseMultiCurrencyForm(
            Path taskRoot,
            String csv,
            String targetCurrency,
            String currencyRates
    ) throws Exception {
        String boundary = "propel-multi-currency-boundary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "budget", "10000");
        writeField(body, boundary, "campaignDays", "7");
        writeField(body, boundary, "location", "");
        writeField(body, boundary, "targetCurrency", targetCurrency);
        writeField(body, boundary, "currencyRates", currencyRates);
        writeField(body, boundary, "photographyMode", "none");
        writeField(body, boundary, "fetchPicsFromLinks", "false");
        writeField(body, boundary, "outputName", "multi-currency.xlsx");
        writeFile(body, boundary, "inputFiles", "multi-currency.csv", "text/csv", csv);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] request = body.toByteArray();
        return MultipartFormData.parse(
                new ByteArrayInputStream(request),
                "multipart/form-data; boundary=" + boundary,
                request.length + 1024L,
                taskRoot);
    }

    private static void writeField(
            ByteArrayOutputStream body, String boundary, String name, String value) throws Exception {
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(value.getBytes(StandardCharsets.UTF_8));
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFile(
            ByteArrayOutputStream body,
            String boundary,
            String fieldName,
            String fileName,
            String contentType,
            String content) throws Exception {
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"" + fieldName
                + "\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content.getBytes(StandardCharsets.UTF_8));
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
