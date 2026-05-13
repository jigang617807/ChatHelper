package com.example.agent.tool.react;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

final class UrlSafety {

    private UrlSafety() {
    }

    static URI requirePublicHttpUrl(String value) {
        try {
            URI uri = new URI(value == null ? "" : value.trim());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("Only http and https URLs are allowed.");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("URL host is required.");
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (normalizedHost.equals("localhost")
                    || normalizedHost.equals("0.0.0.0")
                    || normalizedHost.equals("::1")
                    || normalizedHost.startsWith("127.")
                    || normalizedHost.startsWith("10.")
                    || normalizedHost.startsWith("192.168.")
                    || normalizedHost.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\..*")) {
                throw new IllegalArgumentException("Private or local network URLs are not allowed.");
            }
            return uri;
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid URL: " + ex.getMessage(), ex);
        }
    }
}
