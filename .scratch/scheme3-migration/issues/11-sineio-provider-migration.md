# 11: 交付 sio、siov1、gdas 生产 Provider

**What to build:** 让三个 SineIO 模式在正式 Driver 运行边界中可被发现、执行并关闭，保持现有 SDK 和配置行为。

**Blocked by:** 10: 交付 Driver Boot 与完整本地 Mock mission 链路

**Status:** needs-info

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 PROV-SIO 和其对应章节。

**Inputs:** #04 的逐 factory 矩阵/共存决定、#10 的 Driver/registry、#01 的基线。

- [x] 迁移 SineIO 模块的目录/资源/普通 Maven 依赖，生产 ServiceLoader 发现 sio、siov1、gdas 三个 factory；不升级 SDK 大版本或增加其他 Provider。
- [x] 三种模式分别通过合法/缺字段/非法类型/未知 key 的配置用例，保留原有效值和错误语义。
- [ ] 逐模式在 MinIO 执行声明支持的 put/get/list/delete、multipart 及 #04 记录的额外操作，使用结果/数据完整性验证；不支持项有基线证据，不可用项不算通过。
- [x] 逐模式覆盖 endpoint、认证、超时、4xx/5xx 和支持的底层错误映射；mission close、abort、Driver TERM 后客户端、线程及 native 资源按能力释放。
- [x] 使用 #04 冻结的共享/必要静态隔离方案；正式 Driver 依赖树、重复类和 loader 关闭检查通过，与 s3 的组合实测由 #19 复验。
- [x] 提交各 factory 的生产依赖、descriptor、配置样例、license/checksum 和 CI smoke 结果；生产输入排除源码 JAR、Mock 和 legacy。

## 实现与本地验收

2026-11-02：生产 SineIO 普通 Maven 模块、Driver 依赖及 descriptor 已交付。三个模式的 MinIO put/get/list/head/delete、重复 multipart 与数据校验、SIO range/prefetch、配置/错误映射、mission close/abort 和实际 Boot 进程 TERM 均通过。Java 21 Docker 完整 reactor `clean verify` 共 114 项测试通过，0 失败、错误或跳过；16 个模块的依赖/许可证/制品审计及全部进程重放通过，本轮容器、网络、卷和临时凭据均清理。

实现说明见 [SineIO providers](../../../shadow/sineio-providers.md)，可重放命令、版本、测试/审计/资源释放报告及双轴审查见 [验收证据](../../../shadow/evidence/2026-11-02/sineio-providers/README.md)。

## 待补充信息与未完成验收

第三项仍未完成：#04 明确留给本票的真实归档 restore 与厂商扩展 endpoint 验收缺少可访问端点及归档 fixture。受控 HTTP restore 协议测试、MinIO prefetch/range 读取和重放入口的失败测试均不替代这项验收，因此 PROV-SIO 未整体标为通过。

需要提供三个模式的测试 endpoint、受保护的凭据文件路径，以及各自不可直接读取的归档对象、原始长度和已知 SHA-256。严格重放入口 `shadow/harness/sineio-archive-replay.sh` 已提供；它拒绝缺配置及已可读取的对象，实际 restore 后校验数据及 SIO prefetch/partial range，并为每模式设置覆盖网络和流读取的硬期限。配置模板与命令见实现说明。未定义或不可用的后端不会跳过后算通过。
