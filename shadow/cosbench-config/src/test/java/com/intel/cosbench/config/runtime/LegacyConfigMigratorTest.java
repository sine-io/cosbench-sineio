package com.intel.cosbench.config.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyConfigMigratorTest {

    @TempDir
    Path tempDir;

    @Test
    void convertsControllerAndDriverFixturesWithoutChangingSources() throws Exception {
        Path controller = tempDir.resolve("controller.conf");
        Files.writeString(controller, """
                [controller]
                drivers = 1
                log_level = INFO
                log_file = log/system.log
                archive_dir = archive
                name = Controller
                url = http://127.0.0.1:19088/controller/index.html
                concurrency = 1

                [driver1]
                name = driver1
                url = http://127.0.0.1:18088/driver
                """, StandardCharsets.UTF_8);
        byte[] sourceBefore = Files.readAllBytes(controller);
        Path controllerYaml = tempDir.resolve("controller.yml");
        Path controllerReport = tempDir.resolve("controller-report.json");

        MigrationReport controllerResult = LegacyConfigMigrator.convert(
                controller, controllerYaml, controllerReport);

        assertEquals("controller", controllerResult.role());
        assertEquals(0, controllerResult.unmapped().size());
        assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest
                .getInstance("SHA-256").digest(sourceBefore)), controllerResult.sourceSha256());
        assertTrue(Files.exists(controllerYaml));
        assertTrue(Files.exists(controllerReport));
        assertEquals(java.util.Arrays.toString(sourceBefore),
                java.util.Arrays.toString(Files.readAllBytes(controller)));
        ServiceConfig convertedController = VersionedConfigLoader.load(controllerYaml, key -> null);
        assertEquals("Controller", convertedController.name());
        assertEquals(Path.of("workspace"), convertedController.paths().workspace());
        assertEquals(Path.of("archive"), convertedController.paths().archive());
        assertEquals("/controller", convertedController.listen().contextPath());
        assertEquals(19088, convertedController.listen().port());
        assertEquals(1, convertedController.concurrency());
        assertEquals(1, convertedController.provider().concurrency());
        assertEquals("mock", convertedController.provider().name());
        assertEquals("INFO", convertedController.logging().level());
        assertEquals(Path.of("log/system.log"), convertedController.logging().file());
        assertEquals(32, convertedController.http().pool().maxConnections());
        assertEquals(8, convertedController.http().pool().maxPerRoute());
        assertEquals(java.time.Duration.ofSeconds(30), convertedController.http().pool().idleTimeout());
        assertEquals(1, convertedController.http().retries().maxAttempts());
        assertEquals(java.time.Duration.ofSeconds(30), convertedController.shutdown().gracePeriod());
        assertNull(convertedController.auth().token());
        assertFalse(convertedController.auth().tls().enabled());
        assertTrue(convertedController.web().enabled());
        assertEquals(0, convertedController.web().maxUploadBytes());
        assertEquals("http://127.0.0.1:18088/driver", convertedController.drivers().getFirst().url());

        Path driver = tempDir.resolve("driver.conf");
        Files.writeString(driver, """
                [driver]
                name = driver1
                url = http://127.0.0.1:18088/driver
                log_level = INFO
                log_file = log/system.log
                mission_dir = log/mission
                """, StandardCharsets.UTF_8);
        Path driverYaml = tempDir.resolve("driver.yml");
        Path driverReport = tempDir.resolve("driver-report.json");

        MigrationReport driverResult = LegacyConfigMigrator.convert(driver, driverYaml, driverReport);

        assertEquals("driver", driverResult.role());
        assertTrue(driverResult.unmapped().isEmpty());
        ServiceConfig convertedDriver = VersionedConfigLoader.load(driverYaml, key -> null);
        assertEquals(ServiceRole.DRIVER, convertedDriver.role());
        assertEquals("driver1", convertedDriver.name());
        assertEquals(Path.of("workspace"), convertedDriver.paths().workspace());
        assertEquals(Path.of("archive"), convertedDriver.paths().archive());
        assertEquals(Path.of("log/mission"), convertedDriver.paths().mission());
        assertEquals(18088, convertedDriver.listen().port());
        assertEquals("INFO", convertedDriver.logging().level());
        assertEquals(Path.of("log/system.log"), convertedDriver.logging().file());
        assertEquals(1, convertedDriver.concurrency());
        assertEquals("mock", convertedDriver.provider().name());
        assertEquals(32, convertedDriver.http().pool().maxConnections());
        assertEquals(1, convertedDriver.http().retries().maxAttempts());
        assertEquals(30, convertedDriver.shutdown().gracePeriod().toSeconds());
        assertNull(convertedDriver.auth().token());
        assertFalse(convertedDriver.auth().tls().enabled());
        assertTrue(convertedDriver.web().enabled());
        assertEquals(10L * 1024 * 1024, convertedDriver.web().maxUploadBytes());
        assertNotNull(driverResult.destinationSha256());
    }

    @Test
    void reportsUnmappedLegacyValueAndRefusesToOverwriteDestination() throws Exception {
        Path source = tempDir.resolve("controller.conf");
        Files.writeString(source, """
                [controller]
                name = Controller
                url = http://127.0.0.1:19088/controller
                archive_dir = archive
                old_secret = do-not-copy
                """);
        Path destination = tempDir.resolve("controller.yml");
        Files.writeString(destination, "existing\n");
        Path report = tempDir.resolve("report.json");

        MigrationReport result = LegacyConfigMigrator.convert(source, destination, report);

        assertEquals(1, result.unmapped().size());
        assertTrue(result.unmapped().getFirst().contains("old_secret"));
        assertEquals("existing\n", Files.readString(destination));
        assertFalse(result.success());
        assertTrue(Files.readString(report).contains("destination_exists"));
    }

    @Test
    void reportsUnknownSectionsAndTopLevelValuesInsteadOfDroppingThem() throws Exception {
        Path source = tempDir.resolve("controller-with-unknown.conf");
        Files.writeString(source, """
                orphan = value
                [controller]
                name = Controller
                url = http://127.0.0.1:19088/controller
                [unexpected]
                key = value
                """);
        MigrationReport result = LegacyConfigMigrator.convert(source, tempDir.resolve("unknown.yml"),
                tempDir.resolve("unknown-report.json"));

        assertFalse(result.success());
        assertTrue(result.unmapped().stream().anyMatch(value -> value.contains("orphan")));
        assertTrue(result.unmapped().stream().anyMatch(value -> value.contains("unexpected")));
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.startsWith("legacy_unmapped")));
    }

    @Test
    void reportsDriverSectionsOutsideTheDeclaredControllerCount() throws Exception {
        Path source = tempDir.resolve("controller-with-extra-driver.conf");
        Files.writeString(source, """
                [controller]
                drivers = 1
                name = Controller
                url = http://127.0.0.1:19088/controller
                [driver1]
                name = driver1
                url = http://127.0.0.1:18088/driver
                [driver2]
                name = driver2
                url = http://127.0.0.1:18089/driver
                """);

        MigrationReport result = LegacyConfigMigrator.convert(source, tempDir.resolve("extra.yml"),
                tempDir.resolve("extra-report.json"));

        assertFalse(result.success());
        assertTrue(result.unmapped().stream().anyMatch(value -> value.contains("driver2")));
    }

    @Test
    void refusesSourceAndReportPathCollisionsWithoutChangingSourceBytes() throws Exception {
        Path source = tempDir.resolve("controller.conf");
        Files.writeString(source, """
                [controller]
                drivers = 0
                name = Controller
                """);
        byte[] original = Files.readAllBytes(source);

        MigrationReport sourceCollision = LegacyConfigMigrator.convert(source, tempDir.resolve("first.yml"), source);

        assertFalse(sourceCollision.success());
        assertTrue(sourceCollision.diagnostics().contains("report_path_collision"));
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(source)));

        Path destination = tempDir.resolve("destination.yml");
        MigrationReport destinationCollision = LegacyConfigMigrator.convert(source, destination, destination);

        assertFalse(destinationCollision.success());
        assertTrue(destinationCollision.diagnostics().contains("report_path_collision"));
        assertFalse(Files.exists(destination));
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(source)));
    }

    @Test
    void excludesInvalidLegacyValuesFromMigrationDiagnostics() throws Exception {
        Path source = tempDir.resolve("controller-with-invalid-value.conf");
        Files.writeString(source, """
                [controller]
                drivers = 0
                concurrency = secret-looking-value
                """);
        Path report = tempDir.resolve("safe-report.json");

        MigrationReport result = LegacyConfigMigrator.convert(source, tempDir.resolve("invalid.yml"), report);

        assertFalse(result.success());
        assertTrue(result.unmapped().stream().anyMatch(value -> value.contains("concurrency")));
        assertFalse(Files.readString(report).contains("secret-looking-value"));
    }
}
