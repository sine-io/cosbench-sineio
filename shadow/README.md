# Java 21 Shadow Reactor

This reactor is isolated from the root Tycho/P2 build. It contains the representative `cosbench-config` slice, the versioned YAML/legacy migration boundary, and a thin Java 21 Spring Boot runtime used by the #03 configuration/lifecycle spike. The runtime deliberately exposes only health, read-only archive inspection, and a task-admission seam; workload and Provider migration remain later tickets.

The parent POM fixes Java 21, `maven.compiler.release=21`, Spring Boot's 3.5.16 dependency BOM, Maven 3.10.0, and the lifecycle plugin versions. Every module added under this parent inherits the Java/Maven Enforcer policy.

Run a clean build, empty-cache dependency audit, MinIO smoke, and cleanup with:

```sh
shadow/harness/run.sh /tmp/cosbench-scheme3-02-run
```

The default replay now also runs the #03 configuration/lifecycle check. See [`config-contract.md`](config-contract.md) for schema 1, field ranges, precedence, secret references, legacy mappings, and command behavior.

The harness reports are written to the supplied empty directory. It uses test-only credentials and a self-signed test certificate, starts MinIO and Toxiproxy without a production application, then removes this run's containers, network, and MinIO volume on success, failure, or interruption. Pinned Docker images remain cached for later runs.

For a later module test that needs this environment, pass a Java 21 container command after `--`. It joins the same network and receives secret file paths for MinIO and TLS:

```sh
shadow/harness/run.sh /tmp/cosbench-scheme3-02-provider -- \
  /bin/bash /workspace/shadow/harness/provider-tests.sh
```

`COSBENCH_MINIO_ENDPOINT` is `http://minio:9000`; `COSBENCH_TOXIPROXY_ADMIN_URL` is `http://toxiproxy:8474`. `shadow/harness/toxiproxy.sh` creates a proxy and adds downstream disconnect, response-loss, or timeout toxics.
