package com.intel.cosbench.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Writes and removes a PID file for the stable stop command. */
@Component
public final class PidFileLifecycle {

    private final Path pidFile;

    PidFileLifecycle(@Value("${cosbench.pid-file:}") String configuredPath) {
        this.pidFile = configuredPath == null || configuredPath.isBlank() ? null : Path.of(configuredPath);
    }

    @EventListener(ApplicationReadyEvent.class)
    void writePid() {
        if (pidFile == null) {
            return;
        }
        try {
            if (pidFile.getParent() != null) {
                Files.createDirectories(pidFile.getParent());
            }
            Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write pid file " + pidFile, e);
        }
    }

    @jakarta.annotation.PreDestroy
    void removePid() {
        if (pidFile != null) {
            try {
                Files.deleteIfExists(pidFile);
            } catch (IOException ignored) {
                // The process is already stopping; the stop command reports a stale file if needed.
            }
        }
    }
}
