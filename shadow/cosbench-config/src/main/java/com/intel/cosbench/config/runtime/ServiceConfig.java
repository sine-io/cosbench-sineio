package com.intel.cosbench.config.runtime;

import java.util.List;

/** Immutable, validated configuration consumed by both composition roots. */
public record ServiceConfig(
        int schemaVersion,
        ServiceRole role,
        String name,
        ListenConfig listen,
        ServicePaths paths,
        List<DriverEndpoint> drivers,
        AuthConfig auth,
        HttpConfig http,
        int concurrency,
        ProviderConfig provider,
        LoggingConfig logging,
        WebConfig web,
        ShutdownConfig shutdown) {

    public ServiceConfig {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }
        if (role == null) {
            throw new IllegalArgumentException("role is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (listen == null || paths == null || auth == null || http == null || provider == null || logging == null || web == null
                || shutdown == null) {
            throw new IllegalArgumentException("service configuration contains a null section");
        }
        if (concurrency < 1 || concurrency > 10000) {
            throw new IllegalArgumentException("concurrency must be between 1 and 10000");
        }
        drivers = List.copyOf(drivers == null ? List.of() : drivers);
    }
}
