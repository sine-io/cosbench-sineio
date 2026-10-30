# Controller Boot application (APP-C / #09)

`cosbench-controller` is the ordinary business JAR. It retains the legacy Controller packages, scheduler, tasklets, archive loaders/exporters, XML workload parsing, and `ControllerService` API under standard Maven source paths. `cosbench-controller-app` owns YAML selection, Spring wiring, listener configuration, readiness, logging and shutdown. The Web spike now consumes this business JAR instead of adding `dev/cosbench-controller/src` to its compiler inputs.

The application exposes the real service as both `controllerService` and the compatible `controller` Bean name. The old INI factory remains available in the business JAR for compatibility; Boot creates its context from the shared typed YAML contract. No OSGi service context or legacy factory configuration is loaded by Boot. Controller-to-Driver tasklets and protocol implementations retain their existing behavior; their authenticated transport integration belongs to #14.

## Run and verify

Development and tests run in the digest-pinned Java 21 Docker harness. From the repository root, with an empty report directory:

```sh
bash ci/verify-shadow.sh /tmp/cosbench-controller-09
```

This runs the entire reactor, all dependency/artifact gates, Controller process tests and packaged replay, and the existing configuration, Provider, Web and HTTP replays. Containers, temporary secrets, the network and MinIO volume are removed on every exit path. The packaged Controller replay can also be supplied to the lower-level harness:

```sh
bash shadow/harness/run.sh /tmp/cosbench-controller-09-replay -- \
  /bin/bash /workspace/shadow/harness/controller-process.sh
```

Inside the Java 21 container, the executable entry is:

```sh
java --add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED \
  -jar shadow/cosbench-controller-app/target/cosbench-controller-app-0.1.0-SNAPSHOT.jar \
  --cosbench.config=/installation/conf/controller.yml
curl --fail http://127.0.0.1:19088/controller/health
kill -TERM CONTROLLER_PID
```

The export is the existing Castor 1.3.3 Java 21 archive-writing requirement frozen in #05. Operational commands are integrated in #18.

## Configuration and observable lifecycle

The application reads schema 1 from `--cosbench.config=PATH`, then `COSBENCH_CONFIG`, then `conf/controller.yml`. The shared loader validates the document once before business threads start. YAML controls the listener host/port/context, Driver registry, concurrency, archive/workspace/log paths, logging level and shutdown grace period. `web.enabled: false` runs the service without an HTTP listener. Quote `logging.level: "OFF"`, because YAML 1.1 treats an unquoted `OFF` as a boolean.

An explicit `logging.file` wins over `paths.logs`; otherwise the business logger uses `paths.logs`, whose default is `log/system.log`. Relative paths resolve against the installation directory: the parent of `conf/`, or the directory containing an explicitly selected configuration elsewhere. Existing archive/workspace bytes are retained. Legacy `start`/`stop` workload markers stay in the installation directory even when Java launches from another working directory. Business logging appends to the configured file and closes after the service; Boot startup diagnostics remain available on the process output stream.

| Input/result | Observable contract |
| --- | --- |
| Missing default file | Exit 1, `DEFAULT_FILE_MISSING` |
| Missing explicit file, empty explicit option or option without a value | Exit 1, `EXPLICIT_FILE_MISSING` |
| Empty YAML | Exit 1, `EMPTY_DOCUMENT` |
| Missing required section/field | Exit 1, `REQUIRED_FIELD_MISSING` |
| Malformed YAML / invalid bounds | Exit 1 with `MALFORMED_YAML` / `VALUE_OUT_OF_RANGE` |
| Driver YAML supplied to Controller | Exit 1 with `requires role: controller` |
| Listener port occupied | Exit 1 with Boot's occupied-port diagnostic; initialized service resources are closed |
| Initialized, ready service | `GET /<context>/health`: HTTP 200, `status=UP`, `accepting=true`, real service name and active workload count |
| Service stopping/closed | Admission rejects new submissions, launches and reordering; health returns HTTP 503 while the listener is available |
| TERM | JVM signal exit 143 after service/listener/log closure; the port can be reused |

Shutdown closes service admission on context closure, then drains accepted local work and HTTP concurrently in the same Boot shutdown phase, sharing the configured grace period. Work submitted without `fire` is cancelled and disposed. The drain leaves up to one second (at most half of a short grace period) for interrupting unfinished work, generating its archive and releasing resources. When the drain budget expires, unfinished local work is interrupted and the outcome logged. Repeated service closure is idempotent. The business module raises initialization errors rather than terminating the process; only the application entry assigns failure exit codes.

## Evidence boundaries

`ControllerProcessTest` starts separate real JVMs with the production entry and a test-only HTTP adapter to the public `ControllerService`. It covers compatible Bean injection, health, queue/query, local delay execution, TERM admission ordering, graceful completion and archive output, deadline interruption (including an incomplete HTTP request concurrent with local work), repeated closure, configuration diagnostics, logging, headless mode, installation paths, archive reload and workload ID continuity. The adapter is absent from main output and the executable archive.

`controller-process.sh` runs the executable JAR without test classes. It checks configuration precedence, occupied-port cleanup, idle TERM, restart/port reuse, configured directory placement, and unchanged #03 archive/workspace fixtures. Its reports are under `controller-process/`; Maven retains the service/process assertions in Surefire reports. The same #08 gates scan both new artifacts and their full production/test dependency closures.

The historical-data evidence retains #01/#03's limitation: the available old archive is representative, rather than a supplied production archive. Generated real-service archives are reloaded without rewriting existing history/configuration. Cross-process workload execution is verified by #14, and the complete Controller Web surface by #15/#16.
