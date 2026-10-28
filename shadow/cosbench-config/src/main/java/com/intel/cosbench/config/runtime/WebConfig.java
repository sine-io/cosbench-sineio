package com.intel.cosbench.config.runtime;

public record WebConfig(boolean enabled, long maxUploadBytes) {

    public WebConfig {
        if (maxUploadBytes < 0 || maxUploadBytes > 10L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("web.maxUploadBytes must be between 0 and 10GiB");
        }
    }
}
