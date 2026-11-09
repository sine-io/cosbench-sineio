# Java 21 build and development

The migrated distribution supports `sio`, `siov1`, `gdas` and `s3`. Controller
and Driver are separate Spring Boot processes using schema-1 YAML. The active
migration reactor is `shadow/pom.xml`; switching the root build is ticket #23.
The [old build](BUILD-sineio.md) is retained for previous releases only.

Build in the pinned Docker toolchain from the repository root:

```sh
source shadow/harness/versions.env
sudo docker run --rm -v "$PWD:/workspace" -w /workspace "$MAVEN_IMAGE" \
  mvn -B -ntp -f shadow/pom.xml -DskipTests package
bash shadow/packaging/build-production.sh --output-dir out/java21-release
bash shadow/packaging/verify-production.sh \
  --archive out/java21-release/cosbench-0.6.0.2-java21.tar.gz \
  --report-dir out/java21-package-check --build-images
```

`-DskipTests package` creates artifacts; it is not the acceptance gate. The
complete Docker/MinIO tests and dependency checks use
`bash ci/verify-shadow.sh EMPTY_REPORT_DIR`. The full reactor also supplies a
report directory if the optional local `DriverPerformanceProfileTest` is
present. The controlled #20 profile uses its separate
`shadow/harness/performance-profile.sh` entry with fixed resource limits.
Release validation consumes fixed #19/#20
artifact hashes and rejects a different candidate until the affected evidence
has been repeated. See the [release runbook](docs/release-rollback.md).

For interactive development, start a Java 21 container and keep its shell
open while the background services run:

```sh
source shadow/harness/versions.env
sudo docker run --rm -it --name cosbench-java21-dev \
  -p 127.0.0.1:19088:19088 -p 127.0.0.1:18088:18088 \
  -v "$PWD:/workspace" -w /workspace --entrypoint bash "$MAVEN_IMAGE"
```

Inside that container:

```sh
mvn -B -ntp -f shadow/pom.xml -DskipTests package
export JAVA_OPTS='--add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED'
bash dev/cosbench-dev.sh start --drivers 1 --no-build
# Inspect Controller on http://127.0.0.1:19088/controller/index.html.
bash dev/cosbench-dev.sh restart controller --no-build
bash dev/cosbench-dev.sh stop
exit
```

The development script generates local TLS/YAML under `out/cosbench-dev`.
`--no-build` uses the container's existing artifacts and avoids invoking Docker
inside Docker. Increasing the Driver count requires exposing those ports as
well. Keep generated configuration, archives, mission logs and reports when
removing the development container; do not use `--reset-runtime` on data that
must survive.

Production installation uses the commands and mounts in the packaged
[installation guide](shadow/packaging/templates/docs/production-install.md).
Provider development uses the [SPI guide](shadow/provider-spi.md). Jackson 1.x,
Castor, and AWS/SineIO SDK major upgrades are deferred to separate work.
