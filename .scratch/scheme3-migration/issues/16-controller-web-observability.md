# 16: 迁移 Controller 导出、指标与 Web 边界行为

**What to build:** 让用户读取原日志/配置、CSV/时间线和 Prometheus，并在页面连接或 Session 结束后继续保有后台 workload。

**Blocked by:** 15: 迁移 Controller 页面操作与既有 CLI

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 WEB-C2 和其对应章节。

**Inputs:** #15 的可运行 Controller Web、#01 的下载/CSV/Prometheus/错误页面 fixture 与 #14 的长任务测试。

- [x] stage、timeline、timeline.csv、日志/配置下载及 Prometheus 路径保持；比对内容类型、文件名/长度/权限、标签/指标名称/数值语义和 FreeMarker model。
- [x] 验证 CSS/JS/PNG、welcome/error 页面和 reverse proxy 下 context path，不因资源布局迁移改变原 URL/表单；登录及下载权限遵循基线。
- [x] 运行一个已接受的真实 workload，强制页面请求超时、断开并结束页面 Session；重新登录查询到同一任务继续推进并正常完成，状态/归档不因页面生命周期取消。
- [x] 错误 XML、超限上传、无权限下载及不存在资源的对外结果与基线一致；临时文件和连接被清理。
- [x] Controller 全部 Web/CLI 场景索引闭合，未由 #15 覆盖的路径均有归属和证据；将导出/指标/后台任务边界回归接入 CI。

**Validation evidence:** [Controller WEB-C2 acceptance](../../../shadow/evidence/2026-11-04/controller-web-observability/README.md)
