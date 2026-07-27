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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
