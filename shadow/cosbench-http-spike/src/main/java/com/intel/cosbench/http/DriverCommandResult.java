package com.intel.cosbench.http;

import java.util.Objects;

public record DriverCommandResult(int status, String body) {

    public DriverCommandResult {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be an HTTP status code");
        }
        body = Objects.requireNonNullElse(body, "");
    }
}
