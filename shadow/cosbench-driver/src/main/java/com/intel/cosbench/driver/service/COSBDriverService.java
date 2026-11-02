/**

Copyright 2013 Intel Corporation, All Rights Reserved.
Copyright 2019 OpenIO Corporation, All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.intel.cosbench.driver.service;

import static com.intel.cosbench.model.MissionState.SUBMITTED;

import java.util.*;
import java.time.Duration;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang.math.RandomUtils;

import com.intel.cosbench.api.auth.AuthAPIService;
import com.intel.cosbench.api.storage.StorageAPIService;
import com.intel.cosbench.config.XmlConfig;
import com.intel.cosbench.driver.model.*;
import com.intel.cosbench.driver.repository.*;
import com.intel.cosbench.log.*;
import com.intel.cosbench.model.*;
import com.intel.cosbench.service.*;
import com.intel.cosbench.service.IllegalStateException;

/**
 * This class is the major service for driver.
 *
 * @author ywang19, qzheng7
 *
 */
public class COSBDriverService implements DriverService, MissionListener, AutoCloseable {

    private static final Logger LOGGER = LogFactory.getSystemLogger();

    private DriverContext context;
    private Map<String, MissionHandler> handlers = new ConcurrentHashMap<>();

    private AuthAPIService authAPIs;
    private StorageAPIService storageAPIs;

    private ExecutorService executor;
    private final MissionRepository memRepo = new RAMMissionRepository();
    private volatile boolean accepting;
    private final AtomicBoolean closing = new AtomicBoolean();
    private Duration shutdownGracePeriod = Duration.ofSeconds(30);
    private TriggerService triggerService;

    public COSBDriverService() {
        /* empty */
    }

    public COSBDriverService(
            DriverContext context,
            AuthAPIService authAPIs,
            StorageAPIService storageAPIs,
            Duration shutdownGracePeriod) {
        this.context = Objects.requireNonNull(context, "driver context");
        this.authAPIs = Objects.requireNonNull(authAPIs, "auth API service");
        this.storageAPIs = Objects.requireNonNull(storageAPIs, "storage API service");
        setShutdownGracePeriod(shutdownGracePeriod);
    }

    public void setShutdownGracePeriod(Duration shutdownGracePeriod) {
        if (shutdownGracePeriod == null || shutdownGracePeriod.isNegative() || shutdownGracePeriod.isZero()) {
            throw new IllegalArgumentException("shutdown grace period must be positive");
        }
        this.shutdownGracePeriod = shutdownGracePeriod;
    }

    public void setContext(DriverContext context) {
        this.context = context;
    }

    public void setAuthAPIs(AuthAPIService authAPIs) {
        this.authAPIs = authAPIs;
    }

    public void setStorageAPIs(StorageAPIService storageAPIs) {
        this.storageAPIs = storageAPIs;
    }

    public void init() {
        if (this.context == null) {
            throw new IllegalStateException("Driver Context is not initialized.");
        }
        Objects.requireNonNull(authAPIs, "auth API service");
        Objects.requireNonNull(storageAPIs, "storage API service");
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "cosbench-driver-mission");
            thread.setDaemon(false);
            return thread;
        });
        Path scriptsDirectory = context.getScripts_dir() == null || context.getScripts_dir().isBlank()
                ? Path.of("scripts") : Path.of(context.getScripts_dir());
        triggerService = new TriggerService(scriptsDirectory);
        context.setAliveState(true);
        accepting = true;
    }

    public synchronized void stopAccepting() {
        accepting = false;
    }

    public boolean isAccepting() {
        return accepting;
    }

    @Override
    public synchronized String submit(XmlConfig config) {
        requireAccepting();
        LOGGER.debug("submitting mission ... ");

        MissionContext mission = createMissionContext(config);
        MissionHandler handler = createHandler(mission);
        mission.addListener(this);
        handlers.put(mission.getId(), handler);
        memRepo.saveMission(mission);
        LOGGER.debug("mission {} has been submitted", mission.getId());
        return mission.getId();
    }

    private void requireAccepting() {
        if (!accepting || closing.get()) {
            throw new RejectedExecutionException("Driver is stopping; new missions are rejected");
        }
    }

    private synchronized Future<?> submitMissionTask(MissionHandler handler, Runnable task) {
        requireAccepting();
        synchronized (handler) {
            MissionContext mission = handler.getMissionContext();
            if (mission.getFuture() != null) {
                throw new IllegalStateException("mission is busy");
            }

            FutureTask<Void> future = new FutureTask<>(task, null);
            mission.setFuture(future);
            try {
                executor.execute(future);
            } catch (RuntimeException error) {
                mission.setFuture(null);
                throw error;
            }
            return future;
        }
    }

    private static MissionContext createMissionContext(XmlConfig config) {
        MissionContext context = new MissionContext();
        context.setId(generateMissionId());
        context.setDate(new Date());
        context.setConfig(config);
        context.setState(SUBMITTED);
        return context;
    }

    private static String generateMissionId() {
        String id = "M" + Integer.toHexString(RandomUtils.nextInt(16));
        id += Integer.toHexString(new Date().hashCode());
        id += Integer.toHexString(RandomUtils.nextInt(16));
        return id.toUpperCase();
    }

    private MissionHandler createHandler(MissionContext mission) {
        MissionHandler handler = new MissionHandler(context.getMission_dir());
        handler.setMissionContext(mission);
        handler.setAuthAPIs(authAPIs);
        handler.setStorageAPIs(storageAPIs);
        try {
            handler.init(); // configurations will be parsed
            return handler;
        } catch (RuntimeException | LinkageError error) {
            handler.dispose();
            throw error;
        }
    }

    @Override
    public void login(String id) {
        requireAccepting();
        final MissionHandler handler = handlers.get(id);
        LOGGER.info("handler=" + id);
        if (handler == null)
            throw new IllegalStateException("no mission handler");
        class AuthThread implements Runnable {

            @Override
            public void run() {
                handler.login(); // errors are reflected in state
                handler.getMissionContext().setFuture(null);
            }

        }
        LOGGER.debug("authing mission {} ...", id);
        Future<?> future = submitMissionTask(handler, new AuthThread());
        LOGGER.debug("mission {} has been requested to auth", id);
        yieldExecution(200); // give mission handler a chance
        awaitTermination(future); // mission may be terminated or aborted
    }

    @Override
    public void launch(String id) {
        requireAccepting();
        final MissionHandler handler = handlers.get(id);
        if (handler == null)
            throw new IllegalStateException("no mission handler");
        class DriverThread implements Runnable {

            @Override
            public void run() {
                handler.stress(); // errors are reflected in state
                handler.getMissionContext().setFuture(null);
            }

        }
        LOGGER.debug("launching mission {} ...", id);
        Future<?> future = submitMissionTask(handler, new DriverThread());

        LOGGER.debug("mission {} has been requested to launch", id);
        yieldExecution(200); // give mission handler a chance
    }

    @Override
    public void close(String id) {
        MissionHandler handler = handlers.get(id);
        if (handler == null)
            throw new IllegalStateException("no mission handler");
        LOGGER.debug("closing mission {} ... ", id);
        handler.close();
        LOGGER.debug("mission {} has been closed", id);
    }

    @Override
    public void abort(String id) {
        MissionHandler handler = handlers.get(id);
        if (handler == null)
            return; // already stopped
        LOGGER.debug("aborting mission {} ... ", id);
        handler.abort();
        yieldExecution(200); // give mission handler a chance
        LOGGER.debug("mission {} has been requested to abort", id);
    }

    @Override
    public com.intel.cosbench.protocol.TriggerResponse trigger(String command) {
        requireAccepting();
        return triggerService.execute(command);
    }

    /** Stops admission and gives accepted mission work one bounded drain window. */
    @Override
    public void close() {
        stopAccepting();
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        if (triggerService != null) {
            triggerService.close();
        }
        if (executor == null) {
            return;
        }

        long deadline = System.nanoTime() + shutdownGracePeriod.toNanos();
        executor.shutdown();
        try {
            long drainBudget = Math.max(0, shutdownGracePeriod.toNanos() / 2);
            executor.awaitTermination(drainBudget, TimeUnit.NANOSECONDS);
            // Idle submitted missions have no executor task to drain, but still own
            // provider clients and mission log handles.
            abortOutstandingMissions();
            long remaining = Math.max(0, deadline - System.nanoTime());
            if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
                abortOutstandingMissions();
                remaining = Math.max(0, deadline - System.nanoTime());
                if (remaining > 0) {
                    executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                }
            }
        } catch (InterruptedException e) {
            for (Map.Entry<String, MissionHandler> entry : handlersSnapshot()) {
                entry.getValue().abort();
            }
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (executor.isTerminated()) {
            LOGGER.info("Driver stopped: mission executor terminated");
        } else {
            LOGGER.error("Driver shutdown incomplete: mission executor did not terminate within {}", shutdownGracePeriod);
        }
        context.setAliveState(false);
    }

    private List<Map.Entry<String, MissionHandler>> handlersSnapshot() {
        return new ArrayList<>(handlers.entrySet());
    }

    private void abortOutstandingMissions() {
        for (Map.Entry<String, MissionHandler> entry : handlersSnapshot()) {
            LOGGER.info("aborting mission {} during Driver shutdown", entry.getKey());
            MissionState state = entry.getValue().getMissionContext().getState();
            if (state == MissionState.FINISHED) {
                entry.getValue().close();
            } else {
                entry.getValue().abort();
            }
        }
    }

    private static void yieldExecution(int time) {
        try {
            Thread.sleep(time); // yield execution
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // re-interrupt
        }
    }

    private static void awaitTermination(Future<?> future) {
        try {
            if(future != null)
                future.get(); // wait forever
        } catch (CancellationException ce) {
            LOGGER.warn("task has been cancelled!", ce);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt(); // re-interrupt
        } catch (Exception ee) {
            LOGGER.error("unexcepted exception", ee);
            throw new UnexpectedException(ee); // stop execution
        }
    }

    @Override
    public DriverInfo getDriverInfo() {
        return context;
    }

    @Override
    public MissionInfo getMissionInfo(String id) {
        return memRepo.getMission(id);
    }

    @Override
    public MissionInfo[] getActiveMissions() {
        return memRepo.getActiveMissions();
    }

    @Override
    public MissionInfo[] getHistoryMissions() {
        return memRepo.getInactiveMissions();
    }

    @Override
    public void missionStopped(MissionContext mission) {
        String id = mission.getId();
        MissionHandler handler = handlers.remove(id);
        if (handler != null) {
            handler.dispose();
        }
        LOGGER.debug("handler for mission {} has been detached", id);
    }
}
