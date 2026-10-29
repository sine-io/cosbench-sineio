package com.intel.cosbench.shadow.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.intel.cosbench.api.provider.StaticProviderRegistry;
import com.intel.cosbench.api.storage.NoneStorage;
import com.intel.cosbench.api.storage.StorageAPI;
import com.intel.cosbench.api.storage.StorageAPIFactory;
import com.intel.cosbench.config.Config;
import com.intel.cosbench.log.Logger;

class StaticProviderRegistryTest {
    private static final String STORAGE_SERVICE = "META-INF/services/" + StorageAPIFactory.class.getName();

    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsExpectedProviderNamesWhenStorageDescriptorIsMissing() {
        StaticProviderRegistry.ProviderRegistryException error = assertThrows(
                StaticProviderRegistry.ProviderRegistryException.class,
                () -> StaticProviderRegistry.discover(descriptorClassLoader(), Set.of("sio"), Set.of()));

        assertTrue(error.getMessage().contains("sio"));
        assertTrue(error.getMessage().contains("descriptor"));
    }

    @Test
    void reportsBothFactoryClassesWhenNamesCollide() throws IOException {
        writeDescriptor(TestDuplicateFactoryA.class.getName() + "\n" + TestDuplicateFactoryB.class.getName());

        StaticProviderRegistry.ProviderRegistryException error = assertThrows(
                StaticProviderRegistry.ProviderRegistryException.class,
                () -> StaticProviderRegistry.discover(descriptorClassLoader(), Set.of(), Set.of()));

        assertTrue(error.getMessage().contains(TestDuplicateFactoryA.class.getName()));
        assertTrue(error.getMessage().contains(TestDuplicateFactoryB.class.getName()));
        assertTrue(error.getMessage().contains("duplicate"));
    }

    @Test
    void reportsProviderNameWhenDescriptorReferencesMissingDependency() throws IOException {
        String missingFactory = "com.intel.cosbench.shadow.provider.MissingStorageFactory";
        writeDescriptor(missingFactory);

        StaticProviderRegistry.ProviderRegistryException error = assertThrows(
                StaticProviderRegistry.ProviderRegistryException.class,
                () -> StaticProviderRegistry.discover(descriptorClassLoader(), Set.of(), Set.of()));

        assertTrue(error.getMessage().contains(missingFactory));
        assertTrue(error.getMessage().contains("StorageAPIFactory"));
    }

    @Test
    void disposesStorageWhenInitializationFails() throws IOException {
        FailingStorage.DISPOSED.set(false);
        writeDescriptor(FailingStorageFactory.class.getName());
        StaticProviderRegistry registry = StaticProviderRegistry.discover(
                descriptorClassLoader(), Set.of("broken"), Set.of());

        StaticProviderRegistry.ProviderRegistryException error = assertThrows(
                StaticProviderRegistry.ProviderRegistryException.class,
                () -> registry.createStorage("broken", null, null));

        assertTrue(error.getMessage().contains("broken"));
        assertTrue(error.getCause().getMessage().contains("invalid endpoint"));
        assertTrue(FailingStorage.DISPOSED.get());
    }

    @Test
    void exposesNamesInStableSortedOrder() {
        StaticProviderRegistry registry = StaticProviderRegistry.discover(
                getClass().getClassLoader(), Set.of("sio", "siov1", "gdas", "s3", "mock"), Set.of("mock"));

        assertEquals(List.of("gdas", "mock", "s3", "sio", "siov1"), List.copyOf(registry.storageNames()));
        assertEquals(Set.of("mock"), registry.authNames());
        assertFalse(registry.storageNames().contains("swift"));
    }

    private void writeDescriptor(String providerClasses) throws IOException {
        Path descriptor = temporaryDirectory.resolve(STORAGE_SERVICE);
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, providerClasses + System.lineSeparator());
    }

    private ClassLoader descriptorClassLoader() {
        return new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (name.equals(STORAGE_SERVICE)) {
                    Path descriptor = temporaryDirectory.resolve(STORAGE_SERVICE);
                    return Files.exists(descriptor)
                            ? Collections.enumeration(Set.of(descriptor.toUri().toURL()))
                            : Collections.emptyEnumeration();
                }
                if (name.equals("META-INF/services/com.intel.cosbench.api.auth.AuthAPIFactory")) {
                    return Collections.emptyEnumeration();
                }
                return super.getResources(name);
            }
        };
    }

    public static final class TestDuplicateFactoryA implements StorageAPIFactory {
        @Override
        public String getStorageName() {
            return "duplicate";
        }

        @Override
        public StorageAPI getStorageAPI() {
            return new NoneStorage();
        }
    }

    public static final class TestDuplicateFactoryB implements StorageAPIFactory {
        @Override
        public String getStorageName() {
            return "duplicate";
        }

        @Override
        public StorageAPI getStorageAPI() {
            return new NoneStorage();
        }
    }

    public static final class FailingStorageFactory implements StorageAPIFactory {
        @Override
        public String getStorageName() {
            return "broken";
        }

        @Override
        public StorageAPI getStorageAPI() {
            return new FailingStorage();
        }
    }

    private static final class FailingStorage extends NoneStorage {
        private static final AtomicBoolean DISPOSED = new AtomicBoolean();

        @Override
        public void init(Config config, Logger logger) {
            throw new IllegalArgumentException("invalid endpoint");
        }

        @Override
        public void dispose() {
            DISPOSED.set(true);
        }
    }
}
