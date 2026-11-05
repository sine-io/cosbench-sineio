# Configuration and lifecycle contract (GATE-CFG spike)

This document freezes the small configuration boundary used by the #03 spike. It is a shared contract for the future Controller and Driver composition roots; it does not claim that the complete application has migrated. The executable schema is version `1` and is read by `com.intel.cosbench.config.runtime.VersionedConfigLoader`.

## Files, precedence, and paths

The final installation names are `conf/controller.yml` and `conf/driver.yml`. A process may select another file with `--cosbench.config=PATH` or `COSBENCH_CONFIG`; the command-line property wins over the environment variable. A field written in YAML wins over its documented default. Secrets are the exception in representation only: YAML contains an `auth.token.env` or `auth.token.file` reference, and the referenced environment variable or protected file is resolved at startup. Secret values are never written to a migration report, log, workload, image, or generated YAML.

Relative `workspace`, `archive`, `mission`, and `logging.file` values are resolved against the installation root (the parent of `conf/`) and remain relative in migrated YAML. Absolute values are retained as absolute paths. No conversion or startup operation deletes, renames, or rewrites an existing workspace, archive, mission, log, or source configuration file.

Unknown keys and unknown schema versions fail before service startup. The loader reports stable problem codes so commands and tests can distinguish missing files, empty documents, required fields, invalid types, out-of-range values, unresolved secrets, and migration values that need review.

## Version 1 fields

| YAML path | Type and default | Range/meaning |
| --- | --- | --- |
| `schemaVersion` | integer, required, `1` | Only `1` is supported by this spike. |
| `role` | `controller` or `driver`, required | Selects the process role and default listener port/context. |
| `name` | string, required | Non-empty process name. |
| `listen.host` | string, default `127.0.0.1` | Bind address. |
| `listen.port` | integer; controller `19088`, driver `18088` | `1..65535`. |
| `listen.contextPath` | string; default `/controller` or `/driver` | Normalized to a leading slash with no trailing slash. |
| `paths.workspace` | path, default `workspace` | Existing workspace is read in place. |
| `paths.archive` | path, default `archive` | Existing archive is read in place. |
| `paths.mission` | path, default `mission` | Driver mission directory. |
| `paths.logs` | path, default `log/system.log` | Log file path. |
| `drivers[].name` | string, required per entry | Unique Controller-side Driver name. |
| `drivers[].url` | absolute HTTP(S) URL, required per entry | Driver service endpoint; page cookies do not authorize it. |
| `auth.token.env` / `auth.token.file` | one secret reference | Exactly one is allowed when `auth.token` is present. Empty/unresolved values fail with `SECRET_UNRESOLVED`. The Driver HTTP application requires a resolved token; the separate lifecycle spike permits no token for its local smoke. |
| `auth.tls.enabled` | boolean, default `false` | If enabled, at least `keyStore` or `trustStore` must be referenced. |
| `auth.tls.keyStore` / `trustStore` | protected path, optional | Certificate material is mounted/injected; it is not copied into workloads. |
| `http.pool.maxConnections` | integer, default `32` | `1..10000`. |
| `http.pool.maxPerRoute` | integer, default `min(8,maxConnections)` | `1..maxConnections`. |
| `http.pool.idleTimeout` | duration, default `30s` | `1ms..24h`; units `ns`, `us`, `ms`, `s`, `m`, `h`, `d`. |
| `http.timeouts.connect` | duration, default `2s` | `1ms..7d`; connection timeout. |
| `http.timeouts.read` | duration, default `30s` | `1ms..7d`; response/read timeout. |
| `http.timeouts.command` | duration, default `60s` | `1ms..7d`; command timeout and no greater than workload timeout. |
| `http.timeouts.workload` | duration, default `1h` | `1ms..7d`; accepted workload boundary. |
| `http.retries.maxAttempts` | integer, default `1` | `1..10`; retry policy is limited to explicitly retryable failures in the later HTTP ticket. |
| `http.retries.backoff` | duration, default `0ms` | `0..5m`. |
| `concurrency` | integer, default `1` | Application worker concurrency, `1..10000`. |
| `provider.name` | string, default `mock` | Provider name is resolved by the later startup registry. |
| `provider.concurrency` | integer, default `concurrency` | Provider worker bound, `1..10000`. |
| `logging.level` | enum, default `INFO` | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, or `OFF`. |
| `logging.file` | path, defaults to `paths.logs` (`log/system.log` when omitted) | Explicit `logging.file` wins over `paths.logs`; relative to installation root when relative. |
| `web.enabled` | boolean, default `true` | Enables the process web boundary. |
| `web.maxUploadBytes` | integer; Controller default `0`, Driver default `10485760` | `0..10GiB`; `0` disables the upload limit. The Controller default preserves legacy behavior. A positive value sets a per-file limit and permits an additional 1 MiB of multipart request overhead. |
| `shutdown.gracePeriod` | duration, default `30s` | `1ms..1h`; stop closes admission first, then waits for workers and clients. |

The loader accepts scalar duration values as milliseconds for programmatic callers and uses explicit unit strings in YAML. It does not silently coerce arbitrary strings, duplicate Driver names, invalid URLs, or unknown fields.

The Driver HTTP listener requires `auth.tls.enabled: true` and `auth.tls.keyStore`. Supply the keystore password through Spring's `SERVER_SSL_KEY_STORE_PASSWORD` environment variable (or another protected Spring configuration source); `SERVER_SSL_KEY_STORE_TYPE` defaults to `PKCS12`. A Controller configured with Driver endpoints requires HTTPS URLs, `auth.token`, `auth.tls.enabled: true`, and a trust store. Supply TLS store passwords through the protected `COSBENCH_TLS_STORE_PASSWORD_FILE` path or `COSBENCH_TLS_STORE_PASSWORD`; the Controller loads store material into a process-local SSL context and never writes the password to logs. Clients send `Authorization: Bearer <service-token>` independently of any page session. Processed write identities and their responses remain in memory for the lifetime of the Driver, with no eviction or recovery across a restart. Clients must discover `X-COSBench-Driver-Instance` through authenticated ping and send it on every write, retaining the original value on retries to detect a changed process and an unknown write outcome.

## Legacy mapping

The migration command consumes the existing INI boundary and writes a new file only when the destination does not exist and every input value is mapped:

| Legacy value | Version 1 destination | Notes |
| --- | --- | --- |
| `[controller] name` / `[driver] name` | `name` | Preserved. |
| `[controller] url` / `[driver] url` | `listen.host`, `listen.port`, `listen.contextPath` | `/index.html` is removed from Controller's context path. |
| `[controller] archive_dir` | `paths.archive` | Relative path preserved. |
| `[driver] mission_dir` | `paths.mission` | Relative path preserved. |
| `workspace_dir` | `paths.workspace` | Supported when present. |
| `log_file` | `paths.logs` and `logging.file` | Same relative value retained. |
| `log_level` | `logging.level` | Existing level retained. |
| `[controller] drivers` and `[driverN] name/url` | `drivers[]` | Count and each endpoint are checked. |
| `concurrency` | `concurrency` | Integer validated by the new loader. |

The report records source and destination SHA-256 values, mapped fields, unmapped values, diagnostics, and a boolean success result. Driver output includes an `auth.token.env` reference and TLS `keyStore`; Controller output with Driver endpoints includes the token reference, TLS `trustStore`, and HTTPS endpoints. The defaults are `COSBENCH_SERVICE_TOKEN` and `/run/secrets/cosbench-driver-keystore.p12` or `/run/secrets/cosbench-controller-truststore.p12`; the command accepts overrides for each reference. Operators provision the referenced token and stores before startup. The converter does not read or serialize secret values. An unknown legacy key receives `legacy_unmapped`; no YAML is emitted. An existing destination receives `destination_exists`; it is never overwritten. The original INI checksum is checked by the lifecycle replay before and after conversion.

## Commands and process behavior

The stable command names are:

```text
bin/cosbench-controller [--cosbench.config=PATH]
bin/cosbench-driver [--cosbench.config=PATH]
bin/cosbench-stop [controller|driver|all]
bin/cosbench-config-migrate SOURCE_INI DESTINATION_YAML REPORT_JSON
```

The two start commands launch the Java 21 executable shadow JAR, write a PID file, create configured directories, expose `/<context>/health`, and expose the read-only `/<context>/archive` snapshot. `POST /<context>/tasks` is only a lifecycle seam in this spike. `cosbench-stop` sends TERM, waits up to the configured grace period (or `COSBENCH_STOP_TIMEOUT_SECONDS`), and reports a timeout without killing a process blindly. The Spring lifecycle closes task admission before it shuts down the executor; business workers do not call `System.exit`.

The replay uses the baseline Controller/Driver INI fixtures and a representative old archive fixture. It proves that the source and archive bytes remain unchanged, that archive entries are readable before and after a restart, that both health endpoints become reachable, that a task is accepted before TERM, and that both ports and PID files are released after stop. A real historical archive and production service token remain an acceptance input from #01/#06; the spike does not claim those unavailable fixtures as passed.
