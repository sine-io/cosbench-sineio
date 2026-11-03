package com.intel.cosbench.controller.port;

/** Resolves a command client for a configured Driver endpoint and owns its lifecycle. */
public interface DriverCommandClientProvider extends AutoCloseable {
    DriverCommandClient client(String endpoint);

    @Override
    void close();
}
