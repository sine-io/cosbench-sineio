package com.intel.cosbench.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Observable health and old-archive read seam used by the lifecycle spike. */
@RestController
@RequestMapping
public final class RuntimeHealthController {

    private final ServiceRuntime runtime;
    private final ArchiveReader archiveReader;

    RuntimeHealthController(ServiceRuntime runtime, ArchiveReader archiveReader) {
        this.runtime = runtime;
        this.archiveReader = archiveReader;
    }

    @GetMapping({"/health", "/actuator/health"})
    ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", runtime.isReady() ? "UP" : "STARTING");
        body.put("role", runtime.config().role().wireName());
        body.put("name", runtime.config().name());
        body.put("accepting", runtime.isAccepting());
        body.put("activeTasks", runtime.activeTasks());
        body.put("startedAt", runtime.startedAt());
        Instant stoppedAt = runtime.stoppedAt();
        if (stoppedAt != null) {
            body.put("stoppedAt", stoppedAt);
        }
        return ResponseEntity.status(runtime.isReady() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @GetMapping("/archive")
    ArchiveReader.ArchiveSnapshot archive() {
        return archiveReader.snapshot();
    }
}
