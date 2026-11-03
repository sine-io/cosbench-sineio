package com.intel.cosbench.controller.port;

/** Controller-owned port for issuing one command to a Driver process. */
@FunctionalInterface
public interface DriverCommandClient {
    DriverCommandResponse execute(String command, byte[] payload, boolean write, long workloadDeadlineNanos);
}
