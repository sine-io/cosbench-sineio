package com.intel.cosbench.shadow.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.intel.cosbench.api.auth.AuthAPIFactory;
import com.intel.cosbench.api.storage.StorageAPIFactory;

class ServiceLoaderDiscoveryTest {
    @Test
    void declaresEverySupportedStorageFactoryAndKeepsMockAuthTestOnly() {
        Set<String> storageNames = new TreeSet<>();
        ServiceLoader.load(StorageAPIFactory.class).forEach(factory -> storageNames.add(factory.getStorageName()));

        Set<String> authNames = new TreeSet<>();
        ServiceLoader.load(AuthAPIFactory.class).forEach(factory -> authNames.add(factory.getAuthName()));

        assertEquals(Set.of("mock", "s3", "sio", "siov1", "gdas"), storageNames);
        assertEquals(Set.of("mock"), authNames);
    }
}
