package com.intel.cosbench.config.runtime;

import java.time.Duration;

public record HttpTimeoutConfig(Duration connect, Duration read, Duration command, Duration workload) {

    public HttpTimeoutConfig {
        requirePositive(connect, "http.timeouts.connect");
        requirePositive(read, "http.timeouts.read");
        requirePositive(command, "http.timeouts.command");
        requirePositive(workload, "http.timeouts.workload");
        if (command.compareTo(workload) > 0) {
            throw new IllegalArgumentException("http.timeouts.command must not exceed workload timeout");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException(field + " must be between 1ms and 7d");
        }
    }
}
