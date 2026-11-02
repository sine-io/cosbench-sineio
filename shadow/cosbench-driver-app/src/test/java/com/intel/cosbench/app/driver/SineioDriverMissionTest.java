package com.intel.cosbench.app.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.intel.cosbench.api.provider.StaticProviderRegistry;
import com.intel.cosbench.driver.model.DriverContext;
import com.intel.cosbench.driver.service.COSBAuthAPIService;
import com.intel.cosbench.driver.service.COSBDriverService;
import com.intel.cosbench.driver.service.COSBStorageAPIService;
import com.intel.cosbench.model.MissionState;

class SineioDriverMissionTest {
    @TempDir Path temp;

    @Test
    void discoversAllProductionProvidersAlongsideTestOnlyMock() {
        var registry = StaticProviderRegistry.discover(
                getClass().getClassLoader(), Set.of("sio", "siov1", "gdas", "s3"), Set.of());
        assertEquals(Set.of("sio", "siov1", "gdas", "s3", "mock"), Set.copyOf(registry.storageNames()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sio", "siov1", "gdas", "s3"})
    void completedMissionReportsSuccessfulReadsAndReleasesConnections(String mode) throws Exception {
        String bucket = SineioMissionFixture.prepare(mode);
        try (var service = service()) {
            SineioMissionFixture.await(() -> SineioMissionFixture.storageConnections() == 0, "setup connection leaked");
            String id = service.submit(SineioMissionFixture.mission(mode, bucket, 10));
            service.login(id);
            service.launch(id);
            SineioMissionFixture.await(() -> service.getMissionInfo(id).getState() == MissionState.FINISHED,
                    "mission did not finish: " + mode);
            service.close(id);
            var info = service.getMissionInfo(id);
            assertEquals(MissionState.ACCOMPLISHED, info.getState());
            assertTrue(Arrays.stream(info.getReport().getAllMetrics()).anyMatch(metrics ->
                    metrics.getSampleCount() == 10 && metrics.getTotalSampleCount() == 10
                    && metrics.getByteCount() == 10L * SineioMissionFixture.OBJECT_SIZE));
            SineioMissionFixture.await(() -> SineioMissionFixture.storageConnections() == 0,
                    mode + " retained storage sockets after mission close");
        } finally {
            SineioMissionFixture.remove(mode, bucket);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sio", "siov1", "gdas", "s3"})
    void abortReleasesAnActiveProductionProvidersConnections(String mode) throws Exception {
        String bucket = SineioMissionFixture.prepare(mode);
        try (var service = service()) {
            String id = service.submit(SineioMissionFixture.mission(mode, bucket, 1_000_000));
            service.login(id);
            service.launch(id);
            SineioMissionFixture.await(() -> SineioMissionFixture.storageConnections() > 0,
                    "production mission never connected to MinIO");
            service.abort(id);
            SineioMissionFixture.await(() -> service.getMissionInfo(id).getState() == MissionState.ABORTED,
                    "mission did not abort");
            SineioMissionFixture.await(() -> SineioMissionFixture.storageConnections() == 0,
                    mode + " retained storage sockets after abort");
        } finally {
            SineioMissionFixture.remove(mode, bucket);
        }
    }

    @Test
    void s3MultipartAndListAreAvailableThroughTheDriverProviderFactory() throws Exception {
        String bucket = SineioMissionFixture.prepareBucket("cosbench-s3-mission", "s3");
        var config = SineioMissionFixture.config("s3");
        var storage = SineioMissionFixture.registry().createStorage("s3", config,
                com.intel.cosbench.log.LogFactory.getSystemLogger());
        byte[] multipart = new byte[5 * 1024 * 1024 + 23];
        new java.util.Random(13).nextBytes(multipart);
        try {
            storage.createMultipartObject(bucket, "multipart.bin", new java.io.ByteArrayInputStream(multipart),
                    multipart.length, config);
            try (var object = storage.getObject(bucket, "multipart.bin", config)) {
                assertTrue(Arrays.equals(multipart, object.readAllBytes()));
            }
            try (var listing = storage.getList(bucket, "", config)) {
                String names = new String(listing.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(names.contains("obj1\n"));
                assertTrue(names.contains("multipart.bin\n"));
            }
            storage.deleteObject(bucket, "multipart.bin", config);
        } finally {
            storage.dispose();
            SineioMissionFixture.remove("s3", bucket);
        }
    }

    private COSBDriverService service() throws Exception {
        var registry = SineioMissionFixture.registry();
        DriverContext context = new DriverContext();
        context.setName("SineIO mission test");
        context.setUrl("http://127.0.0.1:18088/driver");
        context.setMission_dir(Files.createDirectories(temp.resolve("missions")).toString());
        context.setScripts_dir(temp.resolve("scripts").toString());
        var service = new COSBDriverService(context, new COSBAuthAPIService(registry.authFactories()),
                new COSBStorageAPIService(registry.storageFactories()), Duration.ofSeconds(3));
        service.init();
        return service;
    }
}
