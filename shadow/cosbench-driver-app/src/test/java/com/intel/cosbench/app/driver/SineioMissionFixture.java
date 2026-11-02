package com.intel.cosbench.app.driver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.intel.cosbench.api.provider.StaticProviderRegistry;
import com.intel.cosbench.config.Config;
import com.intel.cosbench.config.XmlConfig;
import com.intel.cosbench.config.common.KVConfigParser;
import com.intel.cosbench.log.LogFactory;

/** Shared process/service fixtures, using only public SPI and OS resource observations. */
final class SineioMissionFixture {
    static final int OBJECT_SIZE = 4096;

    static StaticProviderRegistry registry() {
        return StaticProviderRegistry.discover(SineioMissionFixture.class.getClassLoader(),
                Set.of("sio", "siov1", "gdas"), Set.of());
    }

    static String storageValues() throws IOException {
        return "endpoint=" + required("COSBENCH_MINIO_ENDPOINT")
                + ";accesskey=" + Files.readString(Path.of(required("COSBENCH_MINIO_ACCESS_KEY_FILE"))).trim()
                + ";secretkey=" + Files.readString(Path.of(required("COSBENCH_MINIO_SECRET_KEY_FILE"))).trim()
                + ";timeout=1000;max_connections=4;path_style_access=true;aws_region=us-east-1";
    }

    static Config config() throws IOException {
        return KVConfigParser.parse(storageValues());
    }

    static String prepare(String mode) throws IOException {
        String bucket = "sineio-mission-" + UUID.randomUUID().toString().substring(0, 8) + "-1";
        Config config = config();
        var storage = registry().createStorage(mode, config, LogFactory.getSystemLogger());
        try {
            storage.createContainer(bucket, config);
            storage.createObject(bucket, "obj1", new ByteArrayInputStream(new byte[OBJECT_SIZE]), OBJECT_SIZE, config);
        } finally {
            storage.dispose();
        }
        return bucket;
    }

    static void remove(String mode, String bucket) throws IOException {
        Config config = config();
        var storage = registry().createStorage(mode, config, LogFactory.getSystemLogger());
        try {
            storage.deleteObject(bucket, "obj1", config);
            storage.deleteContainer(bucket, config);
        } finally {
            storage.dispose();
        }
    }

    static XmlConfig mission(String mode, String bucket, int operations) throws IOException {
        String xml = """
                <mission name="sineio-mission" workers="1" totalWorkers="1" totalOps="%d" interval="1">
                  <auth type="none" config=""/>
                  <storage type="%s" config="%s"/>
                  <operation type="read" ratio="100" config="containers=c(1);cprefix=%s;objects=c(1);oprefix=obj"/>
                </mission>
                """.formatted(operations, mode, storageValues().replace("&", "&amp;").replace("\"", "&quot;"),
                        bucket.substring(0, bucket.length() - 1));
        return new XmlConfig(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Count this JVM's established connections to the fixture's storage port on Linux. */
    static int storageConnections() {
        try {
            Set<String> sockets = new HashSet<>();
            try (var fds = Files.list(Path.of("/proc/self/fd"))) {
                for (Path fd : fds.toList()) {
                    try { sockets.add(Files.readSymbolicLink(fd).toString()); }
                    catch (NoSuchFileException closedDuringSnapshot) { }
                }
            }
            int port = URI.create(required("COSBENCH_MINIO_ENDPOINT")).getPort();
            String portHex = String.format(":%04X", port < 0 ? 80 : port);
            int count = 0;
            for (String table : new String[] {"/proc/net/tcp", "/proc/net/tcp6"}) {
                var rows = Files.readAllLines(Path.of(table));
                for (String row : rows.subList(1, rows.size())) {
                    String[] fields = row.trim().split("\\s+");
                    if (fields[2].endsWith(portHex) && fields[3].equals("01")
                            && sockets.contains("socket:[" + fields[9] + "]")) count++;
                }
            }
            return count;
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    static void await(BooleanSupplier condition, String diagnostic) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new IllegalStateException(diagnostic);
            Thread.sleep(10);
        }
    }

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
