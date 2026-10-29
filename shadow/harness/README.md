# Docker Harness

`run.sh` creates an isolated Compose project for each run. It mounts the repository read/write only into the Java 21 Maven container, uses a fresh tmpfs Maven repository, starts a test-only MinIO server and Toxiproxy, and accepts an optional test command in the Java 21 container. MinIO data is held in a project-scoped named volume that `down --volumes` removes.

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

The Java 21 container receives `COSBENCH_MINIO_ENDPOINT`, `COSBENCH_MINIO_BUCKET`, `COSBENCH_TOXIPROXY_ADMIN_URL`, and file paths in `COSBENCH_MINIO_ACCESS_KEY_FILE`, `COSBENCH_MINIO_SECRET_KEY_FILE`, `COSBENCH_HTTP_TEST_CERT_FILE`, `COSBENCH_HTTP_TEST_KEYSTORE_FILE`, and `COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE`.

Create an HTTP proxy, then add a downstream fault. Run these commands from inside the harness Java container, or set `COSBENCH_TOXIPROXY_ADMIN_URL` to the host-mapped admin endpoint:

```sh
/workspace/shadow/harness/toxiproxy.sh create driver-http driver:18088 18666
/workspace/shadow/harness/toxiproxy.sh add driver-http disconnect
/workspace/shadow/harness/toxiproxy.sh add driver-http response-lost
/workspace/shadow/harness/toxiproxy.sh add driver-http timeout 2000
/workspace/shadow/harness/toxiproxy.sh remove driver-http
```

`disconnect` uses Toxiproxy `reset_peer`; `response-lost` limits downstream response data to zero bytes; `timeout` delays the downstream stream for the requested milliseconds. The test application is responsible for targeting the proxy listener (`toxiproxy:18666` on the Compose network).
