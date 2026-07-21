package com.autoproject.service;

import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FrameDetailsDateParser {
    private static final LocalDate NO_DATE = LocalDate.MIN;
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern UNDERSCORE_DATE = Pattern.compile("(\\d{4})_(\\d{2})_(\\d{2})");
    private static final Pattern COMPACT_DATE = Pattern.compile("(?<!\\d)(\\d{4})(\\d{2})(\\d{2})(?!\\d)");

    private FrameDetailsDateParser() {
    }

    static LocalDate extractDate(String path) {
        if (path == null || path.isBlank()) {
            return NO_DATE;
        }
        String baseName = Paths.get(path).getFileName().toString();
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = baseName.substring(0, dotIndex);
        }
        LocalDate parsed = parseFirstMatch(baseName, ISO_DATE, "yyyy-MM-dd");
        if (parsed != null) {
            return parsed;
        }
        parsed = parseFirstMatch(baseName, UNDERSCORE_DATE, "yyyy_MM_dd");
        if (parsed != null) {
            return parsed;
        }
        parsed = parseCompactDate(baseName);
        return parsed != null ? parsed : NO_DATE;
    }

    private static LocalDate parseFirstMatch(String text, Pattern pattern, String format) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String candidate = matcher.group(0);
        try {
            return LocalDate.parse(candidate, DateTimeFormatter.ofPattern(format, Locale.ROOT));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static LocalDate parseCompactDate(String text) {
        Matcher matcher = COMPACT_DATE.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String candidate = matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3);
        try {
            return LocalDate.parse(candidate, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
