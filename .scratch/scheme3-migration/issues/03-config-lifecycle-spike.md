# 03: 验证统一 YAML、配置转换与最小启停链路

**What to build:** 用有限的真实配置、service 和归档依赖，证明旧安装可转换配置、启动/停止新运行方式并读取旧 archive，冻结两个应用共用的配置合同。

**Blocked by:** 02: 建立最小 Java 21 影子构建与 Docker/MinIO 环境

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 GATE-CFG 和其对应章节。

**Inputs:** #01 的配置边界/旧数据 fixture 与 #02 的工具链/harness；只迁入这条代表性链路所需的依赖，完整应用与命令矩阵留给后续票。

- [x] 固定 YAML schema version、字段类型、默认值、单位、范围、优先级和环境/secret 引用；覆盖监听/Driver 地址、目录、Token/TLS、连接池/空闲回收、四层超时、重试、并发、日志、Provider、Web 和 shutdown grace period。
- [x] 通过真实配置读取/校验边界验证合法值与越界值，按 #01 分别重现五类缺失/非法配置结果；明确未知 key、未知 schema 和不可映射旧值的诊断。
- [x] 代表性 Controller/Driver 旧配置转换后保留有效值及相对路径语义，输出转换报告，原文件内容/checksum 不变；完整字段组合在 #18 扩展。
- [x] 用最小 Boot 进程装配实际 service 配置和归档读取能力，通过 cosbench-controller、cosbench-driver、cosbench-stop、cosbench-config-migrate 的最小调用约定完成转换/健康/TERM/停止验证。
- [x] TERM 后停止接收新任务，在 grace period 内释放本链路端口、executor、日志及客户端；超出期限有确定退出码和可诊断结果，不由业务线程直接 System.exit。
- [ ] 使用 #01 的旧 archive/workspace/mission fixture 验证读取或明确的非破坏迁移诊断，进程重启后仍可访问，原数据保持不变。
- [x] 提交 schema/字段映射、命令约定、代表性 fixture、依赖边界、重放命令和通过报告，供 app/HTTP/运维票复用；普通的空 Boot health 不能代替真实配置/旧数据验证。

## Comments

- 2026-10-28: Added the shared schema-1 contract in `shadow/config-contract.md`, immutable validated configuration records and INI migration/reporting in the shadow `cosbench-config` module. Unknown keys/schema versions, five missing/invalid boundary classes, secret references, duration/range checks, relative paths, and provider/application concurrency are covered by `VersionedConfigTest` and `LegacyConfigMigratorTest`.
- 2026-10-28: Added the Java 21 Spring Boot runtime shadow module, archive reader, health/task lifecycle seam, PID/TERM commands, and `shadow/harness/config-lifecycle.sh`. The full Docker replay passed migration, health, archive read before/after restart, task admission, TERM port release, PID cleanup, unchanged source/archive checksums, and MinIO/HTTP probes. Reproducible evidence is in `shadow/evidence/2026-10-28/config-lifecycle/README.md`; the run ended with `run_status=0`, `cleanup_status=0`, and no remaining project resources.
- 2026-10-28: Human acceptance remains required for a real historical archive/workspace/mission fixture from #01 (the baseline records that fixture as unavailable), production service credentials/TLS and the authenticated HTTP contract owned by #06, and the full application field matrix owned by #18. The representative archive fixture is deliberately not claimed as the missing external evidence.
