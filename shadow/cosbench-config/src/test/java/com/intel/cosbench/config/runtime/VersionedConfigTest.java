package com.intel.cosbench.config.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionedConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void readsControllerContractAndAppliesDocumentedDefaults() throws Exception {
        Path config = tempDir.resolve("controller.yml");
        Files.writeString(config, """
                schemaVersion: 1
                role: controller
                name: Controller
                listen:
                  host: 127.0.0.1
                  port: 19088
                  contextPath: /controller
                paths:
                  workspace: workspace
                  archive: archive
                  logs: log/system.log
                drivers:
                  - name: driver1
                    url: http://127.0.0.1:18088/driver
                auth:
                  token:
                    env: COSBENCH_SERVICE_TOKEN
                  tls:
                    enabled: false
                http:
                  pool:
                    maxConnections: 24
                    maxPerRoute: 8
                    idleTimeout: 45s
                  timeouts:
                    connect: 2s
                    read: 30s
                    command: 60s
                    workload: 1h
                  retries:
                    maxAttempts: 3
                    backoff: 250ms
                concurrency: 4
                provider:
                  name: mock
                  concurrency: 6
                web:
                  enabled: true
                  maxUploadBytes: 10485760
                shutdown:
                  gracePeriod: 15s
                """, StandardCharsets.UTF_8);

        ServiceConfig loaded = VersionedConfigLoader.load(config, key -> "token-from-env");

        assertEquals(1, loaded.schemaVersion());
        assertEquals(ServiceRole.CONTROLLER, loaded.role());
        assertEquals("Controller", loaded.name());
        assertEquals("127.0.0.1", loaded.listen().host());
        assertEquals(19088, loaded.listen().port());
        assertEquals("/controller", loaded.listen().contextPath());
        assertEquals(Path.of("archive"), loaded.paths().archive());
        assertEquals(24, loaded.http().pool().maxConnections());
        assertEquals(java.time.Duration.ofSeconds(45), loaded.http().pool().idleTimeout());
        assertEquals(java.time.Duration.ofHours(1), loaded.http().timeouts().workload());
        assertEquals("token-from-env", loaded.auth().token().value());
        assertFalse(loaded.auth().token().toString().contains("token-from-env"));
        assertEquals(15, loaded.shutdown().gracePeriod().toSeconds());
        assertEquals(6, loaded.provider().concurrency());
        assertEquals(1, loaded.drivers().size());
        assertEquals("driver1", loaded.drivers().getFirst().name());
    }

    @Test
    void rejectsUnknownKeysAndUnsupportedSchemaWithDiagnosticCodes() throws Exception {
        Path unknown = tempDir.resolve("unknown.yml");
        Files.writeString(unknown, """
                schemaVersion: 1
                role: controller
                name: Controller
                listen:
                  port: 19088
                paths:
                  archive: archive
                unexpected: true
                """);

        ConfigValidationException unknownFailure = assertThrows(ConfigValidationException.class,
                () -> VersionedConfigLoader.load(unknown, key -> null));
        assertEquals(ConfigProblemCode.UNKNOWN_KEY, unknownFailure.code());
        assertTrue(unknownFailure.getMessage().contains("unexpected"));

        Path unsupported = tempDir.resolve("unsupported.yml");
        Files.writeString(unsupported, """
                schemaVersion: 99
                role: controller
                name: Controller
                listen:
                  port: 19088
                paths:
                  archive: archive
                """);

        ConfigValidationException schemaFailure = assertThrows(ConfigValidationException.class,
                () -> VersionedConfigLoader.load(unsupported, key -> null));
        assertEquals(ConfigProblemCode.UNSUPPORTED_SCHEMA, schemaFailure.code());
    }

    @Test
    void distinguishesMissingDefaultExplicitEmptyRequiredAndInvalidConfigurations() throws Exception {
        ConfigLoadException defaultMissing = assertThrows(ConfigLoadException.class,
                () -> VersionedConfigLoader.loadDefault(tempDir, ServiceRole.CONTROLLER, key -> null));
        assertEquals(ConfigProblemCode.DEFAULT_FILE_MISSING, defaultMissing.code());

        ConfigLoadException explicitMissing = assertThrows(ConfigLoadException.class,
                () -> VersionedConfigLoader.load(tempDir.resolve("missing.yml"), key -> null));
        assertEquals(ConfigProblemCode.EXPLICIT_FILE_MISSING, explicitMissing.code());

        Path empty = tempDir.resolve("empty.yml");
        Files.writeString(empty, "# intentionally empty\n");
        ConfigValidationException emptyFailure = assertThrows(ConfigValidationException.class,
                () -> VersionedConfigLoader.load(empty, key -> null));
        assertEquals(ConfigProblemCode.EMPTY_DOCUMENT, emptyFailure.code());

        Path missing = tempDir.resolve("missing-required.yml");
        Files.writeString(missing, "schemaVersion: 1\nrole: controller\n");
        ConfigValidationException requiredFailure = assertThrows(ConfigValidationException.class,
                () -> VersionedConfigLoader.load(missing, key -> null));
        assertEquals(ConfigProblemCode.REQUIRED_FIELD_MISSING, requiredFailure.code());

        Path invalid = tempDir.resolve("invalid.yml");
        Files.writeString(invalid, """
                schemaVersion: 1
                role: controller
                name: Controller
                listen:
                  port: 70000
                paths:
                  archive: archive
                """);
        ConfigValidationException invalidFailure = assertThrows(ConfigValidationException.class,
                () -> VersionedConfigLoader.load(invalid, key -> null));
        assertEquals(ConfigProblemCode.VALUE_OUT_OF_RANGE, invalidFailure.code());
        assertFalse(invalidFailure.getMessage().contains("token"));
    }

    @Test
    void keepsScalarTypesAndDurationBoundsStrict() throws Exception {
        Path decimal = tempDir.resolve("decimal.yml");
        Files.writeString(decimal, """
                schemaVersion: 1
                role: controller
                name: Controller
                listen:
                  port: 19088.0
                paths:
                  archive: archive
                """);
        ConfigValidationException decimalFailure = assertThrows(ConfigValidationException.class,
                () -> VersionedConfigLoader.load(decimal, key -> null));
        assertEquals(ConfigProblemCode.INVALID_TYPE, decimalFailure.code());

        Path tooShort = tempDir.resolve("too-short.yml");
        Files.writeString(tooShort, """
                schemaVersion: 1
                role: controller
                name: Controller
                paths:
                  archive: archive
                http:
                  pool:
                    idleTimeout: 500us
                """);
        ConfigValidationException tooShortFailure = assertThrows(ConfigValidationException.class,
                () -> VersionedConfigLoader.load(tooShort, key -> null));
        assertEquals(ConfigProblemCode.VALUE_OUT_OF_RANGE, tooShortFailure.code());

        Path invalidDriver = tempDir.resolve("invalid-driver.yml");
        Files.writeString(invalidDriver, """
                schemaVersion: 1
                role: controller
                name: Controller
                paths:
                  archive: archive
                drivers:
                  - name: driver1
                    url: ftp://127.0.0.1:18088/driver
                """);
        ConfigValidationException invalidDriverFailure = assertThrows(ConfigValidationException.class,
                () -> VersionedConfigLoader.load(invalidDriver, key -> null));
        assertEquals(ConfigProblemCode.INVALID_VALUE, invalidDriverFailure.code());
    }
}
