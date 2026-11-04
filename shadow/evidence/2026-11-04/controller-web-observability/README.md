# #16 Controller Web Observability Evidence

Replay the Java 21 Docker verification, CI gates, configuration lifecycle checks, and the two-process Controller/Driver scenario with:

```sh
bash shadow/harness/run.sh /tmp/cosbench-ticket16-replay -- /bin/bash /workspace/shadow/harness/controller-driver-process.sh
```

The recorded run used the pinned Maven, MinIO, and Toxiproxy images from `shadow/harness/versions.env`. It completed with `run_status=0` and `cleanup_status=0`; no project containers, networks, volumes, or certificate containers remained. Maven `clean verify` passed, all 25 `ControllerProcessTest` cases passed, the MinIO provider smoke test passed, and configuration lifecycle replay passed. See [toolchain.txt](toolchain.txt), [build.log](build.log), [ControllerProcessTest report](tests/cosbench-controller-app/com.intel.cosbench.testing.controller.ControllerProcessTest.txt), [provider-minio-test.log](provider-minio-test.log), [config-lifecycle-summary.txt](config-lifecycle-summary.txt), and [cleanup-status.txt](cleanup-status.txt).

The process replay runs independent Controller and Driver JVMs over authenticated HTTPS. A 30-second Mock workload remained `PROCESSING` after a page request timed out, a client disconnected, and the page Session expired. A fresh login queried the same workload; it later reached `FINISHED` with its archive and trigger output intact. The replay also verifies the `/proxy` context path, root redirect, login and download access, CSS/JavaScript/PNG resources, stage and timeline pages, CSV output, download headers and lengths, missing-resource error pages, and public Prometheus output. The Prometheus report records finished state `2`, 594 read operations, 38,016 bytes, and a 100% success ratio. Malformed and oversized uploads are rejected without leaving multipart temporary files.

The full captured HTTP, CLI, state, archive, and process logs are under [controller-driver-process](controller-driver-process/), including its [summary](controller-driver-process/summary.txt) and [cleanup status](controller-driver-process/cleanup.txt). The Maven suite output and machine-readable test report are under [tests](tests/). Overall orchestration output is in [run-summary.txt](run-summary.txt), [container-build.log](container-build.log), and [docker-cleanup.log](docker-cleanup.log).
