package com.autoproject.service.pics;

import com.autoproject.service.summary.ProposalSummaryRow;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposalImagePipelineTest {

    @Test
    void expandsDottedVenueTypesAndTracksProposalRows() {
        ProposalSummaryRow row = new ProposalSummaryRow(
                "France", null, " Mall . Airport ", null, null, null,
                "Paris", 1, 1);

        List<ProposalImageRequest> requests = ProposalImageRequestParser.parse(List.of(row));

        assertEquals(2, requests.size());
        assertEquals("Mall", requests.get(0).venueType());
        assertEquals("Airports", requests.get(1).venueType());
        assertEquals(2, requests.get(0).proposalRow());
    }

    @Test
    void filtersAgainstWhitelistDeduplicatesAndKeepsOrder() {
        VenueTypeDictionary dictionary = VenueTypeDictionary.load(Path.of("config", "venue_type_dictionary.csv"));
        ProposalSummaryRow row = new ProposalSummaryRow(
                "France", null, "Mall.mall.MALL.Shopping Mall.Airport.Office Building.Grocery",
                null, null, null, "Paris", 1, 1);

        ProposalImageRequestParser.ParseResult parsed = ProposalImageRequestParser.parse(List.of(row), dictionary);

        assertEquals(List.of("Mall", "Airports", "Grocery"),
                parsed.requests().stream().map(ProposalImageRequest::venueType).toList());
        assertEquals(List.of("Shopping Mall", "Office Building"),
                parsed.missingVenueTypes().stream().map(VenueTypeDictionary.MissingVenueType::original).toList());
    }

    @Test
    void fallsBackToUnknownWhenNoCandidateIsWhitelisted() {
        VenueTypeDictionary dictionary = VenueTypeDictionary.load(Path.of("config", "venue_type_dictionary.csv"));
        ProposalSummaryRow row = new ProposalSummaryRow(
                "France", null, "Shopping Center", null, null, null,
                "Paris", 1, 1);

        ProposalImageRequestParser.ParseResult parsed = ProposalImageRequestParser.parse(List.of(row), dictionary);

        assertEquals(1, parsed.requests().size());
        assertEquals("UNKNOWN", parsed.requests().getFirst().venueType());
        assertEquals("shopping center", parsed.missingVenueTypes().getFirst().normalized());
    }

    @Test
    void keepsOnlyCanonicalBillboardsFromOutdoorTaxonomy() {
        VenueTypeDictionary dictionary = VenueTypeDictionary.load(Path.of("config", "venue_type_dictionary.csv"));
        ProposalSummaryRow row = new ProposalSummaryRow(
                "USA", null, "outdoor.billboards.roadside", null, null, null,
                "OUTFRONT", 1, 1);

        ProposalImageRequestParser.ParseResult parsed = ProposalImageRequestParser.parse(List.of(row), dictionary);

        assertEquals(List.of("Billboards"),
                parsed.requests().stream().map(ProposalImageRequest::venueType).toList());
        assertEquals(List.of("outdoor", "roadside"),
                parsed.missingVenueTypes().stream().map(VenueTypeDictionary.MissingVenueType::original).toList());
    }

    @Test
    void readsTheCuratedFeishuImageLinkColumn() throws Exception {
        Path matrix = Files.createTempFile("supply-matrix", ".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Supply");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Country");
            header.createCell(1).setCellValue("MARKET");
            header.createCell(2).setCellValue("Venue Type");
            header.createCell(3).setCellValue("Pictures");
            header.createCell(4).setCellValue("飞书图片link");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("France");
            data.createCell(1).setCellValue("Paris");
            data.createCell(2).setCellValue("Shopping Mall");
            data.createCell(3).setCellValue("https://example.invalid/legacy.jpg");
            data.createCell(4).setCellValue("https://example.invalid/feishu.png");
            try (OutputStream out = Files.newOutputStream(matrix)) {
                workbook.write(out);
            }
        }

        SupplyMatrixImageResolver resolver = SupplyMatrixImageResolver.load(matrix);

        assertFalse(resolver.isEmpty());
        assertEquals(1, resolver.entryCount(), "only the preferred Feishu link column should be indexed");
    }

    @Test
    void createsPlaceholderPicsAndMissingAuditWithoutStoppingExport() throws Exception {
        Path meta = Files.createTempDirectory("pics-meta").resolve("meta");
        ProposalSummaryRow row = new ProposalSummaryRow(
                "FRA", null, "Shopping Mall.Airport", null, null, null,
                "Paris", 1, 1);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var pics = workbook.createSheet("PICS");
            new PicsSheetWriter().writeFromProposalRows(pics, List.of(row), null, meta, false, null);
            assertEquals(1, workbook.getAllPictures().size());
            assertEquals(1, pics.getLastRowNum());
        }

        assertTrue(Files.isDirectory(meta.resolve("images")));
        assertTrue(Files.readString(meta.resolve("missing_images.json")).contains("Airports"));
        assertTrue(Files.isRegularFile(meta.resolve("image_mapping.json")));
        String missingVenue = Files.readString(meta.resolve("missing_venue_type.json"));
        assertTrue(missingVenue.contains("Shopping Mall"));
        assertTrue(missingVenue.contains("shopping mall"));
    }

    @Test
    void writesMatchedFeishuFolderIntoImageCellWithoutDownloading() throws Exception {
        String link = "https://my.feishu.cn/drive/folder/example";
        Path matrix = Files.createTempFile("supply-matrix-link-fallback", ".xlsx");
        Path meta = Files.createTempDirectory("pics-link-fallback").resolve("meta");
        try (XSSFWorkbook matrixWorkbook = new XSSFWorkbook()) {
            var supply = matrixWorkbook.createSheet("Supply");
            Row header = supply.createRow(0);
            header.createCell(0).setCellValue("Media Owner");
            header.createCell(1).setCellValue("Country Code");
            header.createCell(2).setCellValue("Venue Type");
            header.createCell(3).setCellValue("飞书图片link");
            Row data = supply.createRow(1);
            data.createCell(0).setCellValue("OUTFRONT");
            data.createCell(1).setCellValue("USA");
            data.createCell(2).setCellValue("Billboards");
            data.createCell(3).setCellValue(link);
            try (OutputStream out = Files.newOutputStream(matrix)) {
                matrixWorkbook.write(out);
            }
        }

        ProposalSummaryRow proposal = new ProposalSummaryRow(
                "USA", null, "outdoor.billboards.roadside", null, null, null,
                "OUTFRONT", 1, 1);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var pics = workbook.createSheet("PICS");
            new PicsSheetWriter().writeFromProposalRows(
                    pics, List.of(proposal), matrix, meta, false, null);

            assertEquals(1, pics.getRow(1).getCell(3).getNumericCellValue());
            assertEquals(link, pics.getRow(1).getCell(4).getStringCellValue());
            assertEquals(link, pics.getRow(1).getCell(4).getHyperlink().getAddress());
            assertEquals(0, workbook.getAllPictures().size(),
                    "a matched folder link should be preserved without pretending it downloaded an image");
        }
    }

    @Test
    void downloadsAuthenticatedFeishuFolderImageIntoMetaAndPics() throws Exception {
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nAAAAABJRU5ErkJggg==");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-apis/drive/v1/files", exchange -> {
            if (!"Bearer test-user-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/image-token/download")) {
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, png.length);
                exchange.getResponseBody().write(png);
            } else {
                byte[] body = ("{\"code\":0,\"msg\":\"success\",\"data\":{"
                        + "\"files\":[{\"token\":\"image-token\",\"name\":\"poster.png\",\"type\":\"file\"}],"
                        + "\"has_more\":false}}")
                        .getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();

        String oldToken = System.getProperty("propel.feishu.accessToken");
        String oldBase = System.getProperty("propel.feishu.apiBaseUrl");
        String folderLink = "https://my.feishu.cn/drive/folder/test-folder-token";
        Path matrix = Files.createTempFile("supply-matrix-feishu-api", ".xlsx");
        Path meta = Files.createTempDirectory("pics-feishu-api").resolve("meta");
        try {
            System.setProperty("propel.feishu.accessToken", "test-user-token");
            System.setProperty(
                    "propel.feishu.apiBaseUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/open-apis");
            try (XSSFWorkbook matrixWorkbook = new XSSFWorkbook()) {
                var supply = matrixWorkbook.createSheet("Supply");
                Row header = supply.createRow(0);
                header.createCell(0).setCellValue("Media Owner");
                header.createCell(1).setCellValue("Country Code");
                header.createCell(2).setCellValue("Venue Type");
                header.createCell(3).setCellValue("飞书图片link");
                Row data = supply.createRow(1);
                data.createCell(0).setCellValue("OUTFRONT");
                data.createCell(1).setCellValue("USA");
                data.createCell(2).setCellValue("Billboards");
                data.createCell(3).setCellValue(folderLink);
                try (OutputStream out = Files.newOutputStream(matrix)) {
                    matrixWorkbook.write(out);
                }
            }

            ProposalSummaryRow proposal = new ProposalSummaryRow(
                    "USA", null, "outdoor.billboards.roadside", null, null, null,
                    "OUTFRONT", 1, 1);
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                var pics = workbook.createSheet("PICS");
                new PicsSheetWriter().writeFromProposalRows(
                        pics, List.of(proposal), matrix, meta, true, null);

                assertEquals(1, pics.getRow(1).getCell(3).getNumericCellValue());
                assertEquals(folderLink, pics.getRow(1).getCell(4).getStringCellValue());
                assertEquals(1, workbook.getAllPictures().size());
            }
            try (var files = Files.list(meta.resolve("images"))) {
                assertEquals(1, files.filter(Files::isRegularFile).count());
            }
            assertTrue(Files.readString(meta.resolve("image_mapping.json")).contains(folderLink));
        } finally {
            restoreProperty("propel.feishu.accessToken", oldToken);
            restoreProperty("propel.feishu.apiBaseUrl", oldBase);
            server.stop(0);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
