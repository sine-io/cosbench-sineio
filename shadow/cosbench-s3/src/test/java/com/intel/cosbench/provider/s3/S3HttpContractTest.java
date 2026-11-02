package com.intel.cosbench.provider.s3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.intel.cosbench.api.provider.StaticProviderRegistry;
import com.intel.cosbench.api.storage.StorageException;
import com.intel.cosbench.config.common.KVConfigParser;
import com.intel.cosbench.log.LogFactory;

class S3HttpContractTest {
    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 409, 500, 503})
    void retainsHttpStatusAndSdkCauseAcrossStorageOperations(int status) throws Exception {
        try (var server = new Endpoint(status, 0)) {
            var config = config(server.endpoint());
            var storage = registry().createStorage("s3", config, LogFactory.getSystemLogger());
            try {
                assertStatus(status, () -> storage.getObject("bucket", "object", config));
                assertStatus(status, () -> storage.getList("bucket", "object", config));
                assertStatus(status, () -> storage.headObject("bucket", "object", config));
                assertStatus(status, () -> storage.createObject("bucket", "object",
                        new ByteArrayInputStream(new byte[] {1}), 1, config));
                assertStatus(status, () -> storage.createContainer("bucket", config));
                if (status == 404) {
                    assertDoesNotThrow(() -> storage.deleteObject("bucket", "object", config));
                    assertDoesNotThrow(() -> storage.deleteContainer("bucket", config));
                } else {
                    assertStatus(status, () -> storage.deleteObject("bucket", "object", config));
                    assertStatus(status, () -> storage.deleteContainer("bucket", config));
                }
            } finally {
                storage.dispose();
            }
        }
    }

    @Test
    void mapsTimeoutAndConnectionRefusalToStorageExceptionWithOriginalCause() throws Exception {
        try (var server = new Endpoint(200, 1200)) {
            assertTransportFailure(server.endpoint());
        }
        int port;
        try (var unused = new ServerSocket(0)) {
            port = unused.getLocalPort();
        }
        assertTransportFailure("http://127.0.0.1:" + port);
    }

    @Test
    void abortsMultipartUploadWhenAnUploadPartFails() throws Exception {
        try (var server = new MultipartFailureEndpoint()) {
            var config = config(server.endpoint());
            var storage = registry().createStorage("s3", config, LogFactory.getSystemLogger());
            try {
                byte[] content = new byte[] {1, 2, 3};
                var error = assertThrows(StorageException.class,
                        () -> storage.createMultipartObject("bucket", "object",
                                new ByteArrayInputStream(content), content.length, config));
                assertEquals(500,
                        assertInstanceOf(AmazonS3Exception.class, error.getCause()).getStatusCode());
                assertTrue(server.uploadPartRequests.get() > 0);
                assertEquals(1, server.abortRequests.get());
            } finally {
                storage.dispose();
            }
        }
    }

    private static void assertTransportFailure(String endpoint) {
        var config = config(endpoint);
        var storage = registry().createStorage("s3", config, LogFactory.getSystemLogger());
        try {
            var error = assertThrows(StorageException.class, () -> storage.getObject("bucket", "object", config));
            assertEquals(StorageException.class, error.getClass());
            assertInstanceOf(SdkClientException.class, error.getCause());
        } finally {
            storage.dispose();
        }
    }

    private static void assertStatus(int status, org.junit.jupiter.api.function.Executable operation) {
        var error = assertThrows(StorageException.class, operation);
        assertEquals(StorageException.class, error.getClass());
        assertEquals(status, assertInstanceOf(AmazonS3Exception.class, error.getCause()).getStatusCode());
    }

    private static com.intel.cosbench.config.Config config(String endpoint) {
        return KVConfigParser.parse("endpoint=" + endpoint
                + ";accesskey=test;secretkey=test;path_style_access=true;timeout=150");
    }

    private static StaticProviderRegistry registry() {
        return StaticProviderRegistry.discover(S3HttpContractTest.class.getClassLoader(), Set.of("s3"), Set.of());
    }

    private static final class Endpoint implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
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

        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void respond(HttpExchange exchange) throws IOException {
            try {
                exchange.getRequestBody().readAllBytes();
                if (delayMillis > 0) Thread.sleep(delayMillis);
                boolean head = exchange.getRequestMethod().equals("HEAD");
                byte[] body = (status < 400 ? "data"
                        : "<Error><Code>InternalError</Code><Message>S3 contract fixture</Message></Error>")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                exchange.sendResponseHeaders(status, head ? -1 : body.length);
                if (!head) exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static final class MultipartFailureEndpoint implements AutoCloseable {
        private static final String INITIATE_RESULT = "<InitiateMultipartUploadResult"
                + " xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<Bucket>bucket</Bucket><Key>object</Key><UploadId>upload-id</UploadId>"
                + "</InitiateMultipartUploadResult>";
        private static final String INTERNAL_ERROR = "<Error><Code>InternalError</Code>"
                + "<Message>Upload part failed</Message><RequestId>fixture</RequestId>"
                + "<HostId>fixture</HostId></Error>";

        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        private final AtomicInteger uploadPartRequests = new AtomicInteger();
        private final AtomicInteger abortRequests = new AtomicInteger();

        MultipartFailureEndpoint() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::respond);
            server.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void respond(HttpExchange exchange) throws IOException {
            try {
                exchange.getRequestBody().readAllBytes();
                String query = exchange.getRequestURI().getRawQuery();
                if (exchange.getRequestMethod().equals("POST") && query != null && query.contains("uploads")) {
                    send(exchange, 200, INITIATE_RESULT);
                } else if (exchange.getRequestMethod().equals("PUT") && query != null
                        && query.contains("partNumber")) {
                    uploadPartRequests.incrementAndGet();
                    send(exchange, 500, INTERNAL_ERROR);
                } else if (exchange.getRequestMethod().equals("DELETE") && query != null
                        && query.contains("uploadId")) {
                    abortRequests.incrementAndGet();
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    send(exchange, 400, INTERNAL_ERROR);
                }
            } finally {
                exchange.close();
            }
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
