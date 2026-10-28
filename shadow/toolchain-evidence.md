# BUILD-01 and ENV-01 Evidence

Recorded 2026-10-28. The user authorized ticket 02 to proceed using the currently available ticket 01 artifacts while leaving BASE-01/PERF-01 acceptance open. No ticket 01 acceptance result is changed by this work.

## Scope and preserved baseline

The single ordinary Maven module is a minimal `cosbench-config` foundation slice: `Config`, `ConfigException`, `XmlConfig`, the existing Castor mapping registry, and all eight existing mapping XML resources. Copies in `src/main/java` and `src/main/resources` preserve their legacy bytes and Apache-2.0 headers; this ticket does not migrate the full configuration/parser/logging closure. The module has no runtime dependencies and no local JAR input. Later bounded migration tickets expand the slice.

The legacy root POM, source layouts, manifests, P2 repository, and release metadata remain in place. The fixed legacy source is tag `0.6.0.2-sineio`, commit `cd18daf2f6f97f97409f4709d00bbd7389676d6`. The complete release archive remains in the ticket 01 replay output, including `/tmp/cosbench-baseline-replay-default-hardened-1793162854-3818762/cosbench-0.6.0.2-sineio.tar.gz`; SHA-256 is `1421f48a55673e42edd73c7b09a610b2b71ef94c70d46db3fb74dd3c34d22a06`. The [baseline replay documentation](../.scratch/scheme3-migration/baseline/README.md) pins the archive and supplies the download, checksum validation, and Java 8 container replay entry.

To rebuild the preserved old source separately, use a detached worktree at the fixed tag, then its original `ci/build-tycho.sh`. Never point the new Maven reactor at that P2/OSGi output.

```sh
git worktree add --detach /tmp/cosbench-legacy-0.6.0.2 0.6.0.2-sineio
bash /tmp/cosbench-legacy-0.6.0.2/ci/build-tycho.sh /tmp/cosbench-legacy-rebuild
```

## Pinned versions and official compatibility sources

| Input | Pin | Evidence |
| --- | --- | --- |
| Spring Boot BOM | `org.springframework.boot:spring-boot-dependencies:3.5.16` | [Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html) specify Java 17–25, Spring Framework 6.2.19+, and Maven 3.6.3+; Java 21 fits this range. [Exact BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom). |
| Maven | `3.10.0` | [Apache release notes](https://maven.apache.org/docs/3.10.0/release-notes.html) require Java 8+, so Maven can run on the selected JDK 21. |
| Build/test/run JDK | Temurin `21.0.12.1` | Measured by `mvn --version` and the packaged probe inside the digest-pinned Maven image. |
| Compiler plugin | `3.16.0` | [Compiler release docs](https://maven.apache.org/plugins/maven-compiler-plugin/examples/set-compiler-release.html) define `maven.compiler.release`; both main and test compilation log `release 21`. |
| Enforcer / Surefire | `3.6.3` / `3.6.1` | [Enforcer Java rule](https://maven.apache.org/enforcer/enforcer-rules/requireJavaVersion.html), [Surefire plugin](https://maven.apache.org/surefire/maven-surefire-plugin/). All modules inherit the policy. |
| Clean / Resources / JAR / Dependency plugins | `3.5.0` / `3.5.0` / `3.4.2` / `3.11.0` | Exact versions appear in the reactor POM and execution log; the JAR output timestamp is fixed for repeatable content. |
| JUnit / Platform | `5.12.2` / `1.12.2` | Managed by the exact Boot BOM, test scope only; dependency tree is exported. |

The [official Maven image source](https://github.com/carlossg/docker-maven), [Bitnami MinIO image documentation](https://github.com/bitnami/containers/tree/main/bitnami/minio), and [Toxiproxy v2.13.0 documentation](https://github.com/Shopify/toxiproxy/tree/v2.13.0) describe the image/tool behavior. `harness/versions.env` is the executable lock file:

| Image | Digest / observed version |
| --- | --- |
| `maven:3.10.0-eclipse-temurin-21` (`linux/amd64`) | `sha256:d2230484f42a845db4a2ae5c4b4b2ba474636bc9c17233917f95ddf2110ab06d`; Maven 3.10.0 / Java 21.0.12.1 |
| `bitnamilegacy/minio:2024.6.13-debian-12-r2` | `sha256:aa1752895e6d2b420e394d55241d5b2c948960715db0a50bb648f430e447e645`; MinIO `DEVELOPMENT.2024-06-13T22-53-53Z`, commit `20960b6a2ddb9594ee418035b3c7c7fe92ae6a12`, AGPLv3 |
| `ghcr.io/shopify/toxiproxy:2.13.0` | `sha256:5d3dae09bc185dcc5ff6cf43abf6062a4a720aff0c4306a471e773acdcd107aa` |

Official MinIO Docker Hub/Quay image manifests were not anonymously available in this environment, so the environment uses the pinned Bitnami legacy distribution of MinIO for tests. This is a test dependency, not a production runtime image. MinIO's bundled `mc` performs the real object-storage operations; no Provider behavior is claimed by that probe.

## Replays and results

The agreed public seams are the Java 21 `mvn clean verify` build and the Docker/MinIO object round-trip. The initial red run reached Java 21 test compilation with an empty Maven repository and failed on the missing mapping registry. Copying that source and the eight resources made the clean build pass.

The harness replay is:

```sh
shadow/harness/run.sh /tmp/cosbench-scheme3-02-clean
```

The pre-review replay `/tmp/cosbench-scheme3-02-pre-review-20261028` passed one Maven test, the packaged runtime Java 21/class-major-65 probe, all eight mapping resources, duplicate-class inspection, real MinIO put/get/delete, and observable HTTP interruption for each Toxiproxy fault. The clean-checkout verification is recorded after the implementation commit.

The Enforcer negative checks rejected JDK 8 and Maven 3.9.9 (`/tmp/cosbench-scheme3-02-java-policy.log`) and a Java 21 build with `-Dmaven.compiler.release=17` (`/tmp/cosbench-scheme3-02-release-policy.log`).

Earlier end-to-end evidence in `/tmp/cosbench-scheme3-02-run-20261028-0528` had one passing test, eight packaged XML resources, no duplicate classes, and successful PUT/GET/DELETE using the 31-byte payload `cosbench scheme3 minio smoke v1` followed by a newline; upload and download SHA-256 were both `5821e4ae4827e2a59ee2c7355cc72afe63e83e70cd5b0afb8b7aefc4fec00f9e`. The MinIO and Toxiproxy health checks passed. Its cleanup report was `run_status=0`, `cleanup_status=0`, with no project containers, networks, or volumes remaining.

The early MinIO smoke permission failure in `/tmp/cosbench-scheme3-02-run-20261028-0525` reported `run_status=1`, `cleanup_status=0` and no remaining project resources. An interactive Ctrl-C during the custom command in `/tmp/cosbench-scheme3-02-interrupt-20261028` also removed the running one-off Maven container, MinIO, Toxiproxy, their network/volume, and temporary credentials; logs remain in that directory. Existing host containers and volumes were preserved in all cases.
