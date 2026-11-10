# 13: Add Repeatable Docker and MinIO Integration Tests

**What to build:** A repeatable Java 21 development/test environment that validates the migrated applications, HTTP contract, and real object-storage operations against MinIO and cleans up after each run.

**Blocked by:** 07: Load SineIO and S3 Providers in the Production Runtime; 08: Preserve the Driver `/i` HTTP Contract; 09: Keep Controller-to-Driver Calls Reliable During Long Workloads; 10: Preserve Controller Web and CLI Behavior on Boot 3; 11: Preserve Driver Web Behavior on Boot 3; 12: Migrate Configuration and Operator Commands.

**Status:** ready-for-agent

- [ ] Java 21 build and integration tests run reproducibly inside Docker with CI-equivalent configuration.
- [ ] MinIO is provisioned in the test environment with deterministic credentials/buckets and exercises SineIO/S3 smoke operations.
- [ ] The test run covers both applications, HTTP contract, Web smoke, config conversion, graceful stop, and Provider disposal.
- [ ] Docker commands use `sudo`; test completion removes containers, networks, and temporary resources created by the test.
- [ ] Failure output preserves enough logs and reports to diagnose the failing contract without retaining running test resources.
