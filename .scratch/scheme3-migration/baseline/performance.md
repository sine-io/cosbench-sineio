# PERF-01 sampling and current result

## Frozen workload and procedure

The repeatable local probe uses the release Mock storage provider so it has no object-store/network dependency:

- Java 8 release runtime, one Driver process, one worker, one `read` operation;
- 64-byte mock object; `delay=0`, `errors=0`; one warmup then at least three measured runs;
- default `totalOps=10000`; `BASELINE_RUNS` and `BASELINE_OPS` can override those values;
- each run starts before mission submit and ends after the Driver query reports `running=false`; request bodies/statuses, redacted response headers, wall-clock interval, report counts/bytes/average latency/throughput and derived p50/p95/p99 histogram buckets are retained;
- Docker CPU/memory/PID count and Java RSS/high-water RSS/thread/file-descriptor count are sampled after each run;
- build/runtime output records the release checksum, base image digest, host OS/CPU/memory, process logs and generated artifact sizes.

Run it with:

```sh
BASELINE_RUNS=3 BASELINE_OPS=10000 \
  bash .scratch/scheme3-migration/baseline/replay.sh /tmp/cosbench-baseline-perf-01
```

The runner reports throughput emitted by the legacy Driver, and separately records elapsed time including HTTP submit/login/launch/query. Keep these measures distinct. Use completed sample count and success ratio as gates before comparing latency or throughput. The raw close response and archive histogram are retained. The legacy `wrReport` object has no latency fields, so the runner derives p50/p95/p99 as inclusive 10 ms histogram buckets from `report[].latency.histoData`; an empty histogram is recorded as `null`. These buckets are evidence for later product review, not an approved threshold.

## Initial diagnostic run

The first exploratory 1,000-operation runs on the shared 2-vCPU/1.9-GiB VM completed in 452, 464, and 454 ms. The Driver reported 1,001, 1,000, and 1,001 samples (64,064, 64,000, 64,064 bytes) and 7,201, 13,889, and 15,167 ops/s respectively. All reported `100%` success. These were deliberately kept in `/tmp/cosbench-scheme3-baseline-01/noop-1000.csv` during implementation; that file is an exploratory record, not a clean baseline artifact. The replay runner supersedes it with a warmup and larger fixed operation count.

The harness verification with one 100-operation measured run produced 100 samples, 6,400 bytes, and `100%` success. It is a script/integration check, not the locked PERF-01 baseline and must not be used as a regression threshold.

The final default replay was completed on the same 2-vCPU/1.9-GiB host with each container limited to 1 CPU, 768 MiB memory, and 512 PIDs. Each measured run reported 10,000 samples, 640,000 bytes, and `100%` success; elapsed times were 1,551/1,530/1,548 ms, Driver-published throughput was 10,799/17,730/17,544 ops/s, and inclusive p50/p95/p99 buckets were `0~10 ms` in every run. These values are a captured sample for later product review, not an approved threshold.

## Missing measurements and thresholds

The following remain unmeasured or unresolved, so the ticket cannot claim PERF-01 acceptance yet:

- exact Java heap used/committed and garbage-collection activity; this release JRE lacks `jcmd` and `jstat`;
- HTTP pool/keep-alive/idle connection counts; the old client does not expose a stable public metrics endpoint;
- CPU, threads and FD sampled throughout each run rather than only at run boundaries;
- cold-start, first storage operation, graceful stop, residual thread/socket, and archive-size repeat measurements;
- a SineIO and S3 workload on real/compatible endpoints, since no endpoint credentials were provided;
- an approved numeric regression limit from product expectations, based on repeated noise/variation. No percentage threshold is inferred from the short diagnostic data.

`performance.csv` keeps raw run values; it is not a pass/fail file until the product owner records tolerance and comparison method in this document before any migrated build is measured.
