package com.intel.cosbench.config.runtime;

/** Secret references added when converting legacy settings to the secured runtime contract. */
public record MigrationSecuritySettings(String serviceTokenEnv, String driverKeyStore,
        String controllerTrustStore) {

    public static final String DEFAULT_SERVICE_TOKEN_ENV = "COSBENCH_SERVICE_TOKEN";
    public static final String DEFAULT_DRIVER_KEY_STORE = "/run/secrets/cosbench-driver-keystore.p12";
    public static final String DEFAULT_CONTROLLER_TRUST_STORE = "/run/secrets/cosbench-controller-truststore.p12";

    public MigrationSecuritySettings {
        if (serviceTokenEnv == null || !serviceTokenEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("service token environment variable name is invalid");
        }
        if (driverKeyStore == null || driverKeyStore.isBlank()) {
            throw new IllegalArgumentException("Driver keyStore path is required");
        }
        if (controllerTrustStore == null || controllerTrustStore.isBlank()) {
            throw new IllegalArgumentException("Controller trustStore path is required");
        }
    }

    public static MigrationSecuritySettings defaults() {
        return new MigrationSecuritySettings(DEFAULT_SERVICE_TOKEN_ENV, DEFAULT_DRIVER_KEY_STORE,
                DEFAULT_CONTROLLER_TRUST_STORE);
    }
}
