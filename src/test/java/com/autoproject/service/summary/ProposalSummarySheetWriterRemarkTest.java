package com.autoproject.service.summary;

import com.autoproject.model.Brief;
import com.autoproject.model.FrameData;
import com.autoproject.service.ExcelGenerator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposalSummarySheetWriterRemarkTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAppendQuotationValidityDateOneMonthAheadInRemarkRow() throws Exception {
        FrameData frame = new FrameData();
        frame.setMarket("MKT");
        frame.setAssetUuid("uuid-1");
        frame.setImpressions(1000d);
        frame.setAddressIso3CountryCode("GBR");
        frame.setClosestPoi("POI-1");
        frame.setVenueTaxonomyValue("outdoor.billboards");
        frame.setFloorCpm(10d);
        frame.setMediaOwnerCurrency("GBP");
        frame.setVioohSelectOptin("Yes");

        Brief brief = new Brief("Test", 1000, 7);
        Path out = tempDir.resolve("proposal-remarks.xlsx");
        new ExcelGenerator().generate(List.of(frame), out.toString(), brief);

        String expectedDate = LocalDate.now().plusMonths(1)
                .format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH));
        String expectedRemark = "1) The quotation is valid till " + expectedDate + ".";

        try (InputStream in = Files.newInputStream(out);
             Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheet(ProposalSummarySheetWriter.SHEET_NAME);
            boolean found = false;
            for (int rowNum = 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row row = sheet.getRow(rowNum);
                if (row == null || row.getCell(0) == null) {
                    continue;
                }
                String value = row.getCell(0).getStringCellValue();
                if (expectedRemark.equals(value)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "expected remark row: " + expectedRemark);
        }
    }
}
