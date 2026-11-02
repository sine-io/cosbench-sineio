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

package com.intel.cosbench.driver.service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import com.intel.cosbench.api.storage.*;
import com.intel.cosbench.config.*;
import com.intel.cosbench.log.*;

/**
 * This class is the major service for storage on driver.
 *
 * @author ywang19, qzheng7
 *
 */
public class COSBStorageAPIService implements StorageAPIService {

    private static final Logger LOGGER = LogFactory.getSystemLogger();

    private List<StorageAPIFactory> factories = List.of();

    public COSBStorageAPIService() {
        /* empty */
    }

    public COSBStorageAPIService(Collection<? extends StorageAPIFactory> factories) {
        setFactories(List.copyOf(Objects.requireNonNull(factories, "storage factories")));
    }

    public void setFactories(List<StorageAPIFactory> factories) {
        this.factories = List.copyOf(Objects.requireNonNull(factories, "storage factories"));
        StringBuilder buffer = new StringBuilder();
        for (StorageAPIFactory factory : factories)
            buffer.append(factory.getStorageName()).append(", ");
        if (buffer.length() >= 2) {
            buffer.setLength(buffer.length() - 2);
        }
        LOGGER.info("detected supported storage types: {}", buffer.length() == 0 ? "none" : buffer);
    }

    @Override
    public StorageAPI getStorage(String type, Config config, Logger logger) {
        StorageAPI storage = createStorage(type);
        try {
            storage.init(config, logger);
            return storage;
        } catch (RuntimeException | LinkageError error) {
            try {
                storage.dispose();
            } catch (RuntimeException | LinkageError disposeError) {
                error.addSuppressed(disposeError);
            }
            throw new ConfigException("failed to initialize storage provider '" + type + "' from "
                    + storage.getClass().getName() + ": " + error.getMessage(), error);
        }
    }

    private StorageAPI createStorage(String type) {
        if (NoneStorage.API_TYPE.equals(type))
            return new NoneStorage();
        for (StorageAPIFactory factory : factories) {
            if (factory.getStorageName().equals(type)) {
                try {
                    return Objects.requireNonNull(factory.getStorageAPI(), "Factory returned null API");
                } catch (RuntimeException | LinkageError error) {
                    throw new ConfigException("failed to create storage provider '" + type + "' from factory "
                            + factory.getClass().getName() + ": " + error.getMessage(), error);
                }
            }
        }
        String msg = "unrecognized storage type: " + type + "; discovered: "
                + factories.stream().map(StorageAPIFactory::getStorageName).sorted().toList();
        throw new ConfigException(msg);
    }

}
