# 14: Package Production Applications and Verify Rollback

**What to build:** A production archive and image containing the two Boot applications and only their supported dependencies, with an installation and rollback path that preserves existing benchmark data.

**Blocked by:** 12: Migrate Configuration and Operator Commands; 13: Add Repeatable Docker and MinIO Integration Tests.

**Status:** ready-for-agent

- [ ] Production archive/image contains Java 21 runtime requirements, Controller/Driver applications, SineIO/S3, configuration templates, commands, licenses, and required runtime dependencies.
- [ ] Production archive/image excludes Mock, legacy Providers, old Tomcat, source artifacts, development tools, P2, Equinox, and Tycho runtime/build output.
- [ ] A clean installation starts and stops both applications, passes health checks, and reads existing archive/mission data according to the migration contract.
- [ ] A previous release archive can be restored and started without deleting user data; rollback steps are exercised and recorded.
- [ ] Release checksums, licenses, image contents, and installation diagnostics are reproducible in CI.
