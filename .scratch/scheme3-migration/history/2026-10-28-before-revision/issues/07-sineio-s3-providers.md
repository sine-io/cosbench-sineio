# 07: Load SineIO and S3 Providers in the Production Runtime

**What to build:** Production Provider support for SineIO (`sio`, `siov1`, `gdas`) and S3 through the static registry, with storage operations validated against MinIO and SDK conflicts handled only when demonstrated.

**Blocked by:** 06: Discover Mock Providers Through the Static Registry.

**Status:** ready-for-agent

- [ ] Production startup discovers all three SineIO factories and the S3 factory by their established Provider names.
- [ ] Existing Provider configuration semantics, supported operations, and error mapping match the baseline for the tested operations.
- [ ] SineIO and S3 dependency coexistence is checked on the production classpath; a child classloader is added only for a reproducible conflict.
- [ ] MinIO-backed smoke covers applicable put/get/list/delete and multipart operations, authentication, timeouts, and error mapping.
- [ ] Provider clients and worker resources are disposed when missions close and when Driver shuts down.
- [ ] Production artifacts include the required SineIO/S3 libraries and licensing/checksum metadata but exclude Mock and legacy Providers.
