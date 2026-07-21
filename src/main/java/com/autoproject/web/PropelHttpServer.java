package com.autoproject.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

final class PropelHttpServer {
    private static final Map<String, String> MIME_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml",
            "ico", "image/x-icon"
    );

    private final HttpServer server;
    private final long maxUploadBytes;
    private final boolean allowRemoteImages;
    private final Semaphore exportSlots;

    PropelHttpServer(int port) throws IOException {
        this.maxUploadBytes = longEnvironment("PROPEL_MAX_UPLOAD_BYTES", 250L * 1024 * 1024, 1024, Long.MAX_VALUE);
        this.allowRemoteImages = booleanEnvironment("PROPEL_ALLOW_REMOTE_IMAGES", true);
        int concurrentExports = (int) longEnvironment("PROPEL_MAX_CONCURRENT_EXPORTS", 1, 1, 16);
        this.exportSlots = new Semaphore(concurrentExports);
        this.server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        this.server.createContext("/api/health", this::health);
        this.server.createContext("/api/export", this::export);
        this.server.createContext("/", new StaticHandler());
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    void start() {
        server.start();
    }

    void stop() {
        server.stop((int) Duration.ofSeconds(10).toSeconds());
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        String body = "{\"status\":\"ok\",\"maxUploadBytes\":" + maxUploadBytes
                + ",\"allowRemoteImages\":" + allowRemoteImages + "}";
        sendJson(exchange, 200, body);
    }

    private void export(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        if (!exportSlots.tryAcquire()) {
            exchange.getResponseHeaders().set("Retry-After", "15");
            sendJson(exchange, 429, errorJson("Another workbook is being generated. Please try again shortly."));
            return;
        }

        Path taskRoot = null;
        try {
            long contentLength = parseContentLength(exchange.getRequestHeaders().getFirst("Content-Length"));
            if (contentLength > maxUploadBytes) {
                sendJson(exchange, 413, errorJson("The upload exceeds the server limit."));
                return;
            }
            taskRoot = Files.createTempDirectory("propel-web-");
            MultipartFormData.Form form = MultipartFormData.parse(
                    exchange.getRequestBody(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    maxUploadBytes,
                    taskRoot);
            WebExportService.ExportResult result = new WebExportService(allowRemoteImages).export(form, taskRoot);
            sendWorkbook(exchange, result);
        } catch (MultipartFormData.UploadTooLargeException e) {
            sendJson(exchange, 413, errorJson(e.getMessage()));
        } catch (MultipartFormData.BadRequestException | IllegalArgumentException e) {
            sendJson(exchange, 400, errorJson(rootMessage(e)));
        } catch (Exception e) {
            e.printStackTrace(System.err);
            sendJson(exchange, 500, errorJson("Workbook generation failed: " + rootMessage(e)));
        } finally {
            if (taskRoot != null) {
                deleteTree(taskRoot);
            }
            exportSlots.release();
        }
    }

    private static void sendWorkbook(HttpExchange exchange, WebExportService.ExportResult result) throws IOException {
        long length = Files.size(result.path());
        Headers headers = exchange.getResponseHeaders();
        addSecurityHeaders(headers);
        headers.set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        headers.set("Content-Disposition", contentDisposition(result.downloadName()));
        headers.set("Cache-Control", "no-store");
        headers.set("X-Propel-Merged-Rows", Long.toString(result.mergedRows()));
        headers.set("X-Propel-Filtered-Rows", Long.toString(result.filteredRows()));
        exchange.sendResponseHeaders(200, length);
        try (InputStream in = Files.newInputStream(result.path()); OutputStream out = exchange.getResponseBody()) {
            in.transferTo(out);
        }
    }

    private static String contentDisposition(String fileName) {
        String ascii = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        addSecurityHeaders(headers);
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        sendJson(exchange, 405, errorJson("Method not allowed"));
    }

    private static String errorJson(String message) {
        return "{\"error\":\"" + jsonEscape(message) + "\"}";
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "Unknown error";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }

    private static long parseContentLength(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    System.err.println("Could not remove temporary file " + path + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            System.err.println("Could not clean temporary export directory: " + e.getMessage());
        }
    }

    private static void addSecurityHeaders(Headers headers) {
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    private static long longEnvironment(String name, long fallback, long min, long max) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value < min || value > max) {
                throw new IllegalArgumentException(name + " is outside its allowed range");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private static boolean booleanEnvironment(String name, boolean fallback) {
        String raw = System.getenv(name);
        return raw == null || raw.isBlank() ? fallback : Boolean.parseBoolean(raw.trim());
    }

    private static final class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!("GET".equals(exchange.getRequestMethod()) || "HEAD".equals(exchange.getRequestMethod()))) {
                methodNotAllowed(exchange, "GET, HEAD");
                return;
            }
            String requestPath = exchange.getRequestURI().getPath();
            String asset = "/".equals(requestPath) ? "index.html" : requestPath.substring(1);
            if (asset.contains("..") || asset.contains("\\") || asset.startsWith("api/")) {
                sendJson(exchange, 404, errorJson("Not found"));
                return;
            }
            String resourcePath = "/web/" + asset;
            try (InputStream input = PropelHttpServer.class.getResourceAsStream(resourcePath)) {
                if (input == null) {
                    sendJson(exchange, 404, errorJson("Not found"));
                    return;
                }
                byte[] bytes = input.readAllBytes();
                Headers headers = exchange.getResponseHeaders();
                addSecurityHeaders(headers);
                headers.set("Content-Type", mimeType(asset));
                headers.set("Cache-Control", asset.equals("index.html") ? "no-cache" : "public, max-age=3600");
                headers.set("Content-Security-Policy",
                        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                                + "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
                exchange.sendResponseHeaders(200, "HEAD".equals(exchange.getRequestMethod()) ? -1 : bytes.length);
                if (!"HEAD".equals(exchange.getRequestMethod())) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                } else {
                    exchange.close();
                }
            }
        }

        private static String mimeType(String asset) {
            int dot = asset.lastIndexOf('.');
            String extension = dot < 0 ? "" : asset.substring(dot + 1).toLowerCase(Locale.ROOT);
            String known = MIME_TYPES.get(extension);
            if (known != null) {
                return known;
            }
            String guessed = URLConnection.guessContentTypeFromName(asset);
            return guessed == null ? "application/octet-stream" : guessed;
        }
    }
}
