package com.intel.cosbench.http;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory process-lifetime idempotency state. A key is claimed once, and
 * concurrent callers share the same in-flight or completed result.
 */
public final class IdempotencyStore {

    private final Map<IdempotencyKey, Entry> entries = new ConcurrentHashMap<>();

    public IdempotentResult execute(IdempotencyKey key, String fingerprint,
            Supplier<IdempotentResult> operation) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(operation, "operation");
        Entry candidate = new Entry(fingerprint);
        Entry existing = entries.putIfAbsent(key, candidate);
        Entry entry = existing == null ? candidate : existing;
        if (!entry.fingerprint.equals(fingerprint)) {
            throw new IdempotencyConflictException("idempotency key was reused with a different request");
        }
        if (existing == null) {
            try {
                entry.result.complete(Objects.requireNonNull(operation.get(), "operation result"));
            } catch (RuntimeException failure) {
                entry.result.complete(new IdempotentResult(500,
                        "{\"succ\":false,\"error\":\"request execution failed; result may be unknown\"}",
                        fingerprint));
            }
        }
        return entry.result.join();
    }

    public int size() {
        return entries.size();
    }

    private static final class Entry {
        private final String fingerprint;
        private final CompletableFuture<IdempotentResult> result = new CompletableFuture<>();

        private Entry(String fingerprint) {
            this.fingerprint = fingerprint;
        }
    }
}
