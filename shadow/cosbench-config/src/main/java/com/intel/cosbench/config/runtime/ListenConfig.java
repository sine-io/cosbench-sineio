package com.intel.cosbench.config.runtime;

/** Network listener and servlet context settings. */
public record ListenConfig(String host, int port, String contextPath) {

    public ListenConfig {
        host = requireText(host, "listen.host");
        contextPath = normalizeContextPath(contextPath);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("listen.port must be between 1 and 65535");
        }
    }

    static String normalizeContextPath(String value) {
        String path = value == null || value.isBlank() ? "/" : value.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
