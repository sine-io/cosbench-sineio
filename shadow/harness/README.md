# Docker Harness

`run.sh` creates an isolated Compose project for each run. It mounts the repository read/write only into the Java 21 Maven container, uses a fresh tmpfs Maven repository, starts a test-only MinIO server and Toxiproxy, and accepts an optional test command in the Java 21 container. MinIO data is held in a project-scoped named volume that `down --volumes` removes.

```sh
shadow/harness/run.sh /tmp/cosbench-scheme3-02-run
```

The output directory must be empty. It retains the JAR, dependency tree, resource listing, duplicate-class result, toolchain information, MinIO operation log, certificate fingerprint, container logs, and cleanup status. The test credentials, PKCS12 private key, certificate-generation password, and Compose secret files live in a mode-0700 temporary directory and are deleted on every exit path. The public test certificate fingerprint is retained; it is self-signed, has localhost/MinIO/Controller/Driver SANs, and is regenerated per run.

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
