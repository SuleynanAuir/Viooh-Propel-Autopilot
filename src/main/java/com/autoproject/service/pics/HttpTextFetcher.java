package com.autoproject.service.pics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class HttpTextFetcher {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;

    private HttpTextFetcher() {
    }

    static String fetchText(String url, HttpClient client) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "AutoProject/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase().contains("html") && !contentType.toLowerCase().contains("text")) {
                return null;
            }
            try (InputStream in = response.body()) {
                byte[] bytes = readCapped(in);
                return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) >= 0) {
            if (total + n > MAX_TEXT_BYTES) {
                return null;
            }
            total += n;
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
