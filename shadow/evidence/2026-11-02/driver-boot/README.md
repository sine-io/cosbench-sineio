# #10 Driver Boot 与本地 Mock mission 验收证据

以 #09 的提交 `6125cb9fbb7baaef5938370f954f47d1e06aa294` 为起点。最终验证输入由 [source.sha256](source.sha256) 固定；完整 Docker 运行报告保留在本机 `/tmp/cosbench-scheme3-ticket10-reviewcoverage`，本目录保留复验所需摘要、Driver 进程报告、模块审计和测试报告。

## 重放

在报告目录为空的 checkout 中运行：

```sh
bash ci/verify-shadow.sh /tmp/cosbench-driver-10
```

入口从锁定的 Java 21/Maven 镜像启动本地 MinIO 和 Toxiproxy，运行影子 reactor `clean verify`、依赖/制品闸门及 Driver、Controller、Web 和 HTTP 进程检查。工具链见 [toolchain.txt](toolchain.txt)，固定版本与镜像摘要见 `shadow/harness/versions.env`。

## 结果

| 验证 | 结果与证据 |
| --- | --- |
| 全 reactor `clean verify` | 86 项测试通过，0 失败、错误或跳过；[build.log](build.log)，Driver 测试：[Surefire](tests/cosbench-driver-app/) |
| Provider registry | 5 项测试覆盖预期 descriptor 缺失、缺少 factory 依赖、重名 factory、初始化失败时释放及稳定排序；[测试](tests/cosbench-provider-spike/)、[Driver 模块审计](ci/cosbench-driver-shadow/) |
| 本地 Mock mission | 7 项测试覆盖 submit/login/launch/query/close、worker 与结果、mission 日志、直接 abort 与 shutdown 中止在途及未启动任务、成功 Provider init/operation/dispose 顺序和关闭后行为、部分 worker 初始化失败时释放已创建 Provider、trigger 输出/PID 和脚本路径边界；[测试报告](tests/cosbench-driver-app/) |
| Driver 可执行进程 | 两次真实 `java -jar` 启动、共同 YAML 与显式配置优先级、健康检查、TERM 退出码 143、线程/进程清理、端口释放及重启通过；[汇总](driver-process/summary.txt)、[退出结果](driver-process/results.txt)、[进程日志](driver-process/)、[归档扫描](driver-process/jar-contents.txt) |
| 生产/测试边界 | 15 个 reactor 模块通过依赖、许可证/校验和、重复类、`jdeps` 与归档闸门；Driver main JAR 不含 Mock 类或服务描述文件；[闸门汇总](ci/gates-summary.txt)、[Driver app 审计](ci/cosbench-driver-app/)、[边界结果](driver-process/summary.txt) |
| Docker 清理 | `run_status=0`、`cleanup_status=0`，本轮容器、网络、卷和证书容器均无残留；[清理结果](cleanup-status.txt) |

Driver Boot 使用共同 `ServiceConfig` 与 `/driver/health`，从启动时 `StaticProviderRegistry` 注入按名称排序的不可变 factory 列表。Mock factory 与 service descriptor 只在 Driver app 测试 classpath。Driver 收到 TERM 时先关闭任务入口，在配置的 grace period 内结束/中止 mission 并释放 worker Provider 与日志。

本票的可执行入口提供健康检查；Driver `/i` 命令与服务认证由 #13 验收。源码与相关构建/重放脚本的最终校验和见 [source.sha256](source.sha256)。

## 双轴代码审查

Standards 与 Spec 审查记录见 [review.md](review.md)。
