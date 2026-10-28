package com.intel.cosbench.config.runtime;

import java.nio.file.Path;

/** Persistent paths. Values are intentionally retained as relative paths when supplied that way. */
public record ServicePaths(Path workspace, Path archive, Path mission, Path logs) {

    public ServicePaths {
        workspace = clean(workspace, "workspace");
        archive = clean(archive, "archive");
        mission = clean(mission, "mission");
        logs = clean(logs, "log/system.log");
    }

    public Path resolve(Path baseDirectory, Path value) {
        return value.isAbsolute() ? value.normalize() : baseDirectory.resolve(value).normalize();
    }

    public Path workspaceAt(Path baseDirectory) {
        return resolve(baseDirectory, workspace);
    }

    public Path archiveAt(Path baseDirectory) {
        return resolve(baseDirectory, archive);
    }

    public Path missionAt(Path baseDirectory) {
        return resolve(baseDirectory, mission);
    }

    public Path logsAt(Path baseDirectory) {
        return resolve(baseDirectory, logs);
    }

    private static Path clean(Path value, String fallback) {
        return value == null || value.toString().isBlank() ? Path.of(fallback) : value.normalize();
    }
}
