package com.autoproject.web;

/** Starts the headless HTTP version of Propel. */
public final class WebMain {
    private WebMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = intEnvironment("PORT", 8080, 1, 65_535);
        PropelHttpServer server = new PropelHttpServer(port);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "propel-web-shutdown"));
        System.out.println("Propel Web is listening on http://0.0.0.0:" + port);
        Thread.currentThread().join();
    }

    private static int intEnvironment(String name, int defaultValue, int min, int max) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }
}
