package com.intel.cosbench.config.runtime;

public record HttpConfig(HttpPoolConfig pool, HttpTimeoutConfig timeouts, RetryConfig retries) {

    public HttpConfig {
        if (pool == null || timeouts == null || retries == null) {
            throw new IllegalArgumentException("http pool, timeouts, and retries are required");
        }
    }
}
