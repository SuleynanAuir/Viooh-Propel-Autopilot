package com.autoproject.service.pics;

import com.autoproject.service.summary.ProposalSummaryRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
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

/**
 * Resolves PICS images from {@code feishu/supply_matrix.xlsx}.
 *
 * <p>The matrix is matched by the cartesian key requested by the web workflow:
 * Proposal Country + Proposal MARKET + each dotted / tokenized Venue type word. The matching matrix columns are
 * Country Code + Media Owner + tokenized Venue Type, and images come from the Pictures hyperlink column.</p>
 */
final class SupplyMatrixImageResolver {
    private static final int HEADER_SCAN_ROWS = 20;
    private static final int MAX_LINKS_PER_ROW = 8;
    private static final int MAX_IMAGE_CANDIDATES_PER_PAGE = 12;
    private static final Pattern HTML_IMAGE_PATTERN = Pattern.compile(
            "(?i)(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]*content=[\"']([^\"']+)[\"']"
                    + "|<img[^>]+src=[\"']([^\"']+)[\"']");

    private final List<Entry> entries;
    private final Map<LookupKey, List<Entry>> byKey;
    private final Map<String, List<FrameImageLinkFetcher.PooledImage>> imageCache = new LinkedHashMap<>();

    private SupplyMatrixImageResolver(List<Entry> entries) {
        this.entries = entries;
        this.byKey = index(entries);
    }

    static SupplyMatrixImageResolver load(Path matrixPath) {
        if (matrixPath == null || !Files.isRegularFile(matrixPath)) {
            return new SupplyMatrixImageResolver(List.of());
        }
        try (InputStream in = Files.newInputStream(matrixPath); Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            Header header = findHeader(sheet);
            if (header == null) {
                return new SupplyMatrixImageResolver(List.of());
            }
            List<Entry> out = new ArrayList<>();
            for (int r = header.rowIndex() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String mediaOwner = text(row.getCell(header.mediaOwnerCol()));
                String countryCode = text(row.getCell(header.countryCodeCol()));
                String venueType = text(row.getCell(header.venueTypeCol()));
                Cell pictureCell = row.getCell(header.picturesCol());
                String pictureLink = hyperlinkAddress(pictureCell);
                if (isBlank(pictureLink)) {
                    pictureLink = firstUrl(text(pictureCell));
                }
                if (isBlank(mediaOwner) || isBlank(countryCode) || isBlank(venueType) || isBlank(pictureLink)) {
                    continue;
                }
                out.add(new Entry(mediaOwner.trim(), countryCode.trim(), venueType.trim(), pictureLink.trim()));
            }
            return new SupplyMatrixImageResolver(out);
        } catch (Exception ignored) {
            return new SupplyMatrixImageResolver(List.of());
        }
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    List<FrameImageLinkFetcher.PooledImage> resolveImages(
            ProposalSummaryRow row,
            HttpClient httpClient,
            Path metaDir,
            int maxImages) {
        if (row == null || httpClient == null || metaDir == null || maxImages <= 0 || byKey.isEmpty()) {
            return List.of();
        }
        Set<String> countries = tokens(row.getAddressIso3CountryCode());
        Set<String> markets = Set.of(norm(row.getMarket()));
        Set<String> venueTokens = venueTokens(row.getVenueTaxonomyValue());
        if (countries.isEmpty() || markets.isEmpty() || markets.contains("") || venueTokens.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> links = new LinkedHashSet<>();
        for (String country : countries) {
            for (String market : markets) {
                for (String venueToken : venueTokens) {
                    List<Entry> found = byKey.get(new LookupKey(country, market, venueToken));
                    if (found == null) {
                        continue;
                    }
                    for (Entry entry : found) {
                        links.add(entry.pictureLink());
                        if (links.size() >= MAX_LINKS_PER_ROW) {
                            break;
                        }
                    }
                    if (links.size() >= MAX_LINKS_PER_ROW) {
                        break;
                    }
                }
            }
        }
        if (links.isEmpty()) {
            return List.of();
        }
        List<FrameImageLinkFetcher.PooledImage> images = new ArrayList<>();
        for (String link : links) {
            List<FrameImageLinkFetcher.PooledImage> downloaded =
                    imageCache.computeIfAbsent(link, l -> downloadFromMatrixLink(l, httpClient, metaDir));
            for (FrameImageLinkFetcher.PooledImage image : downloaded) {
                images.add(image.withLinkSource(link, "supply_matrix Pictures"));
                if (images.size() >= maxImages) {
                    return images;
                }
            }
        }
        return images;
    }

    private List<FrameImageLinkFetcher.PooledImage> downloadFromMatrixLink(String link, HttpClient httpClient, Path metaDir) {
        List<FrameImageLinkFetcher.PooledImage> out = new ArrayList<>();
        try {
            Files.createDirectories(metaDir);
        } catch (IOException ignored) {
            return List.of();
        }

        for (String directCandidate : directDownloadCandidates(link)) {
            Optional<FrameImageLinkFetcher.PooledImage> direct = FrameImageLinkFetcher.fetchOne(directCandidate, httpClient);
            if (direct.isPresent()) {
                savedCopy(direct.get(), directCandidate, metaDir, out);
                return out;
            }
        }

        for (String candidate : pageImageCandidates(link, httpClient)) {
            Optional<FrameImageLinkFetcher.PooledImage> image = FrameImageLinkFetcher.fetchOne(candidate, httpClient);
            if (image.isEmpty()) {
                continue;
            }
            savedCopy(image.get(), candidate, metaDir, out);
            if (out.size() >= PicsSheetWriter.MAX_PICK_COUNT) {
                break;
            }
        }
        return out;
    }

    private static List<String> directDownloadCandidates(String link) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(link);
        Matcher driveFile = Pattern.compile("https?://drive\\.google\\.com/file/d/([^/?#]+)", Pattern.CASE_INSENSITIVE)
                .matcher(link);
        if (driveFile.find()) {
            out.add("https://drive.google.com/uc?export=download&id=" + driveFile.group(1));
        }
        Matcher openId = Pattern.compile("[?&]id=([^&#]+)", Pattern.CASE_INSENSITIVE).matcher(link);
        if (link.toLowerCase(Locale.ROOT).contains("drive.google.com") && openId.find()) {
            out.add("https://drive.google.com/uc?export=download&id=" + openId.group(1));
        }
        return new ArrayList<>(out);
    }

    private static void savedCopy(
            FrameImageLinkFetcher.PooledImage image,
            String sourceLink,
            Path metaDir,
            List<FrameImageLinkFetcher.PooledImage> out) {
        byte[] data = image.data();
        if (data == null || data.length == 0) {
            return;
        }
        String extension = image.pictureType() == org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG ? ".png" : ".jpg";
        String base = Integer.toHexString(sourceLink.hashCode()) + "-" + out.size() + extension;
        Path target = metaDir.resolve(base);
        try {
            Files.write(target, data);
            out.add(new FrameImageLinkFetcher.PooledImage(data, image.pictureType(), null, sourceLink, "meta/" + base));
        } catch (IOException ignored) {
            // skip unsaved image
        }
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
        Matcher matcher = HTML_IMAGE_PATTERN.matcher(html);
        while (matcher.find()) {
            String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (isBlank(raw)) {
                continue;
            }
            String resolved = resolveUrl(base, raw.trim());
            if (!isBlank(resolved)) {
                out.add(resolved);
            }
            if (out.size() >= MAX_IMAGE_CANDIDATES_PER_PAGE) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    private static String resolveUrl(URI base, String raw) {
        try {
            if (raw.startsWith("//")) {
                return base.getScheme() + ":" + raw;
            }
            return base.resolve(raw).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<LookupKey, List<Entry>> index(List<Entry> entries) {
        Map<LookupKey, List<Entry>> out = new LinkedHashMap<>();
        for (Entry entry : entries) {
            String country = norm(entry.countryCode());
            String market = norm(entry.mediaOwner());
            for (String venueToken : venueTokens(entry.venueType())) {
                out.computeIfAbsent(new LookupKey(country, market, venueToken), ignored -> new ArrayList<>()).add(entry);
            }
        }
        return out;
    }

    private static Header findHeader(Sheet sheet) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), HEADER_SCAN_ROWS); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Map<String, Integer> cols = new LinkedHashMap<>();
            for (Cell cell : row) {
                String value = norm(text(cell));
                if (!value.isEmpty()) {
                    cols.put(value, cell.getColumnIndex());
                }
            }
            Integer mediaOwner = cols.get("mediaowner");
            Integer countryCode = cols.get("countrycode");
            Integer venueType = cols.get("venuetype");
            Integer pictures = cols.get("pictures");
            if (mediaOwner != null && countryCode != null && venueType != null && pictures != null) {
                return new Header(r, mediaOwner, countryCode, venueType, pictures);
            }
        }
        return null;
    }

    private static String hyperlinkAddress(Cell cell) {
        if (cell == null) {
            return null;
        }
        Hyperlink hyperlink = cell.getHyperlink();
        return hyperlink == null ? null : hyperlink.getAddress();
    }

    private static String firstUrl(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("https?://\\S+").matcher(raw);
        return matcher.find() ? matcher.group() : null;
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> null;
        };
    }

    private static Set<String> venueTokens(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (isBlank(raw)) {
            return out;
        }
        for (String dotPart : raw.split("\\.")) {
            String normalizedPart = norm(dotPart);
            if (!normalizedPart.isEmpty()) {
                out.add(normalizedPart);
            }
            out.addAll(tokens(dotPart));
        }
        out.addAll(tokens(raw));
        return out;
    }

    private static Set<String> tokens(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (isBlank(raw)) {
            return out;
        }
        for (String part : raw.split("[^\\p{Alnum}]+")) {
            String normalized = norm(part);
            if (!normalized.isEmpty()) {
                out.add(normalized);
            }
        }
        return out;
    }

    private static String norm(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{Alnum}]+", "");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank() || value.trim().equalsIgnoreCase("null") || value.trim().equals("\\N");
    }

    private record Header(int rowIndex, int mediaOwnerCol, int countryCodeCol, int venueTypeCol, int picturesCol) {
    }

    private record Entry(String mediaOwner, String countryCode, String venueType, String pictureLink) {
    }

    private record LookupKey(String country, String market, String venueToken) {
    }
}
