package com.autoproject.service.pics;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fetches frame preview images from {@code FRAMEIMAGEPATH} URLs (http/https).
 */
final class FrameImageLinkFetcher {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_BODY_BYTES = 12 * 1024 * 1024;
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[;|\\n]+");

    private FrameImageLinkFetcher() {
    }

    static List<String> parseImageLinks(String raw) {
        if (raw == null) {
            return List.of();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("null")
                || trimmed.equals("\\N")
                || trimmed.equals("-")) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String segment : SPLIT_PATTERN.split(trimmed)) {
            String s = segment.trim();
            if (!s.isEmpty()) {
                parts.add(s);
            }
        }
        if (parts.size() <= 1 && !trimmed.contains(";") && !trimmed.contains("|") && !trimmed.contains("\n")) {
            return List.of(trimmed);
        }
        return parts;
    }

    /**
     * Walks up to {@code maxFrames} frames in order, parses each {@link com.autoproject.model.FrameData#getFrameImagePath()},
     * and returns pooled images (deduped by normalized link string). Stops after {@code maxFrames} frames examined
     * (not after N successful downloads). If {@code progress} reports cancelled, stops early without affecting later groups.
     */
    static List<PooledImage> fetchFromFrameLinks(
            List<FrameDataRef> frames,
            int maxFrames,
            HttpClient httpClient,
            PicsLinkProgress progress) {
        PicsLinkProgress prog = progress == null ? PicsLinkProgress.noop() : progress;
        if (frames == null || frames.isEmpty() || maxFrames <= 0) {
            return List.of();
        }
        Set<String> seenUrls = new LinkedHashSet<>();
        List<PooledImage> out = new ArrayList<>();
        int examined = 0;
        outer:
        for (FrameDataRef fr : frames) {
            if (prog.isCancelled()) {
                break;
            }
            if (examined >= maxFrames) {
                break;
            }
            examined++;
            String pathField = fr.frameImagePath();
            if (pathField == null) {
                continue;
            }
            for (String link : parseImageLinks(pathField)) {
                if (prog.isCancelled()) {
                    break outer;
                }
                String norm = normLinkKey(link);
                if (norm.isEmpty() || !seenUrls.add(norm)) {
                    continue;
                }
                fetchOne(link, httpClient)
                        .map(pi -> pi.withLinkSource(link, fr.productFormatName()))
                        .ifPresent(out::add);
            }
        }
        return out;
    }

    private static String normLinkKey(String link) {
        return link.trim().toLowerCase(Locale.ROOT);
    }

    static Optional<PooledImage> fetchOne(String link, HttpClient httpClient) {
        return fetchOneDetailed(link, httpClient).image();
    }

    static FetchResult fetchOneDetailed(String link, HttpClient httpClient) {
        if (link == null) {
            return FetchResult.failure("URL is null");
        }
        String t = link.trim();
        if (t.isEmpty()) {
            return FetchResult.failure("URL is blank");
        }
        if (t.regionMatches(true, 0, "http://", 0, 7) || t.regionMatches(true, 0, "https://", 0, 8)) {
            return fetchHttp(t, httpClient);
        }
        return FetchResult.failure("Only HTTP(S) image URLs are supported");
    }

    private static Optional<PooledImage> readLocalFileToPooled(URI fileUri) {
        try {
            Path p = Path.of(fileUri);
            if (!Files.isRegularFile(p)) {
                return Optional.empty();
            }
            return readPathToPooled(p);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    static Optional<PooledImage> pooledFromLocalPath(Path p) {
        return readPathToPooled(p);
    }

    /** Same as on-disk folder behaviour before link support: trust extension only (tests may use stub bytes). */
    static Optional<PooledImage> pooledFromLocalPathLenient(Path p) {
        try {
            int type = pictureTypeForPath(p);
            if (type < 0) {
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(p);
            return Optional.of(new PooledImage(bytes, type, null, null, null));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<PooledImage> readPathToPooled(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            int type = pictureTypeForPath(p);
            if (type < 0 || !looksLikeImage(bytes)) {
                return Optional.empty();
            }
            return Optional.of(new PooledImage(bytes, type, null, null, null));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static FetchResult fetchHttp(String url, HttpClient client) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("User-Agent", "AutoProject/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                return FetchResult.failure("HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            try (InputStream in = response.body()) {
                byte[] bytes = readCapped(in, MAX_BODY_BYTES);
                if (bytes == null) {
                    return FetchResult.failure("Image exceeds " + MAX_BODY_BYTES + " bytes");
                }
                return decodeImageBytes(bytes, contentType, url);
            }
        } catch (Exception e) {
            String message = e.getMessage();
            return FetchResult.failure(e.getClass().getSimpleName() + (message == null ? "" : ": " + message));
        }
    }

    static FetchResult decodeImageBytes(byte[] bytes, String contentType, String sourceHint) {
        if (looksLikeWebp(bytes)) {
            byte[] png = convertWebpToPng(bytes);
            if (png == null) {
                return FetchResult.failure("WebP decoder could not convert the image to PNG");
            }
            return FetchResult.success(new PooledImage(
                    png, org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG, null, null, null));
        }
        if (!looksLikeImage(bytes)) {
            return FetchResult.failure("Response is not a supported JPG, JPEG, PNG, or WebP image");
        }
        int type = pictureTypeFromContentTypeOrPath(contentType == null ? "" : contentType, sourceHint);
        if (type < 0) {
            type = pictureTypeFromMagic(bytes);
        }
        if (type < 0) {
            return FetchResult.failure("Image format could not be detected");
        }
        return FetchResult.success(new PooledImage(bytes, type, null, null, null));
    }

    private static byte[] convertWebpToPng(byte[] webp) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(webp));
            if (image == null) {
                return null;
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            return ImageIO.write(image, "png", png) ? png.toByteArray() : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static int pictureTypeFromContentTypeOrPath(String contentType, String url) {
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("png")) {
            return org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG;
        }
        if (ct.contains("jpeg") || ct.contains("jpg")) {
            return org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_JPEG;
        }
        try {
            String path = URI.create(url).getPath();
            if (path != null && !path.isEmpty()) {
                int fromUrl = pictureTypeForPath(Path.of(path).getFileName());
                if (fromUrl >= 0) {
                    return fromUrl;
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return -1;
    }

    private static int pictureTypeForPath(Path imagePath) {
        if (imagePath == null || imagePath.getFileName() == null) {
            return -1;
        }
        String name = imagePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG;
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_JPEG;
        }
        return -1;
    }

    private static int pictureTypeFromMagic(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_JPEG;
        }
        if (bytes.length >= 8
                && bytes[0] == (byte) 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47) {
            return org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG;
        }
        return -1;
    }

    private static boolean looksLikeImage(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            return false;
        }
        return pictureTypeFromMagic(bytes) >= 0;
    }

    private static boolean looksLikeWebp(byte[] bytes) {
        return bytes != null
                && bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    /** @return bytes read, or {@code null} if body exceeds {@code maxBytes} */
    private static byte[] readCapped(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(maxBytes, 65_536));
        byte[] chunk = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) >= 0) {
            if (total + n > maxBytes) {
                return null;
            }
            total += n;
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Lightweight projection so this helper stays decoupled from {@code FrameData}. */
    record FrameDataRef(String frameImagePath, String productFormatName) {
    }

    /** In-memory image bytes for PICS; link fields set when image came from {@code FRAMEIMAGEPATH} resolution. */
    record PooledImage(
            byte[] data,
            int pictureType,
            Path tempFileToDelete,
            String sourceLink,
            String productFormatName) {

        PooledImage withLinkSource(String link, String productFormatName) {
            return new PooledImage(data, pictureType, tempFileToDelete, link, productFormatName);
        }

        /** True when this image was obtained from a parsed link / URL field (not local folder scan). */
        boolean fromLinkField() {
            return sourceLink != null && !sourceLink.isBlank();
        }
    }

    record FetchResult(Optional<PooledImage> image, String error) {
        static FetchResult success(PooledImage image) {
            return new FetchResult(Optional.of(image), null);
        }

        static FetchResult failure(String error) {
            return new FetchResult(Optional.empty(), error);
        }
    }
}
