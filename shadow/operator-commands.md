# COSBench Operator Commands

The Java 21 installation uses `bin/cosbench-controller`, `bin/cosbench-driver`,
`bin/cosbench-stop`, and `bin/cosbench-config-migrate`. Start commands run in the
foreground; use a service manager to supervise them in production. Relative
workspace, archive, mission, and log paths remain relative to the installation
root. Conversion and restart leave existing data in place.

## Start Services

```sh
bin/cosbench-controller --config conf/controller.yml --pid-file run/controller.pid
bin/cosbench-driver --config conf/driver-1.yml --pid-file run/driver-1.pid
bin/cosbench-driver --instance 2
```

The controller and driver commands also accept `--cosbench.config=FILE`,
`COSBENCH_CONFIG`, and `COSBENCH_PID_FILE`. The `--config` option takes
precedence over the environment variable. `--instance ID` is Driver-only and
defaults to `conf/driver-ID.yml` and `run/driver-ID.pid`. `JAVA_OPTS`,
`COSBENCH_CONTROLLER_JAR`, `COSBENCH_DRIVER_JAR`, and
`COSBENCH_OPERATOR_CLI_JAR` may be set by the installation or service manager.
Without overrides, the release layout is `lib/cosbench-controller.jar`,
`lib/cosbench-driver.jar`, and `lib/cosbench-operator-cli.jar`.

Start help exits `0`; invalid arguments or missing runtime artifacts exit `2`;
configuration, port, and startup failures exit `1`. A running service handles
TERM through Spring's graceful shutdown lifecycle. Java reports `143` to its
supervisor after TERM.

## Stop Services

```sh
bin/cosbench-stop controller --pid-dir run
bin/cosbench-stop driver --instance 2 --pid-dir run
bin/cosbench-stop all --pid-dir run --timeout 30
```

With `driver` and no instance, every `driver*.pid` in the selected PID directory
is stopped. `--pid-file FILE` selects one file when paired with `controller` or
`driver`. The command sends TERM and waits; it does not force-kill. A stale PID
file is removed only after its PID is confirmed absent. A PID for an unrelated
or mismatched live process is refused and left untouched.
Symbolic-link PID paths are also refused before path normalization.

Help and an already stopped service exit `0`; invalid arguments exit `2`; an
invalid or unrelated PID owner exits `1`; a process that exceeds the timeout
exits `124` and retains its PID file for diagnosis.

## Convert Configuration

```sh
bin/cosbench-config-migrate \
  --source legacy/osgi/release/conf/controller.conf \
  --destination conf/controller.yml \
  --report run/controller-migration.json \
  --service-token-env COSBENCH_SERVICE_TOKEN \
  --controller-trust-store /run/secrets/cosbench-controller-truststore.p12
```

The legacy positional form remains supported:
`bin/cosbench-config-migrate SOURCE_INI DESTINATION_YAML REPORT_JSON`. The
converter writes only a new destination, records source/destination checksums,
and reports unknown or invalid fields without copying their values. Driver
configurations receive a reference to `COSBENCH_SERVICE_TOKEN` and the default
`/run/secrets/cosbench-driver-keystore.p12`; Controller configurations with
Drivers receive the same token reference, a trust store reference, and HTTPS
Driver URLs. Override those references with `--service-token-env`,
`--driver-key-store`, and `--controller-trust-store` when the installation
uses different protected locations. The referenced secret values and
certificate contents are never read or written by the converter. The operator
must provision the token and stores before starting the converted services.
The converter never edits the INI source or replaces an existing YAML
destination. Success exits `0`, a conversion or I/O failure exits `1`, and a
usage or missing-artifact error exits `2`.

Legacy controller `drivers` and `[driverN]` endpoints are mapped into the
Controller list. To create separate Driver processes from a versioned Driver
template, the development operator CLI can generate numbered files and bind
their endpoints into a Controller template:

```sh
java -jar lib/cosbench-operator-cli.jar expand-drivers \
  --config conf/driver-template.yml --output-dir conf --count 2
java -jar lib/cosbench-operator-cli.jar bind-controller \
  --config conf/controller-template.yml --drivers-dir conf --count 2 \
  --output conf/controller.yml
```

Generation increments listen ports and isolates each instance's mission and
log paths. Secret environment/file references remain references in generated
files; resolved secret contents are not serialized. Existing generated files
are accepted only when their contents match exactly.

## Legacy Entry Points

The old `release/start-all.sh`, `start-controller.sh`, `start-driver.sh`,
`stop-all.sh`, `stop-controller.sh`, `stop-driver.sh`, `cosbench-start.sh`, and
`cosbench-stop.sh` manage Equinox/OSGi console ports. They are retained only in
the legacy rollback release and are not wrapped by the Java 21 entry points.
The supported development workflow remains `dev/cosbench-dev.sh start|stop|restart
all|controller|driver`; `--drivers N` selects the number of Driver instances.
Set `COSBENCH_DEV_RUNTIME` to use a separate runtime directory; otherwise the
script uses `out/cosbench-dev`.

## Verification

`ci/verify-shadow.sh EMPTY_REPORT_DIR` runs the clean Java 21 Docker reactor and
process checks, including `shadow/harness/operator-commands.sh`. The operator
replay checks migration checksums, error and repeat behavior, stale and
unrelated PID handling, duplicate starts, port conflicts, two connected Driver
instances, isolated stop, and busy/idle Controller shutdown. The harness removes
its containers and network on every exit path; the named MinIO data volume is
retained.
