package com.intel.cosbench.config.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControllerDriverConfigBinderTest {

    @TempDir
    Path tempDir;

    @Test
    void bindsEveryDriverInstanceWithTlsUrlsAndRetainsControllerSecrets() throws Exception {
        Path controllerTemplate = tempDir.resolve("controller-template.yml");
        Files.writeString(controllerTemplate, """
                schemaVersion: 1
                role: controller
                name: controller
                listen:
                  port: 19088
                paths: {}
                auth:
                  token:
                    env: COSBENCH_SERVICE_TOKEN
                  tls:
                    enabled: true
                    trustStore: conf/dev-trust.p12
                """);
        Path driverTemplate = tempDir.resolve("driver-template.yml");
        Files.writeString(driverTemplate, """
                schemaVersion: 1
                role: driver
                name: driver1
                listen:
                  host: 127.0.0.1
                  port: 18088
                  contextPath: /driver
                paths: {}
                auth:
                  token:
                    env: COSBENCH_SERVICE_TOKEN
                  tls:
                    enabled: true
                    keyStore: conf/dev-server.p12
                """);
        Path driverDirectory = tempDir.resolve("conf");
        DriverInstanceConfigGenerator.generate(driverTemplate, driverDirectory, 2, key -> "test-token");
        Path output = tempDir.resolve("controller-dev.yml");

        ControllerDriverConfigBinder.bind(controllerTemplate, driverDirectory, 2, output, key -> "test-token");

        ServiceConfig controller = VersionedConfigLoader.load(output, key -> "test-token");
        assertEquals(2, controller.drivers().size());
        assertEquals("driver1", controller.drivers().get(0).name());
        assertEquals("https://127.0.0.1:18088/driver", controller.drivers().get(0).url());
        assertEquals("driver2", controller.drivers().get(1).name());
        assertEquals("https://127.0.0.1:18089/driver", controller.drivers().get(1).url());
        assertTrue(Files.readString(output).contains("env: COSBENCH_SERVICE_TOKEN"));
        assertFalse(Files.readString(output).contains("test-token"));
        assertEquals(output, ControllerDriverConfigBinder.bind(controllerTemplate, driverDirectory, 2, output,
                key -> "test-token"));
    }

    @Test
    void refusesToReplaceAnExistingControllerConfig() throws Exception {
        Path controllerTemplate = tempDir.resolve("controller-template.yml");
        Files.writeString(controllerTemplate, """
                schemaVersion: 1
                role: controller
                name: controller
                paths: {}
                auth:
                  token:
                    env: COSBENCH_SERVICE_TOKEN
                  tls:
                    enabled: true
                    trustStore: conf/trust.p12
                """);
        Path driverDirectory = tempDir.resolve("conf");
        Files.createDirectories(driverDirectory);
        Files.writeString(driverDirectory.resolve("driver-1.yml"), """
                schemaVersion: 1
                role: driver
                name: driver1
                listen:
                  port: 18088
                paths: {}
                auth:
                  token:
                    env: COSBENCH_SERVICE_TOKEN
                  tls:
                    enabled: true
                    keyStore: conf/server.p12
                """);
        Path output = tempDir.resolve("controller.yml");
        Files.writeString(output, "operator-owned: true\n");

        assertThrows(IllegalStateException.class,
                () -> ControllerDriverConfigBinder.bind(controllerTemplate, driverDirectory, 1, output,
                        key -> "test-token"));
        assertEquals("operator-owned: true\n", Files.readString(output));
    }

    @Test
    void refusesToBindDriverWithDifferentResolvedServiceToken() throws Exception {
        Path controllerTemplate = tempDir.resolve("controller-template.yml");
        Files.writeString(controllerTemplate, """
                schemaVersion: 1
                role: controller
                name: controller
                paths: {}
                auth:
                  token:
                    env: COSBENCH_CONTROLLER_TOKEN
                  tls:
                    enabled: true
                    trustStore: conf/trust.p12
                """);
        Path drivers = tempDir.resolve("drivers");
        Files.createDirectories(drivers);
        Files.writeString(drivers.resolve("driver-1.yml"), """
                schemaVersion: 1
                role: driver
                name: driver1
                listen:
                  port: 18088
                paths: {}
                auth:
                  token:
                    env: COSBENCH_DRIVER_TOKEN
                  tls:
                    enabled: true
                    keyStore: conf/server.p12
                """);

        assertThrows(IllegalArgumentException.class,
                () -> ControllerDriverConfigBinder.bind(controllerTemplate, drivers, 1, tempDir.resolve("out.yml"),
                        key -> key.equals("COSBENCH_CONTROLLER_TOKEN") ? "controller-token" : "driver-token"));
    }
}
