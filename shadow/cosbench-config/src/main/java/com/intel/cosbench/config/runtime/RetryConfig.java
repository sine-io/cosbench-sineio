package com.intel.cosbench.config.runtime;

import java.time.Duration;

public record RetryConfig(int maxAttempts, Duration backoff) {

    public RetryConfig {
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("http.retries.maxAttempts must be between 1 and 10");
        }
        if (backoff == null || backoff.isNegative() || backoff.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("http.retries.backoff must be between 0 and 5m");
        }
    }
}
