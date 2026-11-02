package com.intel.cosbench.provider.sineio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.intel.cosbench.api.provider.StaticProviderRegistry;
import com.intel.cosbench.config.common.KVConfigParser;
import com.intel.cosbench.log.LogFactory;

class SineioProviderTest {
    @ParameterizedTest
    @ValueSource(strings = {"sio", "siov1", "gdas"})
    void preservesConfigurationDefaultsAndConversionErrors(String mode) {
        var registry = StaticProviderRegistry.discover(
                getClass().getClassLoader(), Set.of("sio", "siov1", "gdas"), Set.of());
        assertEquals(Set.of("sio", "siov1", "gdas"), registry.storageNames());
        assertTrue(registry.authNames().isEmpty());
        var empty = KVConfigParser.parse("");
        if (mode.equals("sio")) {
            var failure = assertThrows(StaticProviderRegistry.ProviderRegistryException.class,
                    () -> registry.createStorage(mode, empty, LogFactory.getSystemLogger()));
            assertTrue(failure.getCause() instanceof NullPointerException);
            assertTrue(failure.getCause().getMessage().contains("Access key ID cannot be blank"));
        } else {
            registry.createStorage(mode, empty, LogFactory.getSystemLogger()).dispose();
        }
        for (String invalid : new String[] {"timeout=invalid", "max_connections=invalid",
                "restore_days=invalid", "part_size=invalid", "path_style_access=invalid",
                "no_verify_ssl=invalid"}) {
            var failure = assertThrows(StaticProviderRegistry.ProviderRegistryException.class,
                    () -> registry.createStorage(mode, KVConfigParser.parse(invalid), LogFactory.getSystemLogger()));
            assertTrue(failure.getMessage().contains(mode));
            assertTrue(failure.getCause() instanceof org.apache.commons.configuration.ConversionException,
                    failure.toString());
            assertEquals(0, failure.getCause().getSuppressed().length);
        }
        var unknownKey = KVConfigParser.parse("accesskey=test;secretkey=test;unknown_key=ignored");
        var storage = registry.createStorage(mode, unknownKey, LogFactory.getSystemLogger());
        storage.dispose();
        Class<? extends RuntimeException> closedError = mode.equals("sio")
                ? com.intel.cosbench.api.storage.StorageException.class : NullPointerException.class;
        assertThrows(closedError, () -> storage.getObject("bucket", "object", unknownKey));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sio", "siov1", "gdas"})
    void disposeClosesTheClientsKeepAliveConnections(String mode) throws Exception {
        var registry = StaticProviderRegistry.discover(
                getClass().getClassLoader(), Set.of("sio", "siov1", "gdas"), Set.of());
        try (var endpoint = new KeepAliveEndpoint()) {
            var config = KVConfigParser.parse("endpoint=" + endpoint.endpoint()
                    + ";accesskey=test;secretkey=test;path_style_access=true;timeout=1000");
            var storage = registry.createStorage(mode, config, LogFactory.getSystemLogger());
            try {
                try (var object = storage.getObject("bucket", "object", config)) {
                    assertArrayEquals("data".getBytes(StandardCharsets.US_ASCII), object.readAllBytes());
                }
                assertTrue(endpoint.hasConnections(), "fixture must observe a reusable connection");
            } finally {
                storage.dispose();
            }
            assertTrue(endpoint.awaitDisconnected(), mode + " leaked its HTTP pool after dispose");
            storage.dispose();
            assertFalse(endpoint.hasConnections());
        }
    }
}
