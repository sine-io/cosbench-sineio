package com.intel.cosbench.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class DriverCommandControllerTest {

    private static final ServiceAuth AUTH = new ServiceAuth("secret-token");

    @Test
    void serviceTokenDoesNotChangeMissionIdentifiers() throws Exception {
        DriverCommandController controller = new DriverCommandController(new ServiceAuth("M"),
                port(new AtomicInteger(), 200, "{\"id\":\"M123456789\",\"succ\":true,\"code\":200}"),
                new IdempotencyStore());
        Map<String, String> headers = Map.of("Authorization", "Bearer M", "Idempotency-Key", "request-1",
                "X-COSBench-Request-Id", "request-1", "X-COSBench-Driver-Instance",
                controller.ping(Map.of("Authorization", "Bearer M"), new byte[0]).getHeaders()
                        .getFirst(DriverCommandController.DRIVER_INSTANCE_HEADER));

        ResponseEntity<String> submitted = controller.submit(headers, "mission".getBytes());

        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(submitted.getBody()).path("id").asText())
                .isEqualTo("M123456789");
    }

    @Test
    void redactsSecretValuesWithoutChangingJsonFieldNamesOrSyntax() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ServiceAuth fieldNameToken = new ServiceAuth("succ");
        String fieldNameResult = fieldNameToken.redact("{\"succ\":true,\"error\":\"succ leaked\"}");
        assertThat(mapper.readTree(fieldNameResult).path("succ").asBoolean()).isTrue();
        assertThat(mapper.readTree(fieldNameResult).path("error").asText()).isEqualTo("[REDACTED] leaked");

        String tokenValueResult = AUTH.redact("{\"name\":\"secret-token\",\"id\":\"M123456789\"}");
        assertThat(mapper.readTree(tokenValueResult).path("name").asText()).isEqualTo("[REDACTED]");
        assertThat(mapper.readTree(tokenValueResult).path("id").asText()).isEqualTo("M123456789");
        String embeddedTokenResult = AUTH.redact("{\"name\":\"driver-secret-token-node\"}");
        assertThat(mapper.readTree(embeddedTokenResult).path("name").asText())
                .isEqualTo("driver-[REDACTED]-node");

        ServiceAuth escapedToken = new ServiceAuth("token\"value");
        String escapedResult = escapedToken.redact("{\"succ\":false,\"error\":\"token\\\"value\"}");
        assertThat(mapper.readTree(escapedResult).path("succ").asBoolean()).isFalse();
        assertThat(mapper.readTree(escapedResult).path("error").asText()).isEqualTo("[REDACTED]");

        ServiceAuth markerToken = new ServiceAuth("REDACTED");
        String markerResult = markerToken.redact("{\"error\":\"REDACTED\"}");
        assertThat(markerResult).doesNotContain("REDACTED");
        assertThat(markerToken.redact(markerResult)).isEqualTo(markerResult);
    }

    @Test
    void pageCookieCannotAuthorizeServiceCommand() {
        AtomicInteger executions = new AtomicInteger();
        DriverCommandController controller = controller(port(executions, 200, "ok"));

        ResponseEntity<String> response = controller.submit(Map.of("Cookie", "JSESSIONID=page"),
                "mission".getBytes());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(executions).hasValue(0);
    }

    @Test
    void validTokenRequiresRequestIdentityForWrites() {
        DriverCommandController controller = controller(port(new AtomicInteger(), 200, "ok"));

        ResponseEntity<String> response = controller.submit(Map.of("Authorization", "Bearer secret-token"),
                "mission".getBytes());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("request identity is required");
    }

    @Test
    void requestIdentityHeadersMustMatch() {
        DriverCommandController controller = controller(port(new AtomicInteger(), 200, "ok"));
        Map<String, String> headers = Map.of("Authorization", "Bearer secret-token",
                "Idempotency-Key", "key-a", "X-COSBench-Request-Id", "request-b");
        ResponseEntity<String> response = controller.submit(headers, "mission".getBytes());
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("headers must match");
    }

    @Test
    void writeRequiresKnownDriverInstanceBeforeItCanExecute() {
        AtomicInteger executions = new AtomicInteger();
        DriverCommandController controller = controller(port(executions, 200, "ok"));
        Map<String, String> headers = Map.of("Authorization", "Bearer secret-token",
                "Idempotency-Key", "request-1", "X-COSBench-Request-Id", "request-1");

        ResponseEntity<String> rejected = controller.submit(headers, "mission".getBytes());

        assertThat(rejected.getStatusCode().value()).isEqualTo(400);
        assertThat(rejected.getBody()).contains("Driver instance is required");
        assertThat(executions).hasValue(0);
    }

    @Test
    void duplicateWriteReturnsOriginalResponseAndRunsOnce() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DriverCommandController.DriverCommandPort port = new DriverCommandController.DriverCommandPort() {
            @Override public DriverCommandResult submit(byte[] body) {
                executions.incrementAndGet();
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return new DriverCommandResult(201, "{\"id\":\"m-1\"}");
            }
            @Override public DriverCommandResult ping(byte[] body) { return ok(); }
            @Override public DriverCommandResult login(byte[] body) { return ok(); }
            @Override public DriverCommandResult launch(byte[] body) { return ok(); }
            @Override public DriverCommandResult query(byte[] body) { return ok(); }
            @Override public DriverCommandResult close(byte[] body) { return ok(); }
            @Override public DriverCommandResult abort(byte[] body) { return ok(); }
            @Override public DriverCommandResult trigger(byte[] body) { return ok(); }
        };
        DriverCommandController controller = controller(port);
        Map<String, String> headers = identity(controller);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> controller.submit(headers, "mission".getBytes()));
            entered.await();
            var second = executor.submit(() -> controller.submit(headers, "mission".getBytes()));
            release.countDown();
            assertThat(first.get().getStatusCode().value()).isEqualTo(201);
            assertThat(second.get().getStatusCode().value()).isEqualTo(201);
            assertThat(second.get().getBody()).isEqualTo("{\"id\":\"m-1\"}");
            assertThat(executions).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameKeyWithChangedPayloadIsConflict() {
        AtomicInteger executions = new AtomicInteger();
        DriverCommandController controller = controller(port(executions, 200, "ok"));
        Map<String, String> headers = identity(controller);

        assertThat(controller.submit(headers, "one".getBytes()).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> conflict = controller.submit(headers, "two".getBytes());

        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(executions).hasValue(1);
    }

    @Test
    void sameKeyOnDifferentCommandsUsesSeparateIdentityScopes() {
        AtomicInteger executions = new AtomicInteger();
        DriverCommandController.DriverCommandPort port = new DriverCommandController.DriverCommandPort() {
            @Override public DriverCommandResult submit(byte[] body) {
                executions.incrementAndGet();
                return ok();
            }
            @Override public DriverCommandResult ping(byte[] body) { return ok(); }
            @Override public DriverCommandResult login(byte[] body) { return ok(); }
            @Override public DriverCommandResult launch(byte[] body) { return ok(); }
            @Override public DriverCommandResult query(byte[] body) { return ok(); }
            @Override public DriverCommandResult close(byte[] body) {
                executions.incrementAndGet();
                return ok();
            }
            @Override public DriverCommandResult abort(byte[] body) { return ok(); }
            @Override public DriverCommandResult trigger(byte[] body) { return ok(); }
        };
        DriverCommandController controller = controller(port);
        Map<String, String> headers = identity(controller);

        assertThat(controller.submit(headers, "mission".getBytes()).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.close(headers, "mission".getBytes()).getStatusCode().value()).isEqualTo(200);
        assertThat(executions).hasValue(2);
    }

    @Test
    void staleDriverInstanceCannotReplayAnAmbiguousWrite() {
        AtomicInteger executions = new AtomicInteger();
        DriverCommandController firstProcess = controller(port(executions, 200, "accepted"));
        Map<String, String> headers = identity(firstProcess);
        ResponseEntity<String> accepted = firstProcess.submit(headers, "mission".getBytes());
        String firstInstance = accepted.getHeaders()
                .getFirst(DriverCommandController.DRIVER_INSTANCE_HEADER);
        assertThat(firstInstance).isNotBlank();

        DriverCommandController restartedProcess = controller(port(executions, 200, "duplicate"));
        Map<String, String> staleHeaders = new java.util.HashMap<>(headers);
        staleHeaders.put(DriverCommandController.DRIVER_INSTANCE_HEADER, firstInstance);
        ResponseEntity<String> unknown = restartedProcess.submit(staleHeaders, "mission".getBytes());

        assertThat(unknown.getStatusCode().value()).isEqualTo(409);
        assertThat(unknown.getBody()).contains(DriverCommandController.DRIVER_INSTANCE_CHANGED);
        assertThat(unknown.getHeaders().getFirst(DriverCommandController.DRIVER_INSTANCE_HEADER))
                .isNotEqualTo(firstInstance);
        assertThat(executions).hasValue(1);
    }

    @Test
    void failedWriteIsReplayedWithoutASecondExecution() {
        AtomicInteger executions = new AtomicInteger();
        DriverCommandController controller = controller(port(executions, 503, "temporary"));
        Map<String, String> headers = identity(controller);

        ResponseEntity<String> first = controller.submit(headers, "one".getBytes());
        ResponseEntity<String> replay = controller.submit(headers, "one".getBytes());

        assertThat(first.getStatusCode().value()).isEqualTo(503);
        assertThat(replay.getStatusCode().value()).isEqualTo(503);
        assertThat(replay.getBody()).isEqualTo("temporary");
        assertThat(executions).hasValue(1);
    }

    @Test
    void readQueryUsesTokenButDoesNotRequireIdempotencyHeaders() {
        AtomicInteger executions = new AtomicInteger();
        DriverCommandController controller = controller(port(executions, 200, "query"));

        ResponseEntity<String> response = controller.query(Map.of("Authorization", "Bearer secret-token"),
                "mission".getBytes());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("query");
        assertThat(executions).hasValue(1);
    }

    private static DriverCommandController controller(DriverCommandController.DriverCommandPort port) {
        return new DriverCommandController(AUTH, port, new IdempotencyStore());
    }

    private static DriverCommandController.DriverCommandPort port(AtomicInteger executions, int status, String body) {
        return new DriverCommandController.DriverCommandPort() {
            @Override public DriverCommandResult submit(byte[] ignored) {
                executions.incrementAndGet();
                return new DriverCommandResult(status, body);
            }
            @Override public DriverCommandResult ping(byte[] ignored) { return new DriverCommandResult(status, body); }
            @Override public DriverCommandResult login(byte[] ignored) { return new DriverCommandResult(status, body); }
            @Override public DriverCommandResult launch(byte[] ignored) { return new DriverCommandResult(status, body); }
            @Override public DriverCommandResult query(byte[] ignored) {
                executions.incrementAndGet();
                return new DriverCommandResult(status, body);
            }
            @Override public DriverCommandResult close(byte[] ignored) { return new DriverCommandResult(status, body); }
            @Override public DriverCommandResult abort(byte[] ignored) { return new DriverCommandResult(status, body); }
            @Override public DriverCommandResult trigger(byte[] ignored) { return new DriverCommandResult(status, body); }
        };
    }

    private static Map<String, String> identity(DriverCommandController controller) {
        return Map.of("Authorization", "Bearer secret-token", "Idempotency-Key", "request-1",
                "X-COSBench-Request-Id", "request-1", "X-COSBench-Driver-Instance",
                controller.ping(Map.of("Authorization", "Bearer secret-token"), new byte[0]).getHeaders()
                        .getFirst(DriverCommandController.DRIVER_INSTANCE_HEADER));
    }

    private static DriverCommandResult ok() {
        return new DriverCommandResult(200, "ok");
    }
}
