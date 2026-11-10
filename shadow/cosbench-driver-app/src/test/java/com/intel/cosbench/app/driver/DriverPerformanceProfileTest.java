package com.intel.cosbench.app.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DriverPerformanceProfileTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int OPERATIONS = 25_000;
    private static final int OBJECT_BYTES = 64;
    private static final String TOKEN_ENV = "COSBENCH_HTTP_SERVICE_TOKEN";
    private static final String TOKEN = "cosbench-test-service-token";
    private static final String MISSION = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mission name="%s" workers="1" totalWorkers="1" totalOps="%d" interval="1">
              <auth type="mock" config="delay=0;errors=0" />
              <storage type="profile" config="size=64;delay=0;errors=0" />
              <operation type="read" ratio="100" config="containers=c(1);objects=c(1)" />
            </mission>
            """;

    @TempDir
    Path temp;

    @Test
    void recordsThreeMatchingDriverRunsAndRuntimeResources() throws Exception {
        Path report = Path.of(requiredEnv("COSBENCH_PERFORMANCE_REPORT_DIR"));
        Files.createDirectories(report);
        int port = reservePort();
        int jmxPort = reservePort();
        Path installation = Files.createDirectories(temp.resolve("installation"));
        Path config = writeConfig(installation, port);
        Path providerClasses = writeMockProviderClasspath(temp.resolve("provider-test-classes"));
        Path firstOperationMarker = temp.resolve("first-operation-time.txt");
        Path processLog = report.resolve("driver-process.log");
        HttpClient client = httpClient();
        long startupStarted = System.nanoTime();
        Process process = startDriver(config, port, jmxPort, processLog, providerClasses, firstOperationMarker);
        JmxMetrics jmx = null;
        boolean stopped = false;
        long startupMillis = -1;
        long stopMillis = -1;
        int exitCode = Integer.MIN_VALUE;
        try {
            awaitHealth(process, client, port);
            startupMillis = elapsedMillis(startupStarted);
            try (var connector = javax.management.remote.JMXConnectorFactory.connect(
                    new javax.management.remote.JMXServiceURL(
                            "service:jmx:rmi:///jndi/rmi://127.0.0.1:" + jmxPort + "/jmxrmi"))) {
                jmx = new JmxMetrics(connector.getMBeanServerConnection(), process.pid());
                runTrials(report, client, port, jmx, driverInstance(client, port), firstOperationMarker);
            }

            long stopping = System.nanoTime();
            process.destroy();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Driver did not stop after TERM");
            stopMillis = elapsedMillis(stopping);
            exitCode = process.exitValue();
            assertEquals(143, exitCode, "Driver TERM exit code");
            assertFalse(isListening(port), "Driver listener remained after stop");
            stopped = true;
        } finally {
            if (!stopped && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }

        long systemLogBytes = fileSize(installation.resolve("log/system.log"));
        long missionLogBytes = treeSize(installation.resolve("missions"));
        Files.writeString(report.resolve("lifecycle.txt"),
                "driver_startup_ms=" + startupMillis + "\n"
                        + "driver_stop_ms=" + stopMillis + "\n"
                        + "term_exit_code=" + exitCode + "\n"
                        + "http_port_released=" + !isListening(port) + "\n"
                        + "driver_process_alive_after_stop=" + process.isAlive() + "\n"
                        + "mission_log_bytes=" + missionLogBytes + "\n"
                        + "system_log_bytes=" + systemLogBytes + "\n"
                        + "provider_http_pool=not_applicable_mock_provider\n"
                        + "controller_process=not_included_driver_only_profile\n",
                StandardCharsets.UTF_8);
    }

    private static void runTrials(Path report, HttpClient client, int port, JmxMetrics jmx, String driverInstance,
            Path firstOperationMarker) throws Exception {
        Path metricsPath = report.resolve("performance.csv");
        Path samplesPath = report.resolve("resource-samples.csv");
        try (BufferedWriter metrics = Files.newBufferedWriter(metricsPath, StandardCharsets.UTF_8);
                BufferedWriter samples = Files.newBufferedWriter(samplesPath, StandardCharsets.UTF_8)) {
            metrics.write("trial,measured,gc_reset_before,requested_ops,reported_samples,total_samples,bytes," +
                    "success_ratio,elapsed_ms," +
                    "published_ops_per_second,avg_operation_ms,p50_bucket_ms,p95_bucket_ms,p99_bucket_ms,error_count," +
                    "retry_count,process_cpu_ms,process_cpu_percent,heap_start_bytes,heap_peak_bytes,heap_end_bytes," +
                    "heap_committed_peak_bytes,thread_start,thread_peak,thread_end,fd_start,fd_peak,fd_end," +
                    "rss_start_kib,rss_peak_kib,rss_end_kib,gc_count_delta,gc_time_ms_delta,first_operation_ms\n");
            samples.write("trial,elapsed_ms,process_cpu_ms,heap_used_bytes,heap_committed_bytes,thread_count," +
                    "open_fd_count,rss_kib,gc_count,gc_time_ms\n");

            for (int trial = 0; trial <= 3; trial++) {
                boolean gcResetBefore = trial > 0;
                if (gcResetBefore) {
                    jmx.collectGarbage();
                }
                TrialResult result = runTrial(trial, report, client, port, jmx, samples, driverInstance,
                        firstOperationMarker, gcResetBefore);
                metrics.write(result.toCsv());
                metrics.flush();
                samples.flush();
            }
        }

        Files.writeString(report.resolve("application-artifacts.txt"),
                "production_image=not_yet_owned_by_ticket_20;see_ticket_21\n"
                        + "profile_workload=one_worker_mock_read_64_bytes_total_ops_25000_delay_0_errors_0\n"
                        + "runtime=DriverApplication_using_Maven_test_classpath_and_test_only_instrumented_Mock_provider\n"
                        + "warmup_runs=1\nmeasured_runs=3\n",
                StandardCharsets.UTF_8);
        assertEquals(3, Files.readAllLines(metricsPath, StandardCharsets.UTF_8).size() - 2,
                "three measured runs plus one warmup must be recorded");
        assertTrue(Files.size(samplesPath) > 0);
    }

    private static TrialResult runTrial(int trial, Path report, HttpClient client, int port, JmxMetrics jmx,
            BufferedWriter samples, String driverInstance, Path firstOperationMarker, boolean gcResetBefore)
            throws Exception {
        String label = trial == 0 ? "warmup" : "run-" + trial;
        String missionName = "performance-profile-" + label;
        byte[] mission = MISSION.formatted(missionName, OPERATIONS).getBytes(StandardCharsets.UTF_8);
        Files.write(report.resolve(label + "-mission.xml"), mission);
        Files.deleteIfExists(firstOperationMarker);

        Snapshot start = jmx.snapshot();
        Snapshot max = start;
        long started = System.nanoTime();
        String idempotencyPrefix = "perf20-" + label;
        HttpResponse<String> submit = write(client, port, "submit", mission,
                idempotencyPrefix + "-submit", driverInstance);
        assertEquals(200, submit.statusCode(), "submit failed: " + submit.body());
        Files.writeString(report.resolve(label + "-submit.json"), submit.body(), StandardCharsets.UTF_8);
        JsonNode submitJson = JSON.readTree(submit.body());
        assertTrue(submitJson.path("succ").asBoolean(), submit.body());
        String missionId = submitJson.path("id").asText();
        assertEquals(driverInstance,
                submit.headers().firstValue("X-COSBench-Driver-Instance").orElseThrow());

        assertSuccess(write(client, port, "login", missionId.getBytes(StandardCharsets.UTF_8),
                idempotencyPrefix + "-login", driverInstance));
        long launchStartedEpochMillis = System.currentTimeMillis();
        assertSuccess(write(client, port, "launch", missionId.getBytes(StandardCharsets.UTF_8),
                idempotencyPrefix + "-launch", driverInstance));

        long firstOperationMillis = -1;
        long firstOperationEpochMillis = -1;
        JsonNode finalQuery = null;
        long queryDeadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < queryDeadline) {
            HttpResponse<String> query = read(client, port, "query", missionId);
            assertEquals(200, query.statusCode(), "query failed: " + query.body());
            JsonNode queryJson = JSON.readTree(query.body());
            if (firstOperationEpochMillis < 0 && Files.exists(firstOperationMarker)) {
                firstOperationEpochMillis = Long.parseLong(Files.readString(firstOperationMarker).trim());
                firstOperationMillis = Math.max(0, firstOperationEpochMillis - launchStartedEpochMillis);
            }
            Snapshot current = jmx.snapshot();
            max = max.max(current);
            writeSample(samples, label, elapsedMillis(started), current);
            finalQuery = queryJson;
            if (!queryJson.path("running").asBoolean(true)) {
                Files.writeString(report.resolve(label + "-query-finished.json"), query.body(),
                        StandardCharsets.UTF_8);
                break;
            }
            Thread.sleep(50);
        }
        assertNotNull(finalQuery, "Driver returned no query result");
        assertFalse(finalQuery.path("running").asBoolean(true), "mission did not finish: " + label);
        assertTrue(firstOperationMillis >= 0, "no first operation was observed: " + label);
        Files.writeString(report.resolve(label + "-first-operation.txt"),
                "launch_epoch_ms=" + launchStartedEpochMillis + "\n"
                        + "first_operation_epoch_ms=" + firstOperationEpochMillis + "\n"
                        + "first_operation_after_launch_ms=" + firstOperationMillis + "\n",
                StandardCharsets.UTF_8);

        HttpResponse<String> close = write(client, port, "close", missionId.getBytes(StandardCharsets.UTF_8),
                idempotencyPrefix + "-close", driverInstance);
        assertEquals(200, close.statusCode(), "close failed: " + close.body());
        Files.writeString(report.resolve(label + "-close.json"), close.body(), StandardCharsets.UTF_8);
        long elapsedNanos = System.nanoTime() - started;
        Snapshot end = jmx.snapshot();
        max = max.max(end);
        JsonNode closeJson = JSON.readTree(close.body());
        assertEquals("ACCOMPLISHED", closeJson.path("state").asText(), close.body());
        JsonNode operation = closeJson.path("report").path(0);
        int samplesCount = operation.path("sampleCount").asInt();
        int totalSamples = operation.path("totalSampleCount").asInt();
        long bytes = operation.path("byteCount").asLong();
        double successRatio = operation.path("ratio").asDouble(-1);
        assertEquals(OPERATIONS, samplesCount, close.body());
        assertEquals(OPERATIONS, totalSamples, close.body());
        assertEquals((long) OPERATIONS * OBJECT_BYTES, bytes, close.body());
        assertEquals(1.0, successRatio, 0.000001, close.body());
        writeSample(samples, label, elapsedMillis(started), end);

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        long cpuDelta = Math.max(0, end.cpuNanos - start.cpuNanos);
        long gcCountDelta = Math.max(0, end.gcCount - start.gcCount);
        long gcTimeDelta = Math.max(0, end.gcTimeMillis - start.gcTimeMillis);
        return new TrialResult(label, trial > 0, gcResetBefore, OPERATIONS, samplesCount, totalSamples, bytes,
                successRatio,
                elapsedMillis, operation.path("throughput").asDouble(), operation.path("avgResTime").asDouble(),
                percentile(operation, 50), percentile(operation, 95), percentile(operation, 99),
                Math.max(0, totalSamples - samplesCount), "not_applicable_direct_driver_mock",
                TimeUnit.NANOSECONDS.toMillis(cpuDelta), elapsedNanos == 0 ? 0 : cpuDelta * 100.0 / elapsedNanos,
                start.heapUsed, max.heapUsed, end.heapUsed, max.heapCommitted,
                start.threads, max.threads, end.threads, start.openFds, max.openFds, end.openFds,
                start.rssKib, max.rssKib, end.rssKib, gcCountDelta, gcTimeDelta, firstOperationMillis);
    }

    private static void assertSuccess(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(JSON.readTree(response.body()).path("succ").asBoolean(), response.body());
    }

    private static HttpResponse<String> write(HttpClient client, int port, String command, byte[] body,
            String key, String instance) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(commandUri(port, command))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/octet-stream")
                .header("Idempotency-Key", key)
                .header("X-COSBench-Request-Id", key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (instance != null) {
            builder.header("X-COSBench-Driver-Instance", instance);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> read(HttpClient client, int port, String command, String body)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(commandUri(port, command))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static URI commandUri(int port, String command) {
        return URI.create("https://127.0.0.1:" + port + "/driver/i/" + command + ".command");
    }

    private static HttpClient httpClient() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        String password = Files.readString(Path.of(requiredEnv("COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE"))).trim();
        try (InputStream input = Files.newInputStream(Path.of(requiredEnv("COSBENCH_HTTP_TEST_TRUSTSTORE_FILE")))) {
            trustStore.load(input, password.toCharArray());
        }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).sslContext(context)
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    private static Process startDriver(Path config, int port, int jmxPort, Path log, Path providerClasses,
            Path firstOperationMarker) throws Exception {
        String surefireClassPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path testClasses = Path.of(System.getProperty("basedir"), "target", "test-classes")
                .toAbsolutePath().normalize();
        String[] entries = surefireClassPath.split(java.util.regex.Pattern.quote(File.pathSeparator));
        assertTrue(Arrays.asList(entries).contains(testClasses.toString()),
                "Surefire classpath must contain Driver Mock providers");
        String classPath = Arrays.stream(entries).filter(entry -> !entry.equals(testClasses.toString()))
                .collect(Collectors.joining(File.pathSeparator));
        classPath += File.pathSeparator + providerClasses;
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = List.of(java.toString(),
                "--add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED",
                "-Dcosbench.providers.expected.storage=profile",
                "-Dcosbench.providers.expected.auth=mock",
                "-Dcosbench.performance.first-operation-marker=" + firstOperationMarker,
                "-Dcom.sun.management.jmxremote",
                "-Dcom.sun.management.jmxremote.port=" + jmxPort,
                "-Dcom.sun.management.jmxremote.rmi.port=" + jmxPort,
                "-Dcom.sun.management.jmxremote.host=127.0.0.1",
                "-Dcom.sun.management.jmxremote.authenticate=false",
                "-Dcom.sun.management.jmxremote.ssl=false",
                "-Djava.rmi.server.hostname=127.0.0.1",
                "-cp", classPath, DriverApplication.class.getName(), "--cosbench.config=" + config);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put(TOKEN_ENV, TOKEN);
        builder.environment().put("SERVER_SSL_KEY_STORE_PASSWORD",
                Files.readString(Path.of(requiredEnv("COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE"))).trim());
        return builder.start();
    }

    private static Path writeMockProviderClasspath(Path root) throws Exception {
        Path mockSource = Path.of(System.getProperty("basedir"), "target", "test-classes", "com", "intel",
                "cosbench", "api", "mock");
        Path mockDestination = Files.createDirectories(root.resolve("com/intel/cosbench/api/mock"));
        try (var files = Files.list(mockSource)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".class")).toList()) {
                Files.copy(file, mockDestination.resolve(file.getFileName()));
            }
        }
        Path services = Files.createDirectories(root.resolve("META-INF/services"));
        Files.writeString(services.resolve("com.intel.cosbench.api.auth.AuthAPIFactory"),
                "com.intel.cosbench.api.mock.MockAuthFactory\n", StandardCharsets.UTF_8);
        Files.writeString(services.resolve("com.intel.cosbench.api.storage.StorageAPIFactory"),
                "com.intel.cosbench.api.mock.PerformanceProfileStorageFactory\n", StandardCharsets.UTF_8);
        return root;
    }

    private static String driverInstance(HttpClient client, int port) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(commandUri(port, "ping"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofString(Long.toString(System.currentTimeMillis()),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), "ping failed: " + response.body());
        return response.headers().firstValue("X-COSBench-Driver-Instance").orElseThrow();
    }

    private static void awaitHealth(Process process, HttpClient client, int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError("Driver exited during startup; see driver-process.log");
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "https://127.0.0.1:" + port + "/driver/health"))
                        .timeout(Duration.ofSeconds(1)).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"accepting\":true")) {
                    return;
                }
            } catch (Exception ignored) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("Driver did not become healthy within 45 seconds");
    }

    private static boolean isListening(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return false;
        } catch (Exception occupied) {
            return true;
        }
    }

    private static Path writeConfig(Path installation, int port) throws Exception {
        Path configDirectory = Files.createDirectories(installation.resolve("conf"));
        Path config = configDirectory.resolve("driver.yml");
        String keyStore = Path.of(requiredEnv("COSBENCH_HTTP_TEST_KEYSTORE_FILE")).toUri().toString();
        Files.writeString(config, """
                schemaVersion: 1
                role: driver
                name: performance-profile-driver
                listen:
                  host: 127.0.0.1
                  port: %d
                  contextPath: /driver
                paths:
                  workspace: workspace
                  archive: archive
                  mission: missions
                  logs: log/system.log
                auth:
                  token:
                    env: %s
                  tls:
                    enabled: true
                    keyStore: "%s"
                concurrency: 1
                provider:
                  name: profile
                  concurrency: 1
                logging:
                  level: INFO
                  file: log/system.log
                web:
                  enabled: true
                  maxUploadBytes: 10485760
                shutdown:
                  gracePeriod: 5s
                """.formatted(port, TOKEN_ENV, keyStore), StandardCharsets.UTF_8);
        return config;
    }

    private static String percentile(JsonNode report, int percentile) {
        JsonNode direct = report.path("latency").path("_" + percentile);
        if (direct.isArray() && direct.size() >= 2) {
            return direct.get(0).asText() + "~" + direct.get(1).asText();
        }
        JsonNode histogram = report.path("latency").path("histoData");
        long total = 0;
        for (JsonNode bucket : histogram) {
            total += bucket.asLong();
        }
        if (total == 0) {
            return "null";
        }
        long rank = (long) Math.ceil(total * percentile / 100.0);
        long seen = 0;
        for (int index = 0; index < histogram.size(); index++) {
            seen += histogram.get(index).asLong();
            if (seen >= rank) {
                return index * 10 + "~" + (index + 1) * 10;
            }
        }
        return "null";
    }

    private static void writeSample(BufferedWriter writer, String trial, long elapsedMillis, Snapshot snapshot)
            throws Exception {
        writer.write(String.join(",", trial, Long.toString(elapsedMillis),
                Long.toString(TimeUnit.NANOSECONDS.toMillis(snapshot.cpuNanos)), Long.toString(snapshot.heapUsed),
                Long.toString(snapshot.heapCommitted), Integer.toString(snapshot.threads),
                Long.toString(snapshot.openFds), Long.toString(snapshot.rssKib), Long.toString(snapshot.gcCount),
                Long.toString(snapshot.gcTimeMillis)));
        writer.newLine();
    }

    private static long fileSize(Path file) throws Exception {
        return Files.exists(file) ? Files.size(file) : 0;
    }

    private static long treeSize(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (Exception ignored) {
                    return 0;
                }
            }).sum();
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static int reservePort() throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be supplied by the Docker performance harness");
        }
        return value;
    }

    private record Snapshot(long cpuNanos, long heapUsed, long heapCommitted, int threads, long openFds,
            long rssKib, long gcCount, long gcTimeMillis) {
        private Snapshot max(Snapshot other) {
            return new Snapshot(Math.max(cpuNanos, other.cpuNanos), Math.max(heapUsed, other.heapUsed),
                    Math.max(heapCommitted, other.heapCommitted), Math.max(threads, other.threads),
                    Math.max(openFds, other.openFds), Math.max(rssKib, other.rssKib),
                    Math.max(gcCount, other.gcCount), Math.max(gcTimeMillis, other.gcTimeMillis));
        }
    }

    private static final class JmxMetrics {
        private static final ObjectName OPERATING_SYSTEM;
        private static final ObjectName MEMORY;
        private static final ObjectName THREADING;
        private static final ObjectName GARBAGE_COLLECTORS;
        private final MBeanServerConnection connection;
        private final long pid;

        static {
            try {
                OPERATING_SYSTEM = new ObjectName("java.lang:type=OperatingSystem");
                MEMORY = new ObjectName("java.lang:type=Memory");
                THREADING = new ObjectName("java.lang:type=Threading");
                GARBAGE_COLLECTORS = new ObjectName("java.lang:type=GarbageCollector,name=*");
            } catch (Exception failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private JmxMetrics(MBeanServerConnection connection, long pid) {
            this.connection = connection;
            this.pid = pid;
        }

        private Snapshot snapshot() throws Exception {
            CompositeData heap = (CompositeData) connection.getAttribute(MEMORY, "HeapMemoryUsage");
            long gcCount = 0;
            long gcTime = 0;
            Set<ObjectName> collectors = connection.queryNames(GARBAGE_COLLECTORS, null);
            for (ObjectName collector : collectors) {
                Object count = connection.getAttribute(collector, "CollectionCount");
                Object time = connection.getAttribute(collector, "CollectionTime");
                if (count instanceof Number number && number.longValue() >= 0) {
                    gcCount += number.longValue();
                }
                if (time instanceof Number number && number.longValue() >= 0) {
                    gcTime += number.longValue();
                }
            }
            return new Snapshot(numberAttribute(OPERATING_SYSTEM, "ProcessCpuTime").longValue(),
                    ((Number) heap.get("used")).longValue(), ((Number) heap.get("committed")).longValue(),
                    numberAttribute(THREADING, "ThreadCount").intValue(),
                    numberAttribute(OPERATING_SYSTEM, "OpenFileDescriptorCount").longValue(),
                    processStatusKib(pid, "VmRSS"), gcCount, gcTime);
        }

        private void collectGarbage() throws Exception {
            connection.invoke(MEMORY, "gc", new Object[0], new String[0]);
        }

        private Number numberAttribute(ObjectName name, String attribute) throws Exception {
            Object value = connection.getAttribute(name, attribute);
            if (!(value instanceof Number number)) {
                throw new IllegalStateException("JMX attribute is not numeric: " + name + "/" + attribute);
            }
            return number;
        }
    }

    private static long processStatusKib(long pid, String key) {
        try {
            Path status = Path.of("/proc", Long.toString(pid), "status");
            for (String line : Files.readAllLines(status, StandardCharsets.US_ASCII)) {
                if (line.startsWith(key + ":")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", "").trim());
                }
            }
        } catch (Exception ignored) {
            return 0;
        }
        return 0;
    }

    private record TrialResult(String trial, boolean measured, boolean gcResetBefore, int requestedOps,
            int reportedSamples, int totalSamples, long bytes, double successRatio, long elapsedMillis, double throughput,
            double averageOperationMillis, String p50, String p95, String p99, int errors, String retries,
            long processCpuMillis, double processCpuPercent, long heapStart, long heapPeak, long heapEnd,
            long heapCommittedPeak, int threadStart, int threadPeak, int threadEnd, long fdStart, long fdPeak,
            long fdEnd, long rssStartKib, long rssPeakKib, long rssEndKib, long gcCountDelta,
            long gcTimeDeltaMillis, long firstOperationMillis) {
        private String toCsv() {
            return String.join(",", trial, Boolean.toString(measured), Boolean.toString(gcResetBefore),
                    Integer.toString(requestedOps),
                    Integer.toString(reportedSamples), Integer.toString(totalSamples), Long.toString(bytes),
                    Double.toString(successRatio), Long.toString(elapsedMillis), Double.toString(throughput),
                    Double.toString(averageOperationMillis), p50, p95, p99, Integer.toString(errors), retries,
                    Long.toString(processCpuMillis), Double.toString(processCpuPercent), Long.toString(heapStart),
                    Long.toString(heapPeak), Long.toString(heapEnd), Long.toString(heapCommittedPeak),
                    Integer.toString(threadStart), Integer.toString(threadPeak), Integer.toString(threadEnd),
                    Long.toString(fdStart), Long.toString(fdPeak), Long.toString(fdEnd), Long.toString(rssStartKib),
                    Long.toString(rssPeakKib), Long.toString(rssEndKib), Long.toString(gcCountDelta),
                    Long.toString(gcTimeDeltaMillis), Long.toString(firstOperationMillis)) + "\n";
        }
    }
}
