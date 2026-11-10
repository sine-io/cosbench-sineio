# 03: Migrate Shared Modules and Define Provider SPI Boundaries

**What to build:** Ordinary Maven artifacts for the shared APIs and foundations so the new Controller, Driver, Web backends, and Providers can use the existing domain contracts without OSGi services.

**Blocked by:** 02: Establish a Java 21 Maven Shadow Build.

**Status:** ready-for-agent

- [ ] Shared API, configuration, core, HTTP, logging, and required serialization modules resolve as ordinary Maven dependencies in the active reactor.
- [ ] Existing public storage/auth factory interfaces and protocol/model behavior remain compatible with the baseline.
- [ ] Ordinary Spring Bean wiring can consume the shared services without OSGi service/list/reference declarations.
- [ ] Provider discovery has a stable boundary based on the existing factory interfaces; shared modules do not depend on concrete SineIO/S3 SDK types.
- [ ] Configuration, protocol, resource-loading, and dependency tests pass without changing workload or mission behavior.
