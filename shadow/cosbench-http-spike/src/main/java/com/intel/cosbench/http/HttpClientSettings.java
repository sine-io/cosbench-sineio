package com.intel.cosbench.http;

import java.time.Duration;
import java.util.Set;

/** Transport-neutral settings mapped from the shared YAML by the app composition root. */
public record HttpClientSettings(int maxConnections, int maxPerRoute, Duration idleTimeout,
        Duration connectTimeout, Duration readTimeout, Duration commandTimeout, Duration workloadTimeout,
        int maxAttempts, Duration retryBackoff, Set<Integer> retryableStatuses) {

    public HttpClientSettings {
        if (retryableStatuses == null || retryableStatuses.isEmpty()) {
            retryableStatuses = Set.of(502, 503, 504);
        }
        if (maxConnections < 1 || maxPerRoute < 1 || maxPerRoute > maxConnections) {
            throw new IllegalArgumentException("invalid HTTP pool bounds");
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 10");
        }
        for (Duration duration : new Duration[] {idleTimeout, connectTimeout, readTimeout, commandTimeout,
                workloadTimeout, retryBackoff}) {
            if (duration == null || duration.isNegative()) {
                throw new IllegalArgumentException("HTTP durations must be present and non-negative");
            }
        }
        if (connectTimeout.isZero() || readTimeout.isZero() || commandTimeout.isZero()
                || workloadTimeout.isZero() || idleTimeout.isZero() || commandTimeout.compareTo(workloadTimeout) > 0) {
            throw new IllegalArgumentException("HTTP timeouts must be positive and command <= workload");
        }
        retryableStatuses = Set.copyOf(retryableStatuses);
        if (retryableStatuses.stream().anyMatch(status -> status < 500 || status > 599)) {
            throw new IllegalArgumentException("retryable statuses must be 5xx");
        }
    }

    public static HttpClientSettings from(com.intel.cosbench.config.runtime.HttpConfig config) {
        java.util.Objects.requireNonNull(config, "config");
        return new HttpClientSettings(config.pool().maxConnections(), config.pool().maxPerRoute(),
                config.pool().idleTimeout(), config.timeouts().connect(), config.timeouts().read(),
                config.timeouts().command(), config.timeouts().workload(), config.retries().maxAttempts(),
                config.retries().backoff(), java.util.Set.of(502, 503, 504));
    }
}
