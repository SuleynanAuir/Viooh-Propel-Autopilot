package com.autoproject.service;

import com.autoproject.model.FrameData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DataMerger {
    private static final Map<String, String> STANDARD_COUNTRY_TO_ISO3 = new HashMap<>();
    private static final Map<String, String> STANDARD_ISO3_TO_COUNTRY = new HashMap<>();

    static {
        initializeStandardCountryMaps();
    }

    public List<FrameData> merge(String... paths) throws Exception {
        return merge(paths, null);
    }

    public List<FrameData> merge(String[] paths, ExportProgress progress) throws Exception {
        List<String> frameListPaths = new ArrayList<>();
        List<String> detailsPaths = new ArrayList<>();
        for (String path : paths) {
            if (FrameDetailsFileDetector.isDetailsFile(path)) {
                detailsPaths.add(path);
            } else {
                frameListPaths.add(path);
            }
        }
        if (frameListPaths.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one frame list CSV/TSV/Excel file is required. Frame-details files alone cannot be exported."
            );
        }

        CsvReader reader = new CsvReader();
        List<FrameData> all = new ArrayList<>();
        for (int i = 0; i < frameListPaths.size(); i++) {
            String path = frameListPaths.get(i);
            if (progress != null) {
                progress.onMergeReadingFile(i, frameListPaths.size(), path);
            }
            System.out.println("Reading: " + path);
            all.addAll(reader.read(path));
        }
        for (String detailsPath : detailsPaths) {
            System.out.println("Reading frame-details: " + detailsPath);
        }
        if (!detailsPaths.isEmpty()) {
            FrameDetailsLookup lookups = new FrameDetailsReader().readMergedLookups(detailsPaths);
            VioohSelectOptinResolver.apply(all, lookups.optinByFrameId());
            VioohSelectCpmLocalResolver.apply(all, lookups.cpmLocalByFrameId());
        } else {
            VioohSelectCpmLocalResolver.apply(all, Map.of());
        }
        fillMissingCountryFields(all);
        if (progress != null) {
            progress.onMergeComplete(all.size());
        }
        return all;
    }

    // method １: supplement national info (mapping table)
    private void fillMissingCountryFields(List<FrameData> rows) {
        Map<String, String> countryToIsoMap = new HashMap<>();
        Map<String, String> isoToCountryMap = new HashMap<>();
        Set<String> ambiguousCountryKeys = new HashSet<>();
        Set<String> ambiguousIsoKeys = new HashSet<>();
        Map<String, CountryPair> poiToCountryPairMap = new HashMap<>();
        Set<String> ambiguousPoiKeys = new HashSet<>();

        for (FrameData row : rows) {
            String country = normalizeText(row.getAddressCountry());
            String iso3 = normalizeText(row.getAddressIso3CountryCode());
            if (country == null || iso3 == null) {
                continue;
            }
            registerUniqueMapping(countryToIsoMap, ambiguousCountryKeys, country, iso3);
            registerUniqueMapping(isoToCountryMap, ambiguousIsoKeys, iso3, country);

            String poi = normalizeText(row.getClosestPoi());
            if (poi != null) {
                registerUniqueCountryPair(poiToCountryPairMap, ambiguousPoiKeys, poi, country, iso3);
            }
        }

        for (int i = 0; i < rows.size(); i++) {
            FrameData row = rows.get(i);
            String country = normalizeText(row.getAddressCountry());
            String iso3 = normalizeText(row.getAddressIso3CountryCode());

            if (country != null && iso3 == null) {
                String isoFromStandard = getIso3FromStandardCountryMap(country);
                if (isoFromStandard != null) {
                    row.setAddressIso3CountryCode(isoFromStandard);
                    continue;
                }
                String isoFromAdjacent = findFromAdjacentByCountry(rows, i, country);
                if (isoFromAdjacent != null) {
                    row.setAddressIso3CountryCode(isoFromAdjacent);
                }
            } else if (country == null && iso3 != null) {
                String countryFromStandard = getCountryFromStandardIsoMap(iso3);
                if (countryFromStandard != null) {
                    row.setAddressCountry(countryFromStandard);
                    continue;
                }
                String countryFromAdjacent = findFromAdjacentByIso(rows, i, iso3);
                if (countryFromAdjacent != null) {
                    row.setAddressCountry(countryFromAdjacent);
                }
            }
        }

        for (FrameData row : rows) {
            String country = normalizeText(row.getAddressCountry());
            String iso3 = normalizeText(row.getAddressIso3CountryCode());

            if (country != null && iso3 == null) {
                String countryKey = canonicalKey(country);
                if (!ambiguousCountryKeys.contains(countryKey)) {
                    String mappedIso3 = countryToIsoMap.get(countryKey);
                    if (mappedIso3 != null) {
                        row.setAddressIso3CountryCode(mappedIso3);
                    }
                }
            } else if (country == null && iso3 != null) {
                String isoKey = canonicalKey(iso3);
                if (!ambiguousIsoKeys.contains(isoKey)) {
                    String mappedCountry = isoToCountryMap.get(isoKey);
                    if (mappedCountry != null) {
                        row.setAddressCountry(mappedCountry);
                    }
                }
            } else if (country == null) {
                String poi = normalizeText(row.getClosestPoi());
                if (poi == null) {
                    continue;
                }
                String poiKey = canonicalKey(poi);
                if (ambiguousPoiKeys.contains(poiKey)) {
                    continue;
                }
                CountryPair mappedPair = poiToCountryPairMap.get(poiKey);
                if (mappedPair != null) {
                    row.setAddressCountry(mappedPair.country);
                    row.setAddressIso3CountryCode(mappedPair.iso3);
                }
            }
        }
    }

    // method 2: supplement national info　(find from adjacent)
    private String findFromAdjacentByCountry(List<FrameData> rows, int index, String country) {
        String fromPrev = null;
        String fromNext = null;

        if (index > 0) {
            FrameData prev = rows.get(index - 1);
            String prevCountry = normalizeText(prev.getAddressCountry());
            String prevIso = normalizeText(prev.getAddressIso3CountryCode());
            if (prevIso != null && prevCountry != null && prevCountry.equalsIgnoreCase(country)) {
                fromPrev = prevIso;
            }
        }

        if (index + 1 < rows.size()) {
            FrameData next = rows.get(index + 1);
            String nextCountry = normalizeText(next.getAddressCountry());
            String nextIso = normalizeText(next.getAddressIso3CountryCode());
            if (nextIso != null && nextCountry != null && nextCountry.equalsIgnoreCase(country)) {
                fromNext = nextIso;
            }
        }

        if (fromPrev != null && fromNext != null && !fromPrev.equalsIgnoreCase(fromNext)) {
            return null;
        }
        return fromPrev != null ? fromPrev : fromNext;
    }

    private String findFromAdjacentByIso(List<FrameData> rows, int index, String iso3) {
        String fromPrev = null;
        String fromNext = null;

        if (index > 0) {
            FrameData prev = rows.get(index - 1);
            String prevCountry = normalizeText(prev.getAddressCountry());
            String prevIso = normalizeText(prev.getAddressIso3CountryCode());
            if (prevCountry != null && prevIso != null && prevIso.equalsIgnoreCase(iso3)) {
                fromPrev = prevCountry;
            }
        }

        if (index + 1 < rows.size()) {
            FrameData next = rows.get(index + 1);
            String nextCountry = normalizeText(next.getAddressCountry());
            String nextIso = normalizeText(next.getAddressIso3CountryCode());
            if (nextCountry != null && nextIso != null && nextIso.equalsIgnoreCase(iso3)) {
                fromNext = nextCountry;
            }
        }

        if (fromPrev != null && fromNext != null && !fromPrev.equalsIgnoreCase(fromNext)) {
            return null;
        }
        return fromPrev != null ? fromPrev : fromNext;
    }

    private void registerUniqueCountryPair(
            Map<String, CountryPair> map,
            Set<String> ambiguousKeys,
            String rawPoi,
            String country,
            String iso3
    ) {
        String key = canonicalKey(rawPoi);
        if (ambiguousKeys.contains(key)) {
            return;
        }
        CountryPair existing = map.get(key);
        if (existing == null) {
            map.put(key, new CountryPair(country, iso3));
            return;
        }
        if (!existing.country.equalsIgnoreCase(country) || !existing.iso3.equalsIgnoreCase(iso3)) {
            map.remove(key);
            ambiguousKeys.add(key);
        }
    }

    private void registerUniqueMapping(
            Map<String, String> map,
            Set<String> ambiguousKeys,
            String rawKey,
            String value
    ) {
        String key = canonicalKey(rawKey);
        if (ambiguousKeys.contains(key)) {
            return;
        }
        String existing = map.get(key);
        if (existing == null) {
            map.put(key, value);
            return;
        }
        if (!existing.equalsIgnoreCase(value)) {
            map.remove(key);
            ambiguousKeys.add(key);
        }
    }

    private String canonicalKey(String value) {
        return value.trim().toUpperCase();
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("null")
                || trimmed.equals("\\N")
                || trimmed.equals("-")) {
            return null;
        }
        return trimmed;
    }

    private String getIso3FromStandardCountryMap(String country) {
        if (country == null) {
            return null;
        }
        return STANDARD_COUNTRY_TO_ISO3.get(canonicalKey(country));
    }

    private String getCountryFromStandardIsoMap(String iso3) {
        if (iso3 == null) {
            return null;
        }
        return STANDARD_ISO3_TO_COUNTRY.get(canonicalKey(iso3));
    }

    private static void initializeStandardCountryMaps() {
        for (String iso2 : Locale.getISOCountries()) {
            try {
                Locale locale = new Locale("", iso2);
                String iso3 = normalizeStatic(locale.getISO3Country());
                String englishCountry = normalizeStatic(locale.getDisplayCountry(Locale.ENGLISH));
                if (iso3 == null || englishCountry == null) {
                    continue;
                }
                STANDARD_COUNTRY_TO_ISO3.putIfAbsent(canonicalStatic(englishCountry), iso3);
                STANDARD_ISO3_TO_COUNTRY.putIfAbsent(canonicalStatic(iso3), englishCountry);
            } catch (Exception ignored) {
                // Keep startup resilient if any locale entry is malformed.
            }
        }
        // Common aliases in ad data exports.
        STANDARD_COUNTRY_TO_ISO3.put("USA", "USA");
        STANDARD_COUNTRY_TO_ISO3.put("U.S.A.", "USA");
        STANDARD_COUNTRY_TO_ISO3.put("UNITED STATES OF AMERICA", "USA");
        STANDARD_COUNTRY_TO_ISO3.put("UK", "GBR");
        STANDARD_COUNTRY_TO_ISO3.put("U.K.", "GBR");
        STANDARD_COUNTRY_TO_ISO3.put("GREAT BRITAIN", "GBR");
    }

    private static String canonicalStatic(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeStatic(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class CountryPair {
        private final String country;
        private final String iso3;

        private CountryPair(String country, String iso3) {
            this.country = country;
            this.iso3 = iso3;
        }
    }
}
