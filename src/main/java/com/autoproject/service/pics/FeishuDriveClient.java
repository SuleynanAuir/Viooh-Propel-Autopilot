package com.autoproject.service.pics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads image files from authenticated Feishu Drive folders through the official Drive API. */
public final class FeishuDriveClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(25);
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES_PER_FOLDER = 20;
    private static final Pattern FOLDER_LINK = Pattern.compile(
            "https?://[^/]*feishu\\.cn/drive/folder/([^/?#]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_FILE = Pattern.compile("(?i).+\\.(?:jpe?g|png|webp)$");
    private static final Object TOKEN_LOCK = new Object();
    private static volatile CachedToken cachedTenantToken;

    private FeishuDriveClient() {
    }

    /** Safe for health endpoints: reports only whether credentials exist, never their values. */
    public static boolean isAuthenticationConfigured() {
        Config config = Config.load();
        return !config.accessToken().isBlank()
                || (!config.appId().isBlank() && !config.appSecret().isBlank());
    }

    static boolean isFeishuFolderLink(String link) {
        return link != null && FOLDER_LINK.matcher(link.trim()).find();
    }

    static FolderFetchResult fetchFolderImages(String folderLink, HttpClient client, int maxImages) {
        if (client == null || maxImages <= 0) {
            return FolderFetchResult.failure("Feishu image download was not initialized");
        }
        Matcher matcher = FOLDER_LINK.matcher(folderLink == null ? "" : folderLink.trim());
        if (!matcher.find()) {
            return FolderFetchResult.failure("The resource is not a Feishu Drive folder URL");
        }
        Config config = Config.load();
        String token;
        try {
            token = accessToken(config, client);
        } catch (Exception e) {
            return FolderFetchResult.failure(message("Could not obtain a Feishu access token", e));
        }
        if (token.isBlank()) {
            return FolderFetchResult.failure(
                    "Feishu authentication is not configured. Set PROPEL_FEISHU_ACCESS_TOKEN, or both "
                            + "PROPEL_FEISHU_APP_ID and PROPEL_FEISHU_APP_SECRET");
        }

        List<FrameImageLinkFetcher.PooledImage> images = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Deque<FolderCursor> pending = new ArrayDeque<>();
        pending.add(new FolderCursor(matcher.group(1), 0));
        while (!pending.isEmpty() && images.size() < maxImages) {
            FolderCursor folder = pending.removeFirst();
            listFolder(folderLink, folder, config, token, client, pending, images, errors, maxImages);
        }
        if (images.isEmpty()) {
            String error = errors.isEmpty()
                    ? "No JPG, JPEG, PNG, or WebP files were found in the Feishu folder"
                    : String.join(" | ", errors);
            return FolderFetchResult.failure(error);
        }
        return new FolderFetchResult(images, errors.isEmpty() ? null : String.join(" | ", errors));
    }

    private static void listFolder(
            String sourceLink,
            FolderCursor folder,
            Config config,
            String accessToken,
            HttpClient client,
            Deque<FolderCursor> pending,
            List<FrameImageLinkFetcher.PooledImage> images,
            List<String> errors,
            int maxImages) {
        String pageToken = "";
        for (int page = 0; page < MAX_PAGES_PER_FOLDER && images.size() < maxImages; page++) {
            URI uri = URI.create(config.apiBaseUrl() + "/drive/v1/files?folder_token="
                    + encode(folder.token()) + "&page_size=" + PAGE_SIZE
                    + (pageToken.isBlank() ? "" : "&page_token=" + encode(pageToken)));
            ApiResponse response = sendJson(uri, accessToken, client);
            if (!response.success()) {
                errors.add("List folder " + folder.token() + ": " + response.error());
                return;
            }
            Map<String, Object> root;
            try {
                root = object(new JsonParser(response.body()).parse());
            } catch (RuntimeException e) {
                errors.add(message("Invalid Feishu folder response", e));
                return;
            }
            int code = integer(root.get("code"), -1);
            if (code != 0) {
                errors.add("Feishu list API " + code + ": " + string(root.get("msg")));
                return;
            }
            Map<String, Object> data = object(root.get("data"));
            for (Object rawFile : array(data.get("files"))) {
                Map<String, Object> file = object(rawFile);
                String fileToken = string(file.get("token"));
                String name = string(file.get("name"));
                String type = string(file.get("type")).toLowerCase(Locale.ROOT);
                if (fileToken.isBlank()) {
                    continue;
                }
                if (type.equals("folder")) {
                    if (folder.depth() < config.maxFolderDepth()) {
                        pending.addLast(new FolderCursor(fileToken, folder.depth() + 1));
                    }
                    continue;
                }
                if (!type.equals("file") || !IMAGE_FILE.matcher(name).matches()) {
                    continue;
                }
                FrameImageLinkFetcher.FetchResult downloaded = downloadFile(
                        sourceLink, fileToken, name, config, accessToken, client);
                if (downloaded.image().isPresent()) {
                    images.add(downloaded.image().get().withLinkSource(sourceLink, name));
                } else {
                    errors.add(name + ": " + downloaded.error());
                }
                if (images.size() >= maxImages) {
                    return;
                }
            }
            boolean hasMore = bool(data.get("has_more"));
            pageToken = string(data.get("next_page_token"));
            if (!hasMore || pageToken.isBlank()) {
                return;
            }
        }
    }

    private static FrameImageLinkFetcher.FetchResult downloadFile(
            String sourceLink,
            String fileToken,
            String name,
            Config config,
            String accessToken,
            HttpClient client) {
        try {
            URI uri = URI.create(config.apiBaseUrl() + "/drive/v1/files/" + encode(fileToken) + "/download");
            HttpRequest request = authorizedRequest(uri, accessToken).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                closeQuietly(response.body());
                return FrameImageLinkFetcher.FetchResult.failure("Feishu download HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            try (InputStream in = response.body()) {
                byte[] bytes = readCapped(in, MAX_FILE_BYTES);
                if (bytes == null) {
                    return FrameImageLinkFetcher.FetchResult.failure(
                            "Image exceeds " + MAX_FILE_BYTES + " bytes");
                }
                return FrameImageLinkFetcher.decodeImageBytes(bytes, contentType, name + " " + sourceLink);
            }
        } catch (Exception e) {
            return FrameImageLinkFetcher.FetchResult.failure(message("Feishu image download failed", e));
        }
    }

    private static ApiResponse sendJson(URI uri, String accessToken, HttpClient client) {
        try {
            HttpRequest request = authorizedRequest(uri, accessToken).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] bytes = readCapped(in, 2 * 1024 * 1024);
                String body = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
                if (response.statusCode() / 100 != 2) {
                    return ApiResponse.failure("HTTP " + response.statusCode() + responseMessage(body));
                }
                return ApiResponse.success(body);
            }
        } catch (Exception e) {
            return ApiResponse.failure(message("Request failed", e));
        }
    }

    private static HttpRequest.Builder authorizedRequest(URI uri, String accessToken) {
        return HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json, image/*, application/octet-stream")
                .header("User-Agent", "viooh-propel-autopilot/1.0");
    }

    private static String accessToken(Config config, HttpClient client) throws Exception {
        if (!config.accessToken().isBlank()) {
            return stripBearer(config.accessToken());
        }
        if (config.appId().isBlank() || config.appSecret().isBlank()) {
            return "";
        }
        String credentialKey = config.apiBaseUrl() + "\0" + config.appId() + "\0" + config.appSecret();
        CachedToken current = cachedTenantToken;
        if (current != null && current.credentialKey().equals(credentialKey)
                && current.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return current.value();
        }
        synchronized (TOKEN_LOCK) {
            current = cachedTenantToken;
            if (current != null && current.credentialKey().equals(credentialKey)
                    && current.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
                return current.value();
            }
            URI uri = URI.create(config.apiBaseUrl() + "/auth/v3/tenant_access_token/internal");
            String body = "{\"app_id\":\"" + jsonEscape(config.appId())
                    + "\",\"app_secret\":\"" + jsonEscape(config.appSecret()) + "\"}";
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Feishu token HTTP " + response.statusCode());
            }
            Map<String, Object> root = object(new JsonParser(response.body()).parse());
            int code = integer(root.get("code"), -1);
            if (code != 0) {
                throw new IOException("Feishu token API " + code + ": " + string(root.get("msg")));
            }
            String value = string(root.get("tenant_access_token"));
            if (value.isBlank()) {
                throw new IOException("Feishu token response did not contain tenant_access_token");
            }
            int expires = Math.max(300, integer(root.get("expire"), 7200));
            cachedTenantToken = new CachedToken(value, Instant.now().plusSeconds(expires), credentialKey);
            return value;
        }
    }

    private static String stripBearer(String value) {
        String trimmed = value.trim();
        return trimmed.regionMatches(true, 0, "Bearer ", 0, 7) ? trimmed.substring(7).trim() : trimmed;
    }

    private static byte[] readCapped(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 65_536));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (total + read > maxBytes) {
                return null;
            }
            total += read;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (Exception ignored) {
            // nothing to do
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String responseMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> root = object(new JsonParser(body).parse());
            String msg = string(root.get("msg"));
            return msg.isBlank() ? "" : ": " + msg;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String message(String prefix, Throwable error) {
        String detail = error == null ? "" : error.getMessage();
        return prefix + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private static String string(Object value) {
        return value instanceof String text ? text : value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean flag && flag;
    }

    record FolderFetchResult(List<FrameImageLinkFetcher.PooledImage> images, String error) {
        FolderFetchResult {
            images = images == null ? List.of() : List.copyOf(images);
        }

        static FolderFetchResult failure(String error) {
            return new FolderFetchResult(List.of(), error);
        }
    }

    private record Config(
            String accessToken,
            String appId,
            String appSecret,
            String apiBaseUrl,
            int maxFolderDepth) {
        static Config load() {
            return new Config(
                    configured("propel.feishu.accessToken", "PROPEL_FEISHU_ACCESS_TOKEN", ""),
                    configured("propel.feishu.appId", "PROPEL_FEISHU_APP_ID", ""),
                    configured("propel.feishu.appSecret", "PROPEL_FEISHU_APP_SECRET", ""),
                    normalizeBaseUrl(configured(
                            "propel.feishu.apiBaseUrl", "PROPEL_FEISHU_API_BASE_URL",
                            "https://open.feishu.cn/open-apis")),
                    configuredInteger("propel.feishu.maxFolderDepth", "PROPEL_FEISHU_MAX_FOLDER_DEPTH", 2, 0, 8));
        }

        private static String normalizeBaseUrl(String value) {
            String trimmed = value.trim();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("PROPEL_FEISHU_API_BASE_URL must use HTTP or HTTPS");
            }
            return trimmed;
        }
    }

    private static String configured(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int configuredInteger(
            String property, String environment, int fallback, int min, int max) {
        String raw = configured(property, environment, "");
        if (raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < min || value > max) {
                throw new IllegalArgumentException(environment + " must be between " + min + " and " + max);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(environment + " must be an integer", e);
        }
    }

    private record CachedToken(String value, Instant expiresAt, String credentialKey) {
    }

    private record FolderCursor(String token, int depth) {
    }

    private record ApiResponse(boolean success, String body, String error) {
        static ApiResponse success(String body) {
            return new ApiResponse(true, body, null);
        }

        static ApiResponse failure(String error) {
            return new ApiResponse(false, null, error);
        }
    }

    /** Minimal dependency-free JSON reader for Feishu API responses. */
    private static final class JsonParser {
        private final String text;
        private int position;

        private JsonParser(String text) {
            this.text = text == null ? "" : text;
        }

        Object parse() {
            skipWhitespace();
            Object value = value();
            skipWhitespace();
            if (position != text.length()) {
                throw error("Unexpected trailing JSON content");
            }
            return value;
        }

        private Object value() {
            skipWhitespace();
            if (position >= text.length()) {
                throw error("Unexpected end of JSON");
            }
            return switch (text.charAt(position)) {
                case '{' -> objectValue();
                case '[' -> arrayValue();
                case '"' -> stringValue();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> numberValue();
            };
        }

        private Map<String, Object> objectValue() {
            expect('{');
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = stringValue();
                skipWhitespace();
                expect(':');
                result.put(key, value());
                skipWhitespace();
                if (consume('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> arrayValue() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return result;
            }
            while (true) {
                result.add(value());
                skipWhitespace();
                if (consume(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String stringValue() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') {
                    return result.toString();
                }
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                if (position >= text.length()) {
                    throw error("Unterminated JSON escape");
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(unicodeEscape());
                    default -> throw error("Invalid JSON escape");
                }
            }
            throw error("Unterminated JSON string");
        }

        private char unicodeEscape() {
            if (position + 4 > text.length()) {
                throw error("Invalid JSON unicode escape");
            }
            String hex = text.substring(position, position + 4);
            position += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw error("Invalid JSON unicode escape");
            }
        }

        private Object numberValue() {
            int start = position;
            if (consume('-')) {
                // optional sign
            }
            digits();
            boolean decimal = false;
            if (consume('.')) {
                decimal = true;
                digits();
            }
            if (consume('e') || consume('E')) {
                decimal = true;
                consume('+');
                consume('-');
                digits();
            }
            if (start == position) {
                throw error("Expected JSON value");
            }
            String value = text.substring(start, position);
            try {
                return decimal ? Double.parseDouble(value) : Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw error("Invalid JSON number");
            }
        }

        private void digits() {
            int start = position;
            while (position < text.length() && Character.isDigit(text.charAt(position))) {
                position++;
            }
            if (start == position) {
                throw error("Expected JSON number digit");
            }
        }

        private Object literal(String expected, Object value) {
            if (!text.startsWith(expected, position)) {
                throw error("Invalid JSON literal");
            }
            position += expected.length();
            return value;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (!consume(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private boolean consume(char expected) {
            if (position < text.length() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + position);
        }
    }
}
