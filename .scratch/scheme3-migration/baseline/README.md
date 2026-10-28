# COSBench legacy baseline

Status: evidence captured for part of BASE-01 and PERF-01; baseline acceptance is not complete.

The baseline target is the last legacy release, `0.6.0.2-sineio`, tag `0.6.0.2-sineio`, source commit `cd18daf2f6f97f97409f4709d00bbd7389676d6`. The release archive was downloaded from the official GitHub Release, checked against its published sidecar and a pinned SHA-256, and its legacy `docker/start-cosbench.sh` wrapper is vendored with a pinned SHA-256. The archive SHA-256 is `1421f48a55673e42edd73c7b09a610b2b71ef94c70d46db3fb74dd3c34d22a06`; its compressed size is 72,922,000 bytes (70 MiB).

## Replay

Run from the repository root with a fresh results directory. All Docker operations in the script use `sudo`. The harness downloads and verifies the release archive, builds a Java 8/Jammy image from a pinned base image digest and checksummed legacy network tools, starts independent Controller and Driver containers on the host network with explicit CPU/memory/PID limits, captures public HTTP and CLI behavior, executes the Driver `/i` lifecycle, replays Mock workloads, samples host/container/process resources, and verifies removal of only the containers and temporary image it created. Logs, redacted HTTP headers/bodies, request fixtures, workload archives, and measurements remain in the results directory.

```sh
bash .scratch/scheme3-migration/baseline/replay.sh /tmp/cosbench-baseline-run-01
```

The default run uses three measured Driver workloads with 10,000 Mock reads each, preceded by one warmup. To change the repeated workload size or count:

```sh
BASELINE_RUNS=3 BASELINE_OPS=10000 \
  bash .scratch/scheme3-migration/baseline/replay.sh /tmp/cosbench-baseline-run-02
```

The runner needs `sudo docker`, `curl`, `jq`, `sha256sum`, `ss`, and access to the GitHub release asset. It refuses a non-empty results directory and refuses to use ports 18088 or 19088 when they are already occupied. It does not mount or modify host COSBench data.

## Fixed inputs

`fixtures/` contains reference Controller and Driver INI configurations, a short Mock workload, the corresponding direct Driver mission used by the normal lifecycle, a long-running Mock workload for cancel/abort, and a harmless trigger script. The replay also saves the generated runtime configurations so the wrapper-derived values can be compared with the reference files. The release package is the rollback artifact; its original configuration and workload examples are contained in the package. Checksums and captured archive copies belong in each replay output directory.

The expected-response examples under `fixtures/expected/` freeze stable fields. Run IDs, timestamps, generated workload/mission IDs, dynamic report values, HTML timestamps/session cookies, archive sizes, and JVM resource readings must be normalized or compared by semantic field. Do not byte-compare dynamic HTML, JSON timing fields, logs, or the real-time histogram CSV; `performance.csv` records derived inclusive 10 ms p50/p95/p99 buckets.

## Captured evidence

See [observations.md](observations.md), [scenarios.md](scenarios.md), and [performance.md](performance.md). Current observations include successful Controller and Driver pages, selected CSS/JavaScript/PNG resources, URL-encoded configuration and advanced form submissions, Controller CLI index and Prometheus contracts, valid Driver ping, all eight `/i` routes (including a complete Mock mission and the abort path), and completed Controller Mock archives. The legacy close-after-abort request returned `409 no mission handler` and is retained as an observed behavior rather than treated as a success.

The first local no-op result produced 10 successful reads, 640 bytes, a 100% success ratio, and `FINISHED`/`ACCOMPLISHED` state. A separate 1,000-operation, three-run diagnostic recorded 1,001/1,000/1,001 samples, 64,064/64,000/64,064 bytes, 100% success and published throughput values of 7,201/13,889/15,167 ops/s. That spread is too large for a performance threshold and those short diagnostic numbers are not the locked PERF-01 baseline. The replay runner therefore uses a warmup, 10,000 operations and at least three measured runs, but acceptance still requires review of the raw data and an agreed product regression budget. The hardened one-run 100-operation check completed with `w1/w2/w3/w4=FINISHED`, `w5=CANCELLED`, selected static/form contracts and Prometheus samples passing, and `run_status=0 / cleanup_status=0`. The final default replay recorded 10,000 samples and 640,000 bytes at 100% success in every run, inclusive p50/p95/p99 buckets of `0~10 ms`, elapsed times of 1,551/1,530/1,548 ms and published throughput of 10,799/17,730/17,544 ops/s.

## Open acceptance items

- Obtain approved SineIO and S3 endpoints/credentials and run their operation, configuration, and error matrices. The public GHCR release image was not anonymously readable from this environment; the verified archive is used to build the identical release package in its repository Docker image shape.
- Exercise every public page form, each of the five CLI actions and all eight `/i` commands with frozen input/output fixtures, including page login/session, CSV/downloads, state transitions, and failure/exit-code cases. The replay runner now covers selected valid page configuration/advanced forms, static resources, completed workload artifacts, Prometheus samples, and the CLI cancel path; remaining form variants, invalid-input cases, and completeness review still need approval.
- Measure the complete PERF-01 set on the target hardware: CPU, heap, threads, file descriptors, open/idle HTTP connections, startup/first-operation/stop times, residual threads, and final production artifact size. The Java 8 release image does not contain `jcmd`/`jstat`; the runner records RSS/high-water RSS/thread/FD counts and Docker CPU/memory, and labels the actual heap as unavailable unless a matching JDK diagnostic tool is supplied.
- Freeze numeric performance tolerances with the product owner using repeat-run variation plus product expectations before any migrated-version measurements.
- Verify independent historical archive/workspace readability and rollback startup from a clean checkout. The repository snapshot has no completed historical workload archive; the new Mock run creates a fresh archive only.

Until those items have evidence, this baseline is useful for development but is not an acceptance pass. Docker containers and the temporary replay image are removed by the runner on success or failure and verified afterward; response headers redact session cookies, the temporary cookie jar is deleted with the build context, and diagnostic output contains no supplied Provider credentials.
