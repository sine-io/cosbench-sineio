package com.intel.cosbench.app.driver;

import java.util.Map;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intel.cosbench.driver.service.COSBDriverService;

/** Health reports service admission and current local mission activity. */
@RestController
public class DriverHealth {
    private final COSBDriverService service;
    private volatile boolean ready;

    public DriverHealth(COSBDriverService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    void ready() {
        ready = true;
    }

    @EventListener(ContextClosedEvent.class)
    void closing() {
        ready = false;
    }

    @GetMapping("/health")
    ResponseEntity<Map<String, Object>> health() {
        boolean accepting = ready && service.isAccepting();
        return ResponseEntity.status(accepting ? 200 : 503).body(Map.of(
                "status", accepting ? "UP" : "STOPPING",
                "role", "driver",
                "name", service.getDriverInfo().getName(),
                "accepting", accepting,
                "activeMissions", service.getActiveMissions().length));
    }
}
