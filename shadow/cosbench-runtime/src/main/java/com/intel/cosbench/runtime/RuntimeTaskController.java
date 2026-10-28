package com.intel.cosbench.runtime;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Small task admission endpoint proving that shutdown closes the acceptance gate first. */
@RestController
@RequestMapping("/tasks")
public final class RuntimeTaskController {

    private final ServiceRuntime runtime;

    RuntimeTaskController(ServiceRuntime runtime) {
        this.runtime = runtime;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> submit(@RequestParam(name = "durationMs", defaultValue = "0") long durationMs) {
        if (durationMs < 0 || durationMs > 3_600_000) {
            return ResponseEntity.badRequest().body(Map.of("error", "durationMs must be between 0 and 3600000"));
        }
        ServiceRuntime.TaskAdmission admission = runtime.submit(() -> {
            try {
                if (durationMs > 0) {
                    Thread.sleep(durationMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        if (admission == ServiceRuntime.TaskAdmission.REJECTED) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "stopping", "accepted", false));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted", "accepted", true, "activeTasks", runtime.activeTasks()));
    }
}
