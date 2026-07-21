package com.autoproject.service.summary;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.util.List;

public class PivotSummarySheetWriter {
    public static final String SHEET_NAME = "Proposal";
    private static final String[] SUMMARY_HEADERS = {
            "Country",
            "POI",
            "Venue type屏幕类型",
            "Floor price 2026 CPM底价",
            "Currency",
            "VIOOHSELECTOPTIN",
            "Screen no. 屏幕数量",
            "Monthly imp月曝光量"
    };

    private final String nullSentinel;

    public PivotSummarySheetWriter(String nullSentinel) {
        this.nullSentinel = nullSentinel;
    }

    public void write(Sheet sheet, List<PivotSummaryRow> rows) {
        writeHeader(sheet);
        int rowNum = 1;
        for (PivotSummaryRow summaryRow : rows) {
            Row row = sheet.createRow(rowNum++);
            int c = 0;
            setTextCell(row.createCell(c++), summaryRow.addressIso3CountryCode());
            setTextCell(row.createCell(c++), summaryRow.closestPoi());
            setTextCell(row.createCell(c++), summaryRow.venueTaxonomyValue());
            setNumberOrNull(row.createCell(c++), summaryRow.floorCpm());
            setTextCell(row.createCell(c++), summaryRow.mediaOwnerCurrency());
            setTextCell(row.createCell(c++), summaryRow.vioohSelectOptin());
            row.createCell(c++).setCellValue(summaryRow.count());
            row.createCell(c).setCellValue(summaryRow.sumImpressions());
        }
    }

    public int getColumnCount() {
        return SUMMARY_HEADERS.length;
    }

    private void writeHeader(Sheet sheet) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < SUMMARY_HEADERS.length; i++) {
            header.createCell(i).setCellValue(SUMMARY_HEADERS[i]);
        }
    }

    private void setTextCell(Cell cell, String value) {
        if (value == null) {
            cell.setCellValue(nullSentinel);
            return;
        }
        String v = value.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("null") || v.equals("\\N") || v.equals("-")) {
            cell.setCellValue(nullSentinel);
            return;
        }
        cell.setCellValue(v);
    }

    private void setNumberOrNull(Cell cell, Number value) {
        if (value == null) {
            cell.setCellValue(nullSentinel);
            return;
        }
        cell.setCellValue(value.doubleValue());
    }
}
