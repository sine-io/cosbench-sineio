# 01: Freeze the Legacy Behavior Baseline

**What to build:** A reproducible baseline of the current release that lets a maintainer compare the migrated applications with the behavior users rely on and recover the old release if migration validation fails.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] Record the current release, Java/runtime environment, launch arguments, supported routes, configuration defaults, directories, logs, exit codes, and provider combinations used by baseline tests.
- [ ] Capture representative Controller page/form, CLI, download, CSV, Prometheus, and Driver `/i` request/response behavior.
- [ ] Run minimal Mock/noop, SineIO, and S3 workloads where credentials/endpoints are available; document unavailable cases without treating them as passing.
- [ ] Preserve a runnable old release archive and demonstrate that it can be started again without deleting or rewriting existing workspace/archive data.
- [ ] Store versioned workload and request/response fixtures that later migration tickets can use for behavioral comparisons.
