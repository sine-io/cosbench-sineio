package com.intel.cosbench.controller.port;

import java.util.Objects;

/** Minimal response data needed by the legacy Controller protocol adapter. */
public record DriverCommandResponse(int status, String body) {
    public DriverCommandResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be an HTTP status code");
        }
        body = Objects.requireNonNullElse(body, "");
    }
}
