package com.autoproject.service;

import com.autoproject.model.Brief;
import com.autoproject.model.FrameData;
import com.autoproject.service.pics.PicsSheetWriter;
import com.autoproject.service.summary.ProposalBuilder;
import com.autoproject.service.summary.ProposalSummarySheetWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ExcelGenerator {

    private static final String NULL_SENTINEL = "\\N";

    private static final String[] HEADERS = {
            "GEOM",
            "SSP",
            "MARKET",
            "VIOOH_ID",
            "ASSETUUID",
            "VISUAL_UNIT_CODE",
            "ADDRESS_THOROUGHFARE",
            "ADDRESS_ADMINISTRATIVE_AREA",
            "ADDRESS_LOCALITY",
            "ADDRESS_POSTAL_CODE",
            "ADDRESS_COUNTRY",
            "ADDRESS_ISO3_COUNTRY_CODE",
            "LATITUDE",
            "LONGITUDE",
            "PRODUCT_FORMAT_NAME",
            "DIGITAL_SPEC_WIDTH",
            "DIGITAL_SPEC_HEIGHT",
            "WIDTHXHEIGHT",
            "ASPECT_RATIO",
            "DIGITAL_SPEC_MOTION_TYPE",
            "DIGITAL_SPEC_FPS",
            "DIGITAL_SPEC_ROTATION",
            "SLOT_DURATION",
            "VENUE_TAXONOMY_ID",
            "VENUE_TAXONOMY_VALUE",
            "FRAMEIMAGEPATH",
            "IMPRESSIONS",
            "FLOORCPM",
            "MEDIAOWNERCURRENCY",
            "VIOOHSELECTOPTIN",
            "CLOSEST_POI",
            "DISTANCE_TO_CLOSEST_POI",
            "IATA",
            "SCORE_P",
            "CLOEST_POI"
    };

    public void generate(List<FrameData> list, String outPath) throws Exception {
        generate(list, outPath, null, null);
    }

    public void generate(List<FrameData> list, String outPath, Brief brief) throws Exception {
        generate(list, outPath, brief, null);
    }

    public void generate(List<FrameData> list, String outPath, Brief brief, ExportProgress exportProgress) throws Exception {
        ExportProgress progress = exportProgress == null ? ExportProgress.noop() : exportProgress;
        Workbook wb = new XSSFWorkbook();
        Sheet allDataSheet = wb.createSheet("UnfilteredFrames");
        Sheet filteredSheet = wb.createSheet("FilteredFrames");
        ProposalSummarySheetWriter summarySheetWriter = new ProposalSummarySheetWriter(NULL_SENTINEL);
        Sheet summarySheet = wb.createSheet(ProposalSummarySheetWriter.SHEET_NAME);
        Sheet picsSheet = wb.createSheet("PICS");
        Sheet poiSheet = wb.createSheet("POI");
        PicsSheetWriter picsSheetWriter = new PicsSheetWriter();

        writeHeader(allDataSheet);
        writeHeader(filteredSheet);
        writePoiSheet(poiSheet, list, brief);
        List<FrameData> filteredFrames = filterFramesForExport(list);
        var proposalRows = new ProposalBuilder().build(filteredFrames);
        boolean fetchPicsFromLinks = brief == null || brief.isPicsFetchFromLinks();
        picsSheetWriter.writeFromProposalRows(
                picsSheet,
                proposalRows,
                supplyMatrixPath(),
                metaDirFor(outPath),
                fetchPicsFromLinks,
                progress);

        summarySheetWriter.write(summarySheet, proposalRows, brief);

        progress.onWritingFrameSheets();
        int allRow = 1;
        int filteredRow = 1;
        for (FrameData d : list) {
            writeFrameDataRow(allDataSheet.createRow(allRow++), d);
        }
        for (FrameData d : filteredFrames) {
            writeFrameDataRow(filteredSheet.createRow(filteredRow++), d);
        }
        for (int i = 0; i < HEADERS.length; i++) {
            allDataSheet.autoSizeColumn(i);
            filteredSheet.autoSizeColumn(i);
        }
        for (int i = 0; i < summarySheetWriter.getColumnCount(); i++) {
            summarySheet.autoSizeColumn(i);
        }
        for (int i = 0; i < 3; i++) {
            poiSheet.autoSizeColumn(i);
        }

        progress.onSavingWorkbook();
        try (FileOutputStream out = new FileOutputStream(outPath)) {
            wb.write(out);
        }
        progress.onExportComplete();
        wb.close();
    }

    private static Path supplyMatrixPath() {
        String configured = System.getProperty("propel.supplyMatrixPath");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PROPEL_SUPPLY_MATRIX_PATH");
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return Path.of("feishu", "supply_matrix.xlsx");
    }

    private static Path metaDirFor(String outPath) {
        Path output = Path.of(outPath == null || outPath.isBlank() ? "output.xlsx" : outPath);
        Path parent = output.toAbsolutePath().getParent();
        return (parent == null ? Path.of("meta") : parent.resolve("meta")).normalize();
    }

    private static void writeHeader(Sheet sheet) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < HEADERS.length; i++) {
            header.createCell(i).setCellValue(HEADERS[i]);
        }
    }


    private static void writeFrameDataRow(Row r, FrameData d) {
        int c = 0;
        setTextCell(r.createCell(c++), d.getGeom());
        setTextCell(r.createCell(c++), d.getSsp());
        setTextCell(r.createCell(c++), d.getMarket());
        setTextCell(r.createCell(c++), d.getVioohId());
        setTextCell(r.createCell(c++), d.getAssetUuid());
        setTextCell(r.createCell(c++), d.getVisualUnitCode());
        setTextCell(r.createCell(c++), d.getAddressThoroughfare());
        setTextCell(r.createCell(c++), d.getAddressAdministrativeArea());
        setTextCell(r.createCell(c++), d.getAddressLocality());
        setTextCell(r.createCell(c++), d.getAddressPostalCode());
        setTextCell(r.createCell(c++), d.getAddressCountry());
        setTextCell(r.createCell(c++), d.getAddressIso3CountryCode());
        setNumberOrNull(r.createCell(c++), d.getLatitude());
        setNumberOrNull(r.createCell(c++), d.getLongitude());
        setTextCell(r.createCell(c++), d.getProductFormatName());
        setNumberOrNull(r.createCell(c++), d.getDigitalSpecWidth());
        setNumberOrNull(r.createCell(c++), d.getDigitalSpecHeight());
        setTextCell(r.createCell(c++), d.getWidthXHeight());
        setTextCell(r.createCell(c++), d.getAspectRatio());
        setTextCell(r.createCell(c++), d.getDigitalSpecMotionType());
        setNumberOrNull(r.createCell(c++), d.getDigitalSpecFps());
        setNumberOrNull(r.createCell(c++), d.getDigitalSpecRotation());
        setNumberOrNull(r.createCell(c++), d.getSlotDuration());
        Cell venueTaxIdCell = r.createCell(c++);
        if (d.getVenueTaxonomyId() != null) {
            venueTaxIdCell.setCellValue(d.getVenueTaxonomyId());
        } else {
            venueTaxIdCell.setCellValue(NULL_SENTINEL);
        }
        setTextCell(r.createCell(c++), d.getVenueTaxonomyValue());
        setTextCell(r.createCell(c++), d.getFrameImagePath());
        Cell impCell = r.createCell(c++);
        if (d.getImpressions() == null) {
            impCell.setCellValue(NULL_SENTINEL);
        } else {
            impCell.setCellValue(d.getImpressions());
        }
        setNumberOrNull(r.createCell(c++), d.getFloorCpm());
        setTextCell(r.createCell(c++), d.getMediaOwnerCurrency());
        setTextCell(r.createCell(c++), d.getVioohSelectOptin());
        setTextCell(r.createCell(c++), d.getClosestPoi());
        setTextCell(r.createCell(c++), d.getDistanceToClosestPoi());
        setTextCell(r.createCell(c++), d.getIata());
        setNumberOrNull(r.createCell(c++), d.getScoreP());
        setTextCell(r.createCell(c++), d.getClosestPoi());
    }

    private static void setTextCell(Cell cell, String s) {
        if (s == null) {
            cell.setCellValue(NULL_SENTINEL);
            return;
        }
        String v = s.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("null") || v.equals("\\N") || v.equals("-")) {
            cell.setCellValue(NULL_SENTINEL);
            return;
        }
        cell.setCellValue(v);
    }

    private static void setNumberOrNull(Cell cell, Number n) {
        if (n == null) {
            cell.setCellValue(NULL_SENTINEL);
            return;
        }
        cell.setCellValue(n.doubleValue());
    }

    private static void writePoiSheet(Sheet poiSheet, List<FrameData> list, Brief brief) {
        Row header = poiSheet.createRow(0);
        header.createCell(0).setCellValue("ADDRESS_COUNTRY");
        header.createCell(1).setCellValue("Location");
        header.createCell(2).setCellValue("POI");

        Map<String, LinkedHashSet<String>> countriesByPoi = collectIso3CountriesByPoi(list);
        String location = brief == null ? "" : normalizeOptionalText(brief.getLocation());
        Set<String> seen = new LinkedHashSet<>();
        int rowNum = 1;
        for (FrameData data : list) {
            String poi = normalizeText(data.getClosestPoi());
            if (!seen.add(poi)) {
                continue;
            }
            Row row = poiSheet.createRow(rowNum++);
            row.createCell(0).setCellValue(formatIso3Countries(countriesByPoi.get(poi)));
            row.createCell(1).setCellValue(location);
            row.createCell(2).setCellValue(poi);
        }
    }

    private static Map<String, LinkedHashSet<String>> collectIso3CountriesByPoi(List<FrameData> list) {
        Map<String, LinkedHashSet<String>> countriesByPoi = new LinkedHashMap<>();
        if (list == null) {
            return countriesByPoi;
        }
        for (FrameData data : list) {
            String poi = normalizeText(data.getClosestPoi());
            String iso3 = normalizeIso3CountryCode(data.getAddressIso3CountryCode());
            if (iso3 == null) {
                continue;
            }
            countriesByPoi.computeIfAbsent(poi, ignored -> new LinkedHashSet<>()).add(iso3);
        }
        return countriesByPoi;
    }

    private static String formatIso3Countries(Set<String> iso3Countries) {
        if (iso3Countries == null || iso3Countries.isEmpty()) {
            return NULL_SENTINEL;
        }
        List<String> sorted = new ArrayList<>(iso3Countries);
        Collections.sort(sorted);
        return String.join("/", sorted);
    }

    private static String normalizeIso3CountryCode(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("null")
                || trimmed.equals(NULL_SENTINEL)
                || trimmed.equals("-")) {
            return null;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static String buildMarketAssetUuidKey(FrameData data) {
        String market = normalizeForDedup(data.getMarket());
        String assetUuid = normalizeForDedup(data.getAssetUuid());
        if (market == null || assetUuid == null) {
            return null;
        }
        return market + "|" + assetUuid;
    }

    private static String normalizeForDedup(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("null") || trimmed.equals("\\N") || trimmed.equals("-")) {
            return null;
        }
        return trimmed.toUpperCase();
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return NULL_SENTINEL;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("null") || trimmed.equals("\\N") || trimmed.equals("-")) {
            return NULL_SENTINEL;
        }
        return trimmed;
    }

    private static String normalizeOptionalText(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("null") || trimmed.equals("\\N") || trimmed.equals("-")) {
            return "";
        }
        return trimmed;
    }

    /**
     * Same inventory as the {@code FilteredFrames} sheet: impressions not null/0, first row per market|assetUuid.
     * Proposal summary and budget logic should use this list, not the full merge result.
     */
    public static List<FrameData> filterFramesForExport(List<FrameData> list) {
        List<FrameData> out = new ArrayList<>();
        if (list == null || list.isEmpty()) {
            return out;
        }
        Set<String> filteredDedupKeys = new LinkedHashSet<>();
        for (FrameData d : list) {
            if (d.getImpressions() == null || d.getImpressions() == 0) {
                continue;
            }
            String dedupeKey = buildMarketAssetUuidKey(d);
            if (dedupeKey != null && !filteredDedupKeys.add(dedupeKey)) {
                continue;
            }
            out.add(d);
        }
        return out;
    }

    /**
     * Row count that matches the {@code FilteredFrames} sheet: non-zero impressions, deduped by market|assetUuid.
     */
    public static long countFilteredExportRows(List<FrameData> list) {
        return filterFramesForExport(list).size();
    }

}
