package com.autoproject.service.summary;

import com.autoproject.model.Brief;
import com.autoproject.model.FrameData;
import com.autoproject.service.DataMerger;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProposalVsDetailCpmIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteOriginalAndDetailCpmColumnsAndUseDetailForVsUplift() throws Exception {
        Path frameList = tempDir.resolve("Paris_1km.csv");
        Files.writeString(frameList, String.join("\n",
                "ASSETUUID,VIOOHSELECTOPTIN,FLOOR_CPM,CURRENCY,IMPRESSIONS,MARKET,ADDRESS_ISO3_COUNTRY_CODE,VENUE_TAXONOMY_VALUE",
                "FRAME-A,Yes,6,USD,1000,MKT,USA,outdoor.billboards"
        ));
        Path details = tempDir.resolve("frames-details.csv");
        Files.writeString(details, String.join("\n",
                "Frame ID,VIOOHSELECTOPTIN,VIOOHSELECTCPMLOCAL",
                "FRAME-A,Yes,7"
        ));

        List<FrameData> merged = new DataMerger().merge(frameList.toString(), details.toString());
        Path out = tempDir.resolve("proposal.xlsx");
        new ExcelGenerator().generate(merged, out.toString(), new Brief("\\N", 1000, 7));

        try (InputStream in = Files.newInputStream(out);
             Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheet(ProposalSummarySheetWriter.SHEET_NAME);
            Row header = sheet.getRow(0);
            assertEquals("Floor price 2026 CPM", header.getCell(4).getStringCellValue());
            assertEquals("VIOOHSELECTCPMLOCAL", header.getCell(5).getStringCellValue());
            assertEquals("Floor price 2026 CPM (VS)", header.getCell(6).getStringCellValue());

            Row data = sheet.getRow(1);
            assertEquals(6d, data.getCell(4).getNumericCellValue(), 1e-6);
            assertEquals(7d, data.getCell(5).getNumericCellValue(), 1e-6);
            assertEquals(8.4d, data.getCell(6).getNumericCellValue(), 1e-6);
        }
    }
}
