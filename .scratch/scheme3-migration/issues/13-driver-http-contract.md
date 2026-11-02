# 13: 交付八条 Driver HTTP 命令与服务认证幂等

**What to build:** 让新 Driver 接受全部既有 /i 命令，并在服务认证和重复写请求下保持可诊断、无重复 mission 动作的行为。

**Blocked by:** 10: 交付 Driver Boot 与完整本地 Mock mission 链路

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 HTTP-D 和其对应章节。

**Inputs:** #06 冻结的认证/请求身份合同、#10 的真实 Driver/Mock 链路和 #01 的全部 /i fixture；本票基于 Mock 开发，不等待 #11/#12。

- [x] 迁移 Driver command backend 与所需公共 Web adapter，保留 ping/submit/login/launch/query/trigger/close/abort 的路径、字段、状态码、业务响应及状态转换。
- [x] 所有 handler 经 DriverService 执行；HTTP 测试完整覆盖 Mock mission 的正常、坏输入、非法状态、Provider login 失败、超时及清理路径。
- [x] 按 #06 在 Driver 验证服务 Token/TLS；拒绝缺失/非法凭据及仅页面 cookie，日志/响应无 secrets。Provider /i/login 与服务认证分别测试。
- [x] 按 #06 保留进程内已处理结果；串行/并发重复、响应丢失重试、任务结束后重放返回冻结结果而不重复执行；同键不同内容及失败重放遵守同一合同。
- [x] 覆盖身份容量/边界和进程重启后结果不明的场景；诊断失败且不盲目重执行，不新增未经规格承诺的跨重启持久化。
- [x] 将全量 HTTP fixture 与故障测试接入 CI；Boot 进程停止时拒绝新命令并释放 HTTP 与 Provider/worker 资源，留可重放报告。

## 完成证据

Java 21 Docker `clean verify`、真实 HTTPS/Mock Driver 命令合同、响应丢失故障重试、TLS/实例重启边界、18 个模块依赖与产物门禁、Driver/Controller 进程清理及容器资源清理均通过；报告与重放步骤见 [验收记录](../../../shadow/evidence/2026-11-02/driver-http/README.md)。
