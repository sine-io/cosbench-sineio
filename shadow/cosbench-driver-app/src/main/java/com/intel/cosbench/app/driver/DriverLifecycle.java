package com.intel.cosbench.app.driver;

import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.intel.cosbench.driver.service.COSBDriverService;

/** Refuse new missions before the bounded Driver and HTTP drain begins. */
@Component
public final class DriverLifecycle implements SmartLifecycle {
    private final COSBDriverService service;
    private volatile boolean running;

    public DriverLifecycle(COSBDriverService service) {
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
        Thread.ofPlatform().name("cosbench-driver-shutdown").start(() -> {
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
