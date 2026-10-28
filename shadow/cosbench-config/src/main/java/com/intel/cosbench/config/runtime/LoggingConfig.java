package com.intel.cosbench.config.runtime;

import java.nio.file.Path;

/** Process logging settings. The file path remains relative to the installation base when supplied that way. */
public record LoggingConfig(String level, Path file) {

    public LoggingConfig {
        if (level == null || level.isBlank()) {
            throw new IllegalArgumentException("logging.level must not be blank");
        }
        level = level.trim().toUpperCase(java.util.Locale.ROOT);
        if (!level.matches("TRACE|DEBUG|INFO|WARN|ERROR|OFF")) {
            throw new IllegalArgumentException("logging.level must be TRACE, DEBUG, INFO, WARN, ERROR, or OFF");
        }
        file = file == null || file.toString().isBlank() ? Path.of("log/system.log") : file.normalize();
    }
}
