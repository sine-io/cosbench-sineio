# Java 21 Policy for Migrated Modules

BUILD-01 establishes this policy for every module subsequently admitted to the shadow reactor. Ticket 08 wires the growing migrated closure into continuous CI; its audit gates must consume this policy.

- Every migrated POM inherits `shadow/pom.xml`; it must not override the Java release, toolchain checks, or pinned lifecycle plugins.
- Maven, test JVMs, packaged-module probes, and later application processes run on Java 21. Compiler and test compiler use `maven.compiler.release=21`; class files have major version 65.
- The parent Enforcer execution rejects other Java majors, Maven versions other than 3.10.0, and any `maven.compiler.release` value other than 21. Do not skip Enforcer or tests in CI.
- Use the digest-pinned Java 21 image in `harness/versions.env`. An updated tag alone is not an accepted toolchain change: record the digest, `mvn --version`, official compatibility evidence, and a clean-cache result.
- New source, resources, and tests use `src/main/java`, `src/main/resources`, and `src/test/java`. Runtime resource tests must also run against the packaged ordinary JAR.
- All active dependencies are explicit Maven coordinates. The new build does not consume P2, Tycho, Eclipse manifests, the root legacy reactor, or local OSGi JARs. Any necessary future local JAR must have source, SHA-256, and license recorded before admission.
- Every module addition extends the clean `verify`, dependency tree, duplicate-class audit, and packaged runtime probe. The continuous gates below cover the entire effective reactor automatically.

## Continuous entry (CI-01)

Run `bash ci/verify-shadow.sh EMPTY_REPORT_DIR` from any working directory. [The workflow](../.github/workflows/shadow-verify.yml) runs this exact entry on pushes, pull requests and manual dispatches, without restoring Maven or build-output caches. The digest-pinned Java 21 container starts with an empty tmpfs Maven repository and runs the whole migrated reactor's `clean verify`; subsequent installs reuse those verified outputs for auditing. The image/BOM/lifecycle versions remain in `harness/versions.env`, `pom.xml`, and the retained `toolchain.txt`.

The same run exercises MinIO, Toxiproxy, configuration/lifecycle, all four production Provider factories and test Mock, packaged Controller and Web, and the authenticated HTTP/Mock mission replay. Controller/app modules inherit all gates; `ControllerProcessTest` verifies real service behavior in separate JVMs and `harness/controller-process.sh` replays the executable with no test classes. `harness/run.sh` remains the lower-level build/configuration/Provider harness; it also invokes the same dependency gates, and accepts custom replays. These entries do not run the root Tycho reactor.

## Blocking checks

`harness/audit/gates.sh REPORT_DIR MAVEN_REPO` audits artifacts already verified and installed in that container. Maven Help 3.5.1 exports the **effective reactor**, including active profiles. Every JAR module is included; unsupported packaging fails instead of silently skipping a module.

| Gate | Failure condition |
| --- | --- |
| Java / Maven / convergence | Wrong toolchain or release, or conflicting dependency paths. Enforcer includes test scopes; provided dependencies are rejected by admission. Commons Logging is fixed to the previously selected 1.1.1. The HTTP spike fixes its previously selected Commons Collections 3.2.2 locally. |
| Dependency tree / admission | Resolution failure, unknown scope, system/local JAR, unknown coordinate, unapproved license expression, changed SHA-256, or installed sibling bytes differing from the current reactor artifact. Maven downloads use strict checksum validation. |
| Production/test boundary | OSGi, Equinox, Spring DM, Tycho/P2, legacy Servlet/Tomcat or Mock dependencies; test frameworks in non-test scopes. Compile/runtime/test are the admitted scopes. |
| Duplicate classes | Duplicate classes across each ordinary main JAR and its actual production or test classpath, including test output and the effective Java 21 entries of multi-release JARs. Only `module-info.class` and non-class metadata are excluded. No child loader exists after #04, so no Provider-specific class exclusion is applied. |
| `jdeps` | Tool failure or any `not found` result in the migrated main JAR's references on its production classpath. Boot's ordinary pre-repackage JAR is checked; unresolved dependencies cannot be hidden by `--ignore-missing-deps`. |
| Published artifacts / resources | Forbidden runtime definitions, application references to removed APIs or legacy Providers, application OSGi manifests, non-21 COSBench bytecode, missing/changed staged classes or resources, or nested JARs outside the declared production closure. Boot's automatically injected JAR tools are disabled. |

Artifacts are inspected as ZIPs, recursively including Boot libraries. Third-party bundles can contain descriptive OSGi metadata or optional adapters (for example Commons Configuration's unused legacy Servlet adapters); those do not establish an application dependency. The gate bans forbidden runtime **definitions** in every production library and removed-API **references** in every migrated application class, while `jdeps` resolves all migrated main classes on the actual production classpath. Standard `META-INF/maven/**/pom.xml` is build provenance, so its test source declarations are not treated as runtime descriptors. This does not exclude a module or relax duplicate-class checks.

Mock classes and SPI descriptor entries are permitted only in the test outputs of `cosbench-provider-spike` and `cosbench-http-spike`. Their main JARs, dependencies, executable archives, and every other module's test output receive the production Mock exclusion. Test frameworks are admitted only in test dependency scope/output. No entire spike module is excluded from production scanning.

`harness/audit/test-gates.sh REPORT_DIR` exercises the public exit-code/diagnostic boundary with real JARs and Maven fixtures: valid resources/dependencies pass; missing resources, OSGi references/dependencies, Mock leakage, unlisted nested dependencies, unknown licenses/coordinates, changed checksums, test dependency leakage, ordinary/multi-release duplicates, missing `jdeps` dependencies, convergence violations and a lower release fail. CI runs these fixtures and retains their individual logs.

## Dependency and license changes

[`dependency-lock.tsv`](dependency-lock.tsv) admits exact `group:artifact:jar[:classifier]:version`, SHA-256 and reviewed SPDX expressions for production **and** test dependencies. It is not regenerated in CI. Artifact provenance is Maven Central at `https://repo.maven.apache.org/maven2/<group path>/<artifact>/<version>/`; both dependency lists and per-module scope/checksum/license reports are retained.

To propose an update after explicitly resolving a new dependency, run in the Java 21 container:

```sh
java shadow/harness/audit/BuildAudit.java lock-candidate /tmp/maven-repository \
  shadow/*/target/ci-dependencies.txt > /reports/dependency-lock.candidate.tsv \
  2> /reports/license-metadata.txt
```

Review the actual resolved JAR and inherited Maven POM license metadata before updating the committed lock. Candidate generation is a convenience, not approval; unknown metadata remains `REVIEW` and fails admission. Castor 1.3.3 lacks POM license metadata: its two JARs include the five-condition ExoLab license plus Apache-2.0 notices. Their reviewed expression is `LicenseRef-Castor-1.3.3 AND Apache-2.0`; the original notice texts are retained under [`harness/audit/licenses/`](harness/audit/licenses/). EDL-1.0 maps to BSD-3-Clause; reactive-streams uses MIT-0. For dual-licensed artifacts the lock records the admitted license alternative (for example Logback's EPL-2.0).

When adding a module, declare it under the same reactor and keep standard output/resource layout, extend behavioral tests and the appropriate replay, review new dependency admission records, and add explicit test exceptions only for the agreed fixture boundary. The automatic module inventory and inherited rules must remain enabled. Production application, distribution, image and rollback acceptance remain owned by the later tickets.

## Reports and preserved old build

The workflow uploads reports even on failure: full build output, Surefire XML/text, effective POM, reactor inventory, verbose dependency trees, scopes/licenses/checksums, JAR/resource listings, artifact scans, duplicate-class and `jdeps` output, fixture diagnostics, prerequisite replay results, and Docker cleanup status. Success, failure and signal exits remove the harness's containers, network, volume and temporary credentials. The Maven repository is container-local and disappears with the one-off container. Image layers may remain cached.

The legacy root build stays at its original entry until ticket 23. Use the fixed legacy tag and the retained release archive described in `toolchain-evidence.md` to reproduce old-build behavior.
