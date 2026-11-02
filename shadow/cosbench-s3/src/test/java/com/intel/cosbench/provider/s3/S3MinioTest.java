package com.intel.cosbench.provider.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.intel.cosbench.api.provider.StaticProviderRegistry;
import com.intel.cosbench.api.storage.StorageException;
import com.intel.cosbench.config.common.KVConfigParser;
import com.intel.cosbench.log.LogFactory;

class S3MinioTest {
    @Test
    void roundTripsObjectListingAndMultipartData() throws Exception {
        var config = KVConfigParser.parse(minioValues() + ";unknown_key=ignored");
        var registry = StaticProviderRegistry.discover(getClass().getClassLoader(), Set.of("s3"), Set.of());
        var storage = registry.createStorage("s3", config, LogFactory.getSystemLogger());
        String bucket = "cosbench-s3-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        boolean created = false;
        try {
            storage.createContainer(bucket, config);
            created = true;
            assertThrows(StorageException.class, () -> storage.getObject(bucket, "missing", config));

            byte[] object = "legacy s3 provider".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            storage.createObject(bucket, "small/object.txt", new ByteArrayInputStream(object), object.length, config);
            try (var downloaded = storage.getObject(bucket, "small/object.txt", config)) {
                assertArrayEquals(object, downloaded.readAllBytes());
            }
            storage.headObject(bucket, "small/object.txt", config);
            try (var listing = storage.getList(bucket, "small/", config)) {
                assertEquals("small/object.txt\n", new String(listing.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
            }

            byte[] multipart = new byte[5 * 1024 * 1024 + 37];
            new java.util.Random(12).nextBytes(multipart);
            storage.createMultipartObject(bucket, "multipart/data.bin",
                    new ByteArrayInputStream(multipart), multipart.length, config);
            try (var downloaded = storage.getObject(bucket, "multipart/data.bin", config)) {
                assertArrayEquals(multipart, downloaded.readAllBytes());
            }
            storage.headObject(bucket, "multipart/data.bin", config);

            storage.deleteObject(bucket, "small/object.txt", config);
            storage.deleteObject(bucket, "multipart/data.bin", config);
            try (var listing = storage.getList(bucket, "", config)) {
                assertEquals(0, listing.readAllBytes().length);
            }
        } finally {
            try {
                if (created) {
                    storage.deleteObject(bucket, "small/object.txt", config);
                    storage.deleteObject(bucket, "multipart/data.bin", config);
                    storage.deleteContainer(bucket, config);
                }
            } finally {
                storage.dispose();
            }
        }
    }

    @Test
    void multipartZeroLengthUsesARegularEmptyPut() throws Exception {
        var config = KVConfigParser.parse(minioValues());
        var registry = StaticProviderRegistry.discover(getClass().getClassLoader(), Set.of("s3"), Set.of());
        var storage = registry.createStorage("s3", config, LogFactory.getSystemLogger());
        String bucket = "cosbench-s3-zero-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        boolean created = false;
        try {
            storage.createContainer(bucket, config);
            created = true;
            storage.createMultipartObject(bucket, "empty.bin", new ByteArrayInputStream(new byte[0]), 0, config);
            try (var downloaded = storage.getObject(bucket, "empty.bin", config)) {
                assertEquals(0, downloaded.readAllBytes().length);
            }
            storage.deleteObject(bucket, "empty.bin", config);
        } finally {
            try {
                if (created) {
                    storage.deleteObject(bucket, "empty.bin", config);
                    storage.deleteContainer(bucket, config);
                }
            } finally {
                storage.dispose();
            }
        }
    }

    private static String minioValues() throws Exception {
        return "endpoint=" + required("COSBENCH_MINIO_ENDPOINT")
                + ";accesskey=" + Files.readString(Path.of(required("COSBENCH_MINIO_ACCESS_KEY_FILE"))).trim()
                + ";secretkey=" + Files.readString(Path.of(required("COSBENCH_MINIO_SECRET_KEY_FILE"))).trim()
                + ";path_style_access=true;timeout=1000";
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing " + name);
        }
        return value;
    }
}
