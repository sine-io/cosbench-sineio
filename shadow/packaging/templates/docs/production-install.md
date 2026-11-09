# Java 21 production installation

The production archive is self-contained with two executable Spring Boot
applications and the operator CLI. It does not contain the old OSGi/Eclipse
runtime, development sources, Mock providers, or unsupported legacy
Providers. Java 21 is required at build and runtime.

Extract the archive into a new directory and create the data directories:

```sh
mkdir -p /opt/cosbench/{run,workspace,archive,mission,log}
```

Copy `conf/controller.yml` and `conf/driver.yml` to the protected
configuration location, set `COSBENCH_SERVICE_TOKEN`, and mount a PKCS12
Driver key store plus a Controller trust store. The store password must come
from `SERVER_SSL_KEY_STORE_PASSWORD` or
`COSBENCH_TLS_STORE_PASSWORD_FILE`. Keep secrets out of the archive and logs.

Start a Driver and inspect its readiness endpoint:

```sh
bin/cosbench-driver --config conf/driver.yml --pid-file run/driver.pid
curl --cacert /run/secrets/cosbench-certificate https://127.0.0.1:18088/driver/health
```

Start the Controller after its `drivers` list contains the HTTPS Driver URL:

```sh
bin/cosbench-controller --config conf/controller.yml --pid-file run/controller.pid
curl http://127.0.0.1:19088/controller/health
```

Stop one process or all local instances with `bin/cosbench-stop`. A successful
stop releases the listener and removes the owned PID file. Configuration
conversion uses `bin/cosbench-config-migrate`; it preserves the original file
and reports unmapped legacy values.

The archive contains `manifest/applications.tsv`,
`manifest/dependencies.tsv`, `manifest/providers.tsv`,
`manifest/installation-files.txt` and `SHA256SUMS`. Verify the latter after
extraction with `sha256sum -c SHA256SUMS`.

Rollback means stopping the new processes, preserving `workspace`, `archive`,
`mission`, `log` and configuration data, and starting the previous complete
archive with its previous Java runtime. Do not replace individual application
JARs between release versions.
