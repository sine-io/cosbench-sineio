# 12: Migrate Configuration and Operator Commands

**What to build:** Versioned YAML configuration and stable operator commands that let existing installations convert settings, start and stop both applications, and retain historical workload data.

**Blocked by:** 04: Run Controller as a Spring Boot Application; 05: Run Driver as a Spring Boot Application; 10: Preserve Controller Web and CLI Behavior on Boot 3; 11: Preserve Driver Web Behavior on Boot 3.

**Status:** ready-for-agent

- [ ] Controller and Driver load their versioned YAML configuration with validated ports, paths, credentials, logging, Provider, concurrency, Web, timeout, and retry settings.
- [ ] The configuration migration command converts supported legacy values, reports unmapped values, and retains the original configuration unchanged.
- [ ] Start and stop commands manage Controller and Driver processes, report useful exit status, and honor graceful shutdown behavior.
- [ ] An installation in an empty directory can start and stop, and an existing installation can restart while preserving workspace, archive, mission, and log directory semantics.
- [ ] Legacy archive/workspace data remains readable or has an explicit diagnostic migration path; no existing user data is deleted or silently reordered.
