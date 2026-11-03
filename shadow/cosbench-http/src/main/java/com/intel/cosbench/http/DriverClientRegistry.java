package com.intel.cosbench.http;

import java.net.URI;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/** Owns and reuses one Controller HTTP adapter per Driver endpoint. */
public final class DriverClientRegistry implements AutoCloseable {

    private final Map<URI, ClientEntry> clients = new HashMap<>();
    private final java.util.function.Function<URI, AuthenticatedDriverClient> clientFactory;
    private boolean closed;
    private final java.util.concurrent.ScheduledExecutorService reaper =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "cosbench-driver-http-client-reaper");
                thread.setDaemon(true);
                return thread;
            });

    public DriverClientRegistry(java.util.function.Function<URI, AuthenticatedDriverClient> clientFactory) {
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        startReaper();
    }

    private void startReaper() {
        reaper.scheduleWithFixedDelay(this::evictIdle, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    public static DriverClientRegistry pooled(HttpClientSettings settings, String token,
            javax.net.ssl.SSLContext sslContext) {
        Objects.requireNonNull(settings, "settings");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("service token is required");
        }
        Semaphore globalSlots = new Semaphore(settings.maxConnections(), true);
        return new DriverClientRegistry(endpoint ->
                AuthenticatedDriverClient.pooled(endpoint, token, settings, sslContext, globalSlots), globalSlots);
    }

    public static DriverClientRegistry pooled(HttpClientSettings settings,
            java.util.function.BiFunction<URI, Semaphore, AuthenticatedDriverClient> clientFactory) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(clientFactory, "clientFactory");
        Semaphore globalSlots = new Semaphore(settings.maxConnections(), true);
        return new DriverClientRegistry(endpoint -> clientFactory.apply(endpoint, globalSlots), globalSlots);
    }

    private DriverClientRegistry(java.util.function.Function<URI, AuthenticatedDriverClient> endpointFactory,
            Semaphore globalSlots) {
        this.clientFactory = endpointFactory;
        Objects.requireNonNull(globalSlots, "globalSlots");
        startReaper();
    }

    public synchronized AuthenticatedDriverClient client(URI endpoint) {
        if (closed) {
            throw new IllegalStateException("Driver HTTP client registry is closed");
        }
        Objects.requireNonNull(endpoint, "endpoint");
        evictIdleLocked();
        ClientEntry entry = clients.computeIfAbsent(endpoint, ignored -> new ClientEntry(clientFactory.apply(endpoint)));
        return entry.client;
    }

    public synchronized int size() {
        evictIdleLocked();
        return clients.size();
    }

    public synchronized void evictIdle() {
        evictIdleLocked();
    }

    private void evictIdleLocked() {
        Iterator<Map.Entry<URI, ClientEntry>> iterator = clients.entrySet().iterator();
        while (iterator.hasNext()) {
            ClientEntry entry = iterator.next().getValue();
            if (entry.client.closeIfIdle()) {
                iterator.remove();
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        reaper.shutdownNow();
        clients.values().forEach(entry -> entry.client.close());
        clients.clear();
    }

    private static final class ClientEntry {
        private final AuthenticatedDriverClient client;

        private ClientEntry(AuthenticatedDriverClient client) {
            this.client = client;
        }
    }
}
