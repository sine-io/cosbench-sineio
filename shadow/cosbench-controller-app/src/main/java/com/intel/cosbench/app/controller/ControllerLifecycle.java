package com.intel.cosbench.app.controller;

import org.springframework.context.SmartLifecycle;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.intel.cosbench.controller.service.COSBControllerService;

/** Close admission first, then drain service and HTTP together before logging is disposed. */
@Component
public final class ControllerLifecycle implements SmartLifecycle {
    private final COSBControllerService service;
    private volatile boolean running;

    public ControllerLifecycle(COSBControllerService service) {
        this.service = service;
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ContextClosedEvent.class)
    void closeAdmission() {
        service.stopAccepting();
    }

    @Override public int getPhase() {
        return WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE;
    }

    @Override public void start() {
        running = true;
    }

    @Override public void stop() {
        try {
            service.close();
        } finally {
            running = false;
        }
    }

    @Override public void stop(Runnable callback) {
        Thread.ofPlatform().name("cosbench-controller-shutdown").start(() -> {
            try {
                stop();
            } finally {
                callback.run();
            }
        });
    }

    @Override public boolean isRunning() {
        return running;
    }
}
