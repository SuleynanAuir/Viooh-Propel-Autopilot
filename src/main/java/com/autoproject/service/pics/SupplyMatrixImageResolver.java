package com.autoproject.service.pics;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Matches Proposal image requests against feishu/supply_matrix.xlsx and materializes matching images. */
final class SupplyMatrixImageResolver {
    private static final int HEADER_SCAN_ROWS = 20;
    private static final int MAX_LINKS_PER_REQUEST = 8;
    private static final int MAX_IMAGE_CANDIDATES_PER_PAGE = 16;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\\\"]+");
    private static final Pattern HTML_IMAGE_PATTERN = Pattern.compile(
            "(?i)(?:property|name)=[\\\"'](?:og:image|twitter:image)[\\\"'][^>]*content=[\\\"']([^\\\"']+)[\\\"']"
                    + "|<img[^>]+(?:src|data-src)=[\\\"']([^\\\"']+)[\\\"']");
    private static final Map<String, String> COUNTRY_ALIASES = buildCountryAliases();

    private final List<Entry> entries;
    private final Map<ExactKey, List<Entry>> exactIndex = new LinkedHashMap<>();
    private final Map<CountryVenueKey, List<Entry>> countryVenueIndex = new LinkedHashMap<>();
    private final Map<String, List<Entry>> countryIndex = new LinkedHashMap<>();
    private final Map<String, List<FrameImageLinkFetcher.PooledImage>> downloadCache = new LinkedHashMap<>();
    private final Map<String, String> downloadErrors = new LinkedHashMap<>();

    private SupplyMatrixImageResolver(List<Entry> entries) {
        this.entries = entries;
        indexEntries();
    }

    static SupplyMatrixImageResolver load(Path matrixPath) {
        if (matrixPath == null || !Files.isRegularFile(matrixPath)) {
            return new SupplyMatrixImageResolver(List.of());
        }
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (InputStream in = Files.newInputStream(matrixPath); Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            Header header = findHeader(sheet, formatter);
            if (header == null) {
                return new SupplyMatrixImageResolver(List.of());
            }
            List<Entry> loaded = new ArrayList<>();
            for (int r = header.rowIndex() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String country = formatter.formatCellValue(row.getCell(header.countryCol())).trim();
                String market = formatter.formatCellValue(row.getCell(header.marketCol())).trim();
                String venueType = formatter.formatCellValue(row.getCell(header.venueTypeCol())).trim();
                List<String> links = links(row.getCell(header.imageLinkCol()), formatter);
                if (VenueTypeParser.isBlank(country) || VenueTypeParser.isBlank(venueType) || links.isEmpty()) {
                    continue;
                }
                for (String link : links) {
                    loaded.add(new Entry(country, market, venueType, link, r + 1));
                }
            }
            return new SupplyMatrixImageResolver(loaded);
        } catch (Exception ignored) {
            return new SupplyMatrixImageResolver(List.of());
        }
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    int entryCount() {
        return entries.size();
    }

    List<FrameImageLinkFetcher.PooledImage> resolveImages(
            ProposalImageRequest request,
            HttpClient httpClient,
            ImageResourceManager resources,
            int maxImages) {
        if (request == null || httpClient == null || resources == null || maxImages <= 0) {
            return List.of();
        }
        List<Entry> matches = findMatches(request);
        if (matches.isEmpty()) {
            resources.recordMissing(request);
            return List.of();
        }
        LinkedHashSet<String> links = new LinkedHashSet<>();
        for (Entry match : matches) {
            links.add(match.imageLink());
            if (links.size() >= MAX_LINKS_PER_REQUEST) {
                break;
            }
        }
        List<FrameImageLinkFetcher.PooledImage> result = new ArrayList<>();
        for (String link : links) {
            List<FrameImageLinkFetcher.PooledImage> downloaded = downloadCache.computeIfAbsent(
                    link, value -> downloadFromResourceLink(value, httpClient));
            if (downloaded.isEmpty()) {
                resources.recordFailure(link, downloadErrors.getOrDefault(link, "No supported image was found at the resource URL"));
                continue;
            }
            for (FrameImageLinkFetcher.PooledImage image : downloaded) {
                try {
                    ImageResourceManager.SavedImage saved = resources.save(
                            request, link, image.data(), image.pictureType());
                    result.add(new FrameImageLinkFetcher.PooledImage(
                            image.data(), image.pictureType(), null, link, "meta/images/" + saved.filename()));
                } catch (Exception e) {
                    resources.recordFailure(link, "Could not save image: " + e.getMessage());
                }
                if (result.size() >= maxImages) {
                    return result;
                }
            }
        }
        if (result.isEmpty()) {
            resources.recordMissing(request);
        }
        return result;
    }

    private List<Entry> findMatches(ProposalImageRequest request) {
        String country = normalizeCountry(request.country());
        String market = VenueTypeParser.normalize(request.market());
        Set<String> venues = VenueTypeParser.normalizedKeys(request.venueType());
        if (country.isEmpty() || venues.isEmpty()) {
            return List.of();
        }
        List<Entry> found = lookupExact(country, market, venues);
        if (!found.isEmpty()) {
            return found;
        }
        found = lookupCountryVenue(country, venues);
        if (!found.isEmpty()) {
            return found;
        }
        return countryIndex.getOrDefault(country, List.of());
    }

    private List<Entry> lookupExact(String country, String market, Set<String> venues) {
        LinkedHashSet<Entry> out = new LinkedHashSet<>();
        for (String venue : venues) {
            out.addAll(exactIndex.getOrDefault(new ExactKey(country, market, venue), List.of()));
        }
        return new ArrayList<>(out);
    }

    private List<Entry> lookupCountryVenue(String country, Set<String> venues) {
        LinkedHashSet<Entry> out = new LinkedHashSet<>();
        for (String venue : venues) {
            out.addAll(countryVenueIndex.getOrDefault(new CountryVenueKey(country, venue), List.of()));
        }
        return new ArrayList<>(out);
    }

    private void indexEntries() {
        for (Entry entry : entries) {
            String country = normalizeCountry(entry.country());
            String market = VenueTypeParser.normalize(entry.market());
            if (country.isEmpty()) {
                continue;
            }
            countryIndex.computeIfAbsent(country, ignored -> new ArrayList<>()).add(entry);
            for (String venue : VenueTypeParser.normalizedKeys(entry.venueType())) {
                exactIndex.computeIfAbsent(new ExactKey(country, market, venue), ignored -> new ArrayList<>()).add(entry);
                countryVenueIndex.computeIfAbsent(
                        new CountryVenueKey(country, venue), ignored -> new ArrayList<>()).add(entry);
            }
        }
    }

    private List<FrameImageLinkFetcher.PooledImage> downloadFromResourceLink(String link, HttpClient httpClient) {
        List<String> errors = new ArrayList<>();
        for (String directCandidate : directDownloadCandidates(link)) {
            FrameImageLinkFetcher.FetchResult fetched = FrameImageLinkFetcher.fetchOneDetailed(directCandidate, httpClient);
            if (fetched.image().isPresent()) {
                return List.of(fetched.image().get());
            }
            errors.add(directCandidate + ": " + fetched.error());
        }

        List<FrameImageLinkFetcher.PooledImage> pageImages = new ArrayList<>();
        for (String candidate : pageImageCandidates(link, httpClient)) {
            FrameImageLinkFetcher.FetchResult fetched = FrameImageLinkFetcher.fetchOneDetailed(candidate, httpClient);
            if (fetched.image().isPresent()) {
                pageImages.add(fetched.image().get());
                if (pageImages.size() >= PicsSheetWriter.MAX_PICK_COUNT) {
                    break;
                }
            } else {
                errors.add(candidate + ": " + fetched.error());
            }
        }
        if (pageImages.isEmpty()) {
            downloadErrors.put(link, String.join(" | ", errors));
        }
        return pageImages;
    }

    private static List<String> directDownloadCandidates(String link) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher driveFile = Pattern.compile("https?://drive\\.google\\.com/file/d/([^/?#]+)", Pattern.CASE_INSENSITIVE)
                .matcher(link);
        if (driveFile.find()) {
            out.add("https://drive.google.com/uc?export=download&id=" + driveFile.group(1));
        }
        Matcher openId = Pattern.compile("[?&]id=([^&#]+)", Pattern.CASE_INSENSITIVE).matcher(link);
        if (link.toLowerCase(Locale.ROOT).contains("drive.google.com") && openId.find()) {
            out.add("https://drive.google.com/uc?export=download&id=" + openId.group(1));
        }
        out.add(link);
        return new ArrayList<>(out);
    }

    private static List<String> pageImageCandidates(String link, HttpClient httpClient) {
        String html = HttpTextFetcher.fetchText(link, httpClient);
        if (html == null || html.isBlank()) {
            return List.of();
        }
        URI base;
        try {
            base = URI.create(link);
        } catch (Exception e) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher matcher = HTML_IMAGE_PATTERN.matcher(html.replace("&amp;", "&"));
        while (matcher.find()) {
            String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (raw == null || raw.isBlank() || raw.startsWith("data:")) {
                continue;
            }
            try {
                out.add(raw.startsWith("//") ? base.getScheme() + ":" + raw : base.resolve(raw).toString());
            } catch (Exception ignored) {
                // continue with the remaining page candidates
            }
            if (out.size() >= MAX_IMAGE_CANDIDATES_PER_PAGE) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    private static Header findHeader(Sheet sheet, DataFormatter formatter) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), HEADER_SCAN_ROWS); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (Cell cell : row) {
                String key = VenueTypeParser.normalize(formatter.formatCellValue(cell));
                if (!key.isEmpty()) {
                    columns.putIfAbsent(key, cell.getColumnIndex());
                }
            }
            Integer country = firstColumn(columns, "country", "countrycode", "addressiso3countrycode");
            Integer market = firstColumn(columns, "market", "mediaowner");
            Integer venue = firstColumn(columns, "venuetype", "venuetaxonomyvalue");
            // Prefer the curated Feishu column; Pictures remains a backwards-compatible fallback.
            Integer link = firstColumn(columns, "飞书图片link", "feishutupianlink", "pictures", "picturelink1");
            if (country != null && market != null && venue != null && link != null) {
                return new Header(r, country, market, venue, link);
            }
        }
        return null;
    }

    private static Integer firstColumn(Map<String, Integer> columns, String... aliases) {
        for (String alias : aliases) {
            Integer column = columns.get(VenueTypeParser.normalize(alias));
            if (column != null) {
                return column;
            }
        }
        return null;
    }

    private static List<String> links(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Hyperlink hyperlink = cell.getHyperlink();
        if (hyperlink != null && hyperlink.getAddress() != null && !hyperlink.getAddress().isBlank()) {
            out.add(hyperlink.getAddress().trim());
        }
        Matcher matcher = URL_PATTERN.matcher(formatter.formatCellValue(cell));
        while (matcher.find()) {
            out.add(matcher.group().replaceAll("[),.;]+$", ""));
        }
        return new ArrayList<>(out);
    }

    private static String normalizeCountry(String raw) {
        String normalized = VenueTypeParser.normalize(raw);
        return COUNTRY_ALIASES.getOrDefault(normalized, normalized);
    }

    private static Map<String, String> buildCountryAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String alpha2 : Locale.getISOCountries()) {
            Locale locale = Locale.of("", alpha2);
            try {
                String iso3 = locale.getISO3Country().toLowerCase(Locale.ROOT);
                aliases.put(VenueTypeParser.normalize(alpha2), iso3);
                aliases.put(VenueTypeParser.normalize(iso3), iso3);
                aliases.put(VenueTypeParser.normalize(locale.getDisplayCountry(Locale.ENGLISH)), iso3);
            } catch (Exception ignored) {
                // skip incomplete locale records
            }
        }
        aliases.put("uk", "gbr");
        aliases.put("usa", "usa");
        aliases.put("unitedstates", "usa");
        return aliases;
    }

    private record Header(int rowIndex, int countryCol, int marketCol, int venueTypeCol, int imageLinkCol) {
    }

    private record Entry(String country, String market, String venueType, String imageLink, int matrixRow) {
    }

    private record ExactKey(String country, String market, String venue) {
    }

    private record CountryVenueKey(String country, String venue) {
    }
}
