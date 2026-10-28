package com.intel.cosbench.config.runtime;

/** The process role represented by a versioned COSBench service configuration. */
public enum ServiceRole {
    CONTROLLER("controller"),
    DRIVER("driver");

    private final String wireName;

    ServiceRole(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ServiceRole parse(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("role is required");
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("role must be a string");
        }
        String normalized = value.toString().trim().toLowerCase(java.util.Locale.ROOT);
        for (ServiceRole role : values()) {
            if (role.wireName.equals(normalized)) {
                return role;
            }
        }
        throw new IllegalArgumentException("unsupported role: " + value);
    }
}
