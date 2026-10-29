package com.intel.cosbench.http;

import java.util.Objects;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Independent service credential carried by Controller-to-Driver requests. */
public record ServiceAuth(String token) {

    public ServiceAuth {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("service token must not be blank");
        }
        token = token.trim();
    }

    public boolean matches(String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    public String principalId() {
        return RequestFingerprint.sha256("service-token", token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
