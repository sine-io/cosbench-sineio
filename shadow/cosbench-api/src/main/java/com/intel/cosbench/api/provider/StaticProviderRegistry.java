package com.intel.cosbench.api.provider;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.intel.cosbench.api.auth.AuthAPI;
import com.intel.cosbench.api.auth.AuthAPIFactory;
import com.intel.cosbench.api.storage.StorageAPI;
import com.intel.cosbench.api.storage.StorageAPIFactory;
import com.intel.cosbench.config.Config;
import com.intel.cosbench.log.Logger;

public final class StaticProviderRegistry {
    private final SortedMap<String, StorageAPIFactory> storageFactories;
    private final SortedMap<String, AuthAPIFactory> authFactories;

    private StaticProviderRegistry(
            SortedMap<String, StorageAPIFactory> storageFactories,
            SortedMap<String, AuthAPIFactory> authFactories) {
        this.storageFactories = Collections.unmodifiableSortedMap(storageFactories);
        this.authFactories = Collections.unmodifiableSortedMap(authFactories);
    }

    public static StaticProviderRegistry discover(
            ClassLoader classLoader,
            Collection<String> expectedStorageNames,
            Collection<String> expectedAuthNames) {
        Objects.requireNonNull(classLoader, "classLoader");
        SortedMap<String, StorageAPIFactory> storageFactories = discoverFactories(
                StorageAPIFactory.class,
                StorageAPIFactory::getStorageName,
                classLoader,
                expectedStorageNames,
                "storage");
        SortedMap<String, AuthAPIFactory> authFactories = discoverFactories(
                AuthAPIFactory.class,
                AuthAPIFactory::getAuthName,
                classLoader,
                expectedAuthNames,
                "auth");
        return new StaticProviderRegistry(storageFactories, authFactories);
    }

    public Set<String> storageNames() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(storageFactories.keySet()));
    }

    public Set<String> authNames() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(authFactories.keySet()));
    }

    /**
     * Returns the discovered storage factories in deterministic provider-name order.
     * The registry owns discovery; consumers receive an immutable snapshot and never
     * need to call {@link ServiceLoader} themselves.
     */
    public Collection<StorageAPIFactory> storageFactories() {
        return List.copyOf(storageFactories.values());
    }

    /**
     * Returns the discovered auth factories in deterministic provider-name order.
     */
    public Collection<AuthAPIFactory> authFactories() {
        return List.copyOf(authFactories.values());
    }

    public StorageAPI createStorage(String name, Config config, Logger logger) {
        StorageAPIFactory factory = storageFactories.get(name);
        if (factory == null) {
            throw unknownProvider("storage", name, storageFactories);
        }
        return initialize(
                "storage",
                name,
                factory.getClass().getName(),
                factory::getStorageAPI,
                api -> api.init(config, logger),
                StorageAPI::dispose);
    }

    public AuthAPI createAuth(String name, Config config, Logger logger) {
        AuthAPIFactory factory = authFactories.get(name);
        if (factory == null) {
            throw unknownProvider("auth", name, authFactories);
        }
        return initialize(
                "auth",
                name,
                factory.getClass().getName(),
                factory::getAuthAPI,
                api -> api.init(config, logger),
                AuthAPI::dispose);
    }

    private static <F> SortedMap<String, F> discoverFactories(
            Class<F> serviceType,
            Function<F, String> nameOf,
            ClassLoader classLoader,
            Collection<String> expectedNames,
            String providerKind) {
        TreeMap<String, F> factories = new TreeMap<>();
        Iterator<F> providers = ServiceLoader.load(serviceType, classLoader).iterator();
        while (true) {
            F factory;
            try {
                if (!providers.hasNext()) {
                    break;
                }
                factory = providers.next();
            } catch (ServiceConfigurationError | LinkageError error) {
                throw new ProviderRegistryException(
                        "Unable to load " + providerKind + " provider from " + serviceType.getName()
                                + ": " + error.getMessage(),
                        error);
            }

            String name;
            try {
                name = nameOf.apply(factory);
            } catch (RuntimeException | LinkageError error) {
                throw new ProviderRegistryException(
                        "Unable to read " + providerKind + " provider name from "
                                + factory.getClass().getName() + ": " + error.getMessage(),
                        error);
            }
            if (name == null || name.isBlank()) {
                throw new ProviderRegistryException(
                        "Invalid " + providerKind + " provider name from " + factory.getClass().getName());
            }

            F duplicate = factories.putIfAbsent(name, factory);
            if (duplicate != null) {
                throw new ProviderRegistryException(
                        "Duplicate " + providerKind + " provider name '" + name + "' from "
                                + duplicate.getClass().getName() + " and " + factory.getClass().getName());
            }
        }

        TreeSet<String> missing = new TreeSet<>(Objects.requireNonNull(expectedNames, "expectedNames"));
        missing.removeAll(factories.keySet());
        if (!missing.isEmpty()) {
            throw new ProviderRegistryException(
                    "Missing " + providerKind + " factory descriptor(s) for expected provider(s): " + missing);
        }
        return factories;
    }

    private static <A> A initialize(
            String providerKind,
            String providerName,
            String factoryClass,
            Supplier<A> create,
            Consumer<A> initialize,
            Consumer<A> dispose) {
        A api = null;
        try {
            api = Objects.requireNonNull(create.get(), "Factory returned null API");
            initialize.accept(api);
            return api;
        } catch (RuntimeException | LinkageError error) {
            if (api != null) {
                try {
                    dispose.accept(api);
                } catch (RuntimeException | LinkageError disposeError) {
                    error.addSuppressed(disposeError);
                }
            }
            throw new ProviderRegistryException(
                    "Failed to initialize " + providerKind + " provider '" + providerName
                            + "' from factory " + factoryClass + ": " + error.getMessage(),
                    error);
        }
    }

    private static ProviderRegistryException unknownProvider(String kind, String name, Map<String, ?> providers) {
        return new ProviderRegistryException(
                "Unknown " + kind + " provider '" + name + "'; discovered: " + providers.keySet());
    }

    public static final class ProviderRegistryException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public ProviderRegistryException(String message) {
            super(message);
        }

        public ProviderRegistryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
