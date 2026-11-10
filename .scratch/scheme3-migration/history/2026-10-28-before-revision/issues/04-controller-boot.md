# 04: Run Controller as a Spring Boot Application

**What to build:** A standalone Java 21 Controller process that loads its service and configuration, reports startup failures clearly, serves a process health check, and shuts down without leaving resources behind.

**Blocked by:** 03: Migrate Shared Modules and Define Provider SPI Boundaries.

**Status:** ready-for-agent

- [ ] Controller starts as an executable Spring Boot application on the configured address and port without Equinox, Spring DM, or the old Tomcat runtime.
- [ ] Compatible Controller Spring Bean definitions load into the application context and expose the Controller service to same-process adapters.
- [ ] Missing or invalid configuration produces a diagnostic startup failure and a non-success exit code rather than terminating from business code.
- [ ] TERM and the stop lifecycle release the HTTP port, worker/executor resources, and logging resources within the configured grace period.
- [ ] A process-level smoke test verifies startup, health, and clean stop using the Java 21 runtime.
