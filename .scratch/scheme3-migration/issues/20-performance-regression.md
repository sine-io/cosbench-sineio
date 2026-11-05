# 20: 执行三次同参数性能与资源对比

**What to build:** 用冻结的同参数基线证明迁移未造成超出约定阈值的性能、资源或业务结果回归。

**Blocked by:** 19: 完成部署组合与全部 Provider 端到端回归

**Status:** needs-info

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 PERF-02 和其对应章节。

**Inputs:** #01 的 PERF-01 数据/采样方法/阈值、#19 已通过集成的固定候选制品；比较参数必须在迁移版测量前固定。

- [ ] 同硬件、容器限制、workload、数据规模和预热条件至少执行三次迁移版运行，并关联可比较的旧版基线；记录版本、采样命令和原始数据。
- [ ] 比较完成数/成功失败数/各 operation 计数，吞吐、p50/p95/p99、错误/重试、CPU/堆/线程/FD/连接池及启停/首次操作耗时、残留线程和制品规模。
- [x] 逐指标按 #01 已冻结阈值给出结论及重复运行波动；阈值未定义或关键基线缺失时明确未通过，不事后调整阈值掩盖结果。
- [ ] 超阈值回归定位到 Web、依赖、loader 或业务等原因，修复后按相同条件复测并关联结果；需改变业务合同的差异先更新方案和验收记录。
- [ ] 输出可重放报告和原始数据位置，成功/失败均清理测试资源，保留生产发布验证需要的性能结论。

## Comments

- 2026-11-05: PERF-02 preflight is recorded in [the performance regression evidence](../../../shadow/evidence/2026-11-05/performance-regression/preflight.md). The explicit not-passed branch is recorded; this does not claim a performance pass or completed comparison. No migrated-version measurement was started: PERF-01 still lacks an approved numeric tolerance and required baseline measurements, and its procedure requires freezing the tolerance before measuring a migrated build. The ticket is waiting for the product-approved tolerance and completion of the missing PERF-01 evidence.
