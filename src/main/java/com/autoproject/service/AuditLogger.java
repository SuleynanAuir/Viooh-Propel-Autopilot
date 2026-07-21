package com.autoproject.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AuditLogger {
    private static final Path LOG_FILE = Path.of("audit_log.txt");
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AuditLogger() {
    }

    public static synchronized void logRun(String action, List<String> inputFiles, boolean success) {
        String timestamp = LocalDateTime.now().format(TS_FORMAT);
        String safeAction = sanitize(action);
        List<String> names = extractNames(inputFiles);
        String filesValue = names.isEmpty() ? "-" : String.join(",", names);
        String status = success ? "SUCCESS" : "FAIL";
        String line = timestamp
                + " | "
                + safeAction
                + " | files="
                + filesValue
                + " | "
                + status
                + System.lineSeparator();

        try {
            Files.writeString(
                    LOG_FILE,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // Best-effort logging: do not break main flow if audit log write fails.
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "UNKNOWN";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "UNKNOWN" : trimmed;
    }

    private static List<String> extractNames(List<String> inputFiles) {
        if (inputFiles == null || inputFiles.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>(inputFiles.size());
        for (String file : inputFiles) {
            if (file == null || file.trim().isEmpty()) {
                continue;
            }
            names.add(Path.of(file).getFileName().toString());
        }
        return names;
    }
}
