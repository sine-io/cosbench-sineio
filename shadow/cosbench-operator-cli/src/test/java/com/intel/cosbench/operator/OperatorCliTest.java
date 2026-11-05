package com.intel.cosbench.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorCliTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesWithNamedOptionsAndReportsChecksums() throws Exception {
        Path source = tempDir.resolve("controller.conf");
        Path destination = tempDir.resolve("controller.yml");
        Path report = tempDir.resolve("migration.json");
        Files.writeString(source, """
                [controller]
                drivers = 0
                name = controller
                archive_dir = archive
                workspace_dir = workspace
                log_file = log/controller.log
                concurrency = 2
                """);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int status = OperatorCli.run(new String[] { "migrate", "--source", source.toString(), "--destination",
                destination.toString(), "--report", report.toString() },
                new PrintStream(stdout), new PrintStream(stderr));

        assertEquals(0, status);
        assertTrue(stdout.toString().contains("migration=passed"));
        assertTrue(stdout.toString().contains("source_sha256="));
        assertTrue(Files.exists(destination));
        assertTrue(Files.exists(report));
        assertTrue(stderr.toString().isEmpty());
    }

    @Test
    void inspectRejectsOccupiedPortAndInvalidRangeWithStableExitCodes() throws Exception {
        Path config = tempDir.resolve("controller.yml");
        int port;
        try (ServerSocket reservation = new ServerSocket()) {
            reservation.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            port = reservation.getLocalPort();
            Files.writeString(config, controllerConfig(port));

            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int status = OperatorCli.run(new String[] { "inspect", "--config", config.toString(), "--role",
                    "controller", "--check-port" }, new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(stderr));

            assertEquals(1, status);
            assertTrue(stderr.toString().contains("listen port is unavailable"));
        }

        Files.writeString(config, controllerConfig(70000));
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int status = OperatorCli.run(new String[] { "inspect", "--config", config.toString() },
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(stderr));
        assertEquals(1, status);
        assertTrue(stderr.toString().contains("value_out_of_range"));
    }

    @Test
    void helpAndMalformedArgumentsHaveDistinctExitCodes() {
        int helpStatus = OperatorCli.run(new String[] { "--help" }, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));
        int usageStatus = OperatorCli.run(new String[] { "migrate", "--source", "legacy.ini", "--unexpected" },
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, helpStatus);
        assertEquals(2, usageStatus);
    }

    private static String controllerConfig(int port) {
        return """
                schemaVersion: 1
                role: controller
                name: controller
                listen:
                  host: 127.0.0.1
                  port: %d
                paths: {}
                """.formatted(port);
    }
}
