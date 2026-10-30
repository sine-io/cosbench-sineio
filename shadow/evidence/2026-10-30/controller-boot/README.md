# #09 Controller Boot 验收证据

APP-C 的实现和本地 Docker 验证已完成。起点为 `f84474072485a913283c8d4aa4be51a5ca90e603`；已验证源码/构建输入由 [source.sha256](source.sha256) 固定，[逐文件核对](source-verification.txt)全部通过。最终提交补充票据状态和报告，不改变已验证的运行源码。

## 重放

在包含本记录的提交上运行，报告目录必须为空：

```sh
bash ci/verify-shadow.sh /tmp/cosbench-controller-09
```

本次使用独立、开始时干净的 checkout `/tmp/cosbench-ticket09-clean` 和容器内空 tmpfs Maven 仓库，完整原始报告保留在 `/tmp/cosbench-ticket09-reviewed-reports`。这是同一 CI 入口的本地 Docker 结果，不声称远端 Actions 已运行。开发过程另有红/绿测试和独立规格探针；一次旧修订重放在全量测试/审计通过后主动停止并清理，最终验收只取本目录对应的完整最终重放。

Maven 3.10.0、Temurin 21.0.12.1、Boot BOM 3.5.16 与镜像摘要/插件继承既有锁定策略；见 [toolchain.txt](toolchain.txt) 与 `shadow/harness/versions.env`。两新模块没有引入新的外部依赖准入项。

## 结果

| 验证 | 结果与证据 |
| --- | --- |
| 空缓存全 reactor `clean verify` | 79 项测试，0 失败、错误、跳过；[build.log](build.log)、[测试汇总](test-summary.txt) |
| Controller 实际进程/public service | 22 项：兼容 Bean、健康、提交/排队/查询、正常与超时 TERM、HTTP/本地工作共用 grace、重复关闭、配置诊断/优先级、OFF、无 Web 模式、目录/日志、旧格式归档重启读取和乱序结束时 ID 延续；[Surefire](tests/cosbench-controller-app/) |
| 可执行 Controller JAR | 无测试探针的真实 `java -jar` 启动、配置优先级、端口冲突及已装配资源关闭、空闲 TERM、端口释放/重启、原 archive/workspace 字节不变；[结果](controller-process/summary.txt)、[进程退出](controller-process/results.txt)、[进程清理](controller-process/cleanup.txt) |
| 全部 13 个 JAR 模块 | 自动 reactor 清单、依赖树/convergence、许可证/SHA-256、生产/测试边界、重复类、资源/嵌套制品及 `jdeps` 全部通过；[清单](ci/reactor.tsv)、[汇总](ci/gates-summary.txt)；Controller/app 单独报告在 `ci/cosbench-controller*` |
| 正常/违规审计 fixture | 27 项通过；[汇总](ci-fixtures-summary.txt)、[逐项诊断](ci-fixtures/) |
| #03 配置生命周期 | 转换、健康、TERM/PID/端口、源配置及代表性 archive checksum 保持；[结果](config-lifecycle-summary.txt) |
| #04 Provider SPI/MinIO | 生产 sio/siov1/gdas/s3 及测试 Mock 的发现、真实操作与资源释放通过；[结果](provider-smoke.txt)、[产物边界](provider-artifact-boundary.txt) |
| #05 Web | 原有 3 项 Web 测试纳入全量测试；打包登录/页面/multipart/下载/静态资源、TERM/端口回归通过；[静态资源对比](web-static-baseline-comparison.txt)、[停止](web-process-stop.txt)、[清理](web-process-cleanup-status.txt) |
| #06 认证 HTTP | 独立 32 项测试通过，TLS、真实 Mock mission、认证、有限重试、幂等、响应丢失和 Driver 重启安全均通过；[测试](http-targeted-tests.log)、[合同](http-contract-summary.txt)、[结果](http-run-summary.txt) |
| 双轴 code-review | Standards 0 项，Spec 0 项未解决发现；三处生命周期/日志问题已复现、修复、复审；[审查记录](review.md)，红/绿日志位于 [tdd/](tdd/) |
| Docker 清理 | `run_status=0`、`cleanup_status=0`，本轮容器/网络/卷/证书容器均无残留；[结果](cleanup-status.txt)。独立调试容器亦已自动删除；[开发清理](development-cleanup-status.txt) |

完整 effective POM、所有模块 Surefire XML/text、原始进程/HTTP/对象存储日志保留在完整报告目录，由现有 workflow 作为运行报告上传。本目录保留构建、逐模块审计、Controller 进程、回归、审查及清理证据。

## 验收边界

#09 交付普通 Controller 业务 JAR 和薄 Boot app，Web spike 复用业务模块。工作负载调度、协议、loader/exporter 继续在原领域模块，健康和真实 service 不使用 #03 的模拟任务实现。

旧数据验证使用 #03 的代表性 archive/workspace fixture，并通过真实 service 生成归档后重启读取；真实生产历史归档仍未提供，未将这一外部输入记为通过。跨进程 Controller→Driver 执行归 #14，完整页面/CLI 归 #15/#16，正式运维命令归 #18。根 Tycho/P2 入口按既定计划保留到 #23。
