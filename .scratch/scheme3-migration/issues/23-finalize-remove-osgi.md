# 23: 切换根构建并完成 OSGi 收缩复验

**What to build:** 将已验证的普通 Maven 构建设为正式入口，隔离 legacy 并移除活跃 OSGi/Tycho/P2 路径，形成证据完整的最终候选制品。

**Blocked by:** 22: 验收实际生产制品、旧数据与回退

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 FINAL-01 和其对应章节。

**Inputs:** #22 发布/回退通过记录，以及验收责任表所有首责票的实际证据；缺失项回到对应首责票处理。

- [ ] 确认所有必需场景及四项前置验证、CI、完整集成和迁移版性能/资源画像有证据；不可用/跳过的必需场景不计通过。BASE-01 的真实配置边界、供应商 endpoint/凭据和客户/历史数据仍未补齐，等待 #01 责任人；收缩时固定旧归档/配置/checkout 已保留。
- [x] 将影子 reactor 提升为根普通 Maven 入口，移除根 Tycho extension/P2；活跃模块使用标准布局与 release=21，根构建不引用 legacy。
- [x] 将非目标 Provider、Manta、ext 和旧 Tomcat 源码及原 OSGi 元数据归档到 legacy；新发行仅支持目标 Provider，旧包继续承担 legacy 运行支持。
- [x] 移除活跃 manifest/PDE/OSGi context 注册、Equinox/旧 Tomcat/OSGi runtime 和旧发布路径；更新脚本/Docker/CI/runbook 的正式入口，并保留配置迁移与回退说明。
- [x] 使用根 `mvn -B -ntp clean verify` 完成 Java 21 构建和依赖树/重复类/jdeps/license/源码/资源/脚本/镜像/归档污染扫描；构建使用空 Maven 缓存。共享 Git worktree 含其它未提交工作，因此不记录为干净 Git checkout。
- [x] 重建最终生产制品，并重跑进程启停、页面/HTTP、全部 Provider MinIO、部署/重启/旧数据/回退；三个应用哈希与 #19/#20 固定输入一致，另完成三轮迁移版性能画像。
- [x] 在最终清单将每个验收 ID 关联到首责票、命令、fixture、版本和报告；更新发行说明。未创建 Java 21 release tag，未宣称方案三整体完成。

## Validation evidence

完整命令、固定版本、全部 25 个验收 ID 的首责映射和报告见[最终证据清单](../../../shadow/evidence/2026-11-10/finalization/README.md)。

2026-11-10：根构建、22 个 reactor JAR 审计、29 个 CI fixture、进程/HTTP/Web/Operator 回放及部署集成通过；归档重建/镜像扫描和最终生产 archive/image 的四 Provider、重启、旧数据及 rollback 复验通过。最终候选 archive SHA-256 为 `25d9186b86d00ca5ae472a790d2bb311545e8ff4b0f10786f0047b431e9bfb52`。唯一未满足的最终前置是 #01 中的 BASE-01 人工基线输入；本票因此等待人工验收，不关闭方案三，也不发 release tag。
