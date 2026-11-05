# #18 Operator Commands Evidence

The isolated operator replay used the digest-pinned Java 21 Maven image and generated temporary TLS material inside its disposable container. It exercised the formal command help and usage errors, INI migration checksums and no-overwrite behavior, config-generation idempotency and secret-reference retention, two independently configured HTTPS Drivers, Controller connectivity, PID ownership, duplicate starts, port conflicts, stale PID cleanup, isolated instance stop, busy and idle Controller shutdown, and the development start/stop/restart workflow.

The operator replay passed with:

```text
status=passed
migration=non-destructive
start_failure=clean
stale_pid=removed
duplicate_start=refused
port_conflict=refused
multiple_drivers=connected
instance_paths=isolated
specified_instance_stop=isolated
busy_and_idle_stop=passed
dev_start_stop_restart=passed
repeated_stop=passed
unrelated_pid=refused
replay_status=0
processes_remaining=none
```

The follow-up replay after review fixes used converted Controller and Driver
INI configurations as the templates for a two-Driver setup. The converter
added only secret references and HTTPS endpoints; the test supplied the token
and certificate stores from the Docker harness secrets, then started both
Drivers and their Controller. The config lifecycle and operator replays both
passed, including archive reads after restart:

```text
config-lifecycle=passed
controller_migration=passed
driver_migration=passed
archive_read_after_restart=passed
operator_commands=passed
multiple_drivers=connected
dev_start_stop_restart=passed
run_status=0
cleanup_status=0
```

The follow-up report is `/tmp/cosbench-scheme3-18-secure-migration-20261105`.

The Controller binder compares the resolved service token used by the
Controller with each generated Driver configuration and rejects a mismatch;
`ControllerDriverConfigBinderTest` covers the rejection without including
either token value in diagnostics.

The initial complete verification command was:

```sh
bash ci/verify-shadow.sh /tmp/cosbench-scheme3-18-final
```

The reactor `clean verify` succeeded and all 21 module gates passed. The packaged Controller, Driver, SineIO, Web, and HTTP checks preceding the Controller/Driver workload replay also passed. The overall harness returned `run_status=1` at the existing `controller-driver-process.sh` metrics comparison: Prometheus reported `cosbench_workload_average_process_time=50.09`, while the archived workload CSV reported `50.05`. This unrelated comparison is retained as a failure, not counted as a pass for #18.

Docker cleanup reported `cleanup_status=0`, no remaining project containers, networks, volumes, or certificate containers. The report directory `/tmp/cosbench-scheme3-18-final` was retained for diagnosis. A representative pre-migration archive is covered by [#03 config lifecycle evidence](../../2026-10-28/config-lifecycle/README.md); a real production historical archive/workspace/mission/log fixture was not supplied and remains a human acceptance input.
