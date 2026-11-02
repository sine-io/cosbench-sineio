package com.intel.cosbench.provider.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import com.intel.cosbench.api.provider.StaticProviderRegistry;
import com.intel.cosbench.api.storage.StorageException;
import com.intel.cosbench.config.common.KVConfigParser;
import com.intel.cosbench.log.LogFactory;

class S3ProviderTest {
    @Test
    void staticRegistryDiscoversS3AndPreservesLegacyConfiguration() {
        var registry = StaticProviderRegistry.discover(
                getClass().getClassLoader(), Set.of("s3"), Set.of());
        assertEquals(Set.of("s3"), registry.storageNames());

        var config = KVConfigParser.parse("endpoint=http://127.0.0.1:9000"
                + ";accesskey=test-access;secretkey=test-secret;timeout=1200"
                + ";proxyhost=127.0.0.1;proxyport=8080;path_style_access=true;unknown_key=ignored");
        var storage = registry.createStorage("s3", config, LogFactory.getSystemLogger());
        try {
            assertEquals("http://127.0.0.1:9000", storage.getParms().get("endpoint"));
            assertEquals("test-access", storage.getParms().get("accesskey"));
            assertEquals("test-secret", storage.getParms().get("secretkey"));
            assertEquals(1200, storage.getParms().get("timeout"));
            assertEquals("127.0.0.1", storage.getParms().get("proxyhost"));
            assertEquals("8080", storage.getParms().get("proxyport"));
            assertEquals(true, storage.getParms().get("path_style_access"));
        } finally {
            storage.dispose();
        }
        assertThrows(StorageException.class, () -> storage.getObject("bucket", "key", config));
    }

    @Test
    void invalidLegacyTimeoutIsDiagnosedByTheRegistry() {
        var registry = StaticProviderRegistry.discover(
                getClass().getClassLoader(), Set.of("s3"), Set.of());
        var failure = assertThrows(StaticProviderRegistry.ProviderRegistryException.class,
                () -> registry.createStorage("s3", KVConfigParser.parse("timeout=invalid"),
                        LogFactory.getSystemLogger()));
        assertTrue(failure.getMessage().contains("s3"));
        assertEquals(org.apache.commons.configuration.ConversionException.class, failure.getCause().getClass());
    }

    @Test
    void missingFieldsUseLegacyDefaultsAndInvalidTypesKeepTheConfigurationCause() {
        var registry = StaticProviderRegistry.discover(getClass().getClassLoader(), Set.of("s3"), Set.of());
        var defaults = registry.createStorage("s3", KVConfigParser.parse(""), LogFactory.getSystemLogger());
        try {
            assertEquals("http://s3.amazonaws.com", defaults.getParms().get("endpoint"));
            assertEquals("", defaults.getParms().get("accesskey"));
            assertEquals("", defaults.getParms().get("secretkey"));
            assertEquals(30000, defaults.getParms().get("timeout"));
            assertEquals(false, defaults.getParms().get("path_style_access"));
        } finally {
            defaults.dispose();
        }
        for (String invalid : new String[] {"timeout=invalid", "path_style_access=invalid", "logging=invalid"}) {
            var failure = assertThrows(StaticProviderRegistry.ProviderRegistryException.class,
                    () -> registry.createStorage("s3", KVConfigParser.parse(invalid), LogFactory.getSystemLogger()));
            assertEquals(org.apache.commons.configuration.ConversionException.class, failure.getCause().getClass());
            assertEquals(0, failure.getCause().getSuppressed().length);
        }
        var proxyFailure = assertThrows(StaticProviderRegistry.ProviderRegistryException.class,
                () -> registry.createStorage("s3", KVConfigParser.parse("proxyhost=localhost;proxyport=invalid"),
                        LogFactory.getSystemLogger()));
        assertEquals(NumberFormatException.class, proxyFailure.getCause().getClass());
        // As in the legacy provider, an incomplete proxy configuration is ignored.
        registry.createStorage("s3", KVConfigParser.parse("proxyport=invalid"), LogFactory.getSystemLogger()).dispose();
    }

    @Test
    void sendsLegacySignatureToPathStyleEndpoint() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        endpoint.start();

        var config = KVConfigParser.parse("endpoint=http://127.0.0.1:" + endpoint.getAddress().getPort()
                + ";accesskey=test-access;secretkey=test-secret;path_style_access=true;timeout=1000");
        var registry = StaticProviderRegistry.discover(getClass().getClassLoader(), Set.of("s3"), Set.of());
        var storage = registry.createStorage("s3", config, LogFactory.getSystemLogger());
        try {
            storage.createContainer("bucket", config);
            assertEquals("/bucket/", requestPath.get());
            assertTrue(authorization.get().startsWith("AWS test-access:"), authorization.get());
        } finally {
            storage.dispose();
            endpoint.stop(0);
        }
    }

    @Test
    void disposeClosesTheLegacySdkKeepAliveConnection() throws Exception {
        try (var endpoint = new S3KeepAliveEndpoint()) {
            var config = KVConfigParser.parse("endpoint=" + endpoint.endpoint()
                    + ";accesskey=test-access;secretkey=test-secret;path_style_access=true;timeout=1000");
            var registry = StaticProviderRegistry.discover(getClass().getClassLoader(), Set.of("s3"), Set.of());
            var storage = registry.createStorage("s3", config, LogFactory.getSystemLogger());
            try {
                try (var object = storage.getObject("bucket", "object", config)) {
                    assertEquals("data", new String(object.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII));
                }
                assertTrue(endpoint.hasConnections(), "fixture must observe an idle pooled connection");
            } finally {
                storage.dispose();
            }
            assertTrue(endpoint.awaitDisconnected(), "S3 client leaked its HTTP connection after dispose");
        }
    }
}
