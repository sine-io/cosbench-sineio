package com.intel.cosbench.config.runtime;

public record ProviderConfig(String name, int concurrency) {

    public ProviderConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("provider.name must not be blank");
        }
        if (concurrency < 1 || concurrency > 10000) {
            throw new IllegalArgumentException("provider.concurrency must be between 1 and 10000");
        }
    }
}
