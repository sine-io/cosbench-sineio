package com.intel.cosbench.http.real;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.intel.cosbench.api.auth.AuthAPI;
import com.intel.cosbench.api.auth.AuthAPIService;
import com.intel.cosbench.api.mock.MockAuthFactory;
import com.intel.cosbench.api.mock.MockStorageFactory;
import com.intel.cosbench.api.storage.StorageAPI;
import com.intel.cosbench.api.storage.StorageAPIService;
import com.intel.cosbench.config.XmlConfig;
import com.intel.cosbench.driver.service.COSBDriverServiceFactory;
import com.intel.cosbench.http.DriverCommandController;
import com.intel.cosbench.http.AuthenticatedDriverClient;
import com.intel.cosbench.http.DriverCommandResult;
import com.intel.cosbench.http.HttpClientSettings;
import com.intel.cosbench.http.IdempotencyStore;
import com.intel.cosbench.http.LegacyDriverCommandPort;
import com.intel.cosbench.http.ServiceAuth;
import com.intel.cosbench.log.Logger;
import com.intel.cosbench.model.MissionState;
import com.intel.cosbench.service.DriverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;

@SpringBootTest(classes = RealDriverHttpTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=0.0.0.0")
class RealDriverHttpContractTest {
    @LocalServerPort
    private int port;

    @Autowired
    private AtomicInteger submitCalls;

    @Autowired
    private DriverService driver;

    @DynamicPropertySource
    static void driverTlsProperties(DynamicPropertyRegistry properties) {
        properties.add("server.ssl.enabled", () -> true);
        properties.add("server.ssl.key-store", () -> requiredEnv("COSBENCH_HTTP_TEST_KEYSTORE_FILE"));
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
        properties.add("server.ssl.key-store-password", RealDriverHttpContractTest::testKeyStorePassword);
    }

    @Test
    void realMockDriverSubmitAndQueryAreAuthenticatedAndIdempotent() throws Exception {
        HttpClient client = tlsHttpClient();
        byte[] workload = Files.readAllBytes(Path.of(
                "/workspace/.scratch/scheme3-migration/baseline/fixtures/mock-noop-mission.xml"));
        URI submitUri = URI.create("https://127.0.0.1:" + port + "/i/submit.command");
        HttpResponse<String> unauthenticated = send(client, submitUri, workload, null);
        assertThat(unauthenticated.statusCode()).isEqualTo(401);

        String key = "real-driver-mission-1";
        HttpResponse<String> submitted = send(client, submitUri, workload, key);
        assertThat(submitted.statusCode()).as("real DriverService submit response")
                .isEqualTo(200);
        String driverInstance = submitted.headers()
                .firstValue(DriverCommandController.DRIVER_INSTANCE_HEADER).orElseThrow();
        HttpResponse<String> replayed = send(client, submitUri, workload, key);
        assertThat(submitted.statusCode()).isEqualTo(200);
        assertThat(replayed.statusCode()).isEqualTo(200);
        assertThat(replayed.body()).isEqualTo(submitted.body());
        assertThat(replayed.headers().firstValue(DriverCommandController.DRIVER_INSTANCE_HEADER))
                .contains(driverInstance);
        assertThat(submitCalls).hasValue(1);

        String missionId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(submitted.body()).get("id").asText();
        assertThat(driver.getMissionInfo(missionId).getState()).isEqualTo(MissionState.SUBMITTED);
        DriverCommandResult queried = driverClient().query(
                missionId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(queried.status()).isEqualTo(200);
        assertThat(queried.body()).contains("\"running\":false");

        try (AuthenticatedDriverClient serviceClient = driverClient()) {
            byte[] missionIdBody = missionId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(serviceClient.login(missionIdBody).status()).isEqualTo(200);
            assertThat(serviceClient.launch(missionIdBody).status()).isEqualTo(200);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (driver.getMissionInfo(missionId).getState() == MissionState.LAUNCHED
                    && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertThat(driver.getMissionInfo(missionId).getState()).isEqualTo(MissionState.FINISHED);
            assertThat(serviceClient.closeMission(missionIdBody).status()).isEqualTo(200);
        }
        assertThat(driver.getMissionInfo(missionId).getState()).isEqualTo(MissionState.ACCOMPLISHED);
        DriverCommandResult completed = driverClient().query(
                missionId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(completed.status()).isEqualTo(200);
        assertThat(completed.body()).contains("\"running\":false");
        HttpResponse<String> lateReplay = send(client, submitUri, workload, key);
        assertThat(lateReplay.statusCode()).isEqualTo(200);
        assertThat(lateReplay.body()).isEqualTo(submitted.body());
        assertThat(submitCalls).hasValue(1);
    }

    @Test
    void droppedDriverResponseReplaysOneRealMockMissionSubmit() throws Exception {
        byte[] mission = Files.readAllBytes(Path.of(
                "/workspace/.scratch/scheme3-migration/baseline/fixtures/mock-noop-mission.xml"));
        int before = submitCalls.get();
        URI admin = URI.create(System.getenv("COSBENCH_TOXIPROXY_ADMIN_URL"));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        String proxyName = "real-driver-response-loss-" + UUID.randomUUID().toString().substring(0, 8);
        String driverHost = java.net.InetAddress.getLocalHost().getHostAddress();
        toxiproxy(http, admin, "POST", "/proxies", "{\"name\":\"" + proxyName
                + "\",\"listen\":\"0.0.0.0:18666\",\"upstream\":\"" + driverHost + ":" + port + "\"}");
        try {
            HttpClientSettings settings = new HttpClientSettings(4, 2, Duration.ofSeconds(20),
                    Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(5),
                    Duration.ofSeconds(30), 2, Duration.ofMillis(500), Set.of(502, 503, 504));
            try (AuthenticatedDriverClient client = new AuthenticatedDriverClient(
                    URI.create("https://toxiproxy:18666"),
                    "real-driver-service-token", settings, testTrustContext())) {
                assertThat(client.ping("instance-discovery".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .status()).isEqualTo(200);
                toxiproxy(http, admin, "POST", "/proxies/" + proxyName + "/toxics",
                        "{\"name\":\"drop-first-response\",\"type\":\"limit_data\","
                        + "\"stream\":\"downstream\",\"toxicity\":1.0,\"attributes\":{\"bytes\":1}}");
                java.util.concurrent.ExecutorService retryExecutor = java.util.concurrent.Executors
                        .newSingleThreadExecutor();
                try {
                    java.util.concurrent.Future<DriverCommandResult> retry = retryExecutor.submit(
                            () -> client.submit(mission));
                    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    while (submitCalls.get() == before && System.nanoTime() < deadline) {
                        Thread.sleep(5);
                    }
                    assertThat(submitCalls).hasValue(before + 1);
                    Thread.sleep(100);
                    assertThat(retry).isNotDone();
                    toxiproxy(http, admin, "DELETE", "/proxies/" + proxyName
                            + "/toxics/drop-first-response", null);
                    DriverCommandResult submitted = retry.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(submitted.status()).isEqualTo(200);
                    String missionId = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(submitted.body()).get("id").asText();
                    assertThat(driver.getMissionInfo(missionId).getState()).isEqualTo(MissionState.SUBMITTED);
                } finally {
                    retryExecutor.shutdownNow();
                }
            }
        } finally {
            toxiproxy(http, admin, "DELETE", "/proxies/" + proxyName, null);
        }
        assertThat(submitCalls).hasValue(before + 1);
    }

    private static void toxiproxy(HttpClient http, URI admin, String method, String path, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(admin.resolve(path)).timeout(Duration.ofSeconds(5));
        if (body != null) {
            request.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
    }

    private AuthenticatedDriverClient driverClient() throws Exception {
        HttpClientSettings settings = new HttpClientSettings(4, 2, Duration.ofSeconds(20),
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(30), 2, Duration.ofMillis(5), Set.of(502, 503, 504));
        return new AuthenticatedDriverClient(URI.create("https://127.0.0.1:" + port),
                "real-driver-service-token", settings, testTrustContext());
    }

    private static HttpResponse<String> send(HttpClient client, URI uri, byte[] body, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Content-Type", MediaType.APPLICATION_XML_VALUE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (key != null) {
            request.header("Authorization", "Bearer real-driver-service-token")
                    .header(DriverCommandController.REQUEST_ID_HEADER, key)
                    .header(DriverCommandController.IDEMPOTENCY_KEY_HEADER, key);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient tlsHttpClient() throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .sslContext(testTrustContext()).build();
    }

    private static javax.net.ssl.SSLContext testTrustContext() throws Exception {
        Path trustStore = Path.of(requiredEnv("COSBENCH_HTTP_TEST_TRUSTSTORE_FILE"));
        char[] password = Files.readString(Path.of(
                requiredEnv("COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE"))).trim().toCharArray();
        try {
            return com.intel.cosbench.http.HttpTlsContext.create(null, trustStore, password);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static String testKeyStorePassword() {
        try {
            return Files.readString(Path.of(
                    requiredEnv("COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE"))).trim();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot read test keystore password", failure);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be supplied by the Docker harness");
        }
        return value;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        AtomicInteger submitCalls() {
            return new AtomicInteger();
        }

        @Bean
        DriverService legacyDriverService(AtomicInteger submitCalls) throws Exception {
            Path directory = Files.createTempDirectory("cosbench-http-real-driver-");
            Path config = directory.resolve("driver.conf");
            Files.writeString(config, "driver.name=http-test-driver\ndriver.url=http://localhost\n"
                    + "driver.mission_dir=" + directory.resolve("missions") + "\n");
            System.setProperty("cosbench.driver.config", config.toString());
            COSBDriverServiceFactory factory = new COSBDriverServiceFactory();
            factory.setAuthAPIs(new MockAuthService());
            factory.setStorageAPIs(new MockStorageService());
            factory.init();
            return new CountingDriverService(factory.getDriverService(), submitCalls);
        }
    }

    @TestConfiguration
    static class DriverHttpConfiguration {
        @Bean
        @Primary
        ServiceAuth serviceAuth() {
            return new ServiceAuth("real-driver-service-token");
        }

        @Bean
        @Primary
        IdempotencyStore idempotencyStore() {
            return new IdempotencyStore();
        }

        @Bean
        @Primary
        DriverCommandController.DriverCommandPort driverCommandPort(DriverService driver) {
            return new LegacyDriverCommandPort(driver);
        }
    }

    private static final class MockAuthService implements AuthAPIService {
        private final MockAuthFactory factory = new MockAuthFactory();
        @Override public AuthAPI getAuth(String type, com.intel.cosbench.config.Config config, Logger logger) {
            AuthAPI api = factory.getAuthAPI();
            api.init(config, logger);
            return api;
        }
    }

    private static final class MockStorageService implements StorageAPIService {
        private final MockStorageFactory factory = new MockStorageFactory();
        @Override public StorageAPI getStorage(String type, com.intel.cosbench.config.Config config, Logger logger) {
            StorageAPI api = factory.getStorageAPI();
            api.init(config, logger);
            return api;
        }
    }

    private static final class CountingDriverService implements DriverService {
        private final DriverService delegate;
        private final AtomicInteger submitCalls;
        private CountingDriverService(DriverService delegate, AtomicInteger submitCalls) {
            this.delegate = delegate;
            this.submitCalls = submitCalls;
        }
        @Override public String submit(XmlConfig config) {
            submitCalls.incrementAndGet();
            return delegate.submit(config);
        }
        @Override public void login(String id) { delegate.login(id); }
        @Override public void launch(String id) { delegate.launch(id); }
        @Override public void close(String id) { delegate.close(id); }
        @Override public void abort(String id) { delegate.abort(id); }
        @Override public com.intel.cosbench.model.DriverInfo getDriverInfo() { return delegate.getDriverInfo(); }
        @Override public com.intel.cosbench.model.MissionInfo getMissionInfo(String id) { return delegate.getMissionInfo(id); }
        @Override public com.intel.cosbench.model.MissionInfo[] getActiveMissions() { return delegate.getActiveMissions(); }
        @Override public com.intel.cosbench.model.MissionInfo[] getHistoryMissions() { return delegate.getHistoryMissions(); }
    }

}
