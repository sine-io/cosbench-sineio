# 17: 迁移 Driver 页面与命令共存

**What to build:** 让 Driver 页面通过本地 service 管理 mission/worker，与 /i 命令在同一 Boot 进程中共存。

**Blocked by:** 13: 交付八条 Driver HTTP 命令与服务认证幂等

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 WEB-D 和其对应章节。

**Inputs:** #05 的 Web 适配模式、#10 的 Driver、#13 的命令 backend 和 #01 的 Driver 页面 fixture；不依赖 Controller client 的实现。

- [x] 迁移 Driver Web 其余页面/资源到标准布局，index/submit/mission/workers、welcome/error、静态资源的路由/model/模板与基线一致。
- [x] 提交 mission、Provider login、launch/close/abort/trigger 及下载日志表单仍通过 DriverService，正常/错误/非法状态的响应和资源释放可验证。
- [x] 页面与 /i 在同进程无 mapping/filter/session 冲突；页面认证不能绕过服务 Token，Provider login 继续走 mission 生命周期。
- [x] 用真实 Driver/Mock 完成页面表单链路及命令交叉查询，比较下载内容/文件名/权限、内容类型、proxy context path 与基线。
- [x] CI 包含 Driver 页面完整路由索引、同进程共存及停止清理；无需等待完整 Controller UI 或生产 Provider 即可单独演示。

**Validation evidence:** [Driver WEB-D acceptance](../../../shadow/evidence/2026-11-04/driver-web/README.md)
