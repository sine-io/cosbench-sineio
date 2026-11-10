# Docker Harness

The continuous entry is `bash ci/verify-shadow.sh EMPTY_REPORT_DIR`. It runs this harness plus the packaged Controller/Driver, active SineIO mission TERM, Web and authenticated HTTP replays and the positive/negative audit fixtures. The default build runs blocking gates for every effective reactor module, including convergence, dependency admission, SHA-256/licenses, production/test boundaries, resources, duplicate classes and `jdeps`. See [`ci-policy.md`](../ci-policy.md) for the extension contract and legacy build entry.

`run.sh` creates an isolated Compose project for each run. It mounts the repository read/write only into the Java 21 Maven container, uses a fresh tmpfs Maven repository, starts a test-only MinIO server and Toxiproxy, and accepts an optional test command in the Java 21 container. MinIO data remains in a project-scoped named volume after each run; cleanup removes only the run containers and network.

```sh
shadow/harness/run.sh /tmp/cosbench-scheme3-02-run
```

The output directory must be empty. It retains the JAR, dependency trees, resource listings, duplicate-class results, toolchain information, MinIO operation log, certificate fingerprint, configuration migration/lifecycle report, Provider SPI smoke and SDK audit, container logs, and cleanup status. The test credentials, PKCS12 private key, certificate-generation password, and Compose secret files live in a mode-0700 temporary directory and are deleted on every exit path. The public test certificate fingerprint is retained; it is self-signed, has localhost/MinIO/Controller/Driver SANs, and is regenerated per run.

The default replay invokes the Provider SPI MinIO smoke and `config-lifecycle.sh` after the shadow build. The provider test discovers all six test-visible registrations, exercises Mock auth/storage, and sequentially runs the four production factories against MinIO on one classpath. The replay also writes the provider dependency tree, duplicate-class audit, artifact boundary, SDK provenance, and checksums. The configuration test converts both baseline INI fixtures, starts the Java 21 Controller and Driver shadow processes, reads a representative old archive before and after a restart, submits one lifecycle task, sends TERM through `bin/cosbench-stop`, and verifies port/PID release and unchanged source/archive checksums. The frozen schema and field mapping are documented in [`shadow/config-contract.md`](../config-contract.md); the provider evidence is in [`shadow/evidence/2026-10-29/provider-spi/README.md`](../evidence/2026-10-29/provider-spi/README.md), and the configuration evidence summary is in [`shadow/evidence/2026-10-28/config-lifecycle/README.md`](../evidence/2026-10-28/config-lifecycle/README.md).

To run a later test in the same network:

```sh
shadow/harness/run.sh /tmp/cosbench-provider-run -- \
  /bin/bash /workspace/path/to/provider-tests.sh
```

The Java 21 container receives `COSBENCH_MINIO_ENDPOINT`, `COSBENCH_MINIO_BUCKET`, `COSBENCH_TOXIPROXY_ADMIN_URL`, and file paths in `COSBENCH_MINIO_ACCESS_KEY_FILE`, `COSBENCH_MINIO_SECRET_KEY_FILE`, `COSBENCH_HTTP_TEST_CERT_FILE`, `COSBENCH_HTTP_TEST_KEYSTORE_FILE`, `COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE`, and `COSBENCH_TLS_STORE_PASSWORD_FILE`.

Create an HTTP proxy, then add a downstream fault. Run these commands from inside the harness Java container, or set `COSBENCH_TOXIPROXY_ADMIN_URL` to the host-mapped admin endpoint:

```sh
/workspace/shadow/harness/toxiproxy.sh create driver-http driver:18088 18666
/workspace/shadow/harness/toxiproxy.sh add driver-http disconnect
/workspace/shadow/harness/toxiproxy.sh add driver-http response-lost
/workspace/shadow/harness/toxiproxy.sh add driver-http timeout 2000
/workspace/shadow/harness/toxiproxy.sh remove driver-http
```

`disconnect` uses Toxiproxy `reset_peer`; `response-lost` limits downstream response data to zero bytes; `timeout` delays the downstream stream for the requested milliseconds. The test application is responsible for targeting the proxy listener (`toxiproxy:18666` on the Compose network).

The production `cosbench-sineio` module has its own SPI/MinIO/configuration/error tests. `sineio-driver-process.sh` loads two test-only probe classes alongside the packaged Driver, checks an exact production registry without Mock, starts a real MinIO read mission for each SineIO mode, and verifies TERM exit and port release. The normal `driver-process.sh` still runs the executable without probes. See [SineIO providers](../sineio-providers.md).

The Driver HTTP checks exercise all eight commands on the production Boot listener with generated TLS material and the local Mock providers. They compare the baseline response fixtures, test service authentication separately from provider login, and cover failure, concurrent, late and lost-response replays. The Controller/Driver process replay starts two independent Java processes, completes a 12-second Mock workload over authenticated HTTPS, runs its trigger, archives its state and checks both process logs and archive data for token leakage. The Controller HTTP tests also cover response loss, bounded retries, connection reuse and idle client reaping. Reports include `driver-http-targeted-tests.log`, `http-contract-summary.txt`, `controller-driver-process/summary.txt`, `driver-process/summary.txt`, and the complete test reports under `tests/`.

The final continuous check runs `operator-commands.sh` against the packaged Controller and Driver JARs. It covers the four stable shell commands, migration checksum and no-overwrite behavior, config-generation idempotency and secret-reference retention, multiple TLS Driver instances, Controller connectivity, duplicate/start-failure/port-conflict behavior, stale and unrelated PID handling, isolated stop, and busy/idle TERM shutdown. See the operator [command runbook](../operator-commands.md) and [#18 replay evidence](../evidence/2026-11-05/operator-commands/README.md).

The complete CI entry then runs `deployment-cleanup-tests.sh` and
`deployment-integration.sh`: unavailable storage and interrupted-workload
cleanup, followed by Controller-only, Driver-only, both and multi-Driver Docker
deployments using the packaged applications. All four production factories run
through the Controller CLI on the production classpath, with operation/byte
counts, TLS/secrets/mounts, isolated stop and restart checks. See the
[INT-01 runbook](../deployment-integration.md). The root reports
`integration-acceptance.tsv` and `integration-summary.txt` join the applicable
stable IDs, concrete commands, reports and final Docker cleanup result.
