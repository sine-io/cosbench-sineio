# 01: 冻结旧版行为、性能基线与回退制品

**What to build:** 提供可重放的旧版行为和性能基线，让后续每个迁移批次有明确的比较对象及可启动的回退包。

**Blocked by:** None (can start immediately).

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 BASE-01、PERF-01 和其对应章节。

**Inputs:** 方案三验收矩阵第 1–8 节、现有发行脚本和配置；读取实际源码/路由确认入口，旧运行时版本以实测记录为准。

- [x] 固定代码/发行版本、容器镜像、JDK、OS、硬件与容器限制、参数和 Provider 组合；旧版在兼容的基线容器中运行，不强行改用新运行时。
- [x] 为验收矩阵的公开路由、页面表单、五类 CLI、八条 /i 命令、CSV/下载/Prometheus、状态转换和目录/退出码建立场景索引与版本化输入/输出；列明动态字段的比较规则。完整表单变体和失败矩阵仍待人工补证。
- [ ] 区分默认文件缺失、显式配置路径缺失、空配置、缺必需字段、非法值；记录真实默认值、错误和退出结果。重排/重提按页面表单采集。
- [ ] 至少提供一个容器中可重放的 Mock/noop 提交、执行、查询、关闭/取消和归档命令；为 sio/siov1/gdas/s3 分别建立操作/配置/错误基线，endpoint 不可用要列出原因及后续补证责任，不计通过。Mock 回放已完成；四类真实 Provider 仍因缺少可用端点和凭据而待补，不计通过。
- [ ] 采集 PERF-01 全部指标：操作计数、吞吐、p50/p95/p99、错误/重试、CPU/堆/线程/FD/连接池、启动/首次操作/停止时间、残留线程和制品大小；固定采样方法、数据量、预热与重复运行参数。
- [ ] 依据同参数重复测量的波动和产品期望记录数值阈值/判定方法及来源，在迁移版测量前冻结；缺少必要期望时明确记录待补项，不能用未定义阈值宣称性能通过。
- [ ] 保存带 checksum 的可运行旧归档、原配置及测试数据副本，演示独立目录启动/停止及历史数据读取；保留对应 checkout/tag 的重建命令。官方 release archive、SHA-256、固定配置和容器重建入口已保存；真实历史归档仍待提供。
- [x] 输出场景索引、重放入口、基线报告及回退记录；Docker 命令带 sudo，成功/失败均清理本轮资源并保留脱敏诊断。

## Comments

- 2026-10-28: Added `baseline/replay.sh`, pinned release/base-image metadata, fixtures, scenario index, observations, performance procedure, and rollback/rebuild notes. The harness was verified with `BASELINE_RUNS=1 BASELINE_OPS=100`: `w1/w2/w3/w4` finished, `w5` cancelled, selected static/form and Prometheus contracts passed, completed workload artifacts returned 200, the versioned Driver JSON contracts passed, and `cleanup_status=0` after container/image removal.
- 2026-10-28: The default `BASELINE_RUNS=3 BASELINE_OPS=10000` replay reported 10,000 samples, 640,000 bytes, and 100% success in all three runs; inclusive p50/p95/p99 buckets were `0~10 ms`, elapsed times were 1,551/1,530/1,548 ms and published throughput was 10,799/17,730/17,544 ops/s under explicit 1 CPU/768 MiB/512 PID container limits. These values remain a captured sample until product thresholds are approved.
- 2026-10-28: The direct Driver abort flow is captured; the subsequent close returns legacy `409 no mission handler`. The pinned release wrapper's stop scripts returned `0`; cleanup verification reported `run_status=0 / cleanup_status=0` with no remaining containers or image. Session cookies are redacted in retained headers and the temporary cookie jar is removed.
- 2026-10-28: Human acceptance is still required for usable SineIO/S3/gdas endpoints and credentials, isolated configuration-boundary exits, complete page/form and failure matrices, missing PERF-01 measurements, and an approved numeric performance threshold. These are not claimed as passing.
