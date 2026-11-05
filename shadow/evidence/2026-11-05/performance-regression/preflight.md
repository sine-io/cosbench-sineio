# PERF-02 Performance Comparison Preflight

Status: not passed; migrated-version measurements are blocked by incomplete PERF-01 inputs.

## Decision

No migrated-version benchmark was run. The PERF-01 procedure says the tolerance and comparison method must be recorded before measuring a migrated build. The baseline currently says no product-approved numeric regression limit exists, so running the candidate now would produce measurements without an admissible acceptance rule.

## Inputs Checked

- #19 integration evidence passed its deployment and Provider matrix. It records candidate source commit `5edbfdba14ea94cd21bb9e4e79778a75c1331977`, Java 21.0.12.1, and Maven 3.10.0. Candidate artifact checksums, source revision, and source-diff fingerprint are retained in [#19 artifact hashes](../deployment-integration/reports/artifacts.sha256), [source commit](../deployment-integration/reports/source-commit.txt), and [source-diff fingerprint](../deployment-integration/reports/source-diff.sha256) (`fe94a3481f4661c3b977a68f5528267df0d524d7412d87f1adc1c0ad15fb0418`).
- The current Driver candidate JAR matches the #19 artifact checksum: `adfa693b4f57030272cd8bdc60ac315679f7c80fbe1aa2481f36d5fbb9573bc2` (`44,893,074` bytes). The Controller and operator JARs are `8c3e0b21176f9a6f6b3d2903ee66a7ef7c99167cf0db0316eb06ed3747aedd83` (`33,304,093` bytes) and `897c2305dca69cb16aaa55f21fb1f78bec5a9e3325029f0f20cee3e621adb4d7` (`3,886,279` bytes). These are development JAR sizes, not a production package comparison.
- PERF-01 defines one Java 8 Driver, one worker, Mock `read`, 64-byte objects, zero delay/errors, one warmup, and three measured runs of 10,000 operations each, with containers limited to 1 CPU, 768 MiB, and 512 PIDs.
- The recorded legacy sample had 10,000 operations, 640,000 bytes, and 100% success in each run. Elapsed times were 1,551/1,530/1,548 ms; published throughput was 10,799/17,730/17,544 ops/s; and p50/p95/p99 were all recorded in the inclusive `0~10 ms` buckets. The throughput max/min spread is about 64%, so these values do not establish an acceptance threshold.

## Missing PERF-01 Evidence

- No approved numeric regression tolerance or comparison method from product expectations.
- The current baseline evidence does not include its raw `performance.csv` or raw metric responses.
- Heap/GC and HTTP connection-pool measurements are absent. CPU, threads, and file descriptors were sampled only at run boundaries, not throughout each run.
- Startup, first-operation, graceful-stop, residual-thread, and comparable production-artifact measurements remain incomplete.
- The baseline issue also records unavailable real/compatible SineIO and S3 endpoints; those Provider runs are not represented by the Mock baseline.

## Metric Disposition

| Metric group | Legacy baseline evidence | Migrated candidate | PERF-02 result |
| --- | --- | --- | --- |
| Completed operations, success/failure, operation counts | Three Mock read runs each recorded 10,000 samples, 640,000 bytes, and 100% success | Not measured | Not passed; no candidate result or approved tolerance |
| Throughput and p50/p95/p99 | Throughput and coarse inclusive `0~10 ms` buckets are recorded for three runs | Not measured | Not passed; the legacy throughput spread is about 64%, and no tolerance is approved |
| Errors and retries | Success ratio is recorded; raw retry counts are unavailable | Not measured | Not passed; complete baseline and candidate retry data are missing |
| CPU, heap, threads, file descriptors, connection pool | Docker/process metrics are only partially sampled at run boundaries; heap/GC and pool counts are unavailable | Not measured | Not passed; the required baseline set is incomplete |
| Startup, first operation, graceful stop, residual threads | Not measured in the accepted baseline | Not measured | Not passed; no comparable baseline or candidate measurements |
| Artifact size | Legacy release archive size is recorded; no comparable decomposed runtime measurement | Current development JAR sizes are recorded above; production package is outside this candidate artifact | Not passed; the artifact boundaries are not comparable |

## Required Before PERF-02 Execution

1. Complete the open PERF-01 measurements and retain the raw baseline data under the baseline evidence directory.
2. Record product-approved tolerances and the per-metric comparison method in [baseline/performance.md](../../../../.scratch/scheme3-migration/baseline/performance.md) before any migrated build is measured.
3. Pin the comparison host, candidate build/artifact, workload, container limits, warmup, and run count, then run at least three migrated trials and capture the PERF-02 metrics and raw data.

Until these inputs exist, PERF-02 is not passed. No Docker containers, images, or temporary resources were created for this preflight.
