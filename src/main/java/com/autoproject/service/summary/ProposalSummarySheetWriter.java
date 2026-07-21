package com.autoproject.service.summary;

import com.autoproject.model.Brief;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class ProposalSummarySheetWriter {
    public static final String SHEET_NAME = "Proposal";
    private static final String[] SUMMARY_HEADERS = {
            "Date",
            "Country",
            "POI",
            "Venue type",
            "Floor price 2026 CPM",
            "VIOOHSELECTCPMLOCAL",
            "Floor price 2026 CPM (VS)",
            "MEDIAOWNERCURRENCY",
            "Floor price 2026 CPM (USD)",
            "VIOOHSELECTOPTIN",
            "MARKET",
            "Screen no.",
            "Monthly impressions",
            "Campaign days",
            "Suggested screen no",
            "Suggested SOT",
            "Impression deliverable",
            "Media Budget (USD)",
            "DSP fee",
            "Total investment"
    };

    private final String nullSentinel;
    private final SuggestionOptimizer suggestionOptimizer = new SuggestionOptimizer();
    private static final int MONTHLY_IMP_COLUMN_INDEX = 12;
    private CellStyle numberStyle;
    private CellStyle percentStyle;
    private CellStyle dspPercentStyle;

    public ProposalSummarySheetWriter(String nullSentinel) {
        this.nullSentinel = nullSentinel;
    }

    public void write(Sheet sheet, List<ProposalSummaryRow> rows, Brief brief) {
        ensureDataStyles(sheet);
        writeHeader(sheet);
        Integer campaignDays = brief == null ? null : brief.getCampaignDays();
        boolean shouldConvertToUsd = brief != null && brief.isConvertBudgetToUsd();
        Map<String, Double> usdExchangeRateByCurrency = brief == null
                ? Collections.emptyMap()
                : brief.getUsdExchangeRateByCurrency();
        List<Double> cpmForBudgetPerRow = new ArrayList<>(rows.size());
        for (ProposalSummaryRow summaryRow : rows) {
            cpmForBudgetPerRow.add(ProposalPricing.effectiveCpmForBudget(summaryRow, brief));
        }
        List<SuggestionOptimizer.Recommendation> recommendations = suggestionOptimizer.recommendGlobal(brief, rows, cpmForBudgetPerRow);
        int rowNum = 1;
        int rowIndex = 0;
        for (ProposalSummaryRow summaryRow : rows) {
            Row row = sheet.createRow(rowNum++);
            int c = 0;
            row.createCell(c++).setBlank();
            setTextCell(row.createCell(c++), summaryRow.getAddressIso3CountryCode());
            setTextCell(row.createCell(c++), summaryRow.getClosestPoi());
            setTextCell(row.createCell(c++), summaryRow.getVenueTaxonomyValue());
            Double evenAdjustedFloorCpm = ProposalPricing.calculateEvenAdjustedFloorCpm(
                    summaryRow.getEffectiveFloorCpm(),
                    summaryRow.getVioohSelectOptin()
            );
            setNumberOrNull(row.createCell(c++), summaryRow.getFloorCpm());
            setNumberOrNull(row.createCell(c++), summaryRow.getEffectiveFloorCpm());
            setNumberOrNull(row.createCell(c++), evenAdjustedFloorCpm);
            String currency = ProposalPricing.normalizeCurrency(summaryRow.getMediaOwnerCurrency());
            setTextCell(row.createCell(c++), currency);
            Double usdFloorCpm = shouldConvertToUsd
                    ? ProposalPricing.convertFloorCpmToUsd(evenAdjustedFloorCpm, currency, usdExchangeRateByCurrency)
                    : null;
            setNumberOrNull(row.createCell(c++), usdFloorCpm);
            setTextCell(row.createCell(c++), summaryRow.getVioohSelectOptin());
            setTextCell(row.createCell(c++), summaryRow.getMarket());
            Cell countCell = row.createCell(c++);
            countCell.setCellValue(summaryRow.getCount());
            countCell.setCellStyle(numberStyle);
            Cell impressionsCell = row.createCell(c);
            impressionsCell.setCellValue(summaryRow.getSumImpressions());
            impressionsCell.setCellStyle(numberStyle);
            c++;
            setNumberOrNull(row.createCell(c++), campaignDays);
            SuggestionOptimizer.Recommendation recommendation = rowIndex < recommendations.size()
                    ? recommendations.get(rowIndex)
                    : SuggestionOptimizer.Recommendation.empty();
            setNumberOrNull(row.createCell(c++), recommendation.suggestedScreenNo());
            setPercentOrNull(row.createCell(c++), recommendation.suggestedSot());
            applyCalculatedFormulas(row, c, shouldConvertToUsd);
            rowIndex++;
        }
        double totalMediaSpend = PhotographyBudgetEvaluator.totalMediaSpend(recommendations);
        appendSumRow(sheet, rowNum);
        int afterSum = rowNum + 1;
        int afterBudget = appendBudgetSummaryTable(sheet, afterSum, brief, totalMediaSpend);
        appendRemarkRows(sheet, afterBudget);
        applyBlackBorders(sheet, 1, rowNum - 1, SUMMARY_HEADERS.length - 1);
    }

    public int getColumnCount() {
        return SUMMARY_HEADERS.length;
    }

    private void writeHeader(Sheet sheet) {
        Row header = sheet.createRow(0);
        CellStyle blueHeaderStyle = createHeaderStyle(sheet, IndexedColors.LIGHT_BLUE);
        CellStyle orangeHeaderStyle = createHeaderStyle(sheet, IndexedColors.LIGHT_ORANGE);
        for (int i = 0; i < SUMMARY_HEADERS.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(SUMMARY_HEADERS[i]);
            cell.setCellStyle(i <= MONTHLY_IMP_COLUMN_INDEX ? blueHeaderStyle : orangeHeaderStyle);
        }
    }

    private CellStyle createHeaderStyle(Sheet sheet, IndexedColors fillColor) {
        Font font = sheet.getWorkbook().createFont();
        font.setFontName("Arial");
        font.setFontHeightInPoints((short) 10);
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());

        CellStyle style = sheet.getWorkbook().createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(fillColor.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
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
        cell.setCellStyle(numberStyle);
    }

    private void setPercentOrNull(Cell cell, Number value) {
        if (value == null) {
            cell.setCellValue(nullSentinel);
            return;
        }
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(percentStyle);
    }

    private void applyCalculatedFormulas(Row row, int startCol, boolean shouldConvertToUsd) {
        int excelRowNum = row.getRowNum() + 1;
        String cpmColumn = shouldConvertToUsd ? "I" : "G";
        Cell impressionDeliverableCell = row.createCell(startCol);
        impressionDeliverableCell.setCellFormula(
                "IFERROR(M" + excelRowNum + "*(N" + excelRowNum + "/30)*(O" + excelRowNum + "/L" + excelRowNum
                        + ")*P" + excelRowNum + ",\"\\N\")");
        impressionDeliverableCell.setCellStyle(numberStyle);

        Cell mediaBudgetCell = row.createCell(startCol + 1);
        mediaBudgetCell.setCellFormula("IFERROR(Q" + excelRowNum + "/1000*" + cpmColumn + excelRowNum + ",\"\\N\")");
        mediaBudgetCell.setCellStyle(numberStyle);

        Cell dspFeeCell = row.createCell(startCol + 2);
        dspFeeCell.setCellFormula("IFERROR(15%,\"\\N\")");
        dspFeeCell.setCellStyle(dspPercentStyle);

        Cell totalInvestmentCell = row.createCell(startCol + 3);
        totalInvestmentCell.setCellFormula("IFERROR((1+S" + excelRowNum + ")*R" + excelRowNum + ",\"\\N\")");
        totalInvestmentCell.setCellStyle(numberStyle);
    }

    private void appendSumRow(Sheet sheet, int nextRowNum) {
        Row sumRow = sheet.createRow(nextRowNum);
        CellStyle sumStyle = createSumStyle(sheet);
        Cell sumLabelCell = sumRow.createCell(0);
        sumLabelCell.setCellValue("SUM");
        sumLabelCell.setCellStyle(sumStyle);

        if (nextRowNum <= 1) {
            Cell colQ = sumRow.createCell(16);
            setTextCell(colQ, null);
            colQ.setCellStyle(sumStyle);
            Cell colR = sumRow.createCell(17);
            setTextCell(colR, null);
            colR.setCellStyle(sumStyle);
            Cell colT = sumRow.createCell(19);
            setTextCell(colT, null);
            colT.setCellStyle(sumStyle);
            return;
        }

        int firstDataExcelRow = 2;
        int lastDataExcelRow = nextRowNum;
        Cell sumQCell = sumRow.createCell(16);
        sumQCell.setCellFormula("SUM(Q" + firstDataExcelRow + ":Q" + lastDataExcelRow + ")");
        sumQCell.setCellStyle(createSumNumberStyle(sheet));
        Cell sumRCell = sumRow.createCell(17);
        sumRCell.setCellFormula("SUM(R" + firstDataExcelRow + ":R" + lastDataExcelRow + ")");
        sumRCell.setCellStyle(createSumNumberStyle(sheet));
        Cell sumTCell = sumRow.createCell(19);
        sumTCell.setCellFormula("SUM(T" + firstDataExcelRow + ":T" + lastDataExcelRow + ")");
        sumTCell.setCellStyle(createSumNumberStyle(sheet));
    }

    private CellStyle createSumStyle(Sheet sheet) {
        Font font = sheet.getWorkbook().createFont();
        font.setFontName("Arial");
        font.setFontHeightInPoints((short) 10);
        font.setBold(true);
        font.setColor(IndexedColors.RED.getIndex());

        CellStyle style = sheet.getWorkbook().createCellStyle();
        style.setFont(font);
        return style;
    }

    private CellStyle createSumNumberStyle(Sheet sheet) {
        Font font = sheet.getWorkbook().createFont();
        font.setFontName("Arial");
        font.setFontHeightInPoints((short) 10);
        font.setBold(true);
        font.setColor(IndexedColors.RED.getIndex());

        CellStyle style = sheet.getWorkbook().createCellStyle();
        style.setFont(font);
        style.setDataFormat(sheet.getWorkbook().createDataFormat().getFormat("#,##0"));
        return style;
    }

    private void ensureDataStyles(Sheet sheet) {
        if (numberStyle != null && percentStyle != null && dspPercentStyle != null) {
            return;
        }
        DataFormat dataFormat = sheet.getWorkbook().createDataFormat();

        numberStyle = sheet.getWorkbook().createCellStyle();
        numberStyle.setDataFormat(dataFormat.getFormat("#,##0"));

        percentStyle = sheet.getWorkbook().createCellStyle();
        percentStyle.setDataFormat(dataFormat.getFormat("0.00%"));

        dspPercentStyle = sheet.getWorkbook().createCellStyle();
        dspPercentStyle.setDataFormat(dataFormat.getFormat("0%"));
    }

    private void applyBlackBorders(Sheet sheet, int startRow, int endRow, int endCol) {
        Map<Short, CellStyle> styleCache = new HashMap<>();
        for (int rowNum = startRow; rowNum <= endRow; rowNum++) {
            Row row = sheet.getRow(rowNum);
            if (row == null) {
                row = sheet.createRow(rowNum);
            }
            for (int colNum = 0; colNum <= endCol; colNum++) {
                Cell cell = row.getCell(colNum);
                if (cell == null) {
                    cell = row.createCell(colNum);
                }
                short originalStyleIndex = cell.getCellStyle().getIndex();
                CellStyle borderedStyle = styleCache.get(originalStyleIndex);
                if (borderedStyle == null) {
                    borderedStyle = sheet.getWorkbook().createCellStyle();
                    borderedStyle.cloneStyleFrom(cell.getCellStyle());
                    borderedStyle.setBorderTop(BorderStyle.THIN);
                    borderedStyle.setBorderBottom(BorderStyle.THIN);
                    borderedStyle.setBorderLeft(BorderStyle.THIN);
                    borderedStyle.setBorderRight(BorderStyle.THIN);
                    borderedStyle.setTopBorderColor(IndexedColors.BLACK.getIndex());
                    borderedStyle.setBottomBorderColor(IndexedColors.BLACK.getIndex());
                    borderedStyle.setLeftBorderColor(IndexedColors.BLACK.getIndex());
                    borderedStyle.setRightBorderColor(IndexedColors.BLACK.getIndex());
                    styleCache.put(originalStyleIndex, borderedStyle);
                }
                cell.setCellStyle(borderedStyle);
            }
        }
    }

    private int appendBudgetSummaryTable(Sheet sheet, int startRow, Brief brief, double totalMediaSpend) {
        if (brief == null || brief.getBudget() <= 0) {
            return startRow;
        }
        int photography = brief.getPhotographyBudget();
        double proposalTotal = totalMediaSpend + photography;
        CellStyle labelStyle = createSumStyle(sheet);
        CellStyle valueStyle = createSumNumberStyle(sheet);
        record BudgetLine(String label, Double amount) {
        }
        List<BudgetLine> lines = List.of(
                new BudgetLine("Budget summary", null),
                new BudgetLine("Media spend (all frames)", totalMediaSpend),
                new BudgetLine("Photography budget", (double) photography),
                new BudgetLine("Total budget", proposalTotal),
                new BudgetLine("Campaign budget (allocation input)", (double) brief.getBudget())
        );
        int r = startRow;
        for (BudgetLine line : lines) {
            Row row = sheet.createRow(r++);
            Cell label = row.createCell(0);
            label.setCellValue(line.label());
            label.setCellStyle(labelStyle);
            Cell value = row.createCell(2);
            if (line.amount() == null) {
                setTextCell(value, null);
            } else {
                value.setCellValue(line.amount());
                value.setCellStyle(valueStyle);
            }
            sheet.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 0, 1));
        }
        return r;
    }

    private void appendRemarkRows(Sheet sheet, int startRow) {
        String[] remarks = {
                "Remark",
                buildQuotationValidityRemark(),
                "3) SOT and floor price are estimates; actual transaction prices may vary."
        };
        int lastCol = SUMMARY_HEADERS.length - 1;
        for (int i = 0; i < remarks.length; i++) {
            int rowNum = startRow + i;
            Row row = sheet.getRow(rowNum);
            if (row == null) {
                row = sheet.createRow(rowNum);
            }
            Cell cell = row.createCell(0);
            cell.setCellValue(remarks[i]);
            sheet.addMergedRegion(new CellRangeAddress(rowNum, rowNum, 0, lastCol));
        }
    }

    private static String buildQuotationValidityRemark() {
        LocalDate validUntil = LocalDate.now().plusMonths(1);
        String formattedDate = validUntil.format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH));
        return "1) The quotation is valid till " + formattedDate + ".";
    }

}
