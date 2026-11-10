# 06: Discover Mock Providers Through the Static Registry

**What to build:** A Driver-side Provider registry that discovers test-only Mock storage and auth factories at startup and can use them for a complete, repeatable test operation without shipping Mock in production.

**Blocked by:** 03: Migrate Shared Modules and Define Provider SPI Boundaries; 05: Run Driver as a Spring Boot Application.

**Status:** ready-for-agent

- [ ] The registry discovers Mock storage and auth factories using startup-time `ServiceLoader` on test classpath only.
- [ ] Duplicate Provider names, missing descriptors/dependencies, and factory initialization errors identify the failing Provider clearly.
- [ ] A test operation verifies factory lookup, configuration, initialization, operation, and disposal order through public Provider behavior.
- [ ] Closing the Driver releases Mock-owned resources, and calls after disposal return the expected diagnostic failure.
- [ ] The production package does not contain Mock classes or service descriptors.
