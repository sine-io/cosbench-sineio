package com.intel.cosbench.config.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class DriverInstanceConfigGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesIdempotentDriverConfigsWithIncrementedPortsAndIsolatedPaths() throws Exception {
        Path source = tempDir.resolve("driver.yml");
        Files.writeString(source, """
                schemaVersion: 1
                role: driver
                name: driver1
                listen:
                  host: 127.0.0.1
                  port: 18088
                  contextPath: /driver
                paths:
                  workspace: workspace
                  archive: archive
                  mission: log/mission
                  logs: log/system.log
                auth:
                  token:
                    env: COSBENCH_SERVICE_TOKEN
                  tls:
                    enabled: true
                    keyStore: conf/server.p12
                    trustStore: conf/trust.p12
                shutdown:
                  gracePeriod: 12s
                logging:
                  level: INFO
                  file: log/system.log
                """);
        byte[] original = Files.readAllBytes(source);
        Path output = tempDir.resolve("conf");

        var generated = DriverInstanceConfigGenerator.generate(source, output, 2, key -> "test-token");

        assertEquals(java.util.List.of(output.resolve("driver-1.yml"), output.resolve("driver-2.yml")), generated);
        ServiceConfig first = VersionedConfigLoader.load(generated.getFirst(), key -> "test-token");
        ServiceConfig second = VersionedConfigLoader.load(generated.get(1), key -> "test-token");
        assertEquals("driver1", first.name());
        assertEquals("driver2", second.name());
        assertEquals(18088, first.listen().port());
        assertEquals(18089, second.listen().port());
        assertEquals(Path.of("log/mission/driver1"), first.paths().mission());
        assertEquals(Path.of("log/mission/driver2"), second.paths().mission());
        assertEquals(Path.of("log/driver1/system.log"), first.logging().file());
        assertEquals(Path.of("log/driver2/system.log"), second.logging().file());
        assertEquals("COSBENCH_SERVICE_TOKEN", first.auth().token().env());
        assertEquals("test-token", first.auth().token().value());
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(source)));

        byte[] firstBefore = Files.readAllBytes(generated.getFirst());
        assertEquals(generated,
                DriverInstanceConfigGenerator.generate(source, output, 2, key -> "test-token"));
        assertTrue(java.util.Arrays.equals(firstBefore, Files.readAllBytes(generated.getFirst())));
    }

    @Test
    void refusesInvalidCountPortOverflowAndConflictingGeneratedFiles() throws Exception {
        Path source = tempDir.resolve("driver.yml");
        Files.writeString(source, """
                schemaVersion: 1
                role: driver
                name: worker
                listen:
                  port: 65535
                paths: {}
                """);

        assertThrows(IllegalArgumentException.class,
                () -> DriverInstanceConfigGenerator.generate(source, tempDir.resolve("bad-count"), 0, key -> null));
        assertThrows(IllegalArgumentException.class,
                () -> DriverInstanceConfigGenerator.generate(source, tempDir.resolve("overflow"), 2, key -> null));

        Path conflictingOutput = tempDir.resolve("conflict");
        Files.createDirectories(conflictingOutput);
        Path existing = conflictingOutput.resolve("driver-1.yml");
        Files.writeString(existing, "operator-owned: true\n");
        assertThrows(IllegalStateException.class,
                () -> DriverInstanceConfigGenerator.generate(source, conflictingOutput, 1, key -> null));
        assertEquals("operator-owned: true\n", Files.readString(existing));
        assertFalse(Files.exists(conflictingOutput.resolve("driver-2.yml")));
    }

    @Test
    void generatedConfigsRetainSecretReferencesInsteadOfResolvedValues() throws Exception {
        Path source = tempDir.resolve("driver.yml");
        Files.writeString(source, """
                schemaVersion: 1
                role: driver
                name: runner
                listen:
                  port: 18100
                paths: {}
                auth:
                  token:
                    file: /run/secrets/cosbench-token
                """);

        Path generated = DriverInstanceConfigGenerator.generate(source, tempDir.resolve("conf"), 1,
                reference -> "resolved-secret").getFirst();
        Map<String, Object> document = new Yaml().load(Files.readString(generated));
        Map<?, ?> auth = (Map<?, ?>) document.get("auth");
        Map<?, ?> token = (Map<?, ?>) auth.get("token");

        assertEquals("/run/secrets/cosbench-token", token.get("file"));
        assertFalse(Files.readString(generated).contains("resolved-secret"));
    }
}
