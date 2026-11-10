# PERF-02 Performance Comparison Preflight

Status: superseded by the 2026-11-06 scope decision; see the migrated-version profile under `shadow/evidence/2026-11-06/performance-profile/`.

## Decision

This preflight originally held migrated-version measurements because no legacy performance threshold had been approved. On 2026-11-06, the user clarified that legacy comparison and legacy-derived thresholds are not required or authoritative. The #20 contract was changed to record a candidate-only three-run profile; it makes no cross-version regression claim.

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

## Historical Metric Disposition

| Metric group | Legacy historical evidence | Use in PERF-02 |
| --- | --- | --- | --- |
| Completed operations, success/failure, operation counts | Three Mock read runs each recorded 10,000 samples, 640,000 bytes, and 100% success | Historical context only; not a comparison gate |
| Throughput and p50/p95/p99 | Published throughput and coarse inclusive `0~10 ms` buckets are recorded; throughput varied by about 64% max-to-min | Legacy values and thresholds are not used to judge the migrated candidate |
| Errors/retries and resources | Retry counts, heap/GC and connection-pool counts are unavailable; other resource readings are partial | Candidate profile records available metrics and marks Mock-only N/A items |
| Startup, first operation, graceful stop, residual threads, artifact size | Accepted legacy baseline is incomplete | Candidate-only observations are reported without cross-version claims |

## Required Before PERF-02 Execution

1. Pin the candidate build/artifact, workload, container limits, warmup, and run count.
2. Run at least three migrated trials and capture the PERF-02 metrics and raw data.
3. Do not infer a relative regression threshold from these historical legacy measurements.

This preflight did not create Docker resources. The superseding candidate profile owns its own run and cleanup evidence.
