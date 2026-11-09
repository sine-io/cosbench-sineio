# Java 21 migration candidate

Controller and Driver run as separate Java 21 Spring Boot applications with
Jakarta Servlet and embedded Tomcat. Configuration is schema-1 YAML; use
`bin/cosbench-config-migrate`, `bin/cosbench-controller`,
`bin/cosbench-driver`, and `bin/cosbench-stop`. Existing pages, CLI actions,
and eight Driver `/i` command paths are retained. Service traffic uses TLS
and its own long-lived token, independently of page sessions.

Production supports SineIO `sio`, `siov1`, `gdas`, and S3 `s3`. Providers are
discovered at startup; additions/removals require a complete rebuild and
restart. Mock is test-only. Other adapters remain supported only by the
previous complete legacy package. Runtime hot installation is not provided.
Jackson 1.x, Castor and AWS/SineIO SDK major upgrades remain separate work.
Castor currently requires the Java export option documented in the
[installation guide](shadow/packaging/templates/docs/production-install.md).

Use the [Java 21 build instructions](BUILD-java21.md),
[Provider SPI guide](shadow/provider-spi.md), and
[release/rollback runbook](docs/release-rollback.md). Keep the complete
previous archive, its Java runtime, original INI, and original data snapshot
through the migration window. Driver mission logs survive on disk; the RAM
mission registry and HTTP idempotency results do not recover across restarts.

The release acceptance record is under
`shadow/evidence/2026-11-09/release-validation/`. It describes generated
previous-release data, not customer historical data. Vendor archive backend
checks retain their previously agreed manual scope. Root-build switching and
final removal of active OSGi/Tycho/P2 metadata belong to #23.
