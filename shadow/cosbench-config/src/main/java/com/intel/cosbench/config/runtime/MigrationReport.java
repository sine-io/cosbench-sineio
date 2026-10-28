package com.intel.cosbench.config.runtime;

import java.util.List;

/** JSON-reportable result of one non-destructive legacy configuration conversion. */
public record MigrationReport(
        String source,
        String destination,
        String role,
        String sourceSha256,
        String destinationSha256,
        List<String> mapped,
        List<String> unmapped,
        List<String> diagnostics,
        boolean success) {

    public MigrationReport {
        mapped = List.copyOf(mapped == null ? List.of() : mapped);
        unmapped = List.copyOf(unmapped == null ? List.of() : unmapped);
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }
}
