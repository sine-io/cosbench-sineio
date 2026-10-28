package com.intel.cosbench.config.runtime;

import java.net.URI;

/** Controller-side address of a Driver process. */
public record DriverEndpoint(String name, String url) {

    public DriverEndpoint {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("drivers[].name must not be blank");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("drivers[].url must not be blank");
        }
        try {
            URI parsed = URI.create(url);
            if (parsed.getScheme() == null || parsed.getHost() == null
                    || (!parsed.getScheme().equalsIgnoreCase("http")
                            && !parsed.getScheme().equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("drivers[].url must be an absolute HTTP(S) URL");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("drivers[].url is invalid: " + url, e);
        }
    }
}
