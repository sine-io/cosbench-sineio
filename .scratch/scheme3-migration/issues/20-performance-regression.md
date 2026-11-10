# 20: 执行三次同参数性能与资源画像

**What to build:** 对固定迁移版候选制品重复运行性能 workload，交付功能计数、性能/资源和运行间波动的可重放画像。不作旧版性能回归判定。

**Blocked by:** 19: 完成部署组合与全部 Provider 端到端回归

**Status:** completed

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 PERF-02 和其对应章节。

**Inputs:** #19 已通过集成的固定候选制品；运行参数、采样方法和资源限制在首轮候选测量前固定。旧版性能数据仅作历史记录，不作为比较基线或阈值。

- [x] 同一迁移版制品、硬件/容器限制、workload、数据规模和预热条件至少执行三次；记录版本、采样命令和逐轮原始数据。
- [x] 记录完成数/成功失败数/各 operation 计数、吞吐、p50/p95/p99、错误/重试、CPU/堆/线程/FD/连接池，以及启动/首次操作/停止耗时、残留线程和可用制品规模。不可适用或尚无制品的指标要注明原因。
- [x] 对三轮功能计数及运行间波动作结论；不使用旧版阈值作通过/失败判定，也不作相对旧版性能回归结论。
- [x] 迁移版运行的功能计数一致、错误为零，吞吐变异系数为 3.41%；没有出现需定位或复测的计数差异/异常波动。
- [x] 输出可重放报告和原始数据位置，成功/失败均清理本轮独占测试资源，为 #22/#23 提供候选版本画像。

## Comments

- 2026-11-05: PERF-02 preflight is recorded in [the performance regression evidence](../../../shadow/evidence/2026-11-05/performance-regression/preflight.md). The explicit not-passed branch is recorded; this does not claim a performance pass or completed comparison. No migrated-version measurement was started: PERF-01 still lacks an approved numeric tolerance and required baseline measurements, and its procedure requires freezing the tolerance before measuring a migrated build. The ticket is waiting for the product-approved tolerance and completion of the missing PERF-01 evidence.
- 2026-11-06: The user clarified that legacy performance comparison and legacy-derived thresholds are not required or authoritative. The acceptance contract now records a three-run migrated-version performance/resource profile only; no cross-version regression verdict is claimed. The prior preflight hold based on the missing legacy threshold is superseded.
- 2026-11-06: Replayed with `sudo docker run --rm` using pinned Maven/Temurin 21 image `maven@sha256:d2230484f42a845db4a2ae5c4b4b2ba474636bc9c17233917f95ddf2110ab06d`, 2 CPUs, 2 GiB memory, 512 PIDs. One warmup and three measured 25,000-operation/64-byte Mock reads each recorded 25,000 successful samples, 1,600,000 bytes and 100% success. With an out-of-window full-GC request before each measured run, published throughput was 47,438.33/51,546.39/49,212.60 ops/s (mean 49,399.11; population CV 3.41%; range/mean 8.32%); p50/p95/p99 were each `0~10 ms`. Driver startup/first operation/stop were 5,040/4/392 ms on the first measured run; the process exited 143, released its port and left no process. The Driver JAR matches #19 SHA-256 `adfa693b4f57030272cd8bdc60ac315679f7c80fbe1aa2481f36d5fbb9573bc2` (44,893,074 bytes). Detailed CSV, samples, raw responses, commands and logs are in [the replay report](../../../shadow/evidence/2026-11-06/performance-profile/README.md). Controller, production image, and provider HTTP-pool metrics are explicitly outside/not applicable to this Driver-only test-classpath Mock profile; the production image remains #21's input. No legacy comparison or pass/fail threshold was used.
- 2026-11-06: All #20 acceptance items are complete. Legacy-version comparison is not part of this ticket; no further #20 implementation work remains.
