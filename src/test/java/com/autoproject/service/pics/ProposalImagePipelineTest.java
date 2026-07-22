package com.autoproject.service.pics;

import com.autoproject.service.summary.ProposalSummaryRow;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposalImagePipelineTest {

    @Test
    void expandsDottedVenueTypesAndTracksProposalRows() {
        ProposalSummaryRow row = new ProposalSummaryRow(
                "France", null, " Shopping Mall . Airport ", null, null, null,
                "Paris", 1, 1);

        List<ProposalImageRequest> requests = ProposalImageRequestParser.parse(List.of(row));

        assertEquals(2, requests.size());
        assertEquals("Shopping Mall", requests.get(0).venueType());
        assertEquals("Airport", requests.get(1).venueType());
        assertEquals(2, requests.get(0).proposalRow());
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
            assertEquals(2, workbook.getAllPictures().size());
            assertEquals(2, pics.getLastRowNum());
        }

        assertTrue(Files.isDirectory(meta.resolve("images")));
        assertTrue(Files.readString(meta.resolve("missing_images.json")).contains("Shopping Mall"));
        assertTrue(Files.isRegularFile(meta.resolve("image_mapping.json")));
    }
}
