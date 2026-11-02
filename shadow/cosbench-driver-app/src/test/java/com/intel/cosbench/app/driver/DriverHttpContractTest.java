package com.intel.cosbench.app.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intel.cosbench.driver.service.COSBDriverService;
import com.intel.cosbench.model.MissionState;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = DriverApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DriverHttpContractTest.TestConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DriverHttpContractTest {
    private static final String TOKEN_ENV = "COSBENCH_DRIVER_HTTP_TEST_TOKEN";
    private static final String TOKEN = "driver-http-test-secret";
    private static final int SERVER_PORT = reservePort();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    private String cachedDriverInstance;

    @Autowired
    private COSBDriverService driver;

    @Autowired
    private DriverLifecycle lifecycle;

    @MockitoBean
    private SineioDriverProcessProbe processProbe;

    @DynamicPropertySource
    static void serverProperties(DynamicPropertyRegistry properties) {
        properties.add("server.port", () -> SERVER_PORT);
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
        properties.add("server.ssl.key-store-password", DriverHttpContractTest::keyStorePassword);
        properties.add("cosbench.providers.expected.storage", () -> "mock");
        properties.add("cosbench.providers.expected.auth", () -> "mock,failing");
    }

    @Test
    @Order(1)
    void allEightCommandsRunAgainstTheMockDriverAndPreserveResponses() throws Exception {
        HttpResponse<String> ping = post("ping", "1782057600000", null, TOKEN, null, Duration.ofSeconds(5));
        assertThat(ping.statusCode()).isEqualTo(200);
        JsonNode pingJson = json(ping);
        assertThat(pingJson.path("code").asInt()).isEqualTo(200);
        assertThat(pingJson.path("succ").asBoolean()).isTrue();
        assertThat(pingJson.path("name").asText()).isEqualTo("driver-http-contract-test");
        assertThat(pingJson.path("address").asText()).contains("/driver");
        assertThat(pingJson.path("timeStamp").asText()).isNotBlank();
        ObjectNode normalizedPing = ((ObjectNode) pingJson).deepCopy();
        normalizedPing.put("name", "127.0.0.1:18088");
        normalizedPing.put("address", "http://127.0.0.1:18088/driver");
        normalizedPing.put("timeStamp", "<dynamic epoch milliseconds>");
        assertThat(normalizedPing).isEqualTo(baseline("driver-ping.json"));

        byte[] mission = fixture("mock-noop-mission.xml");
        String submitKey = "http-submit-1";
        HttpResponse<String> submitted = write("submit", mission, submitKey, null, Duration.ofSeconds(5));
        assertThat(submitted.statusCode()).isEqualTo(200);
        JsonNode submitJson = json(submitted);
        assertThat(submitJson.path("succ").asBoolean()).isTrue();
        String missionId = submitJson.path("id").asText();
        assertThat(missionId).isNotBlank();
        assertLegacyResponse(submitted, "driver-lifecycle-submit.body");

        HttpResponse<String> replay = write("submit", mission, submitKey,
                submitted.headers().firstValue("X-COSBench-Driver-Instance").orElseThrow(), Duration.ofSeconds(5));
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(submitted.body());
        assertThat(replay.headers().firstValue("X-COSBench-Driver-Instance"))
                .isEqualTo(submitted.headers().firstValue("X-COSBench-Driver-Instance"));

        byte[] id = missionId.getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> login = write("login", id, "http-login-1", null, Duration.ofSeconds(5));
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(json(login).path("succ").asBoolean()).isTrue();
        assertLegacyResponse(login, "driver-lifecycle-login.body");

        HttpResponse<String> launch = write("launch", id, "http-launch-1", null, Duration.ofSeconds(5));
        assertThat(launch.statusCode()).isEqualTo(200);
        assertThat(json(launch).path("interval").asInt()).isEqualTo(1);
        assertLegacyResponse(launch, "driver-lifecycle-launch.body");

        awaitState(missionId, MissionState.FINISHED, Duration.ofSeconds(10));
        HttpResponse<String> query = post("query", missionId, null, TOKEN, null, Duration.ofSeconds(5));
        assertThat(query.statusCode()).isEqualTo(200);
        JsonNode queryJson = json(query);
        assertThat(queryJson.path("running").asBoolean()).isFalse();
        assertThat(queryJson.path("report").isArray()).isTrue();
        assertThat(queryJson.path("version").isNumber()).isTrue();
        assertLegacyResponse(query, "driver-lifecycle-query.body");

        HttpResponse<String> close = write("close", id, "http-close-1", null, Duration.ofSeconds(5));
        assertThat(close.statusCode()).isEqualTo(200);
        assertThat(json(close).path("state").asText()).isEqualTo("ACCOMPLISHED");
        assertThat(json(close).path("errorStatistics").isObject()).isTrue();
        assertThat(json(close).path("driverLog").asText()).isNotBlank();
        assertLegacyResponse(close, "driver-lifecycle-close.body", true);
        assertThat(json(close).path("report").get(0).path("sampleCount").asInt()).isEqualTo(10);
        assertThat(json(close).path("report").get(0).path("totalSampleCount").asInt()).isEqualTo(10);
        assertThat(json(close).path("report").get(0).path("byteCount").asLong()).isEqualTo(640);
        assertThat(driver.getMissionInfo(missionId).getState()).isEqualTo(MissionState.ACCOMPLISHED);

        HttpResponse<String> lateReplay = write("submit", mission, submitKey, null, Duration.ofSeconds(5));
        assertThat(lateReplay.body()).isEqualTo(submitted.body());

        HttpResponse<String> trigger = write("trigger",
                "enableTrigger,baseline-trigger.sh,1".getBytes(StandardCharsets.UTF_8), "http-trigger-on-1", null,
                Duration.ofSeconds(5));
        assertThat(trigger.statusCode()).isEqualTo(200);
        JsonNode triggerJson = json(trigger);
        String triggerPid = triggerJson.path("pid").asText();
        assertThat(Long.parseLong(triggerPid)).isPositive();
        assertThat(triggerJson.path("scriptLog").asText()).isEqualTo("baseline-trigger-ok\n");
        assertLegacyResponse(trigger, "driver-trigger-noop.body");

        HttpResponse<String> stoppedTrigger = write("trigger",
                ("killTrigger," + triggerPid + ",baseline-trigger.sh").getBytes(StandardCharsets.UTF_8),
                "http-trigger-off-1", null, Duration.ofSeconds(5));
        assertThat(stoppedTrigger.statusCode()).isEqualTo(200);
        assertThat(json(stoppedTrigger).path("pid").asText()).isEqualTo("0");
        assertThat(json(stoppedTrigger).path("scriptLog").asText()).isEqualTo("baseline-trigger-ok\n");

        HttpResponse<String> secretOutput = write("trigger",
                ("enableTrigger,echo-arg.sh," + TOKEN).getBytes(StandardCharsets.UTF_8),
                "http-trigger-redaction-1", null, Duration.ofSeconds(5));
        assertThat(secretOutput.statusCode()).isEqualTo(200);
        assertThat(secretOutput.body()).doesNotContain(TOKEN);
        assertThat(json(secretOutput).path("scriptLog").asText()).contains("baseline-trigger-ok:[REDACTED]");
    }

    @Test
    @Order(1)
    void rejectsMissingOrInvalidServiceCredentialsAndPageCookies() throws Exception {
        byte[] body = fixture("mock-noop-mission.xml");
        HttpResponse<String> missing = post("submit", body, "", null, null, Duration.ofSeconds(5));
        HttpResponse<String> invalid = post("submit", body, "", "wrong-token", null, Duration.ofSeconds(5));
        HttpResponse<String> cookieOnly = post("submit", body, "", null, "JSESSIONID=page-session",
                Duration.ofSeconds(5));

        for (HttpResponse<String> response : List.of(missing, invalid, cookieOnly)) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("service authentication failed");
            assertThat(response.body()).doesNotContain(TOKEN);
        }
        assertThat(json(missing)).isEqualTo(baseline("driver-service-auth-missing.json"));
        assertThat(json(invalid)).isEqualTo(baseline("driver-service-auth-invalid.json"));

        HttpRequest untrusted = request("ping", "1782057600000".getBytes(StandardCharsets.UTF_8), null, null,
                Duration.ofSeconds(5));
        assertThatThrownBy(() -> HttpClient.newHttpClient().send(untrusted, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(SSLHandshakeException.class);
    }

    @Test
    @Order(1)
    void validatesBadInputsAndInvalidMissionTransitions() throws Exception {
        HttpResponse<String> invalidSubmit = write("submit", fixture("invalid-submit.xml"), "bad-submit-1", null,
                Duration.ofSeconds(5));
        assertThat(invalidSubmit.statusCode()).isEqualTo(400);
        assertThat(json(invalidSubmit).path("error").asText()).isEqualTo("must specify 'workers' for a mission");
        assertThat(json(invalidSubmit)).isEqualTo(baseline("driver-submit-invalid.json"));

        HttpResponse<String> missingMission = post("query", "missing-mission", null, TOKEN, null,
                Duration.ofSeconds(5));
        assertThat(missingMission.statusCode()).isEqualTo(404);
        assertThat(json(missingMission).path("error").asText()).isEqualTo("mission not found");
        assertThat(json(missingMission)).isEqualTo(baseline("driver-mission-not-found.json"));

        String missionId = json(write("submit", fixture("long-mock-mission.xml"), "abort-submit-1", null,
                Duration.ofSeconds(5))).path("id").asText();
        byte[] id = missionId.getBytes(StandardCharsets.UTF_8);
        assertThat(write("login", id, "abort-login-1", null, Duration.ofSeconds(5)).statusCode()).isEqualTo(200);
        assertThat(write("launch", id, "abort-launch-1", null, Duration.ofSeconds(5)).statusCode()).isEqualTo(200);
        awaitState(missionId, MissionState.LAUNCHED, Duration.ofSeconds(5));
        awaitSamples(missionId, Duration.ofSeconds(10));

        HttpResponse<String> invalidClose = write("close", id, "abort-close-1", null, Duration.ofSeconds(5));
        assertThat(invalidClose.statusCode()).isEqualTo(409);
        assertThat(json(invalidClose).path("error").asText()).contains("mission should be in the state of finished");

        HttpResponse<String> abort = write("abort", id, "abort-command-1", null, Duration.ofSeconds(5));
        assertThat(abort.statusCode()).isEqualTo(200);
        assertThat(json(abort).path("driverLog").isTextual()).isTrue();
        assertLegacyResponse(abort, "driver-abort-abort.body");
        assertThat(driver.getMissionInfo(missionId).getState()).isEqualTo(MissionState.ABORTED);

        HttpResponse<String> failedReplay = write("close", id, "abort-close-1", null, Duration.ofSeconds(5));
        assertThat(failedReplay.statusCode()).isEqualTo(invalidClose.statusCode());
        assertThat(failedReplay.body()).isEqualTo(invalidClose.body());
    }

    @Test
    @Order(1)
    void providerLoginFailureIsASeparateBusinessResponseAndSlowLoginCanBeReplayed() throws Exception {
        String failedMissionId = json(write("submit", fixture("failing-auth-mission.xml"), "failing-submit-1", null,
                Duration.ofSeconds(5))).path("id").asText();
        HttpResponse<String> failedLogin = write("login", failedMissionId.getBytes(StandardCharsets.UTF_8),
                "failing-login-1", null, Duration.ofSeconds(5));
        assertThat(failedLogin.statusCode()).isEqualTo(200);
        assertThat(json(failedLogin).path("succ").asBoolean()).isFalse();
        assertThat(json(failedLogin).path("error").asText()).isEqualTo("fail to login - see log for details");
        assertThat(driver.getMissionInfo(failedMissionId).getState()).isEqualTo(MissionState.TERMINATED);
        HttpResponse<String> failedReplay = write("login", failedMissionId.getBytes(StandardCharsets.UTF_8),
                "failing-login-1", null, Duration.ofSeconds(5));
        assertThat(failedReplay.body()).isEqualTo(failedLogin.body());

        String slowMissionId = json(write("submit", fixture("slow-auth-mission.xml"), "slow-submit-1", null,
                Duration.ofSeconds(5))).path("id").asText();
        byte[] id = slowMissionId.getBytes(StandardCharsets.UTF_8);
        HttpRequest timedLogin = request("login", id, "slow-login-1", null, Duration.ofMillis(75));
        assertThatThrownBy(() -> client().send(timedLogin, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(java.net.http.HttpTimeoutException.class);

        HttpResponse<String> replay = write("login", id, "slow-login-1", null, Duration.ofSeconds(5));
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json(replay).path("succ").asBoolean()).isTrue();
        assertThat(driver.getMissionInfo(slowMissionId).getState()).isEqualTo(MissionState.AUTHED);
    }

    @Test
    @Order(1)
    void enforcesIdentityLimitsAndProtectsConcurrentAndConflictingReplays() throws Exception {
        byte[] mission = fixture("mock-noop-mission.xml");
        String maximumKey = "k".repeat(200);
        HttpResponse<String> acceptedBoundary = write("submit", mission, maximumKey, null, Duration.ofSeconds(5));
        assertThat(acceptedBoundary.statusCode()).isEqualTo(200);

        HttpResponse<String> oversized = write("submit", mission, "k".repeat(201), null, Duration.ofSeconds(5));
        assertThat(oversized.statusCode()).isEqualTo(400);

        HttpRequest missingIdentity = HttpRequest.newBuilder(uri("submit"))
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.ofByteArray(mission)).build();
        HttpResponse<String> identityResponse = client().send(missingIdentity, HttpResponse.BodyHandlers.ofString());
        assertThat(identityResponse.statusCode()).isEqualTo(400);
        assertThat(json(identityResponse)).isEqualTo(baseline("driver-service-identity-missing.json"));

        HttpRequest mismatchedIdentity = HttpRequest.newBuilder(uri("submit"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", "key-one").header("X-COSBench-Request-Id", "key-two")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mission)).build();
        assertThat(client().send(mismatchedIdentity, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);

        String concurrentKey = "concurrent-submit-1";
        try (var executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(
                    () -> sendUnchecked(request("submit", mission, concurrentKey, null, Duration.ofSeconds(10))),
                    executor);
            CompletableFuture<HttpResponse<String>> second = CompletableFuture.supplyAsync(
                    () -> sendUnchecked(request("submit", mission, concurrentKey, null, Duration.ofSeconds(10))),
                    executor);
            HttpResponse<String> firstResponse = first.join();
            HttpResponse<String> secondResponse = second.join();
            assertThat(firstResponse.statusCode()).isEqualTo(200);
            assertThat(secondResponse.statusCode()).isEqualTo(200);
            assertThat(secondResponse.body()).isEqualTo(firstResponse.body());

            HttpResponse<String> conflict = write("submit", "different request".getBytes(StandardCharsets.UTF_8),
                    concurrentKey, null, Duration.ofSeconds(5));
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(json(conflict).path("error").asText()).isEqualTo("idempotency key conflict");
            assertThat(json(conflict)).isEqualTo(baseline("driver-idempotency-conflict.json"));

            String oldInstance = "old-driver-instance";
            HttpResponse<String> ambiguous = write("submit", fixture("mock-noop-mission.xml"),
                    "post-restart-submit-1", oldInstance, Duration.ofSeconds(5));
            assertThat(ambiguous.statusCode()).isEqualTo(409);
            assertThat(json(ambiguous).path("error").asText())
                    .isEqualTo("Driver instance changed; write result may be unknown");
        }

        HttpResponse<String> initialSuccess = write("submit", mission, "capacity-first", null,
                Duration.ofSeconds(5));
        assertThat(initialSuccess.statusCode()).isEqualTo(200);
        for (int index = 0; index < 100; index++) {
            assertThat(write("submit", fixture("invalid-submit.xml"), "capacity-" + index, null,
                    Duration.ofSeconds(5)).statusCode()).isEqualTo(400);
        }
        HttpResponse<String> retainedSuccess = write("submit", mission, "capacity-first", null,
                Duration.ofSeconds(5));
        assertThat(retainedSuccess.statusCode()).isEqualTo(initialSuccess.statusCode());
        assertThat(retainedSuccess.body()).isEqualTo(initialSuccess.body());
    }

    @Test
    @Order(2)
    @DirtiesContext
    void shutdownRejectsEveryCommand() throws Exception {
        lifecycle.closeAdmission();
        assertThat(driver.isAccepting()).isFalse();
        for (String command : List.of("ping", "submit", "login", "launch", "query", "trigger", "close", "abort")) {
            HttpResponse<String> response = write(command, "shutdown-test", "shutdown-" + command, null,
                    Duration.ofSeconds(5));
            assertThat(response.statusCode()).as(command).isEqualTo(503);
            assertThat(response.body()).doesNotContain(TOKEN);
        }
    }

    private HttpResponse<String> write(String command, byte[] body, String key, String instance,
            Duration timeout) throws Exception {
        return client().send(request(command, body, key, instance, timeout), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> write(String command, String body, String key, String instance,
            Duration timeout) throws Exception {
        return write(command, body.getBytes(StandardCharsets.UTF_8), key, instance, timeout);
    }

    private HttpResponse<String> post(String command, byte[] body, String key, String token, String cookie,
            Duration timeout) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(command)).timeout(timeout)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (key != null && !key.isEmpty()) {
            request.header("Idempotency-Key", key).header("X-COSBench-Request-Id", key);
        }
        return client().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String command, String body, String key, String token, String cookie,
            Duration timeout) throws Exception {
        return post(command, body.getBytes(StandardCharsets.UTF_8), key, token, cookie, timeout);
    }

    private HttpRequest request(String command, byte[] body, String key, String instance, Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(command)).timeout(timeout)
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (key != null) {
            request.header("Idempotency-Key", key).header("X-COSBench-Request-Id", key);
            if (instance == null) {
                instance = driverInstance();
            }
        }
        if (instance != null) {
            request.header("X-COSBench-Driver-Instance", instance);
        }
        return request.build();
    }

    private synchronized String driverInstance() {
        if (cachedDriverInstance == null) {
            try {
                HttpRequest ping = HttpRequest.newBuilder(uri("ping"))
                        .header("Authorization", "Bearer " + TOKEN)
                        .POST(HttpRequest.BodyPublishers.ofString("1782057600000")).build();
                cachedDriverInstance = client().send(ping, HttpResponse.BodyHandlers.ofString()).headers()
                        .firstValue("X-COSBench-Driver-Instance").orElseThrow();
            } catch (Exception failure) {
                throw new IllegalStateException("cannot discover Driver instance", failure);
            }
        }
        return cachedDriverInstance;
    }

    private URI uri(String command) {
        return URI.create("https://127.0.0.1:" + port + "/driver/i/" + command + ".command");
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).sslContext(trustContext())
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    private static SSLContext trustContext() {
        char[] password = keyStorePassword().toCharArray();
        try {
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(Path.of(requiredEnv("COSBENCH_HTTP_TEST_TRUSTSTORE_FILE")))) {
                trustStore.load(input, password);
            }
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), new SecureRandom());
            return context;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot load test Driver truststore", failure);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String keyStorePassword() {
        try {
            return Files.readString(Path.of(requiredEnv("COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE"))).trim();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot read test Driver keystore password", failure);
        }
    }

    private static byte[] fixture(String name) throws Exception {
        try (InputStream input = DriverHttpContractTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (input == null) {
                throw new IllegalArgumentException("missing Driver HTTP fixture: " + name);
            }
            return input.readAllBytes();
        }
    }

    private void awaitState(String id, MissionState expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (driver.getMissionInfo(id).getState() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(driver.getMissionInfo(id).getState()).isEqualTo(expected);
    }

    private void awaitSamples(String id, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean sampled;
        do {
            sampled = Arrays.stream(driver.getMissionInfo(id).getSnapshot().getReport().getAllMetrics())
                    .anyMatch(metric -> metric.getSampleCount() > 0);
            if (!sampled) {
                Thread.sleep(25);
            }
        } while (!sampled && System.nanoTime() < deadline);
        assertThat(sampled).as("mission has produced a sample").isTrue();
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private static void assertLegacyResponse(HttpResponse<String> response, String captured) throws Exception {
        assertLegacyResponse(response, captured, false);
    }

    private static void assertLegacyResponse(HttpResponse<String> response, String captured, boolean preserveCounts)
            throws Exception {
        JsonNode expected = JSON.readTree(fixture("legacy-responses/" + captured));
        JsonNode actual = json(response);
        normalizeRuntimeValues(expected, preserveCounts);
        normalizeRuntimeValues(actual, preserveCounts);
        assertThat(actual).as(captured).isEqualTo(expected);
    }

    private static void normalizeRuntimeValues(JsonNode node, boolean preserveCounts) {
        if (node.isArray()) {
            node.forEach(value -> normalizeRuntimeValues(value, preserveCounts));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        ObjectNode object = (ObjectNode) node;
        object.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (key.equals("id") || key.equals("pid") || key.equals("driverLog")) {
                assertThat(value.isTextual()).as(key).isTrue();
                object.put(key, "<dynamic>");
            } else if (key.equals("histoData") || key.matches("_[0-9]+")) {
                assertThat(value.isArray()).as(key).isTrue();
                object.put(key, value.size());
            } else if (value.isNumber()
                    && !List.of("code", "interval", "workerCount", "ratio").contains(key)
                    && !(preserveCounts
                            && List.of("sampleCount", "totalSampleCount", "byteCount").contains(key))) {
                object.put(key, 0);
            } else {
                normalizeRuntimeValues(value, preserveCounts);
            }
        });
    }

    private static JsonNode baseline(String name) throws Exception {
        return JSON.readTree(Files.readString(Path.of(System.getProperty("basedir"), "..", "..", ".scratch",
                "scheme3-migration", "baseline", "fixtures", "expected", name)));
    }

    private static int reservePort() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot reserve a test Driver port", failure);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be supplied by the Docker test harness");
        }
        return value;
    }

    private static HttpResponse<String> sendUnchecked(HttpRequest request) {
        try {
            return client().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception failure) {
            throw new IllegalStateException("Driver HTTP request failed", failure);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        Path installationDirectory() throws Exception {
            Path root = Files.createTempDirectory("cosbench-driver-http-");
            Path scripts = Files.createDirectories(root.resolve("scripts"));
            Files.copy(Path.of(System.getProperty("basedir"), "..", "..", ".scratch", "scheme3-migration",
                    "baseline", "fixtures", "trigger-noop.sh"), scripts.resolve("baseline-trigger.sh"));
            Files.writeString(scripts.resolve("echo-arg.sh"),
                    "#!/bin/sh\nprintf 'baseline-trigger-ok:%s\\n' \"$1\"\n");
            return root;
        }

        @Bean
        com.intel.cosbench.config.runtime.ServiceConfig serviceConfig(Path installationDirectory) throws Exception {
            Path config = Files.createDirectories(installationDirectory.resolve("conf")).resolve("driver.yml");
            String keyStore = Path.of(requiredEnv("COSBENCH_HTTP_TEST_KEYSTORE_FILE")).toUri().toString();
            String yaml = """
                    schemaVersion: 1
                    role: driver
                    name: driver-http-contract-test
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
                    logging:
                      level: INFO
                      file: log/system.log
                    shutdown:
                      gracePeriod: 5s
                    """.formatted(SERVER_PORT, TOKEN_ENV, keyStore);
            Files.writeString(config, yaml);
            return com.intel.cosbench.config.runtime.VersionedConfigLoader.load(config,
                    reference -> TOKEN_ENV.equals(reference) ? TOKEN : null);
        }
    }
}
