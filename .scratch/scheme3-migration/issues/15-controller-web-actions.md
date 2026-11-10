# 15: 迁移 Controller 页面操作与既有 CLI

**What to build:** 让用户从原页面和五类 CLI 提交、管理并查看 workload，沿用原 URL、表单和模板。

**Blocked by:** 14: 交付长时间 workload 的可靠跨进程调用

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 WEB-C1 和其对应章节。

**Inputs:** #05 Web 装配证据、#14 完整跨进程链路、#01 页面/CLI fixture；只承担操作页面，导出/时间线/指标在 #16。

- [x] 迁移 Controller Web 的操作页面及资源到普通 Maven 布局，复用 Spring/FreeMarker 可兼容配置，使用同进程 ControllerService。
- [x] 登录、index、matrix、submit、config、advanced-config、workload 页面与提交/配置/取消表单的路由、view model 和输出保持；正确执行排队、触发、查询与归档。
- [x] 重排和重提只通过原页面表单验收：queued 可调整、运行中拒绝，history 重提创建新 id 并沿用原 XML；不新增 CLI 重排/重提入口。
- [x] CLI 保留 index/submit/config/workload/cancel 五类入口；验证 CliLoginFilter、multipart 大小/格式/临时文件、合法/非法 XML、错误状态且无半成品 archive。
- [x] 页面与 CLI 的提交/查询/取消通过真实 Controller/Driver Mock 链路验证，同输入比较 HTML/文本、表单字段及状态；不依赖 handler 类名。
- [x] 保持页面 Session 与服务认证分离，资源读取及 Boot/Jakarta 装配无 OSGi context；新增操作回归接入 CI，交付可演示流程及 fixture。

## 完成证据

- [Controller/Driver 双进程回放](../../../shadow/evidence/2026-11-04/controller-web-observability/controller-driver-process/summary.txt)：页面登录、提交、查询、取消、历史重提；queued 重排成功、运行中重排拒绝；五类 CLI、非法/超限 multipart、无半成品 archive、临时文件清理及服务 Token 不泄漏均通过。
- 同一 `controller-web-actions.xml` 经页面和 CLI 提交后，HTML/文本分别返回 `w3`/`w4`；两份归档配置经 `diff -u` 比较一致，最终状态均为 `CANCELLED`（[页面响应](../../../shadow/evidence/2026-11-04/controller-web-observability/controller-driver-process/web-submit.html)、[CLI 响应](../../../shadow/evidence/2026-11-04/controller-web-observability/controller-driver-process/cli-submit.txt)、[页面归档配置](../../../shadow/evidence/2026-11-04/controller-web-observability/controller-driver-process/archive/w3-controller-web-actions/workload-config.xml)、[CLI 归档配置](../../../shadow/evidence/2026-11-04/controller-web-observability/controller-driver-process/archive/w4-controller-web-actions/workload-config.xml)、[页面取消状态](../../../shadow/evidence/2026-11-04/controller-web-observability/controller-driver-process/web-cancel-state.json)、[CLI 取消状态](../../../shadow/evidence/2026-11-04/controller-web-observability/controller-driver-process/cli-cancel-state.json)）。
- [Controller Web 验收报告](../../../shadow/evidence/2026-11-04/controller-web-observability/README.md)：26 个 ControllerProcessTest 通过，覆盖页面和 CLI 路由、表单、认证及上传边界；报告对已冻结的下载、CSV 和错误响应合同提供了基线比较，页面/CLI 操作的同输入语义比较见上方双进程回放。
- [最终验收清单](../../../shadow/evidence/2026-11-10/finalization/README.md)：WEB-C1 在最终构建和生产制品复验中通过；首责票仍需人工审阅，因此状态为 `ready-for-human`。
