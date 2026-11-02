# 10: 交付 Driver Boot 与完整本地 Mock mission 链路

**What to build:** 让 Driver 进程通过启动时 registry 装配测试 Mock storage/auth，完成真实 mission 生命周期并释放资源。

**Blocked by:** 08: 将构建与依赖审计接入持续 CI

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 APP-D 和其对应章节。

**Inputs:** #03 配置、#04 SPI、#06 Driver 最小适配与 #07/#08 公共实现/CI；本票整合 Driver/app/registry 和测试 Mock，HTTP/页面后续扩展。

- [x] 迁移 Driver 与薄 app，静态 registry 完成发现、名称唯一性、排序、查找和诊断；DriverService 继续消费 factory 接口/list，业务代码不直接调用 ServiceLoader。
- [x] 测试 classpath 包含 Mock storage/auth 并完成本地 submit/login/launch/query/trigger/close 及 abort 路径；断言状态、worker、结果和日志可观察行为。
- [x] 按期望清单检测缺 descriptor/依赖、同名 factory 和初始化失败；定位具体名称/类。生产 classpath 和 app 归档不包含 Mock 类或 descriptor。
- [x] 使用共同 YAML 和健康检查，配置边界按基线；Provider API 的 init/operation/dispose 次序和关闭后行为按原接口验证。
- [x] TERM 先拒绝新任务，再在 grace period 内处理在途 mission/HTTP/worker/Provider client/日志；测试 mission 运行中停止、资源退出及端口释放。
- [x] 将 Driver/app/测试 Mock 与 registry 验证接入 CI，保留实际进程和本地服务链路报告，供生产 Provider 与 HTTP 票直接消费。

## 完成证据

Java 21 Docker `clean verify`、15 个模块的持续构建/依赖审计、真实 Driver 进程重启/TERM/端口释放及容器清理均通过；详细命令、版本、日志和源码校验和见 [验收记录](../../../shadow/evidence/2026-11-02/driver-boot/README.md)。
