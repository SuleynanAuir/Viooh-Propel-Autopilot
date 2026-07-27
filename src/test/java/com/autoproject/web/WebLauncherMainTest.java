package com.autoproject.web;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebLauncherMainTest {

    @Test
    void skipsAnOccupiedPortAndServesTheWebAppOnTheNextCandidate() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            WebLauncherMain.StartedServer started =
                    WebLauncherMain.startFirstAvailable(List.of(occupied.getLocalPort(), 0));
            try {
                assertNotEquals(occupied.getLocalPort(), started.port());
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + started.port() + "/api/health"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> response = HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("\"status\":\"ok\""));
            } finally {
                started.server().stop();
            }
        }
    }

    @Test
    void desktopLauncherAcceptsSessionOnlyFeishuCredentials() throws Exception {
        String previousDesktopMode = System.getProperty("propel.desktopMode");
        String previousAccessToken = System.getProperty("propel.feishu.accessToken");
        String previousAppId = System.getProperty("propel.feishu.appId");
        String previousAppSecret = System.getProperty("propel.feishu.appSecret");
        WebLauncherMain.StartedServer started = null;
        try {
            System.setProperty("propel.desktopMode", "true");
            System.clearProperty("propel.feishu.accessToken");
            System.clearProperty("propel.feishu.appId");
            System.clearProperty("propel.feishu.appSecret");
            started = WebLauncherMain.startFirstAvailable(List.of(0));

            URI authUri = URI.create("http://127.0.0.1:" + started.port() + "/api/feishu-auth");
            HttpRequest rejectedRequest = HttpRequest.newBuilder()
                    .uri(authUri)
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("accessToken=not-accepted"))
                    .build();
            HttpResponse<String> rejected = HttpClient.newHttpClient()
                    .send(rejectedRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(403, rejected.statusCode());

            HttpRequest authRequest = HttpRequest.newBuilder()
                    .uri(authUri)
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-Propel-Desktop", "1")
                    .POST(HttpRequest.BodyPublishers.ofString("accessToken=desktop-test-token"))
                    .build();
            HttpResponse<String> authenticated = HttpClient.newHttpClient()
                    .send(authRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, authenticated.statusCode());
            assertTrue(authenticated.body().contains("\"feishuAuthConfigured\":true"));

            HttpRequest healthRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + started.port() + "/api/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> health = HttpClient.newHttpClient()
                    .send(healthRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"desktopMode\":true"));
            assertTrue(health.body().contains("\"feishuAuthConfigured\":true"));
        } finally {
            if (started != null) {
                started.server().stop();
            }
            restoreProperty("propel.desktopMode", previousDesktopMode);
            restoreProperty("propel.feishu.accessToken", previousAccessToken);
            restoreProperty("propel.feishu.appId", previousAppId);
            restoreProperty("propel.feishu.appSecret", previousAppSecret);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
