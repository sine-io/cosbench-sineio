package com.intel.cosbench.http;

public final class DriverCommandException extends RuntimeException {

    private final int status;

    public DriverCommandException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
