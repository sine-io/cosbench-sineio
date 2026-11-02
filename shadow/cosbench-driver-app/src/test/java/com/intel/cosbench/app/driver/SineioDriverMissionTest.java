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
    void discoversTheThreeProductionModesAlongsideTestOnlyMock() {
        var registry = StaticProviderRegistry.discover(
                getClass().getClassLoader(), Set.of("sio", "siov1", "gdas"), Set.of());
        assertEquals(Set.of("sio", "siov1", "gdas", "mock"), Set.copyOf(registry.storageNames()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sio", "siov1", "gdas"})
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
    @ValueSource(strings = {"sio", "siov1", "gdas"})
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
