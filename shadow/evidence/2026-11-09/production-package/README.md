# #21 Production Package Evidence

PKG-01 was replayed on 2026-11-09 from the working tree with the pinned Java
21 Maven image in `shadow/harness/versions.env`.

Build and package commands:

```sh
sudo docker run --rm \
  -e HOME=/tmp/maven-home -e MAVEN_CONFIG=/tmp/maven-config \
  -v "$PWD:/workspace" -w /workspace \
  maven:3.10.0-eclipse-temurin-21@sha256:d2230484f42a845db4a2ae5c4b4b2ba474636bc9c17233917f95ddf2110ab06d \
  mvn -B -ntp -Dmaven.repo.local=/tmp/maven-repository \
    -f shadow/pom.xml package -DskipTests

bash shadow/packaging/test-packaging.sh
bash shadow/packaging/ci-package.sh /tmp/cosbench-production-package-final5
```

The reactor package build completed successfully for all 22 modules. The CI
package gate reported `status=passed`, `rebuild=1`, `process_probe=1`, and
`images=1`. It produced `cosbench-0.6.0.2-java21.tar.gz` with SHA-256:

```text
672c733249cbedf4c2046d47210dcb3333eb3e1940363d8bc72a95b7419eb6ff
```

The package verifier checked the two executable Boot applications and the
operator CLI, the four production storage factories (`sio`, `siov1`, `gdas`,
`s3`), the final nested Maven dependency closures with ArtifactAudit,
duplicate-class and Java 21 jdeps gates, licenses, manifests, installation
file list, and `SHA256SUMS`. It scanned the archive, every production JAR and
its nested Boot libraries, and every JAR copied into the production image for
Mock, legacy, source, spike, P2, Equinox, OSGi, Spring DM, and old Tomcat
content. The production image uses a Java 21 JRE; the development image is a
separate pinned Maven/Java 21 image with the source tree, MinIO compose file,
and test tooling, while the production image is checked for those paths and
tools.

The empty-install probe copied only the archive, created a temporary PKCS12
store, started Driver on HTTPS `18088` and Controller on HTTP `19088`, checked
`{"accepting":true}`, stopped each process with `bin/cosbench-stop`, and
confirmed that the owned PID files, containers, HTTP endpoints, and listening
ports were removed. The verifier removed its temporary containers and check
images after the run.

The existing broad `ci/verify-shadow.sh` entrypoint was also attempted. Its
SineIO MinIO tests passed, then the reactor stopped at the pre-existing,
untracked `DriverPerformanceProfileTest`, which requires
`COSBENCH_PERFORMANCE_REPORT_DIR` supplied by the dedicated performance
harness. That separate integration/performance entrypoint is outside PKG-01;
its complete MinIO/deployment/release-rollback acceptance remains assigned to
#22.
