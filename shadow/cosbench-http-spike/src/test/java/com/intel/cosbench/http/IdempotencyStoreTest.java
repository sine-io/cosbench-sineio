package com.intel.cosbench.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class IdempotencyStoreTest {

    @Test
    void concurrentDuplicateSharesOneCompletedResult() throws Exception {
        IdempotencyStore store = new IdempotencyStore();
        IdempotencyKey key = new IdempotencyKey("request-1");
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> store.execute(key, "same", () -> {
                executions.incrementAndGet();
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return new IdempotentResult(200, "accepted", "same");
            }));
            var second = executor.submit(() -> store.execute(key, "same", () -> {
                executions.incrementAndGet();
                return new IdempotentResult(200, "wrong", "same");
            }));
            started.await();
            release.countDown();
            assertThat(first.get().body()).isEqualTo("accepted");
            assertThat(second.get().body()).isEqualTo("accepted");
            assertThat(executions).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        IdempotencyStore store = new IdempotencyStore();
        IdempotencyKey key = new IdempotencyKey("request-1");
        store.execute(key, "payload-a", () -> new IdempotentResult(200, "done", "payload-a"));

        assertThatThrownBy(() -> store.execute(key, "payload-b",
                () -> new IdempotentResult(200, "bad", "payload-b")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void failedResultCanBeReplayedWithoutExecutingAgain() {
        IdempotencyStore store = new IdempotencyStore();
        IdempotencyKey key = new IdempotencyKey("request-1");
        AtomicInteger executions = new AtomicInteger();
        IdempotentResult failure = store.execute(key, "payload",
                () -> {
                    executions.incrementAndGet();
                    return new IdempotentResult(503, "temporary failure", "payload");
                });

        IdempotentResult replay = store.execute(key, "payload", () -> {
            executions.incrementAndGet();
            return new IdempotentResult(200, "would duplicate", "payload");
        });

        assertThat(replay).isEqualTo(failure);
        assertThat(executions).hasValue(1);
    }
}
