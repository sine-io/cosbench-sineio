package com.intel.cosbench.config.runtime;

/** TLS settings. Certificate material is referenced by a protected path. */
public record TlsConfig(boolean enabled, String keyStore, String trustStore) {

    public TlsConfig {
        keyStore = blankToNull(keyStore);
        trustStore = blankToNull(trustStore);
        if (enabled && keyStore == null && trustStore == null) {
            throw new IllegalArgumentException("auth.tls requires keyStore or trustStore when enabled");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
