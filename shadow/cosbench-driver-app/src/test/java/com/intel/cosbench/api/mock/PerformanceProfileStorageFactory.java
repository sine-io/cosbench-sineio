package com.intel.cosbench.api.mock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

import com.intel.cosbench.api.storage.StorageAPIFactory;
import com.intel.cosbench.api.storage.StorageAPI;
import com.intel.cosbench.config.Config;

public final class PerformanceProfileStorageFactory implements StorageAPIFactory {
    @Override
    public String getStorageName() {
        return "profile";
    }

    @Override
    public StorageAPI getStorageAPI() {
        return new InstrumentedMockStorage();
    }

    private static final class InstrumentedMockStorage extends MockStorage {
        private final AtomicBoolean firstOperation = new AtomicBoolean();

        @Override
        public InputStream getObject(String container, String object, Config config) {
            recordFirstOperation();
            return super.getObject(container, object, config);
        }

        private void recordFirstOperation() {
            String marker = System.getProperty("cosbench.performance.first-operation-marker");
            if (marker == null || !firstOperation.compareAndSet(false, true)) {
                return;
            }
            try {
                Files.writeString(Path.of(marker), Long.toString(System.currentTimeMillis()) + "\n",
                        StandardCharsets.US_ASCII, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException error) {
                throw new IllegalStateException("cannot record first Mock operation", error);
            }
        }
    }
}
