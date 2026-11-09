# Java 21 production installation

The production archive is self-contained with two executable Spring Boot
applications and the operator CLI. It does not contain the old OSGi/Eclipse
runtime, development sources, Mock providers, or unsupported legacy
Providers. Java 21 is required at build and runtime.

Extract into a new directory, enter it, verify files and create data paths:

```sh
sha256sum -c SHA256SUMS
mkdir -p run workspace archive mission log
export JAVA_OPTS='--add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED'
```

The export is required by the retained Castor 1.3.3 serializer when archiving
on Java 21. Castor/Jackson/SDK major upgrades are separate work.

Copy `conf/controller.yml` and `conf/driver.yml` to a protected configuration
location. Set `COSBENCH_SERVICE_TOKEN` from a protected source, or replace
`auth.token.env` with `auth.token.file` referencing a mounted token. Mount a
PKCS12 Driver key store and Controller trust store at the configured paths.
Driver reads `SERVER_SSL_KEY_STORE_PASSWORD` (and optionally
`SERVER_SSL_KEY_PASSWORD`). Controller's outgoing HTTPS client reads
`COSBENCH_TLS_STORE_PASSWORD_FILE` or `COSBENCH_TLS_STORE_PASSWORD`.
Do not give Driver's `SERVER_SSL_*` variables to Controller: Boot interprets
them as inbound server TLS settings. Keep secrets out of archives and logs.

Start a Driver and inspect its readiness endpoint:

```sh
# Driver terminal; obtain the password from its protected file.
export SERVER_SSL_KEY_STORE_PASSWORD="$(cat /run/secrets/password)"
bin/cosbench-driver --config conf/driver.yml --pid-file run/driver.pid
# Another terminal:
curl --cacert /run/secrets/cosbench-certificate https://127.0.0.1:18088/driver/health
```

Start the Controller after its `drivers` list contains the HTTPS Driver URL:

```sh
# Separate Controller terminal, without Driver's SERVER_SSL_* variables.
export COSBENCH_TLS_STORE_PASSWORD_FILE=/run/secrets/password
bin/cosbench-controller --config conf/controller.yml --pid-file run/controller.pid
# Another terminal:
curl http://127.0.0.1:19088/controller/health
```

Stop one process or all local instances with `bin/cosbench-stop`. A successful
stop releases the listener and removes the owned PID file. Configuration
conversion uses `bin/cosbench-config-migrate`; it preserves the original file
and reports unmapped legacy values.

```sh
bin/cosbench-config-migrate --source /backup/controller.conf \
  --destination conf/controller-new.yml --report controller-migration.json \
  --controller-trust-store /run/secrets/cosbench-truststore.p12
bin/cosbench-config-migrate --source /backup/driver_1.conf \
  --destination conf/driver-new.yml --report driver-migration.json \
  --driver-key-store /run/secrets/cosbench-keystore.p12
bin/cosbench-stop controller --pid-dir run --timeout 40
bin/cosbench-stop driver --pid-dir run --timeout 40
```

Conversion refuses existing destinations: review new YAML and its report
before installing it. Relative paths resolve from the installation root
(the parent of `conf`). Preserve the archive layout, workspace and `start`/
`stop` files. History uses `controller/index.html?loadArch=true`. Driver
mission logs remain readable on disk; RAM mission state and HTTP idempotency
results do not survive restart.

For images, prepare separate data roots owned by UID/GID `10001:10001`, with
YAML under `DATA/conf`. Mount that `conf` read-only over the writable data
root. Set `COSBENCH_ROLE` explicitly for each container. For example, after
building/tagging `cosbench:java21` from the extracted package:

```sh
sudo docker network create cosbench-runtime
sudo docker run -d --name driver --network cosbench-runtime \
  -e COSBENCH_ROLE=driver --env-file /protected/driver.env \
  -e JAVA_OPTS='--add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED' \
  -v /srv/cosbench/driver:/var/lib/cosbench \
  -v /srv/cosbench/driver/conf:/var/lib/cosbench/conf:ro \
  -v /protected/secrets:/run/secrets:ro \
  -p 127.0.0.1:18088:18088 cosbench:java21 driver \
  --config /var/lib/cosbench/conf/driver.yml --pid-file /var/lib/cosbench/run/driver.pid
sudo docker run -d --name controller --network cosbench-runtime \
  -e COSBENCH_ROLE=controller \
  -e COSBENCH_TLS_STORE_PASSWORD_FILE=/run/secrets/password \
  -e JAVA_OPTS='--add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED' \
  -v /srv/cosbench/controller:/var/lib/cosbench \
  -v /srv/cosbench/controller/conf:/var/lib/cosbench/conf:ro \
  -v /protected/secrets:/run/secrets:ro \
  -p 127.0.0.1:19088:19088 cosbench:java21 controller \
  --config /var/lib/cosbench/conf/controller.yml --pid-file /var/lib/cosbench/run/controller.pid
```

`driver.env` contains the protected Driver passwords. Both YAML files should
reference the mounted token file. Controller's Driver URL is
`https://driver:18088/driver`; the certificate must include `driver` in its
SANs. Controller-only uses `drivers: []`. Multiple Drivers use separate
names, data/config/PID directories and host ports, listed in Controller YAML.
Stop Controller first, then Drivers with `sudo docker stop --time 40 NAME`;
inspect exits before removing containers and retain the data mounts.

The archive contains `manifest/applications.tsv`,
`manifest/dependencies.tsv`, `manifest/providers.tsv`,
`manifest/installation-files.txt` and `SHA256SUMS`. Verify the latter after
extraction with `sha256sum -c SHA256SUMS`.

Rollback means stopping the new processes, preserving `workspace`, `archive`,
`mission`, `log` and configuration data, and starting the previous complete
archive with its previous Java runtime. Do not replace individual application
JARs between release versions. Retain the complete previous archive, original
INI and a consistent original data snapshot through the migration window;
run the new version against copies. The adjacent `release-rollback.md` records
release validation and the previous-version startup procedure; see also
`release-notes.md` and `provider-spi.md` in this directory.

Production supports `sio`, `siov1`, `gdas` and `s3`. Discovery is static:
changing factories requires rebuilding and restarting. Other Providers run
only in the complete legacy package; Mock is test-only.
