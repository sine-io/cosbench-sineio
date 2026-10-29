# Ticket 04 Provider SPI Validation

Executable source: the current working tree after #04. Run with:

```sh
shadow/harness/run.sh /tmp/cosbench-scheme3-04-final4-20261029
```

The runner used the pinned Java 21/Maven container, started MinIO and Toxiproxy, and removed the run's containers, network, volumes, and temporary secrets. `cleanup-status.txt` records `run_status=0`, `cleanup_status=0`, and no remaining project resources. `container-build.log` records a successful clean reactor build; the full reactor reported 18 tests passing, and the focused ProviderMinioSmokeTest was run again with zero failures.

## Discovery and Coexistence

`provider-smoke.txt` records these registrations on one shared test classpath:

| SPI | Provider | Factory |
| --- | --- | --- |
| Storage | mock | `com.intel.cosbench.api.mock.MockStorageFactory` |
| Auth | mock | `com.intel.cosbench.api.mock.MockAuthFactory` |
| Storage | sio | `com.intel.cosbench.api.sio.SIOStorageFactory` |
| Storage | siov1 | `com.intel.cosbench.api.sio.SIOStorageFactoryV1` |
| Storage | gdas | `com.intel.cosbench.api.gdas.GdasStorageFactory` |
| Storage | s3 | `com.intel.cosbench.api.S3Stor.S3StorageFactory` |

The production storage descriptor is in the main artifact. Mock source and descriptors are test-only; `provider-artifact-boundary.txt` and `provider-artifact-contents.txt` verify that boundary. `StaticProviderRegistryTest` covers explicit expected-name checks for missing descriptors, missing factory dependencies, duplicate names, and disposal after initialization failure.

## Operation Matrix

| Provider | MinIO result |
| --- | --- |
| Mock auth | init, login, token verification, dispose, login still works after dispose |
| Mock storage | init, create container, put, get 16 bytes, dispose, get still returns 16 bytes after dispose |
| sio / siov1 / gdas | init, create container, missing-object GET maps to `StorageException`, put/get/list/head, multipart put/get/delete, delete object/container, dispose |
| s3 | init, create container, missing-object GET maps to `StorageException`, put/get, delete object/container, dispose |

The legacy S3 implementation inherits `NoneStorage.getList` (empty result), `createMultipartObject` (no-op), `headObject`, and `restoreObject` (no-op). The smoke confirms the empty list and no-op multipart result; it does not represent these as S3 capabilities. After disposal, the existing implementations return `StorageException` for sio/s3 and `NullPointerException` for siov1/gdas; the public SPI test records those legacy types. SIO/GDAS restore and SIO prefetch/range behavior require provider-specific semantics and were not exercised by MinIO. Their production endpoint coverage remains with #11/#12.

## Configuration and Error Matrix

The fixture includes an unknown key, accepted by all six discovered factory registrations. Empty config fails for `sio` because AWS SDK v2 rejects the blank access key; the registry reports `ProviderRegistryException` with the original `NullPointerException`, and disposal adds no secondary error. Empty config initializes and disposes successfully for `siov1`, `gdas`, and `s3`. An invalid numeric `timeout` is reported as `ProviderRegistryException(NumberFormatException)` for all four production storages. Mock auth/storage also pass empty-config initialization and reject invalid numeric `delay`/`size` values through the registry.

| Provider | Invalid-credential GET | Connection refused | HTTP 500 | Slow response timeout | Call after dispose |
| --- | --- | --- | --- | --- | --- |
| sio | `StorageException` / `S3Exception` | `StorageException` / `SdkClientException` | `StorageException` / `S3Exception` | `StorageException` / `SdkClientException` | `StorageException` |
| siov1 | `StorageException` / `AmazonS3Exception` | `StorageException` / `SdkClientException` | `StorageException` / `AmazonS3Exception` | `StorageException` / `SdkClientException` | `NullPointerException` |
| gdas | `StorageException` / `AmazonS3Exception` | `StorageTimeoutException` / `SdkClientException` | `StorageException` / `AmazonS3Exception` | `StorageTimeoutException` / `SdkClientException` | `NullPointerException` |
| s3 | `StorageException` / `AmazonS3Exception` | `StorageException` / `SdkClientException` | `StorageException` / `AmazonS3Exception` | `StorageException` / `SdkClientException` | `StorageException` |

All four report invalid-credential GET and missing-object 404 as `StorageException`. The invalid-credential `createContainer` probe also maps to `StorageException` for all four providers. Legacy AWS SDK v1 `doesBucketExist` treats HTTP 403 as “bucket exists”; `S3Storage.createContainer` now uses `headBucket` so it can propagate 403 while creating a bucket only on 404. A controlled HTTP 301 probe confirms it retains the existing as-existing behavior. Connection refusal targets an unused loopback port; 301, 500, and timeout use a controlled local HTTP endpoint. The exact report also records the unknown key and each cause type.

Each production factory receives the same valid S3-compatible MinIO fixture from `s3-compatible.properties`; test-only credentials are supplied through Compose secrets. The four clients are initialized and operated sequentially while all descriptors are present on the same classpath. Shared classpath passed, so no child classloader was introduced.

## Dependency and Cleanup Reports

- `provider-dependency-tree.txt` records AWS SDK v1 `1.12.767`, AWS SDK v2 `2.26.8`, and test dependencies. Legacy S3 shipped v1 `1.10.76`; SineIO shipped v1 `1.12.767` and v2 `2.26.8`. The shared build selects v1 `1.12.767`, preserving both SDK major versions.
- `provider-duplicate-class-audit.txt` reports no duplicate classes between the provider module and its resolved dependency JARs.
- `provider-sdk-provenance.txt`, `provider-dependency-checksums.txt`, and `provider-artifact.sha256` identify dependency/artifact inputs.
- `provider-minio-test.log`, `provider-smoke.txt`, `container-status.txt`, `docker-compose.log`, `docker-cleanup.log`, and `cleanup-status.txt` retain the run evidence.

The controlled matrix covers invalid credentials, 404, 301, connection refusal, 500, and a delayed response. Other HTTP status variants and vendor-specific restore/prefetch/range behavior remain with the production Provider tickets; MinIO compatibility is not evidence for those cases.
