package com.autoproject.service.pics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads and applies the only legal Venue Type values used by the PICS image pipeline. */
final class VenueTypeDictionary {
    static final String DEFAULT_PATH = "config/venue_type_dictionary.csv";
    private static final String ENV_PATH = "PROPEL_VENUE_TYPE_DICTIONARY_PATH";
    private static final Map<String, String> COMPATIBILITY_ALIASES = Map.of(
            "airport", "airports"
    );

    private final Map<String, String> standardByKey;

    private VenueTypeDictionary(Map<String, String> standardByKey) {
        this.standardByKey = Map.copyOf(standardByKey);
    }

    static VenueTypeDictionary loadConfigured() {
        String configured = System.getenv(ENV_PATH);
        if (configured != null && !configured.isBlank()) {
            VenueTypeDictionary dictionary = load(Path.of(configured.trim()));
            if (!dictionary.isEmpty()) {
                return dictionary;
            }
        }
        VenueTypeDictionary fileDictionary = load(Path.of(DEFAULT_PATH));
        if (!fileDictionary.isEmpty()) {
            return fileDictionary;
        }
        try (InputStream in = VenueTypeDictionary.class.getResourceAsStream("/" + DEFAULT_PATH)) {
            return in == null ? new VenueTypeDictionary(Map.of()) : load(in);
        } catch (IOException ignored) {
            return new VenueTypeDictionary(Map.of());
        }
    }

    static VenueTypeDictionary load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return new VenueTypeDictionary(Map.of());
        }
        try (InputStream in = Files.newInputStream(path)) {
            return load(in);
        } catch (IOException ignored) {
            return new VenueTypeDictionary(Map.of());
        }
    }

    private static VenueTypeDictionary load(InputStream in) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean firstNonBlank = true;
            while ((line = reader.readLine()) != null) {
                String display = cleanCsvValue(line);
                if (display.isEmpty()) {
                    continue;
                }
                if (firstNonBlank) {
                    firstNonBlank = false;
                    if ("venuetype".equals(VenueTypeParser.normalize(display))) {
                        continue;
                    }
                }
                values.putIfAbsent(VenueTypeParser.normalize(display), display);
            }
        }
        return new VenueTypeDictionary(values);
    }

    MatchResult match(String rawVenueType) {
        if (VenueTypeParser.isBlank(rawVenueType)) {
            return new MatchResult(List.of(), List.of());
        }
        LinkedHashSet<String> matched = new LinkedHashSet<>();
        List<MissingVenueType> missing = new ArrayList<>();
        for (String candidate : VenueTypeParser.splitDisplayValues(rawVenueType)) {
            String key = VenueTypeParser.normalize(candidate);
            String aliasedKey = COMPATIBILITY_ALIASES.getOrDefault(key, key);
            String standard = standardByKey.get(aliasedKey);
            if (standard == null) {
                missing.add(new MissingVenueType(candidate, normalizeForAudit(candidate)));
            } else {
                matched.add(standard);
            }
        }
        if (matched.isEmpty()) {
            String unknown = standardByKey.get("unknown");
            if (unknown != null) {
                matched.add(unknown);
            }
        }
        return new MatchResult(new ArrayList<>(matched), missing);
    }

    boolean isEmpty() {
        return standardByKey.isEmpty();
    }

    int size() {
        return standardByKey.size();
    }

    private static String cleanCsvValue(String raw) {
        String value = raw == null ? "" : raw.replace("\uFEFF", "").trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeForAudit(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    record MatchResult(List<String> standardVenueTypes, List<MissingVenueType> missingVenueTypes) {
    }

    record MissingVenueType(String original, String normalized) {
    }
}
