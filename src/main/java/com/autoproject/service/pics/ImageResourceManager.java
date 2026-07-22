package com.autoproject.service.pics;

import org.apache.poi.ss.usermodel.Workbook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns meta/images plus the audit files emitted by the automatic PICS pipeline. */
final class ImageResourceManager {
    private final Path metaDir;
    private final Path imagesDir;
    private final Map<String, Mapping> mappings = new LinkedHashMap<>();
    private final List<ProposalImageRequest> missing = new ArrayList<>();
    private final Map<String, Integer> counters = new LinkedHashMap<>();

    ImageResourceManager(Path metaDir) {
        this.metaDir = metaDir;
        this.imagesDir = metaDir.resolve("images");
    }

    void initialize() throws IOException {
        Files.createDirectories(imagesDir);
    }

    SavedImage save(
            ProposalImageRequest request,
            String sourceUrl,
            byte[] data,
            int pictureType) throws IOException {
        String combination = VenueTypeParser.fileToken(request.country()) + "_"
                + VenueTypeParser.fileToken(request.market()) + "_"
                + VenueTypeParser.fileToken(request.venueType());
        int index = counters.merge(combination, 1, Integer::sum);
        String extension = pictureType == Workbook.PICTURE_TYPE_PNG ? ".png" : ".jpg";
        String filename = combination + "_" + String.format("%03d", index) + extension;
        Path target = imagesDir.resolve(filename);
        Files.write(target, data);
        mappings.put(filename, new Mapping(
                request.country(), request.market(), request.venueType(), sourceUrl, request.proposalRow()));
        return new SavedImage(target, filename);
    }

    void recordMissing(ProposalImageRequest request) {
        missing.add(request);
    }

    void recordFailure(String url, String error) {
        try {
            Files.createDirectories(metaDir);
            String line = Instant.now() + "\t" + safeLog(url) + "\t" + safeLog(error) + System.lineSeparator();
            Files.writeString(
                    metaDir.resolve("failed_download_images.log"),
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Export must continue even if audit logging is unavailable.
        }
    }

    void finish() {
        try {
            Files.createDirectories(metaDir);
            Files.writeString(metaDir.resolve("image_mapping.json"), mappingJson(), StandardCharsets.UTF_8);
            Files.writeString(metaDir.resolve("missing_images.json"), missingJson(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The workbook remains usable even when sidecar files cannot be written.
        }
    }

    private String mappingJson() {
        StringBuilder json = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Mapping> entry : mappings.entrySet()) {
            if (i++ > 0) {
                json.append(",\n");
            }
            Mapping m = entry.getValue();
            json.append("  \"").append(escape(entry.getKey())).append("\": {\n")
                    .append("    \"country\": \"").append(escape(m.country())).append("\",\n")
                    .append("    \"market\": \"").append(escape(m.market())).append("\",\n")
                    .append("    \"venue_type\": \"").append(escape(m.venueType())).append("\",\n")
                    .append("    \"source_url\": \"").append(escape(m.sourceUrl())).append("\",\n")
                    .append("    \"proposal_row\": ").append(m.proposalRow()).append("\n")
                    .append("  }");
        }
        return json.append("\n}\n").toString();
    }

    private String missingJson() {
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < missing.size(); i++) {
            ProposalImageRequest r = missing.get(i);
            if (i > 0) {
                json.append(",\n");
            }
            json.append("  {\n")
                    .append("    \"country\": \"").append(escape(r.country())).append("\",\n")
                    .append("    \"market\": \"").append(escape(r.market())).append("\",\n")
                    .append("    \"venue_type\": \"").append(escape(r.venueType())).append("\",\n")
                    .append("    \"proposal_row\": ").append(r.proposalRow()).append("\n")
                    .append("  }");
        }
        return json.append("\n]\n").toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String safeLog(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    record SavedImage(Path path, String filename) {
    }

    private record Mapping(String country, String market, String venueType, String sourceUrl, int proposalRow) {
    }
}
