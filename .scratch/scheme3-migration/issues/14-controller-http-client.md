# 14: 交付长时间 workload 的可靠跨进程调用

**What to build:** 让 Controller 经新 Driver HTTP 完成真实 workload，在页面 Session 结束、连接空闲或短暂网络故障后仍能继续运行。

**Blocked by:** 09: 交付 Controller Boot 进程和生命周期; 13: 交付八条 Driver HTTP 命令与服务认证幂等

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 HTTP-C 和其对应章节。

**Inputs:** #06 的共同协议、#09 Controller、#13 Driver HTTP，以及共同 YAML/故障 harness。

- [x] 使用既有 tasklet/model/protocol 完成两真实进程间 submit/login/launch/query/trigger/close/abort 链路，workload/mission 状态、日志和归档与基线一致。
- [x] 通过独立 Token 和 HTTPS 信任配置调用 Driver，凭据来自 protected runtime sources；缺失/非法证书或 token 可诊断，不泄漏到 workload、镜像或日志。
- [x] 跨页面 Session 失效维持任务；长生命周期 Token 在整个任务中不自然到期，短期模式若启用则用可控时间验证 refresh 不中断。
- [x] 配置并验证连接池/keep-alive/空闲回收、连接/读取/命令/workload 超时和 Provider/应用并发及重试边界；空闲后继续执行和长时间多请求有复用证据。
- [x] 只对允许的临时故障/5xx 有限重试；每次逻辑写操作在重试中保留相同身份，响应丢失、并发/延迟重试都不重复任务，Driver 重启后的不确定结果按 #06 处理。
- [x] TERM/stop 关闭在途请求和 HTTP pool；任务不会因单个查询/页面请求超时而被取消。两进程 Mock 端到端及故障测试接入 CI。

**Validation evidence:** [Controller HTTP client acceptance](../../../shadow/evidence/2026-11-03/controller-http-client/README.md)
