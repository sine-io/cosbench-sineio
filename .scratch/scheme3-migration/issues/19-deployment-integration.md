# 19: 完成部署组合与全部 Provider 端到端回归

**What to build:** 在共同 Docker 环境验证真实应用、页面、协议、运维和全部生产 Provider 的组合，产生完整集成报告。

**Blocked by:** 11: 交付 sio、siov1、gdas 生产 Provider; 12: 交付旧 SDK 的 S3 生产 Provider; 16: 迁移 Controller 导出、指标与 Web 边界行为; 17: 迁移 Driver 页面与命令共存; 18: 完成配置转换、正式命令与多 Driver 运维

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 INT-01 和其对应章节。

**Inputs:** #11/#12 的逐 Provider 矩阵、#16/#17 全量 Web、#18 正式命令与 CI 的现有全部验证入口。

- [x] Controller-only、Driver-only、both 和多 Driver 四种部署都有真实健康检查；验证端口、配置/TLS/secret、日志/archive/mission 挂载语义和进程隔离。
- [x] 在 test profile 执行完整 Mock workload；在正式 Provider classpath 分别执行 sio/siov1/gdas/s3 workload，逐 factory 记录操作/错误/关闭结果，生产包仍不包含 Mock。
- [x] 同时装载四个生产 factory 并运行代表性操作，验证 SDK/HTTP/日志共存和必要 loader 关闭；必需 endpoint 不可用或操作跳过不能作为通过证据。
- [x] 合并全量页面/CLI/八条 /i、认证/幂等/重试、页面超时不取消任务、旧配置转换、命令幂等、重启旧数据和 graceful stop 回归，所有稳定验收 ID 可追到具体命令和报告。
- [x] 失败和中断时保存脱敏日志、请求/状态/依赖报告，sudo Docker 清理本轮容器/网络/临时资源，无后台进程和测试残留；再次运行得到同样准备条件。
- [x] CI 对完整活跃闭包执行空缓存构建、依赖/重复类/jdeps/license 闸门及以上集成入口；报告同时给出制品和配置版本。

## Comments

- 2026-11-05: Implemented and replayed on `feat/01-freeze-baseline`. The full command `bash ci/verify-shadow.sh /tmp/cosbench-scheme3-19-complete` passed: 21 clean-built modules, 178 tests (0 failures/errors/skips), 21 module gates, all prior process/Web/HTTP/operator replays, all 20 applicable acceptance IDs, and the four-deployment/eight-workload matrix. Docker cleanup completed with no project containers, networks, volumes or certificate containers remaining. Redacted, replayable evidence is in [#19 integration evidence](../../../shadow/evidence/2026-11-05/deployment-integration/README.md).
- Standards review found and rechecked one cleanup dependency issue; Spec review found and rechecked the archive-page query and BASE-01 index mapping. Both axes found no unresolved issues after follow-up. The complete review is in the evidence directory.
- The existing #11 deferral for real archive-backend restore/prefetch/range and #22 historical production data/rollback acceptance remain with their original owners; this ticket does not claim those external validations.
