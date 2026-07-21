package com.autoproject.service.pics;

import com.autoproject.model.FrameData;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private static final int MAX_PICK_COUNT = 3;
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
     * @param fetchFromLinks when false, skips HTTP/local link resolution and uses only the local PICS folder images.
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

        Path localPicsRoot = (localPicsRootPath == null || localPicsRootPath.isBlank())
                ? null
                : Path.of(localPicsRootPath.trim());

        Map<GroupKey, List<FrameData>> framesByGroup = groupFramesByKey(frames);
        int totalGroups = framesByGroup.size();
        prog.onStart(totalGroups);

        Map<FolderTriple, Path> folderIndex =
                localPicsRoot != null && Files.isDirectory(localPicsRoot)
                        ? indexFoldersByTriple(localPicsRoot)
                        : Collections.emptyMap();

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

            List<FrameImageLinkFetcher.PooledImage> pool = new ArrayList<>();
            if (!fromLinks.isEmpty()) {
                pool.addAll(fromLinks);
            }
            // Link-first; if still fewer than {@link #MAX_PICK_COUNT} images, supplement from local folder.
            if (pool.isEmpty() || (fetchFromLinks && pool.size() < MAX_PICK_COUNT)) {
                Path folder = resolveFolderForGroup(key, folderIndex);
                List<Path> imageFiles = folder == null ? Collections.emptyList() : findImageFiles(folder);
                for (Path p : imageFiles) {
                    FrameImageLinkFetcher.pooledFromLocalPathLenient(p).ifPresent(pool::add);
                }
            }

            List<FrameImageLinkFetcher.PooledImage> picked = pickRandomPooled(pool);
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
        for (int i = 0; i <= 7; i++) {
            picsSheet.autoSizeColumn(i);
        }
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
            String formatLine = (pf == null || pf.isBlank()) ? "PRODUCT_FORMAT_NAME: \\N" : "PRODUCT_FORMAT_NAME: " + pf;
            cell.setCellValue(formatLine + "\nLINK: " + img.sourceLink().trim());
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
            anchor.setRow1(rowIndex);
            anchor.setRow2(rowIndex + 1);
            anchor.setCol1(columnIndex);
            anchor.setCol2(columnIndex + 1);
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
