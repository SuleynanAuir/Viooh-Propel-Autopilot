package com.autoproject.service;

import com.autoproject.model.FrameData;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CsvReader {
    private static final Pattern FILE_POI_RADIUS_PATTERN =
            // Accept filenames like:
            // - POI_10km.csv
            // - POI_10.5 km.csv
            // - POI_（10）km_FramesList.csv (also supports full-width parentheses)
            // - POI_10km_FramesList.csv (supports suffix after "km")
            Pattern.compile(
                    "^(.+?)[_-]\\s*(?:[\\(（]\\s*)?(\\d+(?:\\.\\d+)?)\\s*(?:[\\)）]\\s*)?km.*$",
                    Pattern.CASE_INSENSITIVE
            );
    private static final Pattern NUMERIC_KM_IN_FILENAME = Pattern.compile(
            "(?:[_\\-]|\\(|（)\\s*\\d+(?:\\.\\d+)?\\s*km",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FRAMES_LIST_SUFFIX = Pattern.compile("(?i)_(?:FramesList|frameslist)$");
    private static final Pattern ALL_SUFFIX = Pattern.compile("(?i)_all$");

    public List<FrameData> read(String filePath) throws Exception {
        String lower = filePath == null ? "" : filePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || hasExcelFileSignature(filePath)) {
            return readExcel(filePath);
        }
        if (lower.endsWith(".csv") || lower.endsWith(".tsv")) {
            return readCsv(filePath);
        }
        throw new IllegalArgumentException("Unsupported frame-list file type: " + Paths.get(filePath).getFileName());
    }

    private boolean hasExcelFileSignature(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        byte[] header = new byte[8];
        try (InputStream in = Files.newInputStream(Paths.get(filePath))) {
            int read = in.read(header);
            if (read < 4) {
                return false;
            }
            boolean zipBasedOffice = header[0] == 0x50 && header[1] == 0x4B;
            boolean legacyOle =
                    (header[0] & 0xFF) == 0xD0
                            && (header[1] & 0xFF) == 0xCF
                            && (header[2] & 0xFF) == 0x11
                            && (header[3] & 0xFF) == 0xE0;
            return zipBasedOffice || legacyOle;
        } catch (Exception ignored) {
            return false;
        }
    }

    public List<FrameData> readCsv(String filePath) throws Exception {
        List<Charset> candidates = List.of(
                StandardCharsets.UTF_8,
                StandardCharsets.UTF_16LE,
                StandardCharsets.UTF_16BE,
                Charset.forName("GB18030"),
                Charset.forName("windows-1252")
        );
        Exception last = null;
        List<String> attempted = new ArrayList<>();
        for (Charset charset : candidates) {
            try {
                attempted.add(charset.displayName());
                if (!StandardCharsets.UTF_8.equals(charset)) {
                    System.out.println("Notice: retry CSV decode with " + charset.displayName() + ": " + Paths.get(filePath).getFileName());
                }
                return readCsvWithCharset(filePath, charset);
            } catch (MalformedInputException | UnmappableCharacterException e) {
                last = e;
            }
        }
        if (last != null) {
            String fileName = Paths.get(filePath).getFileName().toString();
            throw new IllegalArgumentException(
                    "CSV encoding is unsupported for file: " + fileName
                            + ". Attempted: " + String.join(", ", attempted)
                            + ". Please convert this file to UTF-8 (recommended, without BOM) and retry.",
                    last
            );
        }
        return readCsvWithCharset(filePath, StandardCharsets.UTF_8);
    }

    private List<FrameData> readCsvWithCharset(String filePath, Charset charset) throws Exception {
        List<FrameData> result = new ArrayList<>();
        PoiRadiusInfo poiRadiusInfo = parsePoiRadiusInfo(filePath);
        logPoiFallbackNotice(filePath, poiRadiusInfo);

        try (BufferedReader br = Files.newBufferedReader(Paths.get(filePath), charset)) {
            String headerLine = br.readLine();
            if (headerLine == null || headerLine.trim().isEmpty()) {
                return result;
            }

            char delimiter = detectDelimiter(headerLine);

            String[] headers = parseCsvLine(normalizeLineDelimiters(headerLine, delimiter), delimiter);
            int expectedColumns = headers.length;
            Map<String, Integer> headerIndex = buildHeaderIndex(headers);

            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                // Reconstruct logical records: some sources may incorrectly split a single record into multiple lines.
                // We concatenate subsequent lines until we reach the expected column count.
                String recordLine = normalizeLineDelimiters(line, delimiter);
                String[] values = parseCsvLine(recordLine, delimiter);
                while (values.length < expectedColumns) {
                    br.mark(1024 * 1024);
                    String next = br.readLine();
                    if (next == null) {
                        break;
                    }
                    if (next.trim().isEmpty()) {
                        continue;
                    }
                    recordLine = recordLine + delimiter + normalizeLineDelimiters(next, delimiter);
                    values = parseCsvLine(recordLine, delimiter);
                }

                if (values.length > expectedColumns) {
                    values = Arrays.copyOf(values, expectedColumns);
                }
                result.add(mapFrameData(values, headerIndex, poiRadiusInfo));
            }
        }
        return result;
    }

    private List<FrameData> readExcel(String filePath) throws Exception {
        List<FrameData> result = new ArrayList<>();
        PoiRadiusInfo poiRadiusInfo = parsePoiRadiusInfo(filePath);
        logPoiFallbackNotice(filePath, poiRadiusInfo);

        DataFormatter formatter = new DataFormatter();
        try (InputStream in = Files.newInputStream(Paths.get(filePath));
             Workbook workbook = WorkbookFactory.create(in)) {
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                if (sheet == null) {
                    continue;
                }
                Row headerRow = findFirstNonEmptyRow(sheet, formatter);
                if (headerRow == null) {
                    continue;
                }

                short lastCell = headerRow.getLastCellNum();
                if (lastCell <= 0) {
                    return result;
                }
                int expectedColumns = lastCell;
                String[] headers = rowToValues(headerRow, expectedColumns, formatter);
                Map<String, Integer> headerIndex = buildHeaderIndex(headers);

                for (int rowNum = headerRow.getRowNum() + 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                    Row row = sheet.getRow(rowNum);
                    if (row == null) {
                        continue;
                    }
                    String[] values = rowToValues(row, expectedColumns, formatter);
                    if (isEmptyRecord(values)) {
                        continue;
                    }
                    result.add(mapFrameData(values, headerIndex, poiRadiusInfo));
                }
                return result;
            }
        }
        return result;
    }

    private Map<String, Integer> buildHeaderIndex(String[] headers) {
        Map<String, Integer> headerIndex = new HashMap<>();
        if (headers == null) {
            return headerIndex;
        }
        for (int i = 0; i < headers.length; i++) {
            registerHeaderIndex(headerIndex, headers[i], i);
        }
        return headerIndex;
    }

    private FrameData mapFrameData(String[] values, Map<String, Integer> headerIndex, PoiRadiusInfo poiRadiusInfo) {
        FrameData data = new FrameData();
        data.setGeom(getStr(values, headerIndex, "GEOM"));
        data.setSsp(getStr(values, headerIndex, "SSP"));
        data.setMarket(getStr(values, headerIndex, "MARKET"));
        data.setVioohId(getStr(values, headerIndex, "VIOOH_ID"));
        data.setAssetUuid(getStr(values, headerIndex, "ASSETUUID"));
        data.setVisualUnitCode(getStr(values, headerIndex, "VISUAL_UNIT_CODE"));
        data.setAddressThoroughfare(getStr(values, headerIndex, "ADDRESS_THOROUGHFARE"));
        data.setAddressAdministrativeArea(getStr(values, headerIndex, "ADDRESS_ADMINISTRATIVE_AREA"));
        data.setAddressLocality(getStr(values, headerIndex, "ADDRESS_LOCALITY"));
        data.setAddressPostalCode(getStr(values, headerIndex, "ADDRESS_POSTAL_CODE"));
        data.setAddressCountry(getStr(values, headerIndex, "ADDRESS_COUNTRY"));
        data.setAddressIso3CountryCode(getStr(values, headerIndex, "ADDRESS_ISO3_COUNTRY_CODE"));
        data.setLatitude(getNullableDouble(values, headerIndex, "LATITUDE"));
        data.setLongitude(getNullableDouble(values, headerIndex, "LONGITUDE"));
        data.setProductFormatName(getStr(values, headerIndex, "PRODUCT_FORMAT_NAME"));
        data.setDigitalSpecWidth(getInteger(values, headerIndex, "DIGITAL_SPEC_WIDTH"));
        data.setDigitalSpecHeight(getInteger(values, headerIndex, "DIGITAL_SPEC_HEIGHT"));
        data.setWidthXHeight(getStr(values, headerIndex, "WIDTHXHEIGHT"));
        data.setAspectRatio(getStr(values, headerIndex, "ASPECT_RATIO"));
        data.setDigitalSpecMotionType(getStr(values, headerIndex, "DIGITAL_SPEC_MOTION_TYPE"));
        data.setDigitalSpecFps(getInteger(values, headerIndex, "DIGITAL_SPEC_FPS"));
        data.setDigitalSpecRotation(getInteger(values, headerIndex, "DIGITAL_SPEC_ROTATION"));
        data.setSlotDuration(getInteger(values, headerIndex, "SLOT_DURATION"));
        data.setVenueTaxonomyId(getInteger(values, headerIndex, "VENUE_TAXONOMY_ID"));
        data.setVenueTaxonomyValue(getStr(values, headerIndex, "VENUE_TAXONOMY_VALUE"));
        data.setFrameImagePath(getStr(values, headerIndex, "FRAMEIMAGEPATH"));
        data.setImpressions(getSmartDouble(values, headerIndex, "IMPRESSIONS"));
        data.setFloorCpm(getNullableDouble(values, headerIndex, "FLOORCPM", "FLOOR_CPM"));
        data.setMediaOwnerCurrency(getStr(values, headerIndex, "MEDIAOWNERCURRENCY", "CURRENCY"));
        data.setVioohSelectOptin(getStr(values, headerIndex, "VIOOHSELECTOPTIN", "VIOOH_SELECT_OPTIN"));
        data.setClosestPoi(getStr(values, headerIndex, "CLOSEST_POI"));
        data.setDistanceToClosestPoi(getStr(values, headerIndex, "DISTANCE_TO_CLOSEST_POI"));
        applyFilenamePoiFallback(data, poiRadiusInfo);
        data.setIata(getStr(values, headerIndex, "IATA"));
        data.setScoreP(getNullableDouble(values, headerIndex, "SCORE_P"));
        return data;
    }

    private Row findFirstNonEmptyRow(Sheet sheet, DataFormatter formatter) {
        for (int rowNum = sheet.getFirstRowNum(); rowNum <= sheet.getLastRowNum(); rowNum++) {
            Row row = sheet.getRow(rowNum);
            if (row == null) {
                continue;
            }
            short lastCell = row.getLastCellNum();
            if (lastCell <= 0) {
                continue;
            }
            String[] values = rowToValues(row, lastCell, formatter);
            if (!isEmptyRecord(values)) {
                return row;
            }
        }
        return null;
    }

    private String[] rowToValues(Row row, int expectedColumns, DataFormatter formatter) {
        String[] values = new String[expectedColumns];
        for (int col = 0; col < expectedColumns; col++) {
            values[col] = formatter.formatCellValue(row.getCell(col)).trim();
        }
        return values;
    }

    private boolean isEmptyRecord(String[] values) {
        if (values == null) {
            return true;
        }
        for (String value : values) {
            if (!isInvalidText(value)) {
                return false;
            }
        }
        return true;
    }

    private void logPoiFallbackNotice(String filePath, PoiRadiusInfo poiRadiusInfo) {
        if (!poiRadiusInfo.isMatched()) {
            System.out.println(
                    "Notice: file name has no valid fallback POI; using CLOSEST_POI from input when present: "
                            + Paths.get(filePath).getFileName()
            );
        } else if (!poiRadiusInfo.hasDistance()) {
            System.out.println(
                    "Notice: file name matches POI-only pattern; used only when input CLOSEST_POI is empty: "
                            + Paths.get(filePath).getFileName()
            );
        }
    }

    // detect the delimiter of csv
    private char detectDelimiter(String headerLine) {
        int tabCount = countChar(headerLine, '\t');
        int commaCount = countChar(headerLine, ',');
        int semicolonCount = countChar(headerLine, ';');

        if (tabCount >= commaCount && tabCount >= semicolonCount && tabCount > 0) {
            return '\t';
        }
        if (commaCount >= semicolonCount && commaCount > 0) {
            return ',';
        }
        if (semicolonCount > 0) {
            return ';';
        }
        return ',';
    }

    private int countChar(String source, char target) {
        int count = 0;
        for (char c : source.toCharArray()) {
            if (c == target) {
                count++;
            }
        }
        return count;
    }

    // match header
    private String normalizeHeader(String header) {
        if (header == null) {
            return "";
        }
        return header.replace("\uFEFF", "").replace("\"", "").trim().toUpperCase(Locale.ROOT);
    }

    private void registerHeaderIndex(Map<String, Integer> headerIndex, String header, int index) {
        String normalized = normalizeHeader(header);
        if (normalized.isEmpty()) {
            return;
        }
        headerIndex.putIfAbsent(normalized, index);
        headerIndex.putIfAbsent(compactHeaderKey(normalized), index);
    }

    private String compactHeaderKey(String header) {
        if (header == null) {
            return "";
        }
        return header.replace("_", "").replace(" ", "");
    }

    private String normalizeLineDelimiters(String line, char delimiter) {
        if (line == null) {
            return "";
        }
        String normalized = line;
        // If the file is CSV but some rows contain tabs, treat tabs as delimiters too (common in "mixed" exports).
        if (delimiter == ',') {
            normalized = normalized.replace('\t', ',');
        } else if (delimiter == '\t') {
            // Best-effort: if a continuation line looks like a CSV fragment (many commas, few/no tabs),
            // normalize commas to tabs so it can be merged back into a TSV record.
            int commaCount = countChar(normalized, ',');
            int tabCount = countChar(normalized, '\t');
            if (commaCount > 0 && commaCount > tabCount && tabCount < 3) {
                normalized = normalized.replace(',', '\t');
            }
        }
        return normalized;
    }

    private String[] parseCsvLine(String line, char delimiter) {
        List<String> tokens = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();

        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == delimiter && !inQuotes) {
                tokens.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString().trim());
        return tokens.toArray(new String[0]);
    }

    private PoiRadiusInfo parsePoiRadiusInfo(String filePath) {
        Path path = Paths.get(filePath);
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        baseName = baseName.trim();

        Matcher matcher = FILE_POI_RADIUS_PATTERN.matcher(baseName);
        if (matcher.matches()) {
            String poiName = matcher.group(1).trim();
            String radius = matcher.group(2).trim() + "km";
            if (isInvalidText(poiName) || isInvalidText(radius)) {
                return PoiRadiusInfo.empty();
            }
            return PoiRadiusInfo.withDistance(poiName, radius);
        }
        return parsePoiOnlyInfo(filePath, baseName);
    }

    private PoiRadiusInfo parsePoiOnlyInfo(String filePath, String baseName) {
        if (FrameDetailsFileDetector.isDetailsFile(filePath)) {
            return PoiRadiusInfo.empty();
        }
        if (NUMERIC_KM_IN_FILENAME.matcher(baseName).find()) {
            return PoiRadiusInfo.empty();
        }

        String poiName = stripPoiFilenameSuffixes(baseName);
        if (isInvalidText(poiName)) {
            return PoiRadiusInfo.empty();
        }
        if (!isValidFilenamePoiOnlyBaseName(baseName)) {
            return PoiRadiusInfo.empty();
        }
        return PoiRadiusInfo.poiOnly(poiName);
    }

    private boolean isValidFilenamePoiOnlyBaseName(String baseName) {
        if (ALL_SUFFIX.matcher(baseName).find() || FRAMES_LIST_SUFFIX.matcher(baseName).find()) {
            return true;
        }
        if (baseName.indexOf(' ') >= 0) {
            return true;
        }
        for (int i = 0; i < baseName.length(); i++) {
            char c = baseName.charAt(i);
            if (c > 127 || Character.isUpperCase(c)) {
                return true;
            }
        }
        return countChar(baseName, '_') >= 2;
    }

    private String stripPoiFilenameSuffixes(String baseName) {
        String poiName = baseName;
        Matcher framesListMatcher = FRAMES_LIST_SUFFIX.matcher(poiName);
        if (framesListMatcher.find()) {
            poiName = poiName.substring(0, framesListMatcher.start()).trim();
        }
        Matcher allMatcher = ALL_SUFFIX.matcher(poiName);
        if (allMatcher.find()) {
            poiName = poiName.substring(0, allMatcher.start()).trim();
        }
        return poiName;
    }

    /**
     * Keeps {@code CLOSEST_POI} (and its distance) from the input row whenever the row contains a valid POI.
     * Otherwise, falls back to the POI parsed from the file name and its radius when present.
     */
    private void applyFilenamePoiFallback(FrameData data, PoiRadiusInfo poiRadiusInfo) {
        if (!isInvalidText(data.getClosestPoi()) || poiRadiusInfo.isEmpty()) {
            return;
        }
        data.setClosestPoi(poiRadiusInfo.poiName);
        if (poiRadiusInfo.hasDistance()) {
            data.setDistanceToClosestPoi(poiRadiusInfo.radiusKm);
        }
    }

    private boolean isInvalidText(String v) {
        if (v == null) {
            return true;
        }
        String normalized = v.trim();
        return normalized.isEmpty()
                || normalized.equalsIgnoreCase("null")
                || normalized.equals("\\N")
                || normalized.equals("-");
    }

    private static final class PoiRadiusInfo {
        private final String poiName;
        private final String radiusKm;
        private final boolean matched;

        private PoiRadiusInfo(String poiName, String radiusKm, boolean matched) {
            this.poiName = poiName;
            this.radiusKm = radiusKm;
            this.matched = matched;
        }

        private static PoiRadiusInfo empty() {
            return new PoiRadiusInfo(null, null, false);
        }

        private static PoiRadiusInfo withDistance(String poiName, String radiusKm) {
            return new PoiRadiusInfo(poiName, radiusKm, true);
        }

        private static PoiRadiusInfo poiOnly(String poiName) {
            return new PoiRadiusInfo(poiName, null, true);
        }

        private boolean isEmpty() {
            return poiName == null;
        }

        private boolean hasDistance() {
            return radiusKm != null;
        }

        private boolean isMatched() {
            return matched;
        }
    }

    private Integer getIndexByAliases(Map<String, Integer> map, String... keys) {
        for (String key : keys) {
            String normalized = normalizeHeader(key);
            Integer idx = map.get(normalized);
            if (idx != null) {
                return idx;
            }
            idx = map.get(compactHeaderKey(normalized));
            if (idx != null) {
                return idx;
            }
        }
        return null;
    }

    // convert impression to double
    private Double getSmartDouble(String[] values, Map<String, Integer> map, String... keys) {
        try {
            Integer idx = getIndexByAliases(map, keys);
            if (idx == null || idx < 0 || idx >= values.length) {
                return null;
            }

            String v = values[idx]
                    .replace(",", "")
                    .replace("\"", "")
                    .trim();

            if (v.isEmpty() || v.equalsIgnoreCase("null") || v.equals("\\N") || v.equals("-")) {
                return null;
            }

            return Double.parseDouble(v);
        } catch (Exception e) {
            return null;
        }
    }


    // get String
    private String getStr(String[] values, Map<String, Integer> map, String... keys) {
        try {
            Integer idx = getIndexByAliases(map, keys);
            if (idx == null || idx < 0 || idx >= values.length) {
                return null;
            }
            String v = values[idx].replace("\"", "").trim();
            if (v.isEmpty() || v.equalsIgnoreCase("null") || v.equals("\\N") || v.equals("-")) {
                return null;
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    /** Nullable integer: column missing or empty -> null (e.g. VENUE_TAXONOMY_ID). */
    private Integer getInteger(String[] values, Map<String, Integer> map, String... keys) {
        try {
            Integer idx = getIndexByAliases(map, keys);
            if (idx == null || idx < 0 || idx >= values.length) {
                return null;
            }
            String raw = values[idx].replace(",", "").replace("\"", "").trim();
            if (raw.isEmpty() || raw.equalsIgnoreCase("null") || raw.equals("\\N") || raw.equals("-")) {
                return null;
            }
            return (int) Math.round(Double.parseDouble(raw));
        } catch (Exception e) {
            return null;
        }
    }

    // get nullable double
    private Double getNullableDouble(String[] values, Map<String, Integer> map, String... keys) {
        try {
            Integer idx = getIndexByAliases(map, keys);
            if (idx == null || idx < 0 || idx >= values.length) {
                return null;
            }
            String v = values[idx].replace(",", "").replace("\"", "").trim();
            if (v.isEmpty() || v.equalsIgnoreCase("null") || v.equals("\\N") || v.equals("-")) {
                return null;
            }
            return Double.parseDouble(v);
        } catch (Exception e) {
            return null;
        }
    }
}
