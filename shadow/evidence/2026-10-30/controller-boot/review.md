# #09 双轴代码审查

固定基点：`f84474072485a913283c8d4aa4be51a5ca90e603`。审查命令为 `git diff f84474072485a913283c8d4aa4be51a5ca90e603...HEAD`，实现提交标题为 `Implement Controller Boot process and lifecycle #9`。Standards 和 Spec 由两个独立 agent 并行审查；后续修正分别复审。105 个 Controller 文件与旧源码逐一忽略空白对比，只有 service、processor、heartbeat、repository 和 archiver 五个文件有语义改动。

## Standards

最终报告：0 项文档规范违例，0 项需处理的代码异味。

已核对标准目录、继承的 Java/Maven/plugin/release 策略、显式 Maven 依赖和生产/测试边界、CI 接入、应用装配及测试探针隔离。异步生命周期 callback 保持在 Spring 边界；日志优先级由公共 loader 统一处理。最终取消预留时间、诊断、文档和可观察归档断言一致。未将旧源码原有且本票未改动的代码异味计入本票。

## Spec

最终报告：0 项未解决或新增发现；0 项范围扩张。

初审发现两项 P2，均以失败测试复现后修复：

1. service 和 HTTP 先后各消耗一次 grace period。真实 Docker 探针在 grace=1s、运行本地 delay 和未完成 POST 时用了 2047ms。现在先关闭 admission，再让 service 和 HTTP 在同一 Spring phase 通过异步 callback 同时收尾；组合进程测试验证一次预算。
2. 单独设置 `paths.logs` 被默认 `logging.file` 覆盖。公共 YAML loader 现在令未提供的 `logging.file` 默认取 `paths.logs`，显式 `logging.file` 优先；两种实际进程配置均验证文件位置。

第一次复审进一步发现：固定最多 100ms 的取消/归档预留时间偶尔不够完成冷启动归档，两次探针记录 executor 尚未退出；未证明归档损坏。现在预留 `min(1s, gracePeriod/2)`，并在组合进程测试中断言归档 history、XML、workload.log 和 `.meta`。

最终独立探针连续三次通过：grace=1s、本地 delay=30s、未完成 6000-byte HTTP POST，退出 143 分别用时 1162 / 1181 / 1124ms。三次均拒绝新任务、executor/heartbeat 完成退出、无 incomplete 日志；各有 11 个归档文件、可解析的正确 XML、terminated history、`.meta=1` 和 workload.log。临时探针已清理，未修改仓库。

真实生产历史归档尚未提供的限制保持明确；本票验证代表性 #03 fixture 和真实 service 生成归档的重启读取。跨进程执行、完整 Web 和运维命令仍按 #14–#18 的既定范围验收。

两轴结论：Standards 0 项；Spec 0 项未解决发现。
