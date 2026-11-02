package com.intel.cosbench.provider.sineio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.intel.cosbench.api.provider.StaticProviderRegistry;
import com.intel.cosbench.api.storage.StorageException;
import com.intel.cosbench.api.storage.StorageTimeoutException;
import com.intel.cosbench.config.common.KVConfigParser;
import com.intel.cosbench.log.LogFactory;

class SineioHttpContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"sio", "siov1", "gdas"})
    void preservesHttpAndTransportErrorMapping(String mode) throws Exception {
        for (int status : new int[] {400, 403, 404, 409, 500, 503}) {
            try (var server = new Endpoint(status, 0)) {
                var error = getFailure(mode, server.endpoint(), false);
                assertEquals(StorageException.class, error.getClass());
                if (mode.equals("sio")) {
                    assertEquals(status, ((software.amazon.awssdk.services.s3.model.S3Exception)
                            error.getCause()).statusCode());
                } else {
                    assertEquals(status, ((com.amazonaws.services.s3.model.AmazonS3Exception)
                            error.getCause()).getStatusCode());
                }
                assertTrue(server.requests.size() <= 4, "SDK retries must remain bounded");
            }
        }
        try (var server = new Endpoint(200, 1200)) {
            getFailure(mode, server.endpoint(), true);
        }
        // Reserve then release a port, rather than assuming a well-known port is unused.
        int port;
        try (var unused = new ServerSocket(0)) { port = unused.getLocalPort(); }
        getFailure(mode, "http://127.0.0.1:" + port, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sio", "siov1", "gdas"})
    void retainsRestoreRequestAndMetadataContract(String mode) throws Exception {
        try (var server = new Endpoint(200, 0)) {
            var config = config(server.endpoint(), ";restore_days=3");
            var storage = registry().createStorage(mode, config, LogFactory.getSystemLogger());
            try {
                storage.restoreObject("bucket", "object", config);
            } finally {
                storage.dispose();
            }
            Request restore = server.requests.stream().filter(r -> r.method.equals("POST")).findFirst().orElseThrow();
            assertEquals("/bucket/object?restore", restore.uri);
            assertTrue(restore.body.contains("<Days>3</Days>"), restore.body);
            assertTrue(restore.authorization.startsWith("AWS4-HMAC-SHA256"));
            assertEquals(mode.equals("sio") ? List.of("POST") : List.of("POST", "HEAD"),
                    server.requests.stream().map(Request::method).toList());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sio", "siov1", "gdas"})
    void retainsPrefetchRangePrecedenceAndSigning(String mode) throws Exception {
        try (var server = new Endpoint(200, 0)) {
            var config = config(server.endpoint(),
                    ";is_prefetch=true;is_range_request=true;file_length=64;chunk_length=64");
            var storage = registry().createStorage(mode, config, LogFactory.getSystemLogger());
            try (var object = storage.getObject("bucket", "object", config)) {
                assertEquals("data", new String(object.readAllBytes(), StandardCharsets.UTF_8));
            } finally {
                storage.dispose();
            }
            Request get = server.requests.getFirst();
            assertEquals("GET", get.method);
            assertEquals("/bucket/object", get.uri);
            assertEquals(mode.equals("gdas") ? null : "value", get.prefetch);
            assertEquals(mode.equals("sio") ? "bytes=0-63" : null, get.range);
            assertTrue(get.authorization.startsWith(mode.equals("sio") ? "AWS4-HMAC-SHA256" : "AWS "));
        }
    }

    private static StorageException getFailure(String mode, String endpoint, boolean transport) {
        var config = config(endpoint, ";timeout=150");
        var storage = registry().createStorage(mode, config, LogFactory.getSystemLogger());
        try {
            var error = assertThrows(StorageException.class, () -> storage.getObject("bucket", "object", config));
            assertEquals(transport && !mode.equals("sio") ? StorageTimeoutException.class : StorageException.class,
                    error.getClass());
            assertNotNull(error.getCause());
            if (transport) {
                assertTrue(error.getCause().getClass().getName().endsWith(".SdkClientException"));
            }
            return error;
        } finally {
            storage.dispose();
        }
    }

    private static com.intel.cosbench.config.Config config(String endpoint, String extra) {
        return KVConfigParser.parse("endpoint=" + endpoint
                + ";accesskey=test;secretkey=test;path_style_access=true;aws_region=us-east-1;timeout=1000" + extra);
    }

    private static StaticProviderRegistry registry() {
        return StaticProviderRegistry.discover(SineioHttpContractTest.class.getClassLoader(),
                Set.of("sio", "siov1", "gdas"), Set.of());
    }

    private record Request(String method, String uri, String body, String authorization, String prefetch, String range) { }

    /** Controlled S3 peer for response codes and vendor extension wire contracts. */
    private static final class Endpoint implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private final int status;
        private final int delayMillis;

        Endpoint(int status, int delayMillis) throws IOException {
            this.status = status;
            this.delayMillis = delayMillis;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::respond);
            server.start();
        }

        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private void respond(HttpExchange exchange) {
            try {
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("prefetch"),
                        exchange.getRequestHeaders().getFirst("Range")));
                if (delayMillis > 0) Thread.sleep(delayMillis);
                boolean head = exchange.getRequestMethod().equals("HEAD");
                byte[] body = (status < 400 ? "data"
                        : "<Error><Code>InternalError</Code><Message>SineIO contract fixture</Message></Error>")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", status < 400 ? "application/octet-stream" : "application/xml");
                exchange.getResponseHeaders().set("x-amz-restore", "ongoing-request=\"false\"");
                exchange.sendResponseHeaders(status, head || exchange.getRequestMethod().equals("POST") ? -1 : body.length);
                if (!head && !exchange.getRequestMethod().equals("POST")) exchange.getResponseBody().write(body);
            } catch (IOException ignored) {
                // Timeout probes close their sockets before the response is sent.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        }

        @Override public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
