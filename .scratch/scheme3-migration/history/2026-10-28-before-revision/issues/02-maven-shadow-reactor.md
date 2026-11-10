# 02: Establish a Java 21 Maven Shadow Build

**What to build:** A repeatable Java 21 development build that resolves and compiles the agreed active migration scope through ordinary Maven, while the legacy Tycho build remains available until the replacement is verified.

**Blocked by:** 01: Freeze the Legacy Behavior Baseline.

**Status:** ready-for-agent

- [ ] Build the active dependency closure without Tycho/P2 participation and keep the existing legacy build usable during migration.
- [ ] Use Java 21 for build and tests; select and enforce the lowest compatible bytecode release per module, recording the decision and compatibility evidence.
- [ ] Include only Controller, Driver, core, required Web and shared modules, SineIO, S3, and test-only Mock in the active build candidate; exclude non-target Providers and legacy runtime.
- [ ] Verify standard source/resource/test layout, generated artifacts, Spring resources, service descriptors, and local dependency handling through a clean Maven build.
- [ ] Produce an explicit dependency and duplicate-class report for the active closure, including SineIO and S3 dependencies.
- [ ] Run the build in a Java 21 Docker environment using `sudo` for Docker commands, and remove containers/networks created by the check.
