package com.intel.cosbench.http;

import java.util.Objects;

/** A completed Driver response retained for process-lifetime replay. */
public record IdempotentResult(int status, String body, String fingerprint) {

    public IdempotentResult {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be an HTTP status code");
        }
        body = Objects.requireNonNullElse(body, "");
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
