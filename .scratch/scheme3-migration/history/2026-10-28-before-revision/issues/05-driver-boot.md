# 05: Run Driver as a Spring Boot Application

**What to build:** A standalone Java 21 Driver process that loads its service configuration, exposes a health check, and shuts down its mission and worker resources cleanly.

**Blocked by:** 03: Migrate Shared Modules and Define Provider SPI Boundaries.

**Status:** ready-for-agent

- [ ] Driver starts as an executable Spring Boot application on the configured address and port without Equinox, Spring DM, or the old Tomcat runtime.
- [ ] Compatible Driver Spring Bean definitions load and expose Driver services to same-process adapters.
- [ ] Invalid startup configuration is reported with actionable diagnostics and a non-success process exit code.
- [ ] TERM and the stop lifecycle stop new work, release HTTP and worker resources, and complete within the configured grace period.
- [ ] A process-level smoke test verifies startup, health, and clean stop using the Java 21 runtime.
