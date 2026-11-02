package com.intel.cosbench.api.S3Stor;

import com.intel.cosbench.api.storage.StorageAPI;
import com.intel.cosbench.api.storage.StorageAPIFactory;

public class S3StorageFactory implements StorageAPIFactory {
    @Override
    public String getStorageName() {
        return "s3";
    }

    @Override
    public StorageAPI getStorageAPI() {
        return new S3Storage();
    }
}
