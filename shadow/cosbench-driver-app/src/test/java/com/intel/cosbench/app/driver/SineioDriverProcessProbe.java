package com.intel.cosbench.app.driver;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import com.intel.cosbench.api.provider.StaticProviderRegistry;
import com.intel.cosbench.driver.service.COSBDriverService;
import com.intel.cosbench.model.MissionState;

/** Test-only launcher: real Boot composition root and active production mission at TERM. */
@Component
public final class SineioDriverProcessProbe implements ApplicationListener<ApplicationReadyEvent> {
    public static void main(String[] args) {
        DriverApplication.main(args);
    }

    @Override public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            launchMission(event);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot start production Provider mission", error);
        }
    }

    private static void launchMission(ApplicationReadyEvent event) throws Exception {
        var context = event.getApplicationContext();
        var registry = context.getBean(StaticProviderRegistry.class);
        if (!registry.storageNames().equals(java.util.Set.of("sio", "siov1", "gdas", "s3"))
                || !registry.authNames().isEmpty()) {
            throw new IllegalStateException("Unexpected production providers: " + registry.storageNames());
        }
        String mode = SineioMissionFixture.required("COSBENCH_SINEIO_MODE");
        String bucket = SineioMissionFixture.prepareBucket(
                System.getenv().getOrDefault("COSBENCH_MISSION_BUCKET", "cosbench-term-" + mode), mode);
        var service = context.getBean(COSBDriverService.class);
        String id = service.submit(SineioMissionFixture.mission(mode, bucket, 1_000_000));
        service.login(id);
        service.launch(id);
        SineioMissionFixture.await(() -> service.getMissionInfo(id).getState() == MissionState.LAUNCHED
                && SineioMissionFixture.storageConnections() > 0, "mission did not reach MinIO");
        System.out.println("SINEIO_MISSION_READY mode=" + mode + " mission=" + id);
    }
}
