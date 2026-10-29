package com.intel.cosbench.http;

import java.util.Objects;

public record IdempotencyKey(String value) {

    public IdempotencyKey {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException("idempotency key must contain 1 to 200 characters");
        }
        value = value.trim();
    }

    public static IdempotencyKey scoped(String principal, String command, String value) {
        if (principal == null || principal.isBlank() || command == null || command.isBlank()) {
            throw new IllegalArgumentException("principal and command are required");
        }
        return new IdempotencyKey(principal + ":" + command + ":" + value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IdempotencyKey key && Objects.equals(value, key.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
