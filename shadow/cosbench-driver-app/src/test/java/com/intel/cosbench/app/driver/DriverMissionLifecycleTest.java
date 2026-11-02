package com.intel.cosbench.app.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.intel.cosbench.api.provider.StaticProviderRegistry;
import com.intel.cosbench.api.auth.AuthAPIService;
import com.intel.cosbench.api.auth.NoneAuth;
import com.intel.cosbench.api.storage.NoneStorage;
import com.intel.cosbench.api.storage.StorageAPIService;
import com.intel.cosbench.config.XmlConfig;
import com.intel.cosbench.config.ConfigException;
import com.intel.cosbench.driver.model.DriverContext;
import com.intel.cosbench.driver.service.COSBAuthAPIService;
import com.intel.cosbench.driver.service.COSBDriverService;
import com.intel.cosbench.driver.service.COSBStorageAPIService;
import com.intel.cosbench.log.LogFactory;
import com.intel.cosbench.model.MissionInfo;
import com.intel.cosbench.model.MissionState;
import com.intel.cosbench.protocol.TriggerResponse;

class DriverMissionLifecycleTest {
    private static final String MISSION = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mission name="mock-noop" workers="1" totalWorkers="1" totalOps="10" interval="1">
              <auth type="mock" config="delay=0;errors=0" />
              <storage type="mock" config="size=64;delay=0;errors=0" />
              <operation type="read" ratio="100" config="containers=c(1);objects=c(1)" />
            </mission>
            """;
    private static final String LONG_MISSION = MISSION
            .replace("totalOps=\"10\"", "totalOps=\"1000\"")
            .replace("size=64;delay=0;errors=0", "size=64;delay=1000;errors=0");

    @TempDir Path temp;

    @Test
    void discoveredMockMissionRunsAndClosesWithQueryableReportAndLog() throws Exception {
        COSBDriverService service = newService(Duration.ofSeconds(5));
        try {
            String id = service.submit(config(MISSION));
            service.login(id);
            service.launch(id);

            MissionInfo info = awaitState(service, id, MissionState.FINISHED, Duration.ofSeconds(10));
            assertEquals(1, info.getWorkerCount());

            service.close(id);
            assertEquals(MissionState.ACCOMPLISHED, service.getMissionInfo(id).getState());
            assertTrue(service.getMissionInfo(id).getReport().getAllMetrics().length > 0);
            try (var files = Files.list(temp.resolve("missions"))) {
                assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".log")));
            }
        } finally {
            service.close();
        }
        assertFalse(service.isAccepting());
        assertThrows(RejectedExecutionException.class, () -> service.submit(config(MISSION)));
    }

    @Test
    void shutdownStopsAdmissionAndAbortsAnInFlightMockMissionWithinGrace() throws Exception {
        COSBDriverService service = newService(Duration.ofSeconds(3));
        String id = service.submit(config(LONG_MISSION));
        service.login(id);
        service.launch(id);
        awaitState(service, id, MissionState.LAUNCHED, Duration.ofSeconds(3));

        long started = System.nanoTime();
        service.close();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertFalse(service.isAccepting());
        assertTrue(elapsedMillis < 4000, "Driver shutdown exceeded its configured grace period");
        assertEquals(MissionState.ABORTED, service.getMissionInfo(id).getState());
        assertThrows(RejectedExecutionException.class, () -> service.submit(config(MISSION)));
    }

    @Test
    void explicitAbortStopsAnInFlightMockMission() throws Exception {
        COSBDriverService service = newService(Duration.ofSeconds(3));
        String id = service.submit(config(LONG_MISSION));
        service.login(id);
        service.launch(id);
        awaitState(service, id, MissionState.LAUNCHED, Duration.ofSeconds(3));

        service.abort(id);

        assertEquals(MissionState.ABORTED, awaitState(service, id, MissionState.ABORTED, Duration.ofSeconds(3))
                .getState());
        service.close();
        assertThrows(RejectedExecutionException.class, () -> service.submit(config(MISSION)));
    }

    @Test
    void shutdownDisposesASubmittedMissionThatNeverStarted() throws Exception {
        COSBDriverService service = newService(Duration.ofSeconds(2));
        String id = service.submit(config(MISSION));

        service.close();

        assertEquals(MissionState.ABORTED, service.getMissionInfo(id).getState());
        assertFalse(service.isAccepting());
    }

    @Test
    void successfulMissionDisposesProvidersAfterOperationsAndRejectsUseAfterClose() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        AuthAPIService authService = (type, config, logger) -> {
            TrackingAuth auth = new TrackingAuth(events);
            auth.init(config, logger);
            return auth;
        };
        StorageAPIService storageService = (type, config, logger) -> {
            TrackingStorage storage = new TrackingStorage(events);
            storage.init(config, logger);
            return storage;
        };
        COSBDriverService service = newService(Duration.ofSeconds(5), authService, storageService);
        try {
            String id = service.submit(config(MISSION));
            service.login(id);
            service.launch(id);
            awaitState(service, id, MissionState.FINISHED, Duration.ofSeconds(10));

            service.close(id);

            assertInOrder(events, "auth.init", "auth.operation", "auth.dispose");
            assertInOrder(events, "storage.init", "storage.operation", "storage.dispose");
            assertThrows(com.intel.cosbench.service.IllegalStateException.class, () -> service.close(id));
        } finally {
            service.close();
        }
        assertThrows(RejectedExecutionException.class, () -> service.submit(config(MISSION)));
    }

    @Test
    void partialWorkerInitializationDisposesPreviouslyCreatedProviders() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        List<TrackingAuth> auths = new ArrayList<>();
        TrackingStorage storage = new TrackingStorage(events);
        AtomicInteger storageCreations = new AtomicInteger();
        AuthAPIService authService = (type, config, logger) -> {
            TrackingAuth auth = new TrackingAuth(events);
            auths.add(auth);
            auth.init(config, logger);
            return auth;
        };
        StorageAPIService storageService = (type, config, logger) -> {
            if (storageCreations.incrementAndGet() == 2) {
                throw new ConfigException("intentional second-worker initialization failure");
            }
            storage.init(config, logger);
            return storage;
        };
        COSBDriverService service = newService(Duration.ofSeconds(2), authService, storageService);
        String twoWorkers = MISSION.replace("workers=\"1\" totalWorkers=\"1\"",
                "workers=\"2\" totalWorkers=\"2\"");

        try {
            assertThrows(ConfigException.class, () -> service.submit(config(twoWorkers)));
            assertTrue(storage.disposed);
            assertEquals(2, auths.size());
            assertTrue(auths.stream().allMatch(auth -> auth.disposed));
        } finally {
            service.close();
        }
    }

    @Test
    void localTriggerRunsScriptAndReturnsCapturedOutputAndPid() throws Exception {
        Path scripts = Files.createDirectories(temp.resolve("scripts"));
        Files.writeString(scripts.resolve("trigger-noop.sh"), "#!/bin/sh\nprintf 'baseline-trigger-ok:%s\\n' \"$1\"\n");
        Path outsideScript = Files.writeString(temp.resolve("outside-trigger.sh"), "#!/bin/sh\\nexit 0\\n");
        Files.createSymbolicLink(scripts.resolve("trigger-link.sh"), outsideScript);
        COSBDriverService service = newService(Duration.ofSeconds(2));
        try {
            TriggerResponse enabled = service.trigger("enableTrigger,trigger-noop.sh,argument");

            assertTrue(Long.parseLong(enabled.getPID()) > 0);
            assertTrue(enabled.getScriptLog().contains("baseline-trigger-ok:argument"));
            assertThrows(ConfigException.class,
                    () -> service.trigger("enableTrigger,../trigger-noop.sh"));
            assertThrows(ConfigException.class,
                    () -> service.trigger("enableTrigger,trigger-link.sh"));
            TriggerResponse disabled = service.trigger(
                    "killTrigger," + enabled.getPID() + ",trigger-noop.sh");
            assertEquals("0", disabled.getPID());
            assertTrue(disabled.getScriptLog().contains("baseline-trigger-ok:-k"));
        } finally {
            service.close();
        }
    }

    private COSBDriverService newService(Duration gracePeriod) throws Exception {
        StaticProviderRegistry registry = StaticProviderRegistry.discover(
                getClass().getClassLoader(), Set.of("mock"), Set.of("mock"));
        return newService(gracePeriod,
                new COSBAuthAPIService(registry.authFactories()),
                new COSBStorageAPIService(registry.storageFactories()));
    }

    private COSBDriverService newService(
            Duration gracePeriod, AuthAPIService authAPIs, StorageAPIService storageAPIs) throws Exception {
        DriverContext context = new DriverContext();
        context.setName("test-driver");
        context.setUrl("http://127.0.0.1:0/driver");
        context.setVersion("test");
        context.setMission_dir(Files.createDirectories(temp.resolve("missions")).toString());
        context.setScripts_dir(Files.createDirectories(temp.resolve("scripts")).toString());
        var systemLogs = LogFactory.getSystemLogManager();
        systemLogs.setLogFile(Files.createDirectories(temp.resolve("system-logs")).toFile(), "system.log", false, false);

        COSBDriverService service = new COSBDriverService(
                context,
                authAPIs,
                storageAPIs,
                gracePeriod);
        service.init();
        return service;
    }

    private static final class TrackingAuth extends NoneAuth {
        private final List<String> events;
        private boolean disposed;

        private TrackingAuth(List<String> events) {
            this.events = events;
        }

        @Override
        public void init(com.intel.cosbench.config.Config config, com.intel.cosbench.log.Logger logger) {
            super.init(config, logger);
            events.add("auth.init");
        }

        @Override
        public com.intel.cosbench.api.context.AuthContext login() {
            events.add("auth.operation");
            return super.login();
        }

        @Override
        public void dispose() {
            disposed = true;
            events.add("auth.dispose");
            super.dispose();
        }
    }

    private static final class TrackingStorage extends NoneStorage {
        private final List<String> events;
        private boolean disposed;

        private TrackingStorage(List<String> events) {
            this.events = events;
        }

        @Override
        public void init(com.intel.cosbench.config.Config config, com.intel.cosbench.log.Logger logger) {
            super.init(config, logger);
            events.add("storage.init");
        }

        @Override
        public java.io.InputStream getObject(
                String container, String object, com.intel.cosbench.config.Config config) {
            events.add("storage.operation");
            return super.getObject(container, object, config);
        }

        @Override
        public void dispose() {
            disposed = true;
            events.add("storage.dispose");
            super.dispose();
        }
    }

    private static void assertInOrder(List<String> events, String... expected) {
        int previous = -1;
        for (String event : expected) {
            int current = events.indexOf(event);
            assertTrue(current > previous, "expected " + event + " after index " + previous + " in " + events);
            previous = current;
        }
    }

    private static XmlConfig config(String xml) {
        return new XmlConfig(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static MissionInfo awaitState(
            COSBDriverService service, String id, MissionState expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        MissionInfo info;
        do {
            info = service.getMissionInfo(id);
            if (info != null && info.getState() == expected) {
                return info;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        assertNotNull(info, "mission should remain queryable");
        assertEquals(expected, info.getState());
        return info;
    }
}
