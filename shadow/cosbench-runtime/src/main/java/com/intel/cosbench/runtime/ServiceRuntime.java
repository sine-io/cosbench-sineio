package com.intel.cosbench.runtime;

import java.time.Instant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.SmartLifecycle;

import com.intel.cosbench.config.runtime.ServiceConfig;

/** Owns the process-local acceptance gate and executor so shutdown can be observed and diagnosed. */
public final class ServiceRuntime implements SmartLifecycle {

    private final ServiceConfig config;
    private final ArchiveReader archiveReader;
    private final ExecutorService executor;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicInteger activeTasks = new AtomicInteger();
    private final Instant startedAt = Instant.now();
    private volatile Instant stoppedAt;
    private volatile boolean running = true;

    public ServiceRuntime(ServiceConfig config, ArchiveReader archiveReader) {
        this.config = config;
        this.archiveReader = archiveReader;
        createRuntimeDirectories();
        this.executor = Executors.newFixedThreadPool(config.concurrency(), runnable -> {
            Thread thread = new Thread(runnable, "cosbench-" + config.role().wireName() + "-worker");
            thread.setDaemon(false);
            return thread;
        });
    }

    private void createRuntimeDirectories() {
        try {
            Path base = archiveReader.baseDirectory();
            Files.createDirectories(config.paths().workspaceAt(base));
            Files.createDirectories(config.paths().archiveAt(base));
            Files.createDirectories(config.paths().missionAt(base));
            Path log = config.paths().logsAt(base);
            if (log.getParent() != null) {
                Files.createDirectories(log.getParent());
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot create configured runtime directories", e);
        }
    }

    public ServiceConfig config() {
        return config;
    }

    public ArchiveReader archiveReader() {
        return archiveReader;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant stoppedAt() {
        return stoppedAt;
    }

    public boolean isReady() {
        return ready.get() && running;
    }

    public boolean isAccepting() {
        return accepting.get();
    }

    public int activeTasks() {
        return activeTasks.get();
    }

    public void markReady() {
        ready.set(true);
    }

    public TaskAdmission submit(Runnable task) {
        if (!accepting.get()) {
            return TaskAdmission.REJECTED;
        }
        activeTasks.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    activeTasks.decrementAndGet();
                }
            });
            return TaskAdmission.ACCEPTED;
        } catch (RuntimeException e) {
            activeTasks.decrementAndGet();
            return TaskAdmission.REJECTED;
        }
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        stop(() -> { });
    }

    @Override
    public void stop(Runnable callback) {
        accepting.set(false);
        running = false;
        stoppedAt = Instant.now();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(config.shutdown().gracePeriod().toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public enum TaskAdmission {
        ACCEPTED,
        REJECTED
    }
}
