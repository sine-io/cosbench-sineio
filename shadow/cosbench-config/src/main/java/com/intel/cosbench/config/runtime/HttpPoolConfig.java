package com.intel.cosbench.config.runtime;

import java.time.Duration;

public record HttpPoolConfig(int maxConnections, int maxPerRoute, Duration idleTimeout) {

    public HttpPoolConfig {
        if (maxConnections < 1 || maxConnections > 10000) {
            throw new IllegalArgumentException("http.pool.maxConnections must be between 1 and 10000");
        }
        if (maxPerRoute < 1 || maxPerRoute > maxConnections) {
            throw new IllegalArgumentException("http.pool.maxPerRoute must be between 1 and maxConnections");
        }
        if (idleTimeout == null || idleTimeout.compareTo(Duration.ofMillis(1)) < 0
                || idleTimeout.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("http.pool.idleTimeout must be between 1ms and 24h");
        }
    }
}
