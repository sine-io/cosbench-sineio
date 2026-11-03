/**

Copyright 2013 Intel Corporation, All Rights Reserved.

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

package com.intel.cosbench.controller.service;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang.StringUtils;

import com.intel.cosbench.config.XmlConfig;
import com.intel.cosbench.controller.archiver.*;
import com.intel.cosbench.controller.loader.SimpleWorkloadLoader;
import com.intel.cosbench.controller.model.*;
import com.intel.cosbench.controller.repository.*;
import com.intel.cosbench.log.*;
import com.intel.cosbench.model.*;
import com.intel.cosbench.service.ControllerService;
import com.intel.cosbench.service.WorkloadLoader;

/**
 * This class is the major service for controller.
 *
 * @author ywang19, qzheng7
 *
 */
public class COSBControllerService implements ControllerService, WorkloadListener, AutoCloseable {

    private static final Logger LOGGER = LogFactory.getSystemLogger();

    private AtomicInteger count; /* workload id generator */

    private AtomicInteger order;

    private ControllerContext context;
    private Map<String, WorkloadProcessor> processors;
    private OrderThreadPoolExecutor executor;
    private PingDriverRunner pingDriverRunner;
    private Thread pingDriverThread;
    private WorkloadArchiver archiver;
    private WorkloadLoader loader;
    private final WorkloadRepository memRepo; // in-memory repository to store all workloads information.

    private boolean loadArch = false;
    private volatile boolean accepting;
    private final AtomicBoolean closing = new AtomicBoolean();
    private Duration shutdownGracePeriod = Duration.ofSeconds(30);

    public COSBControllerService() {
        this(new RAMWorkloadRepository());
    }

    public COSBControllerService(WorkloadRepository repository) {
        this.memRepo = Objects.requireNonNull(repository, "workload repository");
    }

    public void setContext(ControllerContext context) {
        this.context = context;

    }

    public void setShutdownGracePeriod(Duration gracePeriod) {
        if (gracePeriod == null || gracePeriod.isNegative() || gracePeriod.isZero()) {
            throw new IllegalArgumentException("shutdown grace period must be positive");
        }
        shutdownGracePeriod = gracePeriod;
    }

    public void init() {
        if(this.context == null) {
            throw new IllegalStateException("Controller Context is not initialized.");
        }

        // initialize workload archiver and loader
        String archive_dir = context.getArchive_dir();
        archiver = new SimpleWorkloadArchiver(archive_dir);
        loader = new SimpleWorkloadLoader(archive_dir);

        count = new AtomicInteger(archiver.getTotalWorkloads());
        order = new AtomicInteger(0);
        processors = new HashMap<String, WorkloadProcessor>();
        processors = Collections.synchronizedMap(processors);
        int concurrency = context.getConcurrency();
        executor = new OrderThreadPoolExecutor(concurrency, concurrency, 0L,
                TimeUnit.MILLISECONDS, new PriorityBlockingQueue<Runnable>(
                        memRepo.getMaxCapacity(),
                        new OrderFutureComparator()));
        pingDriverRunner = new PingDriverRunner(context.getDriverInfos());
        pingDriverThread = new Thread(pingDriverRunner, "cosbench-controller-driver-pinger");
        pingDriverThread.start();
        accepting = true;

    }

    public synchronized void stopAccepting() {
        accepting = false;
    }

    private void requireAccepting() {
        if (!accepting) {
            throw new RejectedExecutionException("Controller is stopping; new workloads are rejected");
        }
    }

    public boolean isAccepting() {
        return accepting;
    }

    public void loadArchivedWorkload() throws IOException {
        List<WorkloadInfo> workloadContexts = loader.loadWorkloadRun();
        if (workloadContexts == null)
            return;
        for (WorkloadInfo workloadContext : workloadContexts)
            memRepo.saveWorkload((WorkloadContext) workloadContext);
    }

    public void unloadArchivedWorkload() {
        for(WorkloadContext workload : memRepo.getArchivedWorkloads()) {
            memRepo.removeWorkload(workload);
            workload = null;
        }
    }


    @Override
    public synchronized String submit(XmlConfig config) {
        requireAccepting();
        LOGGER.debug("[ CT ] - submitting workload ... ");
        WorkloadContext workload = createWorkloadContext(config);
        WorkloadProcessor processor = createProcessor(workload);
        workload.addListener(this);
        processors.put(workload.getId(), processor);
        memRepo.saveWorkload(workload);
        LOGGER.debug("[ CT ] - workload {} submitted", workload.getId());
        return workload.getId();
    }

    @Override
    public String resubmit(String id) throws IOException {
        XmlConfig config = SimpleWorkloadLoader.getWorkloadConfg(memRepo
                .getWorkload(id));
        if (config != null)
            return submit(config);
        LOGGER.debug(
                "[ CT ] - workload {} resubmitted failed, has no workload config",
                id);
        throw new FileNotFoundException("configuration file for workload " + id);
    }

    private WorkloadContext createWorkloadContext(XmlConfig config) {
        WorkloadContext context = new WorkloadContext();
        context.setId(generateWorkloadId());
        context.setOrder(generateOrder());
        context.setSubmitDate(new Date());
        context.setConfig(config);
        context.setState(WorkloadState.QUEUING);
        return context;
    }

    public WorkloadLoader getWorkloadLoader() {
        return loader;
    }

    public boolean getloadArch() {
        return loadArch;
    }

    public void setloadArch(boolean loadArch) {
        this.loadArch = loadArch;

        if(getloadArch()){
            try {
                loadArchivedWorkload();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            unloadArchivedWorkload();
        }
    }

    private String generateWorkloadId() {
        return "w" + count.incrementAndGet();
    }

    private int generateOrder() {
        return order.incrementAndGet();
    }

    private WorkloadProcessor createProcessor(WorkloadContext workload) {
        WorkloadProcessor processor = new WorkloadProcessor();
        processor.setControllerContext(context);
        processor.setWorkloadContext(workload);
        processor.init();
        return processor;
    }

    @Override
    public synchronized void fire(String id) {
        requireAccepting();
        if(id == null) {
            throw new IllegalStateException("invalid workload id.");
        }
        final WorkloadProcessor processor = processors.get(id);
        if (processor == null) {
            throw new IllegalStateException("workload processor is not initialized.");
        }
        LOGGER.debug("[ CT ] - starting workload {} ...", id);
        /* for strong consistency: a lock should be employed here */
        if (processor.getWorkloadContext().getFuture() != null)
            throw new IllegalStateException();
        synchronized(processor) {
            Future<?> future = enqueue(processor);
            processor.getWorkloadContext().setFuture(future);
        }
        LOGGER.debug("[ CT ] - workload {} started", id);
        yieldExecution(200); // give workload processor a chance
    }

    @Override
    public synchronized boolean changeOrder(String id, String neighbourWId, boolean up) {
        requireAccepting();
        if (StringUtils.isEmpty(neighbourWId)) {
            return changeOrder(id, up);
        }
        if ("0".equals(neighbourWId)) // multiple checked workload id
            return false;
        WorkloadProcessor moving = processors.get(id);
        WorkloadProcessor neighbour = processors.get(neighbourWId);
        if (moving == null || neighbour == null)
            return false;
        WorkloadContext movingContext = moving.getWorkloadContext();
        WorkloadContext neighbourContext = neighbour.getWorkloadContext();
        int movingOrder = movingContext.getOrder();
        int neighbourOrder = neighbourContext.getOrder();
        if ((up && movingOrder <= neighbourOrder) || (!up && movingOrder >= neighbourOrder))
            return false;

        int lowOrder = Math.min(movingOrder, neighbourOrder);
        int highOrder = Math.max(movingOrder, neighbourOrder);
        List<WorkloadContext> affected = Arrays.stream(getActiveWorkloads())
                .filter(workload -> workload.getOrder() >= lowOrder && workload.getOrder() <= highOrder)
                .sorted(Comparator.comparingInt(WorkloadContext::getOrder))
                .toList();
        if (affected.size() < 2 || affected.stream()
                .anyMatch(workload -> workload.getState() != WorkloadState.QUEUING)) {
            return false;
        }

        List<Future<?>> removed = new ArrayList<>(affected.size());
        for (WorkloadContext workload : affected) {
            Future<?> future = workload.getFuture();
            if (future == null || !executor.removeQueued(future)) {
                removed.forEach(executor::restoreQueued);
                return false;
            }
            removed.add(future);
        }

        for (Future<?> future : removed) {
            if (!future.cancel(false)) {
                for (WorkloadContext workload : affected) {
                    WorkloadProcessor processor = processors.get(workload.getId());
                    workload.setFuture(enqueue(processor));
                }
                return false;
            }
        }

        for (WorkloadContext workload : affected) {
            int newOrder = workload.getOrder();
            if (up) {
                if (workload == movingContext) {
                    newOrder = neighbourOrder;
                } else if (workload.getOrder() >= neighbourOrder) {
                    newOrder++;
                }
            } else {
                if (workload == movingContext) {
                    newOrder = neighbourOrder;
                } else if (workload.getOrder() <= neighbourOrder) {
                    newOrder--;
                }
            }
            workload.setOrder(newOrder);
            workload.setFuture(null);
        }
        affected.stream().sorted(Comparator.comparingInt(WorkloadContext::getOrder))
                .forEach(workload -> {
                    workload.setFuture(enqueue(processors.get(workload.getId())));
                });
        return true;
    }

    private Future<?> enqueue(WorkloadProcessor processor) {
        return executor.submit(new ControllerThread(processor));
    }

    public synchronized boolean changeOrder(String id, boolean up) {
        requireAccepting();
        WorkloadProcessor processor = processors.get(id);
        if (processor == null || processor.getWorkloadContext().getState() != WorkloadState.QUEUING)
            return false;
        WorkloadContext moving = processor.getWorkloadContext();
        List<WorkloadContext> active = Arrays.stream(getActiveWorkloads())
                .sorted(Comparator.comparingInt(WorkloadContext::getOrder)).toList();
        WorkloadContext neighbour = null;
        if (up) {
            for (int i = active.size() - 1; i >= 0; i--) {
                if (active.get(i).getOrder() < moving.getOrder()) {
                    neighbour = active.get(i);
                    break;
                }
            }
        } else {
            for (WorkloadContext candidate : active) {
                if (candidate.getOrder() > moving.getOrder()) {
                    neighbour = candidate;
                    break;
                }
            }
        }
        return neighbour != null && changeOrder(id, neighbour.getId(), up);
    }

    @Override
    public synchronized void cancel(String id) {
        WorkloadProcessor processor = processors.get(id);
        if (processor == null)
            return; // already stopped
        LOGGER.debug("[ CT ] - canceling workload{} ...", id);
        processor.cancel();
        yieldExecution(500); // give workload processor a chance
        LOGGER.debug("[ CT ] - workload {} cancelled", id);
    }

    private static void yieldExecution(int time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            LOGGER.warn("get interrupted when performing yield");
            Thread.currentThread().interrupt(); // re-interrupt
        }
    }

    @Override
    public ControllerInfo getControllerInfo() {
        return context;
    }

    @Override
    public WorkloadContext getWorkloadInfo(String id) {
        return memRepo.getWorkload(id);
    }

    @Override
    public WorkloadContext[] getActiveWorkloads() {
        return memRepo.getActiveWorkloads();
    }

    @Override
    public WorkloadContext[] getHistoryWorkloads() {
        return memRepo.getInactiveWorkloads();
    }

    @Override
    public WorkloadInfo[] getArchivedWorkloads() {
        return memRepo.getArchivedWorkloads();
    }

    @Override
    public void workloadStarted(WorkloadContext workload) {
        /* empty */
    }

    @Override
    public void workloadStopped(WorkloadContext workload) {
        String id = workload.getId();
        WorkloadProcessor processor = processors.remove(id);
        processor.dispose();
        archiver.archive(workload);
        LOGGER.debug("processor for workload {} has been detached", id);
    }

    @Override
    public File getWorkloadLog(WorkloadInfo info) {
        return archiver.getWorkloadLog(info);
    }

    @Override
    public File getWorkloadConfig(WorkloadInfo info) {
        return archiver.getWorkloadConfig(info);
    }

    /** Idempotent shutdown: close admission, drain accepted work, then interrupt at the deadline. */
    @Override
    public void close() {
        stopAccepting();
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        long deadline = System.nanoTime() + shutdownGracePeriod.toNanos();
        if (pingDriverRunner != null) {
            pingDriverRunner.requestStop();
            pingDriverThread.interrupt();
        }
        if (executor != null) {
            executor.shutdown();
            // Submitted but never fired workloads have no executor task to drain.
            for (WorkloadProcessor processor : processorsSnapshot()) {
                if (processor.getWorkloadContext().getFuture() == null) {
                    processor.cancel();
                }
            }
            try {
                // Include cold archive generation in the budget, especially for short grace periods.
                long cancellationBudget = Math.min(TimeUnit.SECONDS.toNanos(1), shutdownGracePeriod.toNanos() / 2);
                if (!executor.awaitTermination(Math.max(0, deadline - System.nanoTime() - cancellationBudget), TimeUnit.NANOSECONDS)) {
                    LOGGER.warn("Controller drain budget exhausted; interrupting local workloads");
                    interruptWorkloads();
                    executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                }
            } catch (InterruptedException e) {
                interruptWorkloads();
                Thread.currentThread().interrupt();
            }
        }
        if (pingDriverThread != null) {
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    TimeUnit.NANOSECONDS.timedJoin(pingDriverThread, remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        boolean workersStopped = executor == null || executor.isTerminated();
        boolean heartbeatStopped = pingDriverThread == null || !pingDriverThread.isAlive();
        if (workersStopped && heartbeatStopped) {
            LOGGER.info("Controller stopped: executor terminated, heartbeat terminated");
        } else {
            LOGGER.error("Controller shutdown incomplete: executorTerminated={}, heartbeatTerminated={}",
                    workersStopped, heartbeatStopped);
        }
    }

    private List<WorkloadProcessor> processorsSnapshot() {
        if (processors == null) {
            return Collections.emptyList();
        }
        synchronized (processors) {
            return new ArrayList<>(processors.values());
        }
    }

    private void interruptWorkloads() {
        for (WorkloadProcessor processor : processorsSnapshot()) {
            processor.stopNow();
        }
        executor.shutdownNow();
    }

}
