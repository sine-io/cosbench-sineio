# 15: Remove Active OSGi Build Paths and Finalize Migration

**What to build:** A completed scheme-three release whose active source, build, scripts, and production artifacts no longer depend on OSGi, Spring DM, Tycho/P2, Equinox, or the legacy Tomcat runtime, while old sources remain isolated for historical releases.

**Blocked by:** 14: Package Production Applications and Verify Rollback.

**Status:** ready-for-agent

- [ ] All acceptance matrix stages for baseline, Maven build, applications, Web, Providers, packaging, and rollback pass on the release candidate.
- [ ] Active reactor, scripts, Docker images, CI, and production artifacts have no OSGi runtime, Spring DM, Equinox, Tycho/P2, or old Tomcat dependency.
- [ ] Non-target Providers, Manta, extensions, and old runtime metadata are retained only in the legacy archive and are not referenced by the active reactor or production package.
- [ ] Active service descriptors and Spring configuration contain no stale OSGi registrations, and production Provider inventory matches the agreed support boundary.
- [ ] Final scans, clean Maven verify, Java 21 process checks, page and `/i` contracts, MinIO smoke, restart recovery, shutdown, and rollback all pass.
- [ ] Release notes identify supported Providers, Java 21 runtime, configuration migration, Provider restart/repackage requirement, deferred dependency upgrades, and legacy support boundary.
