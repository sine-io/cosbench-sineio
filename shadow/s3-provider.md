# S3 Production Provider

The production `cosbench-s3` module publishes `com.intel.cosbench.api.S3Stor.S3StorageFactory` through the StorageAPI `ServiceLoader` descriptor. The Driver app includes this module in its normal runtime dependency graph and requires `s3` during registry startup. Mock remains test-only.

## Legacy Configuration

The provider retains the legacy semicolon-separated keys and defaults:

| Key | Default | Behavior |
| --- | --- | --- |
| `endpoint` | `http://s3.amazonaws.com` | AWS SDK v1 endpoint override |
| `accesskey` | empty | Static AWS credentials |
| `secretkey` | empty | Static AWS credentials |
| `timeout` | `30000` | Connection and socket timeout in milliseconds |
| `path_style_access` | `false` | AWS SDK v1 S3 client path-style option |
| `proxyhost` | empty | HTTP proxy host when both proxy fields are set |
| `proxyport` | empty | HTTP proxy port when both proxy fields are set |

S3 has no `aws_region` setting in its legacy constants or [sample workload](../release/conf/config-samples/s3-config-sample.xml). The old provider overrides the AWS SDK v1 signer with `S3SignerType`; the SineIO-only `aws_region` option is therefore not applicable to this provider. Unknown keys remain accepted by the existing key/value configuration contract.

The adapter keeps the AWS SDK for Java v1 API, static credential behavior, endpoint override, `S3SignerType`, and path-style setting. The shared migrated runtime resolves v1 `1.12.767`, already used by SineIO and selected by the #04 shared-classpath verification; the old S3 bundle carried v1 `1.10.76`. This preserves one v1 implementation on the tested shared classpath and does not migrate S3 to SDK v2. Both versions' dependency provenance and checksums are captured by the CI reports.

## Operations

The provider implements bucket create/delete, object put/get/list/head/delete, and multipart upload with the AWS SDK v1 APIs. List results are UTF-8 object keys separated by newlines, matching the Driver lister contract. Multipart uploads use 5 MiB parts, complete only after all parts upload, and abort an initiated upload when an operation fails. Zero-length multipart requests use a regular empty-object PUT because S3 rejects an upload with no parts.

Bucket creation checks `HeadBucket`, creates only after 404, and retains the established 301-as-existing behavior. Object and bucket delete treat 404 as already deleted. Other AWS service and SDK failures are wrapped in `StorageException` with the original cause retained. The legacy S3 provider has no archive restore setting or implementation; `restoreObject` keeps the base provider's no-op behavior and is not claimed as a supported S3 operation.

`dispose()` shuts down the AWS SDK v1 client. `S3ProviderTest` checks registry discovery, legacy configuration conversion, the v1 Authorization header, path-style request path, and idle HTTP connection release. `S3MinioTest` checks put/get/list/head/delete, multipart data integrity, zero-length upload, and cleanup against MinIO. The shared Provider smoke verifies S3 alongside the other production factories and exercises credentials, connection failures, 301, 500, timeout, and disposal.

## Replays And Reports

Run the full Java 21 Docker/MinIO and CI replay with:

```sh
bash ci/verify-shadow.sh /tmp/cosbench-s3-provider
```

The replay emits the S3 production JAR inventory and checksum, S3 dependency tree and checksums, license review, duplicate-class report, service-descriptor boundary, and shared-classpath MinIO results. `shadow/cosbench-s3/src/main/resources/examples/s3.properties` records the legacy configuration sample.
