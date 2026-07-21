package com.autoproject.service;

import org.apache.poi.ss.usermodel.Cell;
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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads frame-details exports (Excel/csv) and maps frame identifiers to authoritative VIOOH Select opt-in values.
 */
public class FrameDetailsReader {
    private static final String[] FRAME_ID_HEADER_ALIASES = {
            "FRAME ID",
            "ROUTE FRAME CODE",
            "FRAME CODE",
            "ASSETUUID"
    };
    private static final String[] VIOOH_SELECT_HEADER_ALIASES = {
            "VIOOHSELECTOPTIN",
            "VIOOH_SELECT_OPTIN",
            "VIOOHSELECTED",
            "VIOOH_SELECTED",
            "VIOOH SELECT OPTIN",
            "VIOOH SELECTED"
    };
    private static final String[] VIOOH_SELECT_CPM_LOCAL_HEADER_ALIASES = {
            "VIOOHSELECTCPMLOCAL",
            "VIOOH_SELECT_CPM_LOCAL",
            "VIOOH SELECT CPM LOCAL"
    };

    public Map<String, String> readLookup(String filePath) throws Exception {
        return readDualLookup(filePath).optinByFrameId();
    }

    public Map<String, Double> readCpmLocalLookup(String filePath) throws Exception {
        return readDualLookup(filePath).cpmLocalByFrameId();
    }

    public FrameDetailsLookup readDualLookup(String filePath) throws Exception {
        String lower = filePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return readExcelDualLookup(filePath);
        }
        if (lower.endsWith(".csv") || lower.endsWith(".tsv")) {
            return readCsvDualLookup(filePath);
        }
        throw new IllegalArgumentException("Unsupported frame-details file type: " + Paths.get(filePath).getFileName());
    }

    public Map<String, String> readMergedLookup(List<String> filePaths) throws Exception {
        return readMergedLookups(filePaths).optinByFrameId();
    }

    public FrameDetailsLookup readMergedLookups(List<String> filePaths) throws Exception {
        if (filePaths == null || filePaths.isEmpty()) {
            return new FrameDetailsLookup(Map.of(), Map.of());
        }
        List<String> sorted = new ArrayList<>(filePaths);
        sorted.sort(Comparator
                .comparing(FrameDetailsDateParser::extractDate)
                .thenComparing(path -> Paths.get(path).getFileName().toString().toLowerCase(Locale.ROOT)));

        Map<String, String> mergedOptin = new LinkedHashMap<>();
        Map<String, Double> mergedCpmLocal = new LinkedHashMap<>();
        Map<String, java.time.LocalDate> optinSourceDate = new HashMap<>();
        Map<String, java.time.LocalDate> cpmSourceDate = new HashMap<>();
        for (String path : sorted) {
            java.time.LocalDate fileDate = FrameDetailsDateParser.extractDate(path);
            FrameDetailsLookup partial = readDualLookup(path);
            mergeOptinMaps(mergedOptin, optinSourceDate, partial.optinByFrameId(), fileDate);
            mergeCpmLocalMaps(mergedCpmLocal, cpmSourceDate, partial.cpmLocalByFrameId(), fileDate);
        }
        return new FrameDetailsLookup(mergedOptin, mergedCpmLocal);
    }

    private static void mergeOptinMaps(
            Map<String, String> merged,
            Map<String, java.time.LocalDate> keySourceDate,
            Map<String, String> partial,
            java.time.LocalDate fileDate
    ) {
        for (Map.Entry<String, String> entry : partial.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String canonicalKey = key.toUpperCase(Locale.ROOT);
            String value = entry.getValue();
            String existing = merged.get(canonicalKey);
            if (existing == null) {
                merged.put(canonicalKey, value);
                keySourceDate.put(canonicalKey, fileDate);
                continue;
            }
            java.time.LocalDate existingDate = keySourceDate.get(canonicalKey);
            if (fileDate.isAfter(existingDate)) {
                merged.put(canonicalKey, value);
                keySourceDate.put(canonicalKey, fileDate);
            } else if (fileDate.equals(existingDate)) {
                merged.put(canonicalKey, preferYes(existing, value));
            }
        }
    }

    private static void mergeCpmLocalMaps(
            Map<String, Double> merged,
            Map<String, java.time.LocalDate> keySourceDate,
            Map<String, Double> partial,
            java.time.LocalDate fileDate
    ) {
        for (Map.Entry<String, Double> entry : partial.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String canonicalKey = key.toUpperCase(Locale.ROOT);
            Double value = entry.getValue();
            if (value == null) {
                continue;
            }
            Double existing = merged.get(canonicalKey);
            if (existing == null) {
                merged.put(canonicalKey, value);
                keySourceDate.put(canonicalKey, fileDate);
                continue;
            }
            java.time.LocalDate existingDate = keySourceDate.get(canonicalKey);
            if (!fileDate.isBefore(existingDate)) {
                merged.put(canonicalKey, value);
                keySourceDate.put(canonicalKey, fileDate);
            }
        }
    }

    private FrameDetailsLookup readExcelDualLookup(String filePath) throws Exception {
        Map<String, String> optinLookup = new LinkedHashMap<>();
        Map<String, Double> cpmLocalLookup = new LinkedHashMap<>();
        DataFormatter formatter = new DataFormatter();
        try (InputStream in = Files.newInputStream(Paths.get(filePath));
             Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null) {
                return new FrameDetailsLookup(optinLookup, cpmLocalLookup);
            }
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                return new FrameDetailsLookup(optinLookup, cpmLocalLookup);
            }
            Map<String, Integer> headerIndex = buildHeaderIndex(headerRow, formatter);
            Integer frameIdCol = resolveFrameIdColumn(headerIndex);
            Integer vioohCol = resolveVioohSelectColumn(headerIndex);
            Integer cpmLocalCol = resolveVioohSelectCpmLocalColumn(headerIndex);
            if (frameIdCol == null) {
                throw new IllegalArgumentException(
                        "Frame-details file is missing Frame ID / Route frame code / Frame code column: "
                                + Paths.get(filePath).getFileName()
                );
            }
            if (vioohCol == null) {
                throw new IllegalArgumentException(
                        "Frame-details file is missing VIOOH Select opt-in column: "
                                + Paths.get(filePath).getFileName()
                );
            }
            for (int rowNum = sheet.getFirstRowNum() + 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row row = sheet.getRow(rowNum);
                if (row == null) {
                    continue;
                }
                String frameId = normalizeFrameId(formatter.formatCellValue(row.getCell(frameIdCol)));
                String optin = normalizeOptin(formatter.formatCellValue(row.getCell(vioohCol)));
                if (frameId == null || optin == null) {
                    continue;
                }
                String canonicalKey = frameId.toUpperCase(Locale.ROOT);
                optinLookup.put(canonicalKey, optin);
                if (cpmLocalCol != null) {
                    Double cpmLocal = parseCpmLocal(formatter.formatCellValue(row.getCell(cpmLocalCol)));
                    if (cpmLocal != null) {
                        cpmLocalLookup.put(canonicalKey, cpmLocal);
                    }
                }
            }
        }
        return new FrameDetailsLookup(optinLookup, cpmLocalLookup);
    }

    private Map<String, Integer> buildHeaderIndex(Row headerRow, DataFormatter formatter) {
        Map<String, Integer> headerIndex = new HashMap<>();
        short lastCell = headerRow.getLastCellNum();
        for (int col = 0; col < lastCell; col++) {
            Cell cell = headerRow.getCell(col);
            if (cell == null) {
                continue;
            }
            String header = normalizeHeader(formatter.formatCellValue(cell));
            if (!header.isEmpty()) {
                headerIndex.put(header, col);
            }
        }
        return headerIndex;
    }

    private FrameDetailsLookup readCsvDualLookup(String filePath) throws Exception {
        List<Charset> candidates = List.of(
                StandardCharsets.UTF_8,
                StandardCharsets.UTF_16LE,
                StandardCharsets.UTF_16BE,
                Charset.forName("GB18030"),
                Charset.forName("windows-1252")
        );
        Exception last = null;
        for (Charset charset : candidates) {
            try {
                return readCsvDualLookupWithCharset(filePath, charset);
            } catch (MalformedInputException | UnmappableCharacterException e) {
                last = e;
            }
        }
        if (last != null) {
            throw new IllegalArgumentException(
                    "Frame-details CSV encoding is unsupported: " + Paths.get(filePath).getFileName(),
                    last
            );
        }
        return readCsvDualLookupWithCharset(filePath, StandardCharsets.UTF_8);
    }

    private FrameDetailsLookup readCsvDualLookupWithCharset(String filePath, Charset charset) throws Exception {
        Map<String, String> optinLookup = new LinkedHashMap<>();
        Map<String, Double> cpmLocalLookup = new LinkedHashMap<>();
        try (BufferedReader br = Files.newBufferedReader(Paths.get(filePath), charset)) {
            String headerLine = br.readLine();
            if (headerLine == null || headerLine.trim().isEmpty()) {
                return new FrameDetailsLookup(optinLookup, cpmLocalLookup);
            }
            char delimiter = detectDelimiter(headerLine);
            String[] headers = parseCsvLine(normalizeLineDelimiters(headerLine, delimiter), delimiter);
            Map<String, Integer> headerIndex = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                headerIndex.put(normalizeHeader(headers[i]), i);
            }
            Integer frameIdCol = resolveFrameIdColumn(headerIndex);
            Integer vioohCol = resolveVioohSelectColumn(headerIndex);
            Integer cpmLocalCol = resolveVioohSelectCpmLocalColumn(headerIndex);
            if (frameIdCol == null) {
                throw new IllegalArgumentException(
                        "Frame-details file is missing Frame ID / Route frame code / Frame code column: "
                                + Paths.get(filePath).getFileName()
                );
            }
            if (vioohCol == null) {
                throw new IllegalArgumentException(
                        "Frame-details file is missing VIOOH Select opt-in column: "
                                + Paths.get(filePath).getFileName()
                );
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] values = parseCsvLine(normalizeLineDelimiters(line, delimiter), delimiter);
                if (frameIdCol >= values.length || vioohCol >= values.length) {
                    continue;
                }
                String frameId = normalizeFrameId(values[frameIdCol]);
                String optin = normalizeOptin(values[vioohCol]);
                if (frameId == null || optin == null) {
                    continue;
                }
                String canonicalKey = frameId.toUpperCase(Locale.ROOT);
                optinLookup.put(canonicalKey, optin);
                if (cpmLocalCol != null && cpmLocalCol < values.length) {
                    Double cpmLocal = parseCpmLocal(values[cpmLocalCol]);
                    if (cpmLocal != null) {
                        cpmLocalLookup.put(canonicalKey, cpmLocal);
                    }
                }
            }
        }
        return new FrameDetailsLookup(optinLookup, cpmLocalLookup);
    }

    private Integer resolveFrameIdColumn(Map<String, Integer> headerIndex) {
        for (String alias : FRAME_ID_HEADER_ALIASES) {
            Integer idx = headerIndex.get(alias);
            if (idx != null) {
                return idx;
            }
        }
        return null;
    }

    private Integer resolveVioohSelectColumn(Map<String, Integer> headerIndex) {
        for (String alias : VIOOH_SELECT_HEADER_ALIASES) {
            Integer idx = headerIndex.get(alias);
            if (idx != null) {
                return idx;
            }
        }
        return null;
    }

    private Integer resolveVioohSelectCpmLocalColumn(Map<String, Integer> headerIndex) {
        for (String alias : VIOOH_SELECT_CPM_LOCAL_HEADER_ALIASES) {
            Integer idx = headerIndex.get(alias);
            if (idx != null) {
                return idx;
            }
        }
        return null;
    }

    private Double parseCpmLocal(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.replace("\"", "").trim();
        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("null")
                || trimmed.equals("\\N")
                || trimmed.equals("-")) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(trimmed);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeHeader(String header) {
        if (header == null) {
            return "";
        }
        return header.replace("\uFEFF", "").replace("\"", "").trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeFrameId(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.replace("\"", "").trim();
        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("null")
                || trimmed.equals("\\N")
                || trimmed.equals("-")) {
            return null;
        }
        return trimmed;
    }

    private String normalizeOptin(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.replace("\"", "").trim();
        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("null")
                || trimmed.equals("\\N")
                || trimmed.equals("-")) {
            return null;
        }
        if ("YES".equalsIgnoreCase(trimmed) || "Y".equalsIgnoreCase(trimmed) || "TRUE".equalsIgnoreCase(trimmed)) {
            return "Yes";
        }
        if ("NO".equalsIgnoreCase(trimmed) || "N".equalsIgnoreCase(trimmed) || "FALSE".equalsIgnoreCase(trimmed)) {
            return "No";
        }
        return trimmed;
    }

    private static String preferYes(String left, String right) {
        if (isYes(left) || isYes(right)) {
            return "Yes";
        }
        return left;
    }

    private static boolean isYes(String value) {
        return value != null && "YES".equals(value.trim().toUpperCase(Locale.ROOT));
    }

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

    private String normalizeLineDelimiters(String line, char delimiter) {
        if (line == null) {
            return "";
        }
        if (delimiter == ',') {
            return line.replace('\t', ',');
        }
        return line;
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
}
