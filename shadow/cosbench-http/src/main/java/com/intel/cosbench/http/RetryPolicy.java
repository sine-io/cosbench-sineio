package com.intel.cosbench.http;

import java.util.Set;

/** Retry policy limited to transport failures and explicitly retryable 5xx. */
public record RetryPolicy(int maxAttempts, Set<Integer> retryableStatuses) {

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 10");
        }
        retryableStatuses = Set.copyOf(retryableStatuses == null ? Set.of() : retryableStatuses);
        if (retryableStatuses.stream().anyMatch(status -> status < 500 || status > 599)) {
            throw new IllegalArgumentException("retryable statuses must be 5xx");
        }
    }

    public boolean retryStatus(int status) {
        return retryableStatuses.contains(status);
    }
}
