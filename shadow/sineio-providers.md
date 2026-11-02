# SineIO production providers (#11)

`cosbench-sineio` is an ordinary Java 21 Maven JAR. Its production storage descriptor registers `sio`, `siov1`, and `gdas`; it has no auth descriptor. `cosbench-driver-app` depends on this JAR and checks all three registrations at startup. The Provider spike consumes the same production JAR, retaining only the legacy S3 adapter in its own main artifact. The root Tycho tree remains the rollback input until #23.

## Dependencies and release inputs

All dependencies resolve from Maven coordinates. AWS SDK v1 remains `1.12.767`, v2 remains `2.26.8`, as frozen by #04. The parent imports the v2 BOM so the executable Driver resolves the same SDK closure as the spike. The existing Boot/Commons dependency management and `dependency-lock.tsv` retain dependency versions, SHA-256 and license admission. The provider includes `META-INF/LICENSE` (MIT); dependency license/checksum and duplicate-class reports are generated for both the provider and the executable Driver by the CI gates.

The Driver uses the shared application classloader. There is no child loader or native SDK client to close; all three implementations use synchronous Java HTTP clients. #19 repeats the combined SineIO/S3 deployment. No local JARs, source JARs, Mock, spike module, OSGi metadata or other providers belong to the Driver production closure. #21 consumes the Driver JAR, provider JAR, their admitted dependencies and the packaged examples.

## Configuration

The `examples/sio.properties`, `examples/siov1.properties`, and `examples/gdas.properties` resources list reference values for each mode. Render the placeholders from protected runtime credentials, join the entries with semicolons, then set the mission's `<storage type="..." config="..."/>` attribute. The Provider does not interpolate environment variables or load properties files itself. Preserve XML escaping when rendering an attribute.

Existing defaults and conversion behavior remain unchanged: unknown keys are ignored; `sio` rejects blank credentials during SDK initialization; empty `siov1`/`gdas` configuration initializes and disposes without contacting the default endpoint. Invalid numeric/boolean values through the real `KVConfigParser` retain Commons Configuration's `ConversionException`; the registry/Driver adds the provider diagnostic. `aws_region` is consumed by `sio`; the two SDK-v1 modes retain their original signing and endpoint behavior. The SDK-v1 `no_verify_ssl` flag retains its existing process-wide effect.

## Operations and lifecycle

All modes support create/delete bucket, put/get/list/head/delete object and multipart upload. Tests compare complete bytes with varied payloads and repeat uploads with a short final part to cover the existing `part_size` behavior. `sio` and `siov1` also perform range/prefetch GETs against MinIO. The HTTP fixture checks the `prefetch` header, Range header, original simultaneous-flag precedence and signing: `sio` sends both headers, while `siov1` prioritizes prefetch. GDAS ignores those keys as before.

Restore retains its original SDK call, configured day count and the SDK-v1 follow-up metadata request. A controlled S3 HTTP peer verifies the successful request/response contract; this is not a claim that the MinIO fixture provides a Glacier/archive restore backend or that vendor prefetch scheduling has been exercised. No unavailable backend scenario is counted as a MinIO operation success.

The real-backend acceptance remains open until an archive-capable endpoint is provided for all three modes. Copy `harness/fixtures/sineio-archive.properties.example` to a protected directory as `archive.properties`, render all placeholders, and place the referenced credential files in that directory. Supply three independent archived objects, their expected byte counts and SHA-256 values. From the host run:

```sh
bash shadow/harness/sineio-archive-replay.sh /absolute/fixture-directory /tmp/cosbench-sineio-archive
```

This launches the pinned Java 21 Docker image with read-only fixture/production-JAR mounts. It requires an initial GET to fail with `InvalidObjectState`, submits restore, verifies complete data, and checks the two SIO modes' prefetch/range reads. Range validation uses a shorter fixed prefix whose expected digest comes from the restored object after its complete SHA-256 has matched the fixture; an ignored Range header fails the byte-count check. Each mode has a hard deadline covering initialization, restore, polling, streamed reads and extension reads. A timeout fails the process even if an SDK call consumes interruption. It does not delete supplied objects. Missing fixtures, initially readable STANDARD objects, wrong data and SDK failures also fail; reports remain `status=failed` until every mode passes. Container cleanup runs on every exit path, including an interrupted Docker pull/create. The caller retains ownership of the protected fixture directory. This runner is separate from MinIO CI because the repository has no archive-capable test endpoint or archived fixture; running only MinIO CI does not close this acceptance item.

`bash shadow/harness/test-sineio-archive-replay.sh EMPTY_REPORT_DIR` replays failure cases with a controlled peer: a slow restore, polling beyond the deadline, ignored Range and an interrupted container. These test runner failure behavior and are not evidence of a real archive backend.

HTTP 400/403/404/409/500/503, invalid MinIO credentials, connection refusal and delayed responses retain the SDK cause and original COSBench classification. In particular, SDK-v1 GET transport failures map to `StorageTimeoutException`; `sio` wraps them in `StorageException`. Post-dispose GET remains `StorageException` for `sio` and `NullPointerException` for `siov1`/`gdas`.

`sio` owns the Apache HTTP client it passes to the SDK and now closes that client as well as the S3 client, including failed initialization cleanup. The SDK-v1 modes keep their existing shutdown of both normal and restore clients. Mission abort marks workers finished before interrupting SDK calls, which can consume interruption while mapping an error; this prevents another operation from starting during shutdown. SPI tests observe keep-alive connection EOF at the external peer, and Driver tests observe this JVM's storage sockets after mission close and abort. A test-only launcher runs the packaged Driver's real entry point with only two external probe classes and no Mock descriptor, starts a MinIO mission for each mode and sends TERM. The normal Driver executable replay also runs without any probe classes.

## Replay

```sh
bash ci/verify-shadow.sh /tmp/cosbench-sineio-11
```

This runs the full reactor once, dependency/artifact gates and process replays with pinned Java 21/Maven/MinIO images. `tests/cosbench-sineio/`, `tests/cosbench-driver-app/`, `ci/cosbench-sineio/`, `ci/cosbench-driver-app/` and `sineio-driver-process/` hold the results. The harness removes its containers, networks, volumes and temporary secrets on success, failure and interruption.
