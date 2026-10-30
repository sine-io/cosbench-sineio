# 08: 将构建与依赖审计接入持续 CI

**What to build:** 让每个后续迁移批次在加入 reactor 时就受到相同构建、资源、依赖和许可证检查约束。

**Blocked by:** 07: 按已验证合同迁移公共模块

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 CI-01 和其对应章节。

**Inputs:** #02 的构建/harness、#07 的已迁入公共闭包以及四项验证的重放入口。

- [x] CI 在干净 checkout/空 Maven 缓存中使用 Java 21 和统一 release=21 构建已迁入模块及验证范围；固定的工具/BOM 版本可追踪。
- [x] dependency tree/convergence、duplicate classes、jdeps、license/checksum 和生产/测试依赖边界检查作为失败即阻断的任务，不仅生成可忽略报告。
- [x] CI 扫描当前新产物对 OSGi/Spring DM/旧 Tomcat/P2/legacy/Mock 的违规引用；测试产物的 Mock 例外范围明确，生产过滤不能靠漏跑模块实现。
- [x] 验证一项违规依赖或资源缺失会使检查失败，正常 fixture 可通过；静态隔离若存在，按 #04 的实际 loader 边界检查，不放宽全局排除。
- [x] 后续票加入模块和行为时必须同步扩展同一流水线；本票不等待所有生产应用完成，也不声称完整发行验收已经通过。
- [x] 保留构建/测试/审计报告并清理 Docker 资源；说明活跃验证入口与旧基线重建入口，避免两种构建互相污染。

## Comments

- 2026-10-30: 实现完成并提交维护者审阅。新增 `Java 21 migration gates` workflow，push/PR/manual dispatch 执行统一入口 `bash ci/verify-shadow.sh EMPTY_REPORT_DIR`，从空缓存运行完整 reactor 和四项前置重放，失败时也保存报告。11 个模块由 effective reactor 自动枚举，未按固定生产清单漏扫 spike。
- 2026-10-30: Enforcer 检查 Java/Maven/release/convergence；审计阻断未知坐标/许可证、SHA-256 变化、生产/测试依赖混入、普通及多版本重复类、jdeps 缺失依赖、资源/字节码/嵌套包污染。Mock 只允许在 Provider/HTTP spike 的 test output；#04 的共享 classpath 无 child loader，也未添加全局类排除。依赖准入表覆盖 135 项生产/测试依赖；Castor 缺失的 POM 许可信息从实际 JAR 的许可文本审查记录。
- 2026-10-30: [最终 Docker 证据](../../../shadow/evidence/2026-10-30/continuous-build-gates/README.md)通过：干净 checkout、空 tmpfs Maven 仓库，57 项全量测试、27 项正常/违规 fixture、独立 HTTP 32 项测试，配置/Provider/Web/HTTP 重放及 11 模块审计均通过；规范和规格复审各 0 项未解决发现。`run_status=0`、`cleanup_status=0`，无本轮容器、网络、卷或证书容器残留。
- 后续票必须按 `shadow/ci-policy.md` 接入同一流水线、补充行为重放并审查新增依赖锁；旧基线重建继续使用固定旧 tag 的独立 Tycho 入口。CI-01 不更改此前真实历史数据或人工验收状态，也不提前宣称生产应用、镜像或完整发行验收通过。
