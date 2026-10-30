# 09: 交付 Controller Boot 进程和生命周期

**What to build:** 让 Controller 作为 Java 21 Boot 进程读取共同 YAML、提供健康检查和真实服务，并在启动失败及 TERM 时有明确结果。

**Blocked by:** 08: 将构建与依赖审计接入持续 CI

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 APP-C 和其对应章节。

**Inputs:** #03 的配置/退出码合同、#05 的最小组合方式、#07 的公共模块、#08 的 CI 与 #01 的 Controller 行为基线。

- [x] 迁移 Controller 业务模块及薄 app 的标准目录/依赖，产出可执行应用；真实 ControllerService 及兼容 Bean 在同进程可注入，业务逻辑保留在原模块。
- [x] 使用 #03 的统一 YAML，缺失/空/非法配置分别按基线处理；诊断错误并由入口设置退出码，业务线程不直接终止进程。
- [x] 健康检查反映已装配服务，启动端口冲突等失败可诊断；从实际 service 提交/排队/查询代表性 workload 验证服务可用，跨进程执行链路在 #14 完成。
- [x] TERM 停止接收新任务，在配置 grace period 内结束在途本地工作、HTTP listener、executor 和日志；验证空闲与有工作两种关闭情况、端口释放和无残留进程。
- [x] 旧 archive 读取和目录/日志语义与 #01/#03 fixture 相同；Docker 进程测试覆盖启动/健康/重复停止相关生命周期结果。
- [x] 把 Controller/app 加入 #08 的 CI，产物无 Equinox/Spring DM/旧 Tomcat 运行依赖，留下启动/失败/关闭命令与报告。

## Comments

- 2026-10-30: #09 实现完成，提交维护者审阅。新增标准目录的 `cosbench-controller`（105 个 Java 类）和薄 `cosbench-controller-app`；真实 `ControllerService` 以 `controllerService`/兼容 `controller` Bean 同进程注入。Web spike 改为依赖普通业务 JAR，Controller→Driver tasklet/protocol 未改写。
- 2026-10-30: Boot 使用 #03 共同 YAML，启动前统一校验，默认/显式缺失、空文档、非法值和角色错误分别诊断，由入口设置失败退出码；业务初始化抛出异常。健康反映真实 service，支持配置端口/目录/日志及无 Web 模式；`logging.file` 显式优先，否则采用 `paths.logs`。
- 2026-10-30: TERM 先拒绝新任务，service/HTTP 同阶段共享 grace period；正常工作收尾，超时工作预留取消与冷归档时间，重复关闭幂等。进程测试验证归档、executor/heartbeat、端口和退出码；修复乱序结束（含排队任务先取消）导致 `.meta` 回退/重启 ID 重复的问题。
- 2026-10-30: [最终 Docker 验收](../../../shadow/evidence/2026-10-30/controller-boot/README.md)通过：独立干净 checkout、空缓存 `clean verify` 79 项（其中 Controller 22 项），13 模块全量审计、27 审计 fixture、Controller 可执行 JAR，以及既有配置/Provider/Web/认证 HTTP 重放（HTTP 独立 32 项）均通过。规范/规格双轴最终各 0 项未解决发现；`run_status=0`、`cleanup_status=0`，无本轮 Docker 资源残留。
- 历史归档验证保留 #01/#03 的原限制：当前是代表性 fixture 与真实 service 生成归档的重启读取，真实生产历史 fixture 尚未提供，不记为已通过。跨进程执行、完整 Web、正式运维入口继续由 #14–#18 验收；本票未提前声明完整发行验收通过。
