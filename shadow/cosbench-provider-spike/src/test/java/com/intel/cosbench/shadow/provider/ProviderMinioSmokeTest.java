package com.intel.cosbench.shadow.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import com.intel.cosbench.api.auth.AuthAPI;
import com.intel.cosbench.api.auth.AuthAPIFactory;
import com.intel.cosbench.api.provider.StaticProviderRegistry;
import com.intel.cosbench.api.context.AuthContext;
import com.intel.cosbench.api.storage.StorageAPI;
import com.intel.cosbench.api.storage.StorageAPIFactory;
import com.intel.cosbench.api.storage.StorageException;
import com.intel.cosbench.config.Config;
import com.intel.cosbench.log.Logger;

class ProviderMinioSmokeTest {
    private static final int MULTIPART_PART_SIZE = 5 * 1024 * 1024;
    private static final byte[] OBJECT = "cosbench provider spi smoke".getBytes(StandardCharsets.UTF_8);
    private static final Map<String, Class<? extends RuntimeException>> POST_DISPOSE_ERRORS = Map.of(
            "sio", StorageException.class,
            "siov1", NullPointerException.class,
            "gdas", NullPointerException.class,
            "s3", StorageException.class);

    @Test
    void exercisesAllFactoriesAndProductionStorageProvidersOnOneClasspath() throws Throwable {
        StaticProviderRegistry registry = StaticProviderRegistry.discover(
                getClass().getClassLoader(),
                Set.of("mock", "s3", "sio", "siov1", "gdas"),
                Set.of("mock"));
        Map<String, String> minioValues = loadMinioValues();
        Config minioConfig = mapConfig(minioValues);
        Logger logger = noOpLogger();
        Path reportPath = Path.of(requiredEnvironment("COSBENCH_PROVIDER_REPORT"));
        Files.createDirectories(reportPath.getParent());

        try (BufferedWriter report = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8)) {
            report.write("shared_classpath=true\n");
            report.write("endpoint=" + minioConfig.get("endpoint") + "\n");
            report.write("storage_factories=" + registry.storageNames() + "\n");
            report.write("auth_factories=" + registry.authNames() + "\n");
            report.write("storage_factory_classes=" + factoryClasses(
                    ServiceLoader.load(StorageAPIFactory.class, getClass().getClassLoader()),
                    StorageAPIFactory::getStorageName) + "\n");
            report.write("auth_factory_classes=" + factoryClasses(
                    ServiceLoader.load(AuthAPIFactory.class, getClass().getClassLoader()),
                    AuthAPIFactory::getAuthName) + "\n");
            report.write("unknown_config_key=accepted by all six discovered factory registrations\n");

            for (String provider : List.of("sio", "siov1", "gdas", "s3")) {
                exerciseConfiguration(provider, registry, logger, report);
                exerciseAuthorizationError(provider, registry, minioValues, logger, report);
                exerciseEndpointError(provider, registry, minioValues, logger, report);
            }

            try (TestS3Server server = new TestS3Server(500, 0)) {
                for (String provider : List.of("sio", "siov1", "gdas", "s3")) {
                    exerciseStorageError(
                            provider,
                            registry,
                            minioValues,
                            server.endpoint(),
                            "http-500",
                            logger,
                            report);
                }
            }

            try (TestS3Server server = new TestS3Server(301, 0)) {
                exerciseS3RedirectBehavior(registry, minioValues, server.endpoint(), logger, report);
            }

            try (TestS3Server server = new TestS3Server(200, 1800)) {
                for (String provider : List.of("sio", "siov1", "gdas", "s3")) {
                    exerciseStorageError(
                            provider,
                            registry,
                            minioValues,
                            server.endpoint(),
                            "slow-response-timeout",
                            logger,
                            report);
                }
            }

            exerciseMockLifecycle(registry, logger, report);
            for (String provider : List.of("sio", "siov1", "gdas", "s3")) {
                exerciseStorage(provider, registry, minioConfig, logger, report);
            }
        }
    }

    private static void exerciseConfiguration(
            String provider,
            StaticProviderRegistry registry,
            Logger logger,
            BufferedWriter report) throws IOException {
        if (provider.equals("sio")) {
            StaticProviderRegistry.ProviderRegistryException missingAccessKey = assertThrows(
                    StaticProviderRegistry.ProviderRegistryException.class,
                    () -> registry.createStorage(provider, mapConfig(Map.of()), logger));
            assertTrue(missingAccessKey.getMessage().contains(provider), missingAccessKey.getMessage());
            assertTrue(missingAccessKey.getCause() instanceof NullPointerException);
            assertTrue(missingAccessKey.getCause().getMessage().contains("Access key ID cannot be blank"));
            assertEquals(0, missingAccessKey.getCause().getSuppressed().length);
            report.write(provider + "-config-empty=ProviderRegistryException(NullPointerException: blank access key)\n");
        } else {
            StorageAPI defaults = registry.createStorage(provider, mapConfig(Map.of()), logger);
            defaults.dispose();
            report.write(provider + "-config-empty=default init/dispose passed\n");
        }

        StaticProviderRegistry.ProviderRegistryException error = assertThrows(
                StaticProviderRegistry.ProviderRegistryException.class,
                () -> registry.createStorage(provider, mapConfig(Map.of("timeout", "not-an-integer")), logger));
        assertTrue(error.getMessage().contains(provider), error.getMessage());
        assertTrue(error.getCause() instanceof NumberFormatException, String.valueOf(error.getCause()));
        assertEquals(0, error.getCause().getSuppressed().length);
        report.write(provider + "-config-invalid-timeout=ProviderRegistryException(NumberFormatException)\n");
    }

    private static void exerciseAuthorizationError(
            String provider,
            StaticProviderRegistry registry,
            Map<String, String> minioValues,
            Logger logger,
            BufferedWriter report) throws IOException {
        Map<String, String> values = new HashMap<>(minioValues);
        values.put("accesskey", "invalid-provider-spike-key");
        values.put("secretkey", "invalid-provider-spike-secret");
        Config config = mapConfig(values);
        StorageAPI storage = registry.createStorage(provider, config, logger);
        try {
            StorageException error = assertThrows(StorageException.class, () -> {
                try (InputStream object = storage.getObject("cosbench-smoke", "missing-object", config)) {
                    object.readAllBytes();
                }
            }, provider + " should report invalid credentials as a COSBench storage error");
            String cause = error.getCause() == null ? "none" : error.getCause().getClass().getSimpleName();
            report.write(provider + "-invalid-auth-get=" + error.getClass().getSimpleName() + "<-" + cause + "\n");

            String bucket = "cosbench-auth-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            StorageException createError = assertThrows(
                    StorageException.class,
                    () -> storage.createContainer(bucket, config),
                    provider + " should report invalid credentials during bucket creation as a COSBench storage error");
            String createCause = createError.getCause() == null
                    ? "none"
                    : createError.getCause().getClass().getSimpleName();
            report.write(provider + "-invalid-auth-create-container="
                    + createError.getClass().getSimpleName() + "<-" + createCause + "\n");
        } finally {
            storage.dispose();
        }
    }

    private static void exerciseEndpointError(
            String provider,
            StaticProviderRegistry registry,
            Map<String, String> minioValues,
            Logger logger,
            BufferedWriter report) throws IOException {
        Map<String, String> values = new HashMap<>(minioValues);
        values.put("endpoint", "http://127.0.0.1:1");
        values.put("timeout", "250");
        exerciseStorageError(provider, registry, values, values.get("endpoint"), "connection-refused", logger, report);
    }

    private static void exerciseStorageError(
            String provider,
            StaticProviderRegistry registry,
            Map<String, String> values,
            String endpoint,
            String scenario,
            Logger logger,
            BufferedWriter report) throws IOException {
        Map<String, String> configValues = new HashMap<>(values);
        configValues.put("endpoint", endpoint);
        if (scenario.equals("slow-response-timeout")) {
            configValues.put("timeout", "250");
        }
        Config config = mapConfig(configValues);
        StorageAPI storage = registry.createStorage(provider, config, logger);
        String bucket = "cosbench-error-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            StorageException error = assertThrows(
                    StorageException.class,
                    () -> storage.createContainer(bucket, config),
                    provider + " should report " + scenario + " as a COSBench storage error");
            String cause = error.getCause() == null ? "none" : error.getCause().getClass().getSimpleName();
            report.write(provider + "-" + scenario + "=" + error.getClass().getSimpleName() + "<-" + cause + "\n");
        } finally {
            storage.dispose();
        }
    }

    private static void exerciseS3RedirectBehavior(
            StaticProviderRegistry registry,
            Map<String, String> minioValues,
            String endpoint,
            Logger logger,
            BufferedWriter report) throws IOException {
        Map<String, String> values = new HashMap<>(minioValues);
        values.put("endpoint", endpoint);
        Config config = mapConfig(values);
        StorageAPI storage = registry.createStorage("s3", config, logger);
        try {
            assertDoesNotThrow(() -> storage.createContainer("redirected-bucket", config));
            report.write("s3-http-301=createContainer treated bucket as existing\n");
        } finally {
            storage.dispose();
        }
    }

    private static <F> Map<String, String> factoryClasses(Iterable<F> factories, Function<F, String> nameOf) {
        Map<String, String> classes = new TreeMap<>();
        for (F factory : factories) {
            classes.put(nameOf.apply(factory), factory.getClass().getName());
        }
        return classes;
    }

    private static void exerciseMockLifecycle(StaticProviderRegistry registry, Logger logger, BufferedWriter report)
            throws IOException {
        AuthAPI defaultAuth = registry.createAuth("mock", mapConfig(Map.of()), logger);
        try {
            assertNotNull(defaultAuth.login());
        } finally {
            defaultAuth.dispose();
        }

        StaticProviderRegistry.ProviderRegistryException invalidAuthConfig = assertThrows(
                StaticProviderRegistry.ProviderRegistryException.class,
                () -> registry.createAuth("mock", mapConfig(Map.of("delay", "not-an-integer")), logger));
        assertTrue(invalidAuthConfig.getCause() instanceof NumberFormatException);

        StorageAPI defaultStorage = registry.createStorage("mock", mapConfig(Map.of()), logger);
        defaultStorage.dispose();

        StaticProviderRegistry.ProviderRegistryException invalidStorageConfig = assertThrows(
                StaticProviderRegistry.ProviderRegistryException.class,
                () -> registry.createStorage("mock", mapConfig(Map.of("size", "not-a-long")), logger));
        assertTrue(invalidStorageConfig.getCause() instanceof NumberFormatException);

        Config authConfig = mapConfig(Map.of(
                "token", "provider-spike-token",
                "delay", "0",
                "provider_spike_unknown_key", "ignored"));
        AuthAPI auth = registry.createAuth("mock", authConfig, logger);
        try {
            AuthContext context = auth.login();
            assertNotNull(context);
            assertEquals("provider-spike-token", context.get("token"));
        } finally {
            auth.dispose();
        }
        AuthContext contextAfterDispose = auth.login();
        assertNotNull(contextAfterDispose);
        assertEquals("provider-spike-token", contextAfterDispose.get("token"));

        Config mockConfig = mapConfig(Map.of(
                "size", "16",
                "delay", "0",
                "errors", "0",
                "provider_spike_unknown_key", "ignored"));
        StorageAPI storage = registry.createStorage("mock", mockConfig, logger);
        try {
            storage.createContainer("mock", mockConfig);
            storage.createObject("mock", "object", new ByteArrayInputStream(OBJECT), OBJECT.length, mockConfig);
            try (InputStream object = storage.getObject("mock", "object", mockConfig)) {
                assertEquals(16, object.readAllBytes().length);
            }
        } finally {
            storage.dispose();
        }
        try (InputStream afterDispose = storage.getObject("mock", "object", mockConfig)) {
            assertEquals(16, afterDispose.readAllBytes().length);
        }
        report.write("mock-auth-config-empty=default login/dispose passed\n");
        report.write("mock-auth-config-invalid-delay=ProviderRegistryException(NumberFormatException)\n");
        report.write("mock-storage-config-empty=default init/dispose passed\n");
        report.write("mock-storage-config-invalid-size=ProviderRegistryException(NumberFormatException)\n");
        report.write("mock-auth=init,login(token verified),dispose,post-dispose=login still works\n");
        report.write("mock-storage=init,create-container,put,get(16 bytes),dispose,post-dispose=get(16 bytes) still works\n");
    }

    private static void exerciseStorage(
            String provider,
            StaticProviderRegistry registry,
            Config config,
            Logger logger,
            BufferedWriter report) throws Throwable {
        String bucket = "cosbench-" + provider + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String objectName = "provider-smoke.txt";
        StorageAPI storage = registry.createStorage(provider, config, logger);
        boolean containerCreated = false;
        boolean objectCreated = false;
        boolean multipartObjectCreated = false;
        Throwable primaryFailure = null;
        StringBuilder operations = new StringBuilder("init");

        try {
            storage.createContainer(bucket, config);
            containerCreated = true;
            operations.append(",create-container");

            assertThrows(StorageException.class, () -> {
                try (InputStream missing = storage.getObject(bucket, "missing-object", config)) {
                    missing.readAllBytes();
                }
            });
            operations.append(",get-missing=StorageException");

            storage.createObject(bucket, objectName, new ByteArrayInputStream(OBJECT), OBJECT.length, config);
            objectCreated = true;
            operations.append(",put");

            try (InputStream object = storage.getObject(bucket, objectName, config)) {
                assertArrayEquals(OBJECT, object.readAllBytes());
            }
            operations.append(",get");

            try (InputStream listing = storage.getList(bucket, objectName, config)) {
                String listingResult = new String(listing.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(listingResult.contains(objectName), listingResult);
            }
            operations.append(",list");

            storage.headObject(bucket, objectName, config);
            operations.append(",head");

            byte[] multipartObject = new byte[MULTIPART_PART_SIZE + 37];
            Arrays.fill(multipartObject, (byte) 0x5a);
            storage.createMultipartObject(
                    bucket,
                    "provider-smoke-multipart.bin",
                    new ByteArrayInputStream(multipartObject),
                    multipartObject.length,
                    config);
            multipartObjectCreated = true;
            try (InputStream multipart = storage.getObject(bucket, "provider-smoke-multipart.bin", config)) {
                assertArrayEquals(multipartObject, multipart.readAllBytes());
            }
            storage.deleteObject(bucket, "provider-smoke-multipart.bin", config);
            multipartObjectCreated = false;
            operations.append(",multipart-put/get/delete");

            storage.deleteObject(bucket, objectName, config);
            objectCreated = false;
            operations.append(",delete");
            storage.deleteContainer(bucket, config);
            containerCreated = false;
            operations.append(",delete-container");
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            Throwable cleanupFailure = null;
            if (objectCreated) {
                cleanupFailure = cleanup(cleanupFailure, () -> storage.deleteObject(bucket, objectName, config));
            }
            if (multipartObjectCreated) {
                cleanupFailure = cleanup(
                        cleanupFailure,
                        () -> storage.deleteObject(bucket, "provider-smoke-multipart.bin", config));
            }
            if (containerCreated) {
                cleanupFailure = cleanup(cleanupFailure, () -> storage.deleteContainer(bucket, config));
            }
            cleanupFailure = cleanup(cleanupFailure, storage::dispose);
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }

        operations.append(",dispose");
        Class<? extends RuntimeException> postDisposeError = POST_DISPOSE_ERRORS.get(provider);
        assertThrows(postDisposeError, () -> storage.getObject(bucket, objectName, config));
        operations.append(",post-dispose=").append(postDisposeError.getSimpleName());

        if (provider.equals("s3")) {
            report.write("s3-restore=legacy S3 has no archive restore configuration or implementation; not applicable\n");
        } else if (provider.equals("sio") || provider.equals("siov1")) {
            report.write(provider + "-restore/prefetch/range=not exercised by MinIO fixture\n");
        } else if (provider.equals("gdas")) {
            report.write("gdas-restore=not exercised by MinIO fixture\n");
        }
        report.write(provider + "=" + operations + "\n");
        report.flush();
    }

    private static Throwable cleanup(Throwable previous, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError error) {
            if (previous == null) {
                return error;
            }
            previous.addSuppressed(error);
        }
        return previous;
    }

    private static Map<String, String> loadMinioValues() throws IOException {
        Properties fixture = new Properties();
        try (InputStream input = ProviderMinioSmokeTest.class.getResourceAsStream("/providers/s3-compatible.properties")) {
            assertNotNull(input, "s3-compatible.properties test fixture is required");
            fixture.load(input);
        }

        Map<String, String> values = new HashMap<>();
        for (String key : fixture.stringPropertyNames()) {
            values.put(key, resolveFixtureValue(fixture.getProperty(key)));
        }
        values.put("provider_spike_unknown_key", "ignored");
        return values;
    }

    private static final class TestS3Server implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final int status;
        private final long delayMillis;

        private TestS3Server(int status, long delayMillis) throws IOException {
            this.status = status;
            this.delayMillis = delayMillis;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool();
            server.setExecutor(executor);
            server.createContext("/", this::respond);
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void respond(HttpExchange exchange) {
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                byte[] body = ("<Error><Code>InternalError</Code><Message>provider test</Message></Error>")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                exchange.sendResponseHeaders(status, exchange.getRequestMethod().equals("HEAD") ? -1 : body.length);
                if (!exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseBody().write(body);
                }
            } catch (IOException ignored) {
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

    private static String resolveFixtureValue(String value) throws IOException {
        if (!value.startsWith("${") || !value.endsWith("}")) {
            return value;
        }
        String variable = value.substring(2, value.length() - 1);
        String resolved = requiredEnvironment(variable);
        return variable.endsWith("_FILE")
                ? Files.readString(Path.of(resolved), StandardCharsets.UTF_8).trim()
                : resolved;
    }

    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is missing: " + key);
        }
        return value;
    }

    private static Config mapConfig(Map<String, String> values) {
        return new Config() {
            @Override
            public String get(String key) {
                return values.get(key);
            }

            @Override
            public String get(String key, String fallback) {
                return values.getOrDefault(key, fallback);
            }

            @Override
            public int getInt(String key) {
                return Integer.parseInt(values.get(key));
            }

            @Override
            public int getInt(String key, int fallback) {
                return values.containsKey(key) ? Integer.parseInt(values.get(key)) : fallback;
            }

            @Override
            public long getLong(String key) {
                return Long.parseLong(values.get(key));
            }

            @Override
            public long getLong(String key, long fallback) {
                return values.containsKey(key) ? Long.parseLong(values.get(key)) : fallback;
            }

            @Override
            public double getDouble(String key) {
                return Double.parseDouble(values.get(key));
            }

            @Override
            public double getDouble(String key, double fallback) {
                return values.containsKey(key) ? Double.parseDouble(values.get(key)) : fallback;
            }

            @Override
            public boolean getBoolean(String key) {
                return Boolean.parseBoolean(values.get(key));
            }

            @Override
            public boolean getBoolean(String key, boolean fallback) {
                return values.containsKey(key) ? Boolean.parseBoolean(values.get(key)) : fallback;
            }
        };
    }

    private static Logger noOpLogger() {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] { Logger.class },
                (proxy, method, arguments) -> method.getReturnType() == boolean.class ? false : null);
    }
}
