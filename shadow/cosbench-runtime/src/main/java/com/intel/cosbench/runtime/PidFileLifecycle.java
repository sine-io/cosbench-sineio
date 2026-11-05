package com.intel.cosbench.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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
            Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            if (isCurrentProcessPidFile()) {
                return;
            }
            throw new IllegalStateException("PID file already exists; use the operator command to clear a stale file: "
                    + pidFile, e);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write pid file " + pidFile, e);
        }
    }

    private boolean isCurrentProcessPidFile() {
        if (Files.isSymbolicLink(pidFile)) {
            return false;
        }
        try {
            return Files.readString(pidFile, StandardCharsets.UTF_8).trim()
                    .equals(Long.toString(ProcessHandle.current().pid()));
        } catch (IOException ignored) {
            return false;
        }
    }

    @jakarta.annotation.PreDestroy
    void removePid() {
        if (pidFile != null && !Files.isSymbolicLink(pidFile)) {
            try {
                String contents = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
                if (contents.equals(Long.toString(ProcessHandle.current().pid()))) {
                    Files.deleteIfExists(pidFile);
                }
            } catch (IOException ignored) {
                // The process is already stopping; the stop command reports a stale file if needed.
            }
        }
    }
}
