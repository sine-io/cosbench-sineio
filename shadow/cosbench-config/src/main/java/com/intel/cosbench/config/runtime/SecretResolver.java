package com.intel.cosbench.config.runtime;

/** Resolves a protected runtime secret without putting its value in YAML. */
@FunctionalInterface
public interface SecretResolver {

    String resolve(String reference);

    static SecretResolver environment() {
        return reference -> {
            if (reference == null || reference.isBlank()) {
                return null;
            }
            String value = System.getenv(reference);
            if (value != null && !value.isBlank()) {
                return value;
            }
            java.nio.file.Path path = java.nio.file.Path.of(reference);
            try {
                if (java.nio.file.Files.isRegularFile(path)) {
                    String fileValue = java.nio.file.Files.readString(path).trim();
                    return fileValue.isEmpty() ? null : fileValue;
                }
            } catch (java.io.IOException e) {
                return null;
            }
            return null;
        };
    }
}
