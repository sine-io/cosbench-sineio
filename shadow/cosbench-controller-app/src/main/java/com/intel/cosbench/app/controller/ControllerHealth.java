package com.intel.cosbench.app.controller;

import java.util.Map;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intel.cosbench.controller.service.COSBControllerService;

/** Readiness depends on the initialized service and its admission gate. */
@RestController
public class ControllerHealth {
    private final COSBControllerService service;
    private volatile boolean ready;

    public ControllerHealth(COSBControllerService service) {
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
                "status", accepting ? "UP" : "STOPPING", "role", "controller",
                "name", service.getControllerInfo().getName(), "accepting", accepting,
                "activeWorkloads", service.getActiveWorkloads().length));
    }
}
