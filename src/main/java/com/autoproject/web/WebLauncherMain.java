package com.autoproject.web;

import javax.swing.JOptionPane;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.BindException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * One-click desktop launcher for the local Propel web application.
 *
 * <p>The packaged launcher starts the same Java backend used by {@link WebMain}, opens the user's default browser,
 * and keeps the server alive until the launcher process is closed. When the default port is occupied it
 * automatically tries the next local port.</p>
 */
public final class WebLauncherMain {
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8080;
    private static final int LAST_FALLBACK_PORT = 8099;

    private WebLauncherMain() {
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            showLaunchError(rootMessage(error));
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        // Enables the loopback-only credentials endpoint used by the packaged desktop launcher.
        // Public/container WebMain never sets this flag.
        System.setProperty("propel.desktopMode", "true");
        List<Integer> candidatePorts = configuredCandidatePorts();
        StartedServer started = startFirstAvailable(candidatePorts);
        URI webUri = URI.create("http://" + LOOPBACK_HOST + ":" + started.port() + "/");
        Runtime.getRuntime().addShutdownHook(
                new Thread(started.server()::stop, "propel-web-launcher-shutdown"));
        try {
            openBrowser(webUri);
            System.out.println("Propel Web opened at " + webUri);
            new CountDownLatch(1).await();
        } catch (Throwable error) {
            started.server().stop();
            throw error;
        }
    }

    static StartedServer startFirstAvailable(List<Integer> candidatePorts) throws Exception {
        if (candidatePorts == null || candidatePorts.isEmpty()) {
            throw new IllegalArgumentException("At least one local port candidate is required");
        }
        BindException lastBindError = null;
        for (int port : candidatePorts) {
            try {
                PropelHttpServer server = new PropelHttpServer(LOOPBACK_HOST, port);
                server.start();
                return new StartedServer(server, server.port());
            } catch (BindException error) {
                lastBindError = error;
            }
        }
        BindException unavailable = new BindException(
                "No available local port was found. Close an existing Propel Web process and try again.");
        if (lastBindError != null) {
            unavailable.initCause(lastBindError);
        }
        throw unavailable;
    }

    private static List<Integer> configuredCandidatePorts() {
        String raw = System.getenv("PORT");
        if (raw != null && !raw.isBlank()) {
            return List.of(parsePort(raw));
        }
        List<Integer> ports = new ArrayList<>();
        for (int port = DEFAULT_PORT; port <= LAST_FALLBACK_PORT; port++) {
            ports.add(port);
        }
        ports.add(0);
        return ports;
    }

    private static int parsePort(String raw) {
        try {
            int port = Integer.parseInt(raw.trim());
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("PORT must be between 1 and 65535");
            }
            return port;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("PORT must be an integer", error);
        }
    }

    private static void openBrowser(URI uri) throws Exception {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(uri);
            return;
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command;
        if (os.contains("win")) {
            command = List.of("rundll32", "url.dll,FileProtocolHandler", uri.toString());
        } else if (os.contains("mac")) {
            command = List.of("open", uri.toString());
        } else {
            command = List.of("xdg-open", uri.toString());
        }
        new ProcessBuilder(command)
                .directory(Path.of(System.getProperty("user.home", ".")).toFile())
                .start();
    }

    private static void showLaunchError(String message) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        JOptionPane.showMessageDialog(
                null,
                "Propel Web could not start.\n\n" + message,
                "Propel Web",
                JOptionPane.ERROR_MESSAGE);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    record StartedServer(PropelHttpServer server, int port) {
    }
}
