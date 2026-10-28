package com.intel.cosbench.config.runtime;

/** A service token reference and its resolved in-memory value. */
public record TokenConfig(String env, String file, String value) {

    public TokenConfig {
        if ((env == null || env.isBlank()) == (file == null || file.isBlank())) {
            throw new IllegalArgumentException("auth.token must contain exactly one env or file reference");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("auth.token reference resolved to an empty secret");
        }
    }

    public String reference() {
        return env != null && !env.isBlank() ? "env:" + env : "file:" + file;
    }

    @Override
    public String toString() {
        return "TokenConfig[reference=" + reference() + ", value=<redacted>]";
    }
}
