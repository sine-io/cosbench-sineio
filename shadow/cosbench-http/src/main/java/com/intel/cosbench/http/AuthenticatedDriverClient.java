package com.intel.cosbench.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;

/** Controller-side pooled HTTP/1.1 adapter for the legacy Driver /i protocol. */
public final class AuthenticatedDriverClient implements AutoCloseable {

    private static final String REQUEST_ID_HEADER = "X-COSBench-Request-Id";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String DRIVER_INSTANCE_HEADER = "X-COSBench-Driver-Instance";
    private static final String DRIVER_INSTANCE_CHANGED = "Driver instance changed; write result may be unknown";

    private final URI baseUri;
    private final String token;
    private final HttpClientSettings config;
    private final RetryPolicy retryPolicy;
    private final HttpClient client;
    private final Semaphore poolSlots;
    private final Semaphore globalPoolSlots;
    private final AtomicLong lastUsedNanos = new AtomicLong(System.nanoTime());
    private volatile String driverInstanceId;
    private int activeCalls;
    private volatile boolean closed;

    public AuthenticatedDriverClient(URI baseUri, String token, HttpClientSettings config) {
        this(baseUri, token, config, null, null, null);
    }

    public AuthenticatedDriverClient(URI baseUri, String token, HttpClientSettings config,
            SSLContext sslContext) {
        this(baseUri, token, config, sslContext, null, null);
    }

    AuthenticatedDriverClient(URI baseUri, String token, HttpClientSettings config,
            SSLContext sslContext, HttpClient client) {
        this(baseUri, token, config, sslContext, client, null);
    }

    AuthenticatedDriverClient(URI baseUri, String token, HttpClientSettings config,
            SSLContext sslContext, HttpClient client, Semaphore sharedGlobalPoolSlots) {
        if (baseUri == null || token == null || token.isBlank() || config == null) {
            throw new IllegalArgumentException("base URI, service token, and HTTP config are required");
        }
        this.baseUri = ensureTrailingSlash(baseUri);
        this.token = token;
        this.config = config;
        this.retryPolicy = new RetryPolicy(config.maxAttempts(), config.retryableStatuses());
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.client = client == null ? builder.build() : client;
        this.poolSlots = new Semaphore(config.maxPerRoute(), true);
        this.globalPoolSlots = sharedGlobalPoolSlots == null
                ? new Semaphore(config.maxConnections(), true) : sharedGlobalPoolSlots;
    }

    static AuthenticatedDriverClient pooled(URI baseUri, String token, HttpClientSettings config,
            SSLContext sslContext, Semaphore globalPoolSlots) {
        return new AuthenticatedDriverClient(baseUri, token, config, sslContext, null, globalPoolSlots);
    }

    public DriverCommandResult execute(String command, byte[] payload, boolean write,
            Duration commandTimeout) {
        return execute(command, payload, write, commandTimeout, Long.MAX_VALUE);
    }

    private DriverCommandResult execute(String command, byte[] payload, boolean write,
            Duration commandTimeout, long externalDeadlineNanos) {
        beginCall();
        if (command == null || !command.matches("[a-z]+") || payload == null || commandTimeout == null
                || commandTimeout.isNegative() || commandTimeout.isZero() || commandTimeout.compareTo(config.workloadTimeout()) > 0) {
            endCall();
            throw new IllegalArgumentException("command, payload, and positive command timeout are required");
        }
        try {
            String requestId = UUID.randomUUID().toString();
            String idempotencyKey = write ? requestId : null;
            Duration effectiveTimeout = min(commandTimeout, config.commandTimeout());
            long commandDeadlineNanos = earlierDeadline(deadlineAfter(effectiveTimeout), externalDeadlineNanos);
            String expectedInstance = write ? currentDriverInstance(command, commandDeadlineNanos) : null;
            int attempts = 0;
            while (true) {
                Duration remainingTimeout = remaining(commandDeadlineNanos);
                if (remainingTimeout.isZero()) {
                    throw deadlineExceeded(command, write, requestId);
                }
                attempts++;
                boolean slotsAcquired = false;
                try {
                    acquireSlots(remainingTimeout);
                    slotsAcquired = true;
                    remainingTimeout = remaining(commandDeadlineNanos);
                    if (remainingTimeout.isZero()) {
                        throw deadlineExceeded(command, write, requestId);
                    }
                    HttpRequest request = request(command, payload, write, requestId, idempotencyKey,
                            remainingTimeout, expectedInstance);
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    lastUsedNanos.set(System.nanoTime());
                    response.headers().firstValue(DRIVER_INSTANCE_HEADER)
                            .ifPresent(value -> driverInstanceId = value);
                    DriverCommandResult result = new DriverCommandResult(response.statusCode(), response.body());
                    if (retryPolicy.retryStatus(response.statusCode()) && attempts < retryPolicy.maxAttempts()) {
                        lastUsedNanos.set(System.nanoTime());
                        backoff(attempts, commandDeadlineNanos, command, write, requestId);
                        continue;
                    }
                    if (response.statusCode() >= 400) {
                        if (write && response.statusCode() == 409
                                && response.body().contains(DRIVER_INSTANCE_CHANGED)) {
                            driverInstanceId = null;
                        }
                        throw new DriverCommandException(response.statusCode(), response.body());
                    }
                    return result;
                } catch (IOException failure) {
                    lastUsedNanos.set(System.nanoTime());
                    if (attempts >= retryPolicy.maxAttempts() || !retryableTransport(failure)) {
                        throw new DriverTransportException("Driver " + command + " failed after " + attempts
                                + " attempt(s); requestId=" + requestId + "; result may be unknown", failure);
                    }
                    backoff(attempts, commandDeadlineNanos, command, write, requestId);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new DriverTransportException("Driver " + command + " was interrupted; requestId="
                            + requestId + "; result may be unknown", interrupted);
                } finally {
                    if (slotsAcquired) {
                        releaseSlots();
                    }
                }
            }
        } finally {
            endCall();
        }
    }

    public DriverCommandResult submit(byte[] body) {
        return execute("submit", body, true, config.commandTimeout());
    }

    public DriverCommandResult query(byte[] body) {
        return execute("query", body, false, config.commandTimeout());
    }

    public DriverCommandResult ping(byte[] body) {
        return execute("ping", body, false, config.commandTimeout());
    }

    public DriverCommandResult login(byte[] body) {
        return execute("login", body, true, config.commandTimeout());
    }

    public DriverCommandResult launch(byte[] body) {
        return execute("launch", body, true, config.commandTimeout());
    }

    public DriverCommandResult closeMission(byte[] body) {
        return execute("close", body, true, config.commandTimeout());
    }

    public DriverCommandResult abort(byte[] body) {
        return execute("abort", body, true, config.commandTimeout());
    }

    public DriverCommandResult trigger(byte[] body) {
        return execute("trigger", body, true, config.commandTimeout());
    }

    public DriverCommandResult executeWorkloadCommand(String command, byte[] payload, boolean write,
            long workloadDeadlineNanos) {
        long remainingNanos = workloadDeadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new DriverTransportException("workload deadline expired before Driver " + command, null);
        }
        return execute(command, payload, write,
                min(config.commandTimeout(), Duration.ofNanos(remainingNanos)), workloadDeadlineNanos);
    }

    public synchronized boolean idleExpired() {
        return activeCalls == 0
                && System.nanoTime() - lastUsedNanos.get() >= config.idleTimeout().toNanos();
    }

    private synchronized void beginCall() {
        if (closed) {
            throw new IllegalStateException("Driver HTTP client is closed");
        }
        activeCalls++;
    }

    private synchronized void endCall() {
        activeCalls--;
        lastUsedNanos.set(System.nanoTime());
    }

    boolean closeIfIdle() {
        synchronized (this) {
            if (!idleExpired()) {
                return false;
            }
            closed = true;
            client.shutdownNow();
            return true;
        }
    }

    private HttpRequest request(String command, byte[] payload, boolean write,
            String requestId, String idempotencyKey, Duration timeout, String expectedInstance) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve("i/" + command + ".command"))
                .timeout(min(timeout, config.readTimeout()))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (write) {
            builder.header(REQUEST_ID_HEADER, requestId)
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .header(DRIVER_INSTANCE_HEADER, expectedInstance)
                    .header("X-COSBench-Request-Fingerprint", RequestFingerprint.sha256(command, payload));
        }
        return builder.build();
    }

    private String currentDriverInstance(String command, long deadlineNanos) {
        String current = driverInstanceId;
        if (current != null) {
            return current;
        }
        Duration timeout = remaining(deadlineNanos);
        if (timeout.isZero()) {
            throw new DriverTransportException("command deadline expired before Driver " + command
                    + "; write was not sent", null);
        }
        for (int attempt = 1; ; attempt++) {
            Duration remainingTimeout = remaining(deadlineNanos);
            if (remainingTimeout.isZero()) {
                throw new DriverTransportException("command deadline expired identifying Driver instance before "
                        + command + "; write was not sent", null);
            }
            boolean slotsAcquired = false;
            try {
                acquireSlots(remainingTimeout);
                slotsAcquired = true;
                remainingTimeout = remaining(deadlineNanos);
                if (remainingTimeout.isZero()) {
                    throw new DriverTransportException("command deadline expired identifying Driver instance before "
                            + command + "; write was not sent", null);
                }
                HttpRequest boundedPing = request("ping",
                        Long.toString(System.currentTimeMillis()).getBytes(java.nio.charset.StandardCharsets.UTF_8), false,
                        UUID.randomUUID().toString(), null, remainingTimeout, null);
                HttpResponse<String> response = client.send(boundedPing, HttpResponse.BodyHandlers.ofString());
                lastUsedNanos.set(System.nanoTime());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    String discovered = response.headers()
                            .firstValue(DRIVER_INSTANCE_HEADER)
                            .filter(value -> !value.isBlank())
                            .orElseThrow(() -> new DriverTransportException(
                                    "Driver did not provide an instance identity before " + command, null));
                    driverInstanceId = discovered;
                    return discovered;
                }
                if (attempt >= retryPolicy.maxAttempts() || !retryPolicy.retryStatus(response.statusCode())) {
                    throw new DriverCommandException(response.statusCode(), response.body());
                }
                backoff(attempt, deadlineNanos, command, false, "");
            } catch (IOException failure) {
                if (attempt >= retryPolicy.maxAttempts() || !retryableTransport(failure)) {
                    throw new DriverTransportException("could not identify Driver instance before " + command
                            + "; write was not sent", failure);
                }
                backoff(attempt, deadlineNanos, command, false, "");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DriverTransportException("interrupted identifying Driver instance before " + command
                        + "; write was not sent", interrupted);
            } finally {
                if (slotsAcquired) {
                    releaseSlots();
                }
            }
        }
    }

    private void acquireSlots(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean globalAcquired = false;
        boolean routeAcquired = false;
        boolean acquisitionComplete = false;
        try {
            globalAcquired = globalPoolSlots.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (!globalAcquired) {
                throw new DriverTransportException("global HTTP connection pool acquisition timed out", null);
            }
            long remaining = deadline - System.nanoTime();
            routeAcquired = remaining > 0 && poolSlots.tryAcquire(remaining, TimeUnit.NANOSECONDS);
            if (!routeAcquired) {
                throw new DriverTransportException("per-route HTTP connection pool acquisition timed out", null);
            }
            acquisitionComplete = true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DriverTransportException("Driver connection pool acquisition was interrupted", interrupted);
        } finally {
            if (!acquisitionComplete) {
                if (routeAcquired) {
                    poolSlots.release();
                }
                if (globalAcquired) {
                    globalPoolSlots.release();
                }
            }
        }
    }

    private void releaseSlots() {
        poolSlots.release();
        globalPoolSlots.release();
    }

    private void backoff(int attempt, long deadlineNanos, String command, boolean write, String requestId) {
        Duration delay = config.retryBackoff().multipliedBy(attempt);
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw deadlineExceeded(command, write, requestId);
        }
        if (!delay.isZero()) {
            long sleepNanos = Math.min(delay.toNanos(), remainingNanos);
            try {
                TimeUnit.NANOSECONDS.sleep(sleepNanos);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DriverTransportException("Driver retry backoff was interrupted", interrupted);
            }
        }
        if (deadlineNanos - System.nanoTime() <= 0) {
            throw deadlineExceeded(command, write, requestId);
        }
    }

    private static long deadlineAfter(Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
    }

    private static long earlierDeadline(long left, long right) {
        if (right == Long.MAX_VALUE) {
            return left;
        }
        long now = System.nanoTime();
        return left - now <= right - now ? left : right;
    }

    private static Duration remaining(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        return remainingNanos <= 0 ? Duration.ZERO : Duration.ofNanos(remainingNanos);
    }

    private static DriverTransportException deadlineExceeded(String command, boolean write, String requestId) {
        String outcome = write ? "; result may be unknown; requestId=" + requestId : "";
        return new DriverTransportException("Driver " + command + " command deadline expired" + outcome, null);
    }

    private static boolean retryableTransport(IOException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof javax.net.ssl.SSLException) {
                return false;
            }
            if (current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.NoRouteToHostException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.net.SocketException
                    || current instanceof java.io.EOFException) {
                return true;
            }
            if (current instanceof IOException) {
                String message = current.getMessage();
                if (message != null && (message.equals("HTTP/1.1 header parser received no bytes")
                        || message.startsWith("BUFFER_UNDERFLOW with EOF,"))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static URI ensureTrailingSlash(URI uri) {
        String value = uri.toString();
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    @Override
    public synchronized void close() {
        closed = true;
        client.shutdownNow();
    }
}
