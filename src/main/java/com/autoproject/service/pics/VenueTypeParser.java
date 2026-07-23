package com.autoproject.service.pics;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Normalizes and expands the dotted Venue Type taxonomy used by Proposal and supply_matrix. */
final class VenueTypeParser {
    private VenueTypeParser() {
    }

    static List<String> splitDisplayValues(String raw) {
        if (isBlank(raw)) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String part : raw.split("\\.")) {
            String cleaned = cleanDisplay(part);
            if (!cleaned.isEmpty()) {
                values.add(cleaned);
            }
        }
        return new ArrayList<>(values);
    }

    static Set<String> normalizedKeys(String raw) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String display : splitDisplayValues(raw)) {
            String key = normalize(display);
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        if (!isBlank(raw)) {
            String key = normalize(raw);
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String ascii = Normalizer.normalize(value.trim(), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        return ascii.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    static String fileToken(String value) {
        if (isBlank(value)) {
            return "unknown";
        }
        String ascii = Normalizer.normalize(value.trim(), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        String token = ascii.replaceAll("[^\\p{L}\\p{N}]+", "_").replaceAll("^_+|_+$", "");
        return token.isEmpty() ? "unknown" : token;
    }

    private static String cleanDisplay(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    static boolean isBlank(String value) {
        return value == null
                || value.isBlank()
                || value.trim().equalsIgnoreCase("null")
                || value.trim().equals("\\N")
                || value.trim().equals("-");
    }
}
