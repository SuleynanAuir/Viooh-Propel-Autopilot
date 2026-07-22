package com.autoproject.service.pics;

import com.autoproject.model.FrameData;
import com.autoproject.service.summary.ProposalSummaryRow;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.IOUtils;
import org.apache.poi.util.Units;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class PicsSheetWriter {
    private static final String NULL_SENTINEL = "\\N";
    private static final String FOLDER_SEPARATOR = "\u2014";
    private static final int MIN_PICK_COUNT = 2;
    static final int MAX_PICK_COUNT = 3;
    /** Default max frames per PICS group whose {@code FRAMEIMAGEPATH} links are tried (non-billboard venue). */
    private static final int MAX_FRAMES_PER_GROUP_FOR_LINKS_DEFAULT = 20;
    /** Max frames for venue taxonomy in the {@code billboard.*} class (dotted or underscore form). */
    private static final int MAX_FRAMES_PER_GROUP_FOR_LINKS_BILLBOARD = 40;
    private final Random random = new Random();
    private CellStyle picMetaTextStyle;

    public void write(Sheet picsSheet, List<FrameData> frames, String localPicsRootPath) {
        write(picsSheet, frames, localPicsRootPath, true, null);
    }

    /**
     * @param fetchFromLinks when false, skips FRAMEIMAGEPATH resolution.
     * @param progress       optional; notified per group (may be called from a background thread).
     */
    public void write(
            Sheet picsSheet,
            List<FrameData> frames,
            String localPicsRootPath,
            boolean fetchFromLinks,
            PicsLinkProgress progress) {
        PicsLinkProgress prog = progress == null ? PicsLinkProgress.noop() : progress;
        picMetaTextStyle = null;
        writeHeader(picsSheet);
        if (frames == null || frames.isEmpty()) {
            prog.onStart(0);
            Row row = picsSheet.createRow(1);
            row.createCell(0).setCellValue("No frames. Skip PICS insertion.");
            finishColumnSizing(picsSheet);
            return;
        }

        Map<GroupKey, List<FrameData>> framesByGroup = groupFramesByKey(frames);
        int totalGroups = framesByGroup.size();
        prog.onStart(totalGroups);

        HttpClient httpClient = fetchFromLinks ? FrameImageLinkFetcher.newClient() : null;
        int rowNum = 1;
        int completed = 0;
        for (Map.Entry<GroupKey, List<FrameData>> entry : framesByGroup.entrySet()) {
            GroupKey key = entry.getKey();
            List<FrameImageLinkFetcher.PooledImage> fromLinks = List.of();
            if (fetchFromLinks && httpClient != null) {
                List<FrameImageLinkFetcher.FrameDataRef> refs = entry.getValue().stream()
                        .map(f -> new FrameImageLinkFetcher.FrameDataRef(
                                normalizeText(f.getFrameImagePath()),
                                normalizeText(f.getProductFormatName())))
                        .toList();
                int maxFramesForLinks = maxFramesForLinkFetchByVenue(key.venueType);
                fromLinks = FrameImageLinkFetcher.fetchFromFrameLinks(
                        refs, maxFramesForLinks, httpClient, prog);
            }

            List<FrameImageLinkFetcher.PooledImage> picked = pickRandomPooled(fromLinks);
            int imageRowIndex = rowNum;
            Row row = picsSheet.createRow(imageRowIndex);
            row.setHeightInPoints(130f);
            row.createCell(0).setCellValue(key.mediaOwner);
            row.createCell(1).setCellValue(key.countryDisplay);
            row.createCell(2).setCellValue(key.venueType);
            row.createCell(3).setCellValue(picked.size());

            for (int i = 0; i < picked.size(); i++) {
                insertPooledImage(picsSheet, imageRowIndex, 4 + i, picked.get(i));
            }
            if (picked.stream().anyMatch(FrameImageLinkFetcher.PooledImage::fromLinkField)) {
                writeLinkMetaRow(picsSheet, imageRowIndex + 1, picked);
                rowNum = imageRowIndex + 2;
            } else {
                rowNum = imageRowIndex + 1;
            }
            completed++;
            prog.onGroupDone(completed, totalGroups, key.venueType);
        }

        finishColumnSizing(picsSheet);
    }

    /**
     * Writes PICS rows from Proposal dimensions and resolves images from {@code feishu/supply_matrix.xlsx}.
     *
     * <p>Each Proposal row contributes Country + MARKET + dotted Venue type tokens. Images are downloaded to
     * {@code metaDir} before being inserted into this sheet.</p>
     */
    public void writeFromProposalRows(
            Sheet picsSheet,
            List<ProposalSummaryRow> proposalRows,
            Path supplyMatrixPath,
            Path metaDir,
            boolean fetchFromLinks,
            PicsLinkProgress progress) {
        PicsLinkProgress prog = progress == null ? PicsLinkProgress.noop() : progress;
        picMetaTextStyle = null;
        writeHeader(picsSheet);
        if (proposalRows == null || proposalRows.isEmpty()) {
            prog.onStart(0);
            Row row = picsSheet.createRow(1);
            row.createCell(0).setCellValue("No Proposal rows. Skip PICS insertion.");
            finishColumnSizing(picsSheet);
            return;
        }

        List<ProposalImageRequest> requests = ProposalImageRequestParser.parse(proposalRows);
        prog.onStart(requests.size());
        SupplyMatrixImageResolver resolver = fetchFromLinks
                ? SupplyMatrixImageResolver.load(supplyMatrixPath)
                : SupplyMatrixImageResolver.load(null);
        HttpClient httpClient = fetchFromLinks && !resolver.isEmpty() ? FrameImageLinkFetcher.newClient() : null;
        ImageResourceManager resources = new ImageResourceManager(metaDir);
        try {
            resources.initialize();
        } catch (IOException ignored) {
            // PICS still renders placeholders when the sidecar directory is not writable.
        }

        int rowNum = 1;
        int completed = 0;
        for (ProposalImageRequest request : requests) {
            List<FrameImageLinkFetcher.PooledImage> resolved = httpClient == null
                    ? List.of()
                    : resolver.resolveImages(request, httpClient, resources, MAX_PICK_COUNT);
            if (httpClient == null) {
                resources.recordMissing(request);
            }
            List<FrameImageLinkFetcher.PooledImage> picked = resolved.isEmpty()
                    ? List.of(createPlaceholder(request))
                    : new ArrayList<>(resolved.subList(0, Math.min(MAX_PICK_COUNT, resolved.size())));
            int imageRowIndex = rowNum;
            Row row = picsSheet.createRow(imageRowIndex);
            row.setHeightInPoints(130f);
            row.createCell(0).setCellValue(normalizeOrNullSentinel(request.market()));
            row.createCell(1).setCellValue(normalizeOrNullSentinel(request.country()));
            row.createCell(2).setCellValue(normalizeOrNullSentinel(request.venueType()));
            row.createCell(3).setCellValue(resolved.size());

            for (int i = 0; i < picked.size(); i++) {
                insertPooledImage(picsSheet, imageRowIndex, 4 + i, picked.get(i));
            }
            if (picked.stream().anyMatch(FrameImageLinkFetcher.PooledImage::fromLinkField)) {
                writeLinkMetaRow(picsSheet, imageRowIndex + 1, picked);
                rowNum = imageRowIndex + 2;
            } else {
                rowNum = imageRowIndex + 1;
            }
            completed++;
            prog.onGroupDone(completed, requests.size(), request.venueType());
        }

        resources.finish();
        finishColumnSizing(picsSheet);
    }

    /**
     * Billboard venue class ({@code billboard.*} or {@code billboard_*} folder-style) tries more frames for link images.
     */
    static int maxFramesForLinkFetchByVenue(String venueTaxonomyValue) {
        if (venueTaxonomyValue == null || venueTaxonomyValue.isBlank()) {
            return MAX_FRAMES_PER_GROUP_FOR_LINKS_DEFAULT;
        }
        String v = venueTaxonomyValue.trim().toLowerCase(Locale.ROOT);
        if (v.equals("billboard")
                || v.startsWith("billboard.")
                || v.startsWith("billboard_")) {
            return MAX_FRAMES_PER_GROUP_FOR_LINKS_BILLBOARD;
        }
        return MAX_FRAMES_PER_GROUP_FOR_LINKS_DEFAULT;
    }

    /** Number of PICS sheet groups (Market + Country + Venue taxonomy) for the given frames. */
    public int countPicsGroups(List<FrameData> frames) {
        if (frames == null || frames.isEmpty()) {
            return 0;
        }
        return groupFramesByKey(frames).size();
    }

    private void finishColumnSizing(Sheet picsSheet) {
        // Fixed widths are deterministic in both desktop and headless container exports.
        picsSheet.setColumnWidth(0, 24 * 256);
        picsSheet.setColumnWidth(1, 16 * 256);
        picsSheet.setColumnWidth(2, 28 * 256);
        picsSheet.setColumnWidth(3, 18 * 256);
        picsSheet.setColumnWidth(4, 24 * 256);
        picsSheet.setColumnWidth(5, 24 * 256);
        picsSheet.setColumnWidth(6, 24 * 256);
    }

    /**
     * Groups frames by PICS row key (market, country display, venue taxonomy). Order follows first occurrence in {@code frames}.
     */
    Map<GroupKey, List<FrameData>> groupFramesByKey(List<FrameData> frames) {
        Map<GroupKey, List<FrameData>> result = new LinkedHashMap<>();
        for (FrameData frame : frames) {
            GroupKey groupKey = toGroupKey(frame);
            if (groupKey == null) {
                continue;
            }
            result.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(frame);
        }
        return result;
    }

    private GroupKey toGroupKey(FrameData frame) {
        String mediaOwner = normalizeText(frame.getMarket());
        if (mediaOwner == null) {
            mediaOwner = normalizeText(frame.getSsp());
        }
        String iso3 = normalizeText(frame.getAddressIso3CountryCode());
        String countryName = normalizeText(frame.getAddressCountry());
        String venueType = normalizeText(frame.getVenueTaxonomyValue());
        if (mediaOwner == null || venueType == null) {
            return null;
        }
        String countryDisplay = countryName != null ? countryName : iso3;
        if (countryDisplay == null) {
            return null;
        }
        List<String> countryFolderTokens = countryFolderMatchTokens(iso3, countryName);
        if (countryFolderTokens.isEmpty()) {
            return null;
        }
        return new GroupKey(mediaOwner, countryDisplay, venueType, countryFolderTokens);
    }

    /**
     * Lists on-disk image paths for each PICS group (folder fallback). Used by tests; production {@link #write} prefers
     * {@code FRAMEIMAGEPATH} links first when any resolve.
     */
    Map<GroupKey, List<Path>> collectImagesByGroup(List<FrameData> frames, Path localPicsRoot) {
        Map<GroupKey, List<Path>> result = new LinkedHashMap<>();
        Set<GroupKey> uniqueGroups = new LinkedHashSet<>();
        for (FrameData frame : frames) {
            GroupKey groupKey = toGroupKey(frame);
            if (groupKey == null) {
                continue;
            }
            uniqueGroups.add(groupKey);
        }

        Map<FolderTriple, Path> folderIndex = indexFoldersByTriple(localPicsRoot);
        for (GroupKey groupKey : uniqueGroups) {
            Path folder = resolveFolderForGroup(groupKey, folderIndex);
            List<Path> imageFiles = folder == null ? Collections.emptyList() : findImageFiles(folder);
            if (!imageFiles.isEmpty()) {
                result.put(groupKey, imageFiles);
            } else {
                result.putIfAbsent(groupKey, Collections.emptyList());
            }
        }
        return result;
    }

    /**
     * Indexes direct child folders whose name is three segments separated by em dash ({@link #FOLDER_SEPARATOR}),
     * or by ASCII hyphen as fallback. Keys are case-insensitive-normalized (trim, lower case).
     */
    private Map<FolderTriple, Path> indexFoldersByTriple(Path localPicsRoot) {
        Map<FolderTriple, Path> index = new LinkedHashMap<>();
        if (localPicsRoot == null || !Files.isDirectory(localPicsRoot)) {
            return index;
        }
        try (Stream<Path> stream = Files.list(localPicsRoot)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                String[] parts = splitFolderName(dir.getFileName().toString());
                if (parts == null) {
                    return;
                }
                FolderTriple key = new FolderTriple(normMatch(parts[0]), normMatch(parts[1]), normMatch(parts[2]));
                index.putIfAbsent(key, dir);
            });
        } catch (IOException ignored) {
            // empty index
        }
        return index;
    }

    private Path resolveFolderForGroup(GroupKey groupKey, Map<FolderTriple, Path> folderIndex) {
        String mo = normMatch(groupKey.mediaOwner);
        List<String> venueCandidates = venueMatchCandidates(groupKey.venueType);
        venueCandidates.sort(Comparator.comparingInt(String::length).reversed());
        for (String v : venueCandidates) {
            for (String co : groupKey.countryFolderMatchTokens) {
                Path found = folderIndex.get(new FolderTriple(mo, co, v));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Normalized second-segment tokens to try against disk folders (ISO3 and/or full country name). */
    private static List<String> countryFolderMatchTokens(String iso3, String countryName) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (iso3 != null) {
            set.add(normMatch(iso3));
        }
        if (countryName != null) {
            set.add(normMatch(countryName));
        }
        return new ArrayList<>(set);
    }

    /**
     * Normalized venue tokens to match disk folders that use short names (e.g. {@code Train_Stations})
     * when CSV has a dotted taxonomy (e.g. {@code transit.train_stations.platform}).
     */
    static List<String> venueMatchCandidates(String venueTaxonomy) {
        String trimmed = venueTaxonomy.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(normMatch(trimmed.replace('.', '_')));
        String[] dotParts = trimmed.split("\\.");
        for (int start = 0; start < dotParts.length; start++) {
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < dotParts.length; i++) {
                if (dotParts[i].isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('_');
                }
                sb.append(dotParts[i]);
            }
            if (sb.length() > 0) {
                set.add(normMatch(sb.toString()));
            }
        }
        for (String p : dotParts) {
            if (!p.isEmpty()) {
                set.add(normMatch(p));
            }
        }
        return new ArrayList<>(set);
    }

    private static String normMatch(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static final Pattern HYPHEN_SPLIT = Pattern.compile("-");

    /** @return three segments, or null if the name is not a valid MO—Country—Venue folder */
    static String[] splitFolderName(String folderBaseName) {
        if (folderBaseName == null || folderBaseName.isEmpty()) {
            return null;
        }
        String[] em = folderBaseName.split(Pattern.quote(FOLDER_SEPARATOR), -1);
        if (em.length == 3 && segmentOk(em[0]) && segmentOk(em[1]) && segmentOk(em[2])) {
            return em;
        }
        String[] hy = HYPHEN_SPLIT.split(folderBaseName, -1);
        if (hy.length == 3 && segmentOk(hy[0]) && segmentOk(hy[1]) && segmentOk(hy[2])) {
            return hy;
        }
        return null;
    }

    private static boolean segmentOk(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private record FolderTriple(String mediaOwner, String country, String venue) {}

    List<Path> pickRandomImages(List<Path> images) {
        if (images == null || images.isEmpty()) {
            return Collections.emptyList();
        }
        List<Path> shuffled = new ArrayList<>(images);
        Collections.shuffle(shuffled, random);
        int targetCount;
        if (shuffled.size() >= MAX_PICK_COUNT) {
            targetCount = MIN_PICK_COUNT + random.nextInt(MAX_PICK_COUNT - MIN_PICK_COUNT + 1);
        } else {
            targetCount = shuffled.size();
        }
        return shuffled.subList(0, targetCount);
    }

    private List<FrameImageLinkFetcher.PooledImage> pickRandomPooled(List<FrameImageLinkFetcher.PooledImage> images) {
        if (images == null || images.isEmpty()) {
            return Collections.emptyList();
        }
        List<FrameImageLinkFetcher.PooledImage> shuffled = new ArrayList<>(images);
        Collections.shuffle(shuffled, random);
        int targetCount;
        if (shuffled.size() >= MAX_PICK_COUNT) {
            targetCount = MIN_PICK_COUNT + random.nextInt(MAX_PICK_COUNT - MIN_PICK_COUNT + 1);
        } else {
            targetCount = shuffled.size();
        }
        return new ArrayList<>(shuffled.subList(0, targetCount));
    }

    /** One row directly under image columns: {@code PRODUCT_FORMAT_NAME} and link for each image sourced from links. */
    private void writeLinkMetaRow(Sheet sheet, int metaRowIndex, List<FrameImageLinkFetcher.PooledImage> picked) {
        Row metaRow = sheet.createRow(metaRowIndex);
        metaRow.setHeightInPoints(78f);
        CellStyle style = getOrCreatePicMetaStyle(sheet);
        for (int i = 0; i < picked.size(); i++) {
            FrameImageLinkFetcher.PooledImage img = picked.get(i);
            var cell = metaRow.createCell(4 + i);
            cell.setCellStyle(style);
            if (!img.fromLinkField()) {
                continue;
            }
            String pf = img.productFormatName();
            String sourceLine = (pf == null || pf.isBlank()) ? "SOURCE: \\N" : "SOURCE: " + pf;
            cell.setCellValue(sourceLine + "\nLINK: " + img.sourceLink().trim());
        }
    }

    private CellStyle getOrCreatePicMetaStyle(Sheet sheet) {
        if (picMetaTextStyle == null) {
            CellStyle st = sheet.getWorkbook().createCellStyle();
            st.setWrapText(true);
            st.setVerticalAlignment(VerticalAlignment.TOP);
            picMetaTextStyle = st;
        }
        return picMetaTextStyle;
    }

    private void insertPooledImage(Sheet sheet, int rowIndex, int columnIndex, FrameImageLinkFetcher.PooledImage pooled) {
        byte[] bytes = pooled.data();
        if (bytes == null || bytes.length == 0) {
            return;
        }
        int pictureType = pooled.pictureType();
        if (pictureType < 0) {
            return;
        }
        try {
            Workbook workbook = sheet.getWorkbook();
            int pictureIndex = workbook.addPicture(bytes, pictureType);
            Drawing<?> drawing = sheet.createDrawingPatriarch();
            ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
            int[] dimensions = imageDimensions(bytes);
            int imageWidth = dimensions[0];
            int imageHeight = dimensions[1];
            double cellWidth = Math.max(1d, sheet.getColumnWidthInPixels(columnIndex));
            Row targetRow = sheet.getRow(rowIndex);
            double cellHeight = Math.max(1d,
                    (targetRow == null ? sheet.getDefaultRowHeightInPoints() : targetRow.getHeightInPoints()) * 96d / 72d);
            double padding = 6d;
            double scale = Math.min(
                    Math.max(1d, cellWidth - 2d * padding) / imageWidth,
                    Math.max(1d, cellHeight - 2d * padding) / imageHeight);
            double renderedWidth = imageWidth * scale;
            double renderedHeight = imageHeight * scale;
            int left = (int) Math.round((cellWidth - renderedWidth) / 2d);
            int top = (int) Math.round((cellHeight - renderedHeight) / 2d);
            anchor.setRow1(rowIndex);
            anchor.setRow2(rowIndex);
            anchor.setCol1(columnIndex);
            anchor.setCol2(columnIndex);
            anchor.setDx1(Units.pixelToEMU(Math.max(0, left)));
            anchor.setDy1(Units.pixelToEMU(Math.max(0, top)));
            anchor.setDx2(Units.pixelToEMU(Math.max(1, left + (int) Math.round(renderedWidth))));
            anchor.setDy2(Units.pixelToEMU(Math.max(1, top + (int) Math.round(renderedHeight))));
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
            drawing.createPicture(anchor, pictureIndex);
        } catch (Exception ignored) {
            // skip unreadable / invalid image payloads
        } finally {
            Path tmp = pooled.tempFileToDelete();
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    private static int[] imageDimensions(byte[] bytes) {
        if (bytes != null && bytes.length >= 24
                && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return new int[]{Math.max(1, readInt(bytes, 16)), Math.max(1, readInt(bytes, 20))};
        }
        if (bytes != null && bytes.length > 4
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            int offset = 2;
            while (offset + 8 < bytes.length) {
                if ((bytes[offset] & 0xFF) != 0xFF) {
                    offset++;
                    continue;
                }
                int marker = bytes[offset + 1] & 0xFF;
                if (marker == 0xD8 || marker == 0xD9) {
                    offset += 2;
                    continue;
                }
                int length = ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
                if (length < 2 || offset + 2 + length > bytes.length) {
                    break;
                }
                if ((marker >= 0xC0 && marker <= 0xC3)
                        || (marker >= 0xC5 && marker <= 0xC7)
                        || (marker >= 0xC9 && marker <= 0xCB)
                        || (marker >= 0xCD && marker <= 0xCF)) {
                    int height = ((bytes[offset + 5] & 0xFF) << 8) | (bytes[offset + 6] & 0xFF);
                    int width = ((bytes[offset + 7] & 0xFF) << 8) | (bytes[offset + 8] & 0xFF);
                    return new int[]{Math.max(1, width), Math.max(1, height)};
                }
                offset += 2 + length;
            }
        }
        return new int[]{1, 1};
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private FrameImageLinkFetcher.PooledImage createPlaceholder(ProposalImageRequest request) {
        // Static PNG avoids depending on desktop font/graphics services in headless container exports.
        byte[] bytes = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nAAAAABJRU5ErkJggg==");
        return new FrameImageLinkFetcher.PooledImage(
                bytes, Workbook.PICTURE_TYPE_PNG, null, null, "PLACEHOLDER");
    }

    private void writeHeader(Sheet sheet) {
        Row row = sheet.createRow(0);
        row.createCell(0).setCellValue("Market");
        row.createCell(1).setCellValue("Country");
        row.createCell(2).setCellValue("VenueType");
        row.createCell(3).setCellValue("PickedImageCount");
        row.createCell(4).setCellValue("Image1");
        row.createCell(5).setCellValue("Image2");
        row.createCell(6).setCellValue("Image3");
    }

    private void insertImage(Sheet sheet, int rowIndex, int columnIndex, Path imagePath) {
        try (InputStream in = Files.newInputStream(imagePath)) {
            byte[] bytes = IOUtils.toByteArray(in);
            int pictureType = detectPictureType(imagePath);
            if (pictureType < 0) {
                return;
            }
            Workbook workbook = sheet.getWorkbook();
            int pictureIndex = workbook.addPicture(bytes, pictureType);
            Drawing<?> drawing = sheet.createDrawingPatriarch();
            ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setRow1(rowIndex);
            anchor.setRow2(rowIndex + 1);
            anchor.setCol1(columnIndex);
            anchor.setCol2(columnIndex + 1);
            drawing.createPicture(anchor, pictureIndex);
        } catch (IOException ignored) {
            // skip unreadable images
        }
    }

    private int detectPictureType(Path imagePath) {
        String name = imagePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return Workbook.PICTURE_TYPE_PNG;
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return Workbook.PICTURE_TYPE_JPEG;
        }
        return -1;
    }

    private List<Path> findImageFiles(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) {
            return Collections.emptyList();
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedImage)
                    .toList();
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private boolean isSupportedImage(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || fileName.endsWith(".png");
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("null")
                || trimmed.equals(NULL_SENTINEL)
                || trimmed.equals("-")) {
            return null;
        }
        return trimmed;
    }

    private String normalizeOrNullSentinel(String value) {
        String normalized = normalizeText(value);
        return normalized == null ? NULL_SENTINEL : normalized;
    }

    static final class GroupKey {
        private final String mediaOwner;
        /** Shown in PICS "Country" column: full name when available, otherwise ISO3. */
        private final String countryDisplay;
        private final String venueType;
        /** Second folder segment may be ISO3 (GBR) or full name; try all that exist on the frame. */
        private final List<String> countryFolderMatchTokens;

        GroupKey(String mediaOwner, String countryDisplay, String venueType, List<String> countryFolderMatchTokens) {
            this.mediaOwner = mediaOwner;
            this.countryDisplay = countryDisplay;
            this.venueType = venueType;
            this.countryFolderMatchTokens = List.copyOf(countryFolderMatchTokens);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof GroupKey that)) {
                return false;
            }
            return mediaOwner.equals(that.mediaOwner)
                    && countryDisplay.equals(that.countryDisplay)
                    && venueType.equals(that.venueType);
        }

        @Override
        public int hashCode() {
            int result = mediaOwner.hashCode();
            result = 31 * result + countryDisplay.hashCode();
            result = 31 * result + venueType.hashCode();
            return result;
        }
    }
}
