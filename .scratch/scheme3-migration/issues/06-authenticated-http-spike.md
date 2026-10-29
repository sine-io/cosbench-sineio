# 06: 验证带服务认证、重试和幂等的 Driver HTTP 链路

**What to build:** 使代表性 Driver 写命令在 Token/TLS、网络故障和重试下只执行一次，并冻结 Controller 与 Driver 共用的新认证和请求身份合同。

**Blocked by:** 03: 验证统一 YAML、配置转换与最小启停链路; 04: 验证全部 factory 的 SPI 生命周期与 SDK 共存

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 GATE-HTTP 和其对应章节。

**Inputs:** #01 的 /i fixture、#02 的故障注入/测试证书、#03 的配置合同、#04 的 Mock SPI；使用真实 DriverService 与 Mock，不等待全量 Driver HTTP backend。

- [x] 选择现有 /i 写命令及查询构成最小真实 mission 链路，Boot/Jakarta handler 调用真实 COSBDriverService + Mock auth/storage；覆盖 submit/replay/query/login/launch/close 及 accomplished 后查询。
- [x] Driver HTTP adapter 校验 Bearer 服务 Token；缺失/非法 Token 和仅带页面 cookie 的请求返回 401。/i/login 的 Provider 认证业务保持独立；服务认证失败有独立 fixture。
- [x] 固定请求身份头、principal+command key 作用域、原始载荷指纹、缺身份 400、同键不同内容 409、并发重复单次执行及失败结果重放；HTTP adapter 不改写业务载荷。
- [x] Driver 在进程生命周期保留结果；真实 HTTP 端点的首个响应被 Toxiproxy 丢弃，AuthenticatedDriverClient 使用相同 key/body 重试，同一 Mock mission 只提交一次。mission 完成后的晚到重放返回原响应；跨重启的旧实例写重试返回 409 并诊断为结果未知。
- [x] 共同 YAML 配置映射到 pool/timeout/retry policy；验证 HTTP/1.1 endpoint client 复用模型、全局与每 endpoint 并发边界、idle eviction 不关闭活跃请求，以及 connect/read/command/workload timeout 配置边界。
- [x] 仅配置的 502/503/504 与明确网络异常有限重试；401/其他 4xx 和未配置 5xx 不重试，身份和载荷保持稳定。
- [x] 页面 Session 与服务凭据分离，真实 Mock mission 经服务认证 login/launch/close 并在无页面 cookie 的后续 query 中仍可查询。MVP 使用无自然过期的长生命周期 Token，通过 secret/config rotation 轮换；压测期间不刷新或过期。
- [x] 提交可重放 HTTP 约定、认证/身份/conflict fixture、幂等容量取舍、依赖树及 Java 21 Docker 报告；最终报告在 `shadow/evidence/2026-10-29/http/`。

**Implementation note:** 真实 HTTPS 验收使用 `LegacyDriverCommandPort` 适配 `COSBDriverService`，并由 Mock auth/storage 执行 mission 生命周期。Toxiproxy 截断真实 submit TLS 响应后，`AuthenticatedDriverClient` 用相同 key/body 重试并重放单次 mission。Driver 返回进程实例 ID；客户端对每个写请求带上已确认的实例 ID，进程重启后的旧实例重试返回 409 并报告结果未知，不会在新进程重复执行。命令和 workload timeout 共用绝对截止时间，所有重试与退避都受该截止时间限制。幂等结果仍只保存在 Driver 进程内存，不承诺跨重启恢复原响应。MVP Token 无自然过期，通过配置 secret 轮换。测试证据位于 `shadow/evidence/2026-10-29/http/`。
