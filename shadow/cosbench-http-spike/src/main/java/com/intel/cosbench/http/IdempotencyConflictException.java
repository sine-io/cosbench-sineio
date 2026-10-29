package com.intel.cosbench.http;

/** Raised when an idempotency key is reused with a different request body. */
public final class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
