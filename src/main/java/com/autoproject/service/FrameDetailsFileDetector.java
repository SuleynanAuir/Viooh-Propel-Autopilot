package com.autoproject.service;

import java.nio.file.Paths;
import java.util.Locale;

public final class FrameDetailsFileDetector {
    private FrameDetailsFileDetector() {
    }

    public static boolean isDetailsFile(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String fileName = Paths.get(path).getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex >= fileName.length() - 1) {
            return false;
        }
        String baseName = fileName.substring(0, dotIndex);
        String extension = fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        if (!"xlsx".equals(extension) && !"xls".equals(extension) && !"csv".equals(extension) && !"tsv".equals(extension)) {
            return false;
        }
        String baseNameLower = baseName.toLowerCase(Locale.ROOT);
        if (baseNameLower.contains("details")) {
            return true;
        }
        String normalized = baseNameLower.replace('_', ' ').replace('-', ' ');
        return normalized.contains("vs cpm");
    }
}
