# Java 21 Shadow Reactor

The root Maven entry delegates to this reactor. Its shared-module batch contains ordinary Maven JARs for `cosbench-log4j`, `cosbench-castor`, `cosbench-log`, `cosbench-config`, `cosbench-api`, `cosbench-core`, and `cosbench-http`. The #03-#06 validation spikes and the #03 runtime consume these artifacts instead of recompiling shared sources from the archived legacy tree.

The shared modules keep the legacy package names and public Storage/Auth SPI, model, protocol, Castor XML, and HTTP helper behavior. New Java, resources, and tests use standard Maven paths. `cosbench-api` owns static `ServiceLoader` registration and lifecycle diagnostics; Mock remains test-only in the Provider spike. The `cosbench-config` JAR owns the old Castor/XML and INI contract plus the versioned YAML configuration seam. The original root Tycho reactor and its checked-in P2 inputs are preserved under `legacy/osgi/` and at the fixed legacy tag; the active root reactor does not reference them.

The shadow runtime remains a thin Java 21 Spring Boot process used by the #03 configuration/lifecycle spike. It exposes health, read-only archive inspection, and a task-admission seam; full Controller/Driver behavior remains in later tickets.

Ticket #09 adds the ordinary `cosbench-controller` business module and executable `cosbench-controller-app`. The application wires the real `ControllerService`, shared YAML and logging, readiness and graceful local-work shutdown. The Web spike consumes the migrated business JAR. See [Controller application](controller-app.md) for commands, configuration/exit results, test coverage and the remaining archive/HTTP/Web acceptance boundaries.

Ticket #11 adds the ordinary `cosbench-sineio` production JAR to the executable Driver. The production descriptor registers `sio`, `siov1`, and `gdas` on the shared classpath; Mock stays in test sources. See [SineIO providers](sineio-providers.md) for configuration examples, SDK versions, operation/error coverage and mission/TERM resource checks.

The parent POM fixes Java 21, `maven.compiler.release=21`, Spring Boot's 3.5.16 dependency BOM, Maven 3.10.0, and the lifecycle plugin versions. Every module added under this parent inherits the Java/Maven Enforcer policy.

Run the continuous empty-cache build, blocking dependency/license/artifact gates, four prerequisite replays and cleanup with:

```sh
bash ci/verify-shadow.sh /tmp/cosbench-scheme3-ci
```

The lower-level `shadow/harness/run.sh EMPTY_REPORT_DIR` retains the build, audit, SPI and #03 configuration/lifecycle checks. See [`ci-policy.md`](ci-policy.md) for admission rules, report contents, negative fixtures and how to extend CI when adding a module; see [`config-contract.md`](config-contract.md) for schema 1, field ranges, precedence, secret references, legacy mappings, and command behavior.

The harness reports are written to the supplied empty directory. It uses test-only credentials and a self-signed test certificate, starts MinIO and Toxiproxy without a production application, then removes this run's containers and network on success, failure, or interruption. The project-scoped MinIO data volume is retained. Pinned Docker images remain cached for later runs.

For a later module test that needs this environment, pass a Java 21 container command after `--`. It joins the same network and receives secret file paths for MinIO and TLS:

```sh
shadow/harness/run.sh /tmp/cosbench-scheme3-02-provider -- \
  /bin/bash /workspace/shadow/harness/provider-tests.sh
```

`COSBENCH_MINIO_ENDPOINT` is `http://minio:9000`; `COSBENCH_TOXIPROXY_ADMIN_URL` is `http://toxiproxy:8474`. `shadow/harness/toxiproxy.sh` creates a proxy and adds downstream disconnect, response-loss, or timeout toxics.
