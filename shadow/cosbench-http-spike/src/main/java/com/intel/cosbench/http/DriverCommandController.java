package com.intel.cosbench.http;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Jakarta adapter for the legacy Driver /i contract. The service remains an
 * injected domain port; this controller owns only transport/auth/idempotency.
 */
@RestController
@RequestMapping("/i")
public final class DriverCommandController {

    public static final String SERVICE_TOKEN_HEADER = "Authorization";
    public static final String REQUEST_ID_HEADER = "X-COSBench-Request-Id";
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String DRIVER_INSTANCE_HEADER = "X-COSBench-Driver-Instance";
    public static final String DRIVER_INSTANCE_CHANGED = "Driver instance changed; write result may be unknown";

    private final ServiceAuth serviceAuth;
    private final DriverCommandPort driver;
    private final IdempotencyStore idempotency;
    private final String driverInstanceId = UUID.randomUUID().toString();

    public DriverCommandController(ServiceAuth serviceAuth, DriverCommandPort driver,
            IdempotencyStore idempotency) {
        this.serviceAuth = serviceAuth;
        this.driver = driver;
        this.idempotency = idempotency;
    }

    public static DriverCommandController from(com.intel.cosbench.config.runtime.ServiceConfig config,
            DriverCommandPort driver, IdempotencyStore idempotency) {
        if (config == null || config.auth() == null || config.auth().token() == null) {
            throw new IllegalArgumentException("service configuration requires a resolved auth token");
        }
        return new DriverCommandController(new ServiceAuth(config.auth().token().value()), driver, idempotency);
    }

    @PostMapping(value = "/ping.command", consumes = MediaType.TEXT_PLAIN_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> ping(@RequestHeader Map<String, String> headers,
            @RequestBody(required = false) byte[] body) {
        return command("ping", headers, body, false, () -> driver.ping(bodyOrEmpty(body)));
    }

    @PostMapping(value = "/ping.command", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> pingBinary(@RequestHeader Map<String, String> headers,
            @RequestBody(required = false) byte[] body) {
        return command("ping", headers, body, false, () -> driver.ping(bodyOrEmpty(body)));
    }

    @PostMapping(value = "/submit.command", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> submit(@RequestHeader Map<String, String> headers,
            @RequestBody(required = false) byte[] body) {
        return command("submit", headers, body, true, () -> driver.submit(bodyOrEmpty(body)));
    }

    @PostMapping(value = "/login.command", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> login(@RequestHeader Map<String, String> headers,
            @RequestBody(required = false) byte[] body) {
        return command("login", headers, body, true, () -> driver.login(bodyOrEmpty(body)));
    }

    @PostMapping(value = "/launch.command", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> launch(@RequestHeader Map<String, String> headers,
            @RequestBody(required = false) byte[] body) {
        return command("launch", headers, body, true, () -> driver.launch(bodyOrEmpty(body)));
    }

    @PostMapping(value = "/query.command", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> query(@RequestHeader Map<String, String> headers,
            @RequestBody(required = false) byte[] body) {
        return command("query", headers, body, false, () -> driver.query(bodyOrEmpty(body)));
    }

    @PostMapping(value = "/close.command", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> close(@RequestHeader Map<String, String> headers,
            @RequestBody(required = false) byte[] body) {
        return command("close", headers, body, true, () -> driver.close(bodyOrEmpty(body)));
    }

    @PostMapping(value = "/abort.command", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> abort(@RequestHeader Map<String, String> headers,
            @RequestBody(required = false) byte[] body) {
        return command("abort", headers, body, true, () -> driver.abort(bodyOrEmpty(body)));
    }

    @PostMapping(value = "/trigger.command", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> trigger(@RequestHeader Map<String, String> headers,
            @RequestBody(required = false) byte[] body) {
        return command("trigger", headers, body, true, () -> driver.trigger(bodyOrEmpty(body)));
    }

    private ResponseEntity<String> command(String command, Map<String, String> headers,
            byte[] body, boolean write, Command commandAction) {
        String token = header(headers, SERVICE_TOKEN_HEADER);
        if (token == null || !token.startsWith("Bearer ") || !serviceAuth.matches(token.substring(7))) {
            return response(401, "{\"succ\":false,\"error\":\"service authentication failed\"}");
        }
        String expectedInstance = header(headers, DRIVER_INSTANCE_HEADER);
        if (write && expectedInstance != null && !driverInstanceId.equals(expectedInstance)) {
            return response(409, "{\"succ\":false,\"error\":\""
                    + DRIVER_INSTANCE_CHANGED + "\"}");
        }
        byte[] payload = bodyOrEmpty(body);
        if (!write) {
            return execute(commandAction);
        }
        String key = firstHeader(headers, IDEMPOTENCY_KEY_HEADER);
        String requestId = firstHeader(headers, REQUEST_ID_HEADER);
        if (key == null || key.isBlank() || key.length() > 200
                || requestId == null || requestId.isBlank() || requestId.length() > 200) {
            return response(400, "{\"succ\":false,\"error\":\"request identity is required\"}");
        }
        if (!key.equals(requestId)) {
            return response(400, "{\"succ\":false,\"error\":\"request identity headers must match\"}");
        }
        String fingerprint = RequestFingerprint.sha256(command, payload);
        try {
            IdempotentResult result = idempotency.execute(
                    IdempotencyKey.scoped(serviceAuth.principalId(), command, key), fingerprint,
                    () -> toIdempotent(execute(commandAction), fingerprint));
            return response(result.status(), result.body());
        } catch (IdempotencyConflictException conflict) {
            return response(409, "{\"succ\":false,\"error\":\"idempotency key conflict\"}");
        } catch (RuntimeException failure) {
            return response(500, "{\"succ\":false,\"error\":\"request result unavailable\"}");
        }
    }

    private ResponseEntity<String> execute(Command action) {
        try {
            DriverCommandResult result = action.run();
            return response(result.status(), result.body());
        } catch (DriverCommandException failure) {
            return response(failure.status(), failure.getMessage());
        } catch (RuntimeException failure) {
            return response(500, "{\"succ\":false,\"error\":\"internal driver error\"}");
        }
    }

    private ResponseEntity<String> response(int status, String body) {
        return ResponseEntity.status(status).header(DRIVER_INSTANCE_HEADER, driverInstanceId)
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static IdempotentResult toIdempotent(ResponseEntity<String> response, String fingerprint) {
        return new IdempotentResult(response.getStatusCode().value(), response.getBody(), fingerprint);
    }

    private static String firstHeader(Map<String, String> headers, String name) {
        return header(headers, name);
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static byte[] bodyOrEmpty(byte[] body) {
        return body == null ? new byte[0] : body;
    }

    @FunctionalInterface
    private interface Command {
        DriverCommandResult run();
    }

    /** Application port deliberately keeps DriverService out of HTTP code. */
    public interface DriverCommandPort {
        DriverCommandResult ping(byte[] body);
        DriverCommandResult submit(byte[] body);
        DriverCommandResult login(byte[] body);
        DriverCommandResult launch(byte[] body);
        DriverCommandResult query(byte[] body);
        DriverCommandResult close(byte[] body);
        DriverCommandResult abort(byte[] body);
        DriverCommandResult trigger(byte[] body);
    }
}
