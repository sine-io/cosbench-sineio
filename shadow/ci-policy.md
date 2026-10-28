# Java 21 Policy for Migrated Modules

BUILD-01 establishes this policy for every module subsequently admitted to the shadow reactor. Ticket 08 wires the growing migrated closure into continuous CI; its audit gates must consume this policy.

- Every migrated POM inherits `shadow/pom.xml`; it must not override the Java release, toolchain checks, or pinned lifecycle plugins.
- Maven, test JVMs, packaged-module probes, and later application processes run on Java 21. Compiler and test compiler use `maven.compiler.release=21`; class files have major version 65.
- The parent Enforcer execution rejects other Java majors, Maven versions other than 3.10.0, and any `maven.compiler.release` value other than 21. Do not skip Enforcer or tests in CI.
- Use the digest-pinned Java 21 image in `harness/versions.env`. An updated tag alone is not an accepted toolchain change: record the digest, `mvn --version`, official compatibility evidence, and a clean-cache result.
- New source, resources, and tests use `src/main/java`, `src/main/resources`, and `src/test/java`. Runtime resource tests must also run against the packaged ordinary JAR.
- All active dependencies are explicit Maven coordinates. The new build does not consume P2, Tycho, Eclipse manifests, the root legacy reactor, or local OSGi JARs. Any necessary future local JAR must have source, SHA-256, and license recorded before admission.
- Every module addition extends the clean `verify`, dependency tree, duplicate-class audit, and packaged runtime probe; ticket 08 adds convergence, `jdeps`, and full license gates for the migrated closure.

The repeatable baseline command is `shadow/harness/run.sh EMPTY_REPORT_DIR`. It starts with an empty Maven repository and exports the artifact, resource list, dependency tree, duplicate-class result, toolchain, test output, MinIO smoke, and cleanup result.

The legacy root build stays at its original entry until ticket 23. Use the fixed legacy tag and the retained release archive described in `toolchain-evidence.md` to reproduce old-build behavior.
