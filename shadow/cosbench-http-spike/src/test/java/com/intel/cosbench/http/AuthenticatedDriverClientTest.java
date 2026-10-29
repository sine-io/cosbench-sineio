package com.intel.cosbench.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AuthenticatedDriverClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void responseLossRetryKeepsWriteIdentityAndRunsOnce() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        Map<String, String> fingerprints = new ConcurrentHashMap<>();
        server = server(exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer token");
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            assertThat(key).isNotBlank();
            assertThat(exchange.getRequestHeaders().getFirst("X-COSBench-Request-Id")).isEqualTo(key);
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String fingerprint = RequestFingerprint.sha256("submit", payload.getBytes(StandardCharsets.UTF_8));
            String existing = fingerprints.putIfAbsent(key, fingerprint);
            if (existing == null) {
                executions.incrementAndGet();
                exchange.close();
                return;
            }
            assertThat(existing).isEqualTo(fingerprint);
            respond(exchange, 201, "{\"id\":\"m-1\"}");
        });
        AuthenticatedDriverClient client = client(2);
        try {
            DriverCommandResult result = client.submit("mission".getBytes(StandardCharsets.UTF_8));
            assertThat(result.status()).isEqualTo(201);
            assertThat(result.body()).isEqualTo("{\"id\":\"m-1\"}");
            assertThat(executions).hasValue(1);
        } finally {
            client.close();
        }
    }

    @Test
    void restartAfterAcceptedWriteRejectsRetryAsUnknownInsteadOfRepeatingIt() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> instance =
                new java.util.concurrent.atomic.AtomicReference<>("driver-1");
        java.util.concurrent.atomic.AtomicReference<String> firstKey = new java.util.concurrent.atomic.AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/driver/i/ping.command", exchange -> {
            exchange.getResponseHeaders().set(DriverCommandController.DRIVER_INSTANCE_HEADER, instance.get());
            respond(exchange, 200, "{\"instance\":\"" + instance.get() + "\"}");
        });
        server.createContext("/driver/i/submit.command", exchange -> {
            String requestKey = exchange.getRequestHeaders().getFirst(DriverCommandController.IDEMPOTENCY_KEY_HEADER);
            String expectedInstance = exchange.getRequestHeaders().getFirst(DriverCommandController.DRIVER_INSTANCE_HEADER);
            int requestNumber = requests.incrementAndGet();
            if (requestNumber == 1) {
                firstKey.set(requestKey);
                assertThat(expectedInstance).isEqualTo("driver-1");
                executions.incrementAndGet();
                instance.set("driver-2");
                exchange.getResponseHeaders().set(DriverCommandController.DRIVER_INSTANCE_HEADER, "driver-1");
                exchange.close();
                return;
            }
            if (!instance.get().equals(expectedInstance)) {
                assertThat(requestKey).isEqualTo(firstKey.get());
                exchange.getResponseHeaders().set(DriverCommandController.DRIVER_INSTANCE_HEADER, instance.get());
                respond(exchange, 409, "{\"error\":\""
                        + DriverCommandController.DRIVER_INSTANCE_CHANGED + "\"}");
                return;
            }
            executions.incrementAndGet();
            exchange.getResponseHeaders().set(DriverCommandController.DRIVER_INSTANCE_HEADER, instance.get());
            respond(exchange, 200, "{\"id\":\"m-2\"}");
        });
        server.start();
        try (AuthenticatedDriverClient client = client(2)) {
            assertThatThrownBy(() -> client.submit("mission".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(DriverCommandException.class)
                    .hasMessageContaining(DriverCommandController.DRIVER_INSTANCE_CHANGED);
            assertThat(executions).hasValue(1);
            assertThat(client.submit("next-mission".getBytes(StandardCharsets.UTF_8)).body())
                    .isEqualTo("{\"id\":\"m-2\"}");
            assertThat(executions).hasValue(2);
            assertThat(requests).hasValue(3);
        }
    }

    @Test
    void configuredServerErrorsRetryWithinAttemptLimit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> {
            int attempt = calls.incrementAndGet();
            respond(exchange, attempt < 3 ? 503 : 200, attempt < 3 ? "busy" : "ok");
        });
        AuthenticatedDriverClient client = client(3);
        try {
            assertThat(client.submit("mission".getBytes(StandardCharsets.UTF_8)).body()).isEqualTo("ok");
            assertThat(calls).hasValue(3);
        } finally {
            client.close();
        }
    }

    @Test
    void authFailuresDoNotRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 401, "denied");
        });
        AuthenticatedDriverClient client = client(4);
        try {
            assertThatThrownBy(() -> client.submit("mission".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(DriverCommandException.class)
                    .extracting(error -> ((DriverCommandException) error).status()).isEqualTo(401);
            assertThat(calls).hasValue(1);
        } finally {
            client.close();
        }
    }

    @Test
    void retriesConfiguredServerErrorsOnly() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 501, "not configured");
        });
        AuthenticatedDriverClient client = client(4);
        try {
            assertThatThrownBy(() -> client.submit("mission".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(DriverCommandException.class)
                    .extracting(error -> ((DriverCommandException) error).status()).isEqualTo(501);
            assertThat(calls).hasValue(1);
        } finally {
            client.close();
        }
    }

    @Test
    void tlsClientContextVerifiesLocalServerCertificate() throws Exception {
        Path keyStore = Path.of(requiredEnv("COSBENCH_HTTP_TEST_KEYSTORE_FILE"));
        Path trustStore = Path.of(requiredEnv("COSBENCH_HTTP_TEST_TRUSTSTORE_FILE"));
        char[] password = java.nio.file.Files.readString(Path.of(
                requiredEnv("COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE"))).toCharArray();
        javax.net.ssl.SSLContext serverContext = HttpTlsContext.create(keyStore, trustStore, password);
        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        https.createContext("/driver/i/query.command", exchange -> respond(exchange, 200, "{\"running\":true}"));
        https.start();
        try {
            URI base = URI.create("https://localhost:" + https.getAddress().getPort() + "/driver");
            AuthenticatedDriverClient client = new AuthenticatedDriverClient(base, "token", settings(1),
                    HttpTlsContext.create(null, trustStore, password));
            try {
                assertThat(client.query(new byte[0]).body()).isEqualTo("{\"running\":true}");
            } finally {
                client.close();
            }
        } finally {
            https.stop(0);
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be supplied by the Docker harness");
        }
        return value;
    }

    @Test
    void missingTrustConfigurationFailsClosed() {
        assertThatThrownBy(() -> HttpTlsContext.create(null, null, "password".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TLS key or trust store");
    }

    @Test
    void tlsClientRejectsCertificateWithoutTrustAnchor() throws Exception {
        Path keyStore = Path.of(requiredEnv("COSBENCH_HTTP_TEST_KEYSTORE_FILE"));
        char[] password = java.nio.file.Files.readString(Path.of(
                requiredEnv("COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE"))).toCharArray();
        javax.net.ssl.SSLContext serverContext = HttpTlsContext.create(keyStore, null, password);
        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        https.createContext("/driver/i/query.command", exchange -> respond(exchange, 200, "ok"));
        https.start();
        try {
            AuthenticatedDriverClient client = new AuthenticatedDriverClient(
                    URI.create("https://localhost:" + https.getAddress().getPort() + "/driver"),
                    "token", settings(1));
            try {
                assertThatThrownBy(() -> client.query(new byte[0]))
                        .isInstanceOf(DriverTransportException.class)
                        .hasMessageContaining("result may be unknown");
            } finally {
                client.close();
            }
        } finally {
            https.stop(0);
            java.util.Arrays.fill(password, '\0');
        }
    }

    @Test
    void clientSettingsUseSharedYamlHttpContract() {
        var config = com.intel.cosbench.config.runtime.VersionedConfigLoader.load(
                Path.of("/workspace/conf/controller.yml"), reference -> "contract-token");
        HttpClientSettings settings = HttpClientSettings.from(config.http());
        assertThat(settings.maxConnections()).isEqualTo(config.http().pool().maxConnections());
        assertThat(settings.commandTimeout()).isEqualTo(config.http().timeouts().command());
        assertThat(settings.maxAttempts()).isEqualTo(config.http().retries().maxAttempts());
    }

    @Test
    void clientPoolAcquisitionTimeoutDoesNotReleaseAnUnacquiredSlot() throws Exception {
        HttpClientSettings settings = new HttpClientSettings(1, 1, Duration.ofSeconds(5),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(50),
                Duration.ofSeconds(2), 1, Duration.ZERO, java.util.Set.of(503));
        Semaphore globalSlot = new Semaphore(1);
        assertThat(globalSlot.tryAcquire()).isTrue();
        AuthenticatedDriverClient client = AuthenticatedDriverClient.pooled(
                URI.create("http://127.0.0.1:1/driver"), "token", settings, null, globalSlot);
        try {
            assertThatThrownBy(() -> client.query(new byte[0]))
                    .isInstanceOf(DriverTransportException.class)
                    .hasMessageContaining("pool acquisition timed out");
            assertThat(globalSlot.availablePermits()).isZero();
        } finally {
            globalSlot.release();
            client.close();
        }
    }

    @Test
    void registryCreatesEndpointClientsWithOneSharedGlobalSemaphore() {
        HttpClientSettings settings = new HttpClientSettings(1, 1, Duration.ofSeconds(5),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(2), 1, Duration.ZERO, java.util.Set.of(503));
        var globalSlotsByClient = new java.util.concurrent.ConcurrentHashMap<URI, Semaphore>();
        try (DriverClientRegistry registry = DriverClientRegistry.pooled(settings,
                (endpoint, slots) -> {
                    globalSlotsByClient.put(endpoint, slots);
                    return AuthenticatedDriverClient.pooled(endpoint, "token", settings, null, slots);
                })) {
            registry.client(URI.create("http://driver-a/"));
            registry.client(URI.create("http://driver-b/"));
            assertThat(globalSlotsByClient).hasSize(2);
            assertThat(globalSlotsByClient.values().stream().distinct()).hasSize(1);
        }
    }

    @Test
    void invalidPoolSettingsAreRejectedBeforeBuildingRegistry() {
        assertThatThrownBy(() -> new HttpClientSettings(2, 3, Duration.ofSeconds(5),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(2), 1, Duration.ZERO, java.util.Set.of(503)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pool bounds");
    }

    @Test
    void idleRegistryEvictionDoesNotCloseAnActiveDriverCall() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/driver/i/query.command", exchange -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            respond(exchange, 200, "ok");
        });
        server.start();
        HttpClientSettings settings = new HttpClientSettings(2, 2, Duration.ofMillis(5),
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(5), 1, Duration.ZERO, java.util.Set.of(503));
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/driver");
        try (DriverClientRegistry registry = DriverClientRegistry.pooled(settings,
                (uri, slots) -> AuthenticatedDriverClient.pooled(uri, "token", settings, null, slots))) {
            AuthenticatedDriverClient client = registry.client(endpoint);
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var result = executor.submit(() -> client.query(new byte[0]));
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(20);
                registry.evictIdle();
                assertThat(registry.size()).isEqualTo(1);
                release.countDown();
                assertThat(result.get().body()).isEqualTo("ok");
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    private HttpServer server(ExchangeAction action) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext("/driver/i/ping.command", exchange -> {
            exchange.getResponseHeaders().set(DriverCommandController.DRIVER_INSTANCE_HEADER, "instance-1");
            respond(exchange, 200, "{\"instance\":\"test\"}");
        });
        value.createContext("/driver/i/submit.command", action::run);
        value.start();
        return value;
    }

    private AuthenticatedDriverClient client(int attempts) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/driver");
        return new AuthenticatedDriverClient(base, "token", settings(attempts));
    }

    private static HttpClientSettings settings(int attempts) {
        return new HttpClientSettings(8, 4,
                Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(30), attempts, Duration.ZERO, java.util.Set.of(502, 503, 504));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeAction {
        void run(HttpExchange exchange) throws IOException;
    }
}
