package com.intel.cosbench.config.runtime;

import java.time.Duration;

public record ShutdownConfig(Duration gracePeriod) {

    public ShutdownConfig {
        if (gracePeriod == null || gracePeriod.compareTo(Duration.ofMillis(1)) < 0
                || gracePeriod.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("shutdown.gracePeriod must be between 1ms and 1h");
        }
    }
}
