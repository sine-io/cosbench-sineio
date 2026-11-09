# 22: 验收实际生产制品、旧数据与回退

**What to build:** 证明用户仅凭生产归档/镜像就能安装、运行、读取旧结果并回退，同时得到与实际命令一致的说明。

**Blocked by:** 20: 执行三次同参数性能与资源画像; 21: 生成独立生产归档与 Java 21 镜像

**Status:** completed

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 REL-01 和其对应章节。

**Inputs:** #19 完整集成、#20 迁移版性能/资源画像、#21 固定生产归档/镜像、#01 的旧回退包及原配置/数据副本。

- [x] 核对集成、性能与生产制品源码/依赖版本一致；若打包输入变更影响先前证据，先重跑受影响验证。
- [x] 在空目录/干净容器仅复制生产文件完成 Controller-only、Driver-only、both、多 Driver 健康/启停；启动不依赖源码、测试 classpath、P2 或 Eclipse。
- [x] 从实际生产镜像/归档分别发现 sio/siov1/gdas/s3 并执行 MinIO workload，共存及数据结果通过；页面/CLI/八条 /i 合同可用且生产不含 Mock。
- [x] 挂载旧配置/数据副本，完成转换、重启后历史/archive/mission 读取、graceful stop；原文件保留且无隐式重排。
- [x] 停止新进程，用上一版完整归档及保留的原配置启动并读取旧数据；验证回退不会删除用户数据，记录版本、checksum、命令、退出码和资源清理。
- [x] README、构建说明、开发脚本使用说明、Provider/SPI 指南及 release notes 指向同一套 Java 21/YAML/新命令；明确支持范围、静态发现需重启、legacy 仅旧包运行及暂缓依赖升级。
- [x] 输出版本化发布验收记录与回退 runbook，已有回退制品保留到迁移窗口结束；本票不以开发镜像 smoke 代替生产制品验证。

## Comments

2026-11-09：REL-01 完成，见[版本化验收记录](../../../shadow/evidence/2026-11-09/release-validation/README.md)
和[回退 runbook](../../../docs/release-rollback.md)。归档/实际生产镜像分别通过四种部署、
四类 Provider 的 MinIO 数据操作、页面/CLI/八条 `/i`、重启及停机；完整命令退出 0，
本任务资源清理通过。三份应用 JAR 与 #19/#20 的固定输入相同，打包改动仅增加交付文档。

旧数据由固定的上一版完整 Java 8 包生成，用副本完成转换与新版本重启，再以原 INI
回退旧包；原 config/archive/workspace/mission 校验一致。未提供客户历史数据，
不将该 fixture 解释为客户数据验收。旧包和全部报告/数据副本保留至迁移窗口结束。

全量 reactor 35 suites / 179 tests 全通过，21 个模块依赖门禁通过。既有 CI wrapper
最终退出 1：按用户要求保护了 MinIO 数据卷，与其清理阶段要求删除卷的策略冲突。
原始退出码和安全清理记录均保留；无遗留任务容器/网络，卷及含 archive/mission 的
受保护运行目录保留。Standards 与 Spec 两轴复审各 0 项未解决；#23 范围未改动。
