package com.intel.cosbench.provider.sineio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.intel.cosbench.api.provider.StaticProviderRegistry;
import com.intel.cosbench.api.storage.StorageException;
import com.intel.cosbench.config.common.KVConfigParser;
import com.intel.cosbench.log.LogFactory;

class SineioMinioTest {
    @ParameterizedTest
    @ValueSource(strings = {"sio", "siov1", "gdas"})
    void roundTripsObjectsRepeatedMultipartUploadsAndLists(String mode) throws Exception {
        String values = minioValues();
        var config = KVConfigParser.parse(values + ";part_size=5242880;storage_class=STANDARD;unknown_key=ignored");
        var registry = StaticProviderRegistry.discover(
                getClass().getClassLoader(), Set.of("sio", "siov1", "gdas"), Set.of());
        var storage = registry.createStorage(mode, config, LogFactory.getSystemLogger());
        String bucket = "sineio-" + mode + "-" + UUID.randomUUID().toString().substring(0, 8);
        boolean created = false;
        try {
            storage.createContainer(bucket, config);
            created = true;
            assertThrows(StorageException.class, () -> storage.getObject(bucket, "missing", config));
            byte[] data = new byte[5 * 1024 * 1024 + 37];
            new Random(11).nextBytes(data);
            for (int length : new int[] {4096, data.length, 17, data.length - 23}) {
                byte[] expected = Arrays.copyOf(data, length);
                String key = "object-" + length;
                try {
                    if (length == 4096) {
                        storage.createObject(bucket, key, new ByteArrayInputStream(expected), length, config);
                    } else {
                        storage.createMultipartObject(bucket, key, new ByteArrayInputStream(expected), length, config);
                    }
                    try (var object = storage.getObject(bucket, key, config)) {
                        assertArrayEquals(expected, object.readAllBytes());
                    }
                    storage.headObject(bucket, key, config);
                    try (var listing = storage.getList(bucket, key, config)) {
                        assertEquals(key + "\n", new String(listing.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    if (length == 4096 && !mode.equals("gdas")) {
                        verifyRangeAndPrefetch(mode, registry, values, bucket, key, expected);
                    }
                } finally {
                    storage.deleteObject(bucket, key, config);
                }
                assertThrows(StorageException.class, () -> storage.headObject(bucket, key, config));
                try (var listing = storage.getList(bucket, key, config)) {
                    assertEquals(0, listing.readAllBytes().length);
                }
            }
        } finally {
            try {
                if (created) storage.deleteContainer(bucket, config);
            } finally {
                storage.dispose();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sio", "siov1", "gdas"})
    void invalidCredentialsRetainTheSdkCause(String mode) throws Exception {
        var config = KVConfigParser.parse(minioValues() + ";accesskey=invalid;secretkey=invalid");
        var registry = StaticProviderRegistry.discover(getClass().getClassLoader(), Set.of(mode), Set.of());
        var storage = registry.createStorage(mode, config, LogFactory.getSystemLogger());
        try {
            var error = assertThrows(StorageException.class,
                    () -> storage.getObject("cosbench-smoke", "missing", config));
            assertTrue(error.getCause().getClass().getName().endsWith(
                    mode.equals("sio") ? ".S3Exception" : ".AmazonS3Exception"));
        } finally {
            storage.dispose();
        }
    }

    private static void verifyRangeAndPrefetch(String mode, StaticProviderRegistry registry, String values,
            String bucket, String key, byte[] expected) throws Exception {
        var config = KVConfigParser.parse(values + ";is_range_request=true;file_length=4096;chunk_length=64");
        var storage = registry.createStorage(mode, config, LogFactory.getSystemLogger());
        try (var range = storage.getObject(bucket, key, config)) {
            byte[] actual = range.readAllBytes();
            assertEquals(64, actual.length);
            boolean found = false;
            for (int start = 0; start <= expected.length - actual.length; start++) {
                if (Arrays.equals(expected, start, start + actual.length, actual, 0, actual.length)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "range must return a contiguous slice of the stored data");
        } finally {
            storage.dispose();
        }
        config = KVConfigParser.parse(values + ";is_prefetch=true");
        storage = registry.createStorage(mode, config, LogFactory.getSystemLogger());
        try (var prefetch = storage.getObject(bucket, key, config)) {
            assertArrayEquals(expected, prefetch.readAllBytes());
        } finally {
            storage.dispose();
        }
    }

    private static String minioValues() throws Exception {
        return "endpoint=" + required("COSBENCH_MINIO_ENDPOINT")
                + ";accesskey=" + Files.readString(Path.of(required("COSBENCH_MINIO_ACCESS_KEY_FILE"))).trim()
                + ";secretkey=" + Files.readString(Path.of(required("COSBENCH_MINIO_SECRET_KEY_FILE"))).trim()
                + ";path_style_access=true;aws_region=us-east-1;timeout=1000;max_connections=4";
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
