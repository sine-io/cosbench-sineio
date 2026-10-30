# #08 持续构建与依赖闸门证据

CI-01 对当前已迁入闭包验证通过。执行起点为 `3a2dc65cca3e8e99d5b93acc01b73f6be2f77e0d`，最终源码快照为 `9bf39e06560e9f9f897b107949f82d8f532b45a9`；19 个已验证实现/配置文件的内容由 [source.sha256](source.sha256) 固定。最终提交补充本票状态、许可文本和报告，没有更改已验证的实现。

## 重放

在包含本记录的提交上运行；报告目录必须为空：

```sh
bash ci/verify-shadow.sh /tmp/cosbench-continuous-gates
```

最终重放使用独立且开始时干净的 checkout `/tmp/cosbench-ticket08-reviewed-clean`，完整原始报告保留在 `/tmp/cosbench-ticket08-reviewed-reports`。命令与 GitHub Actions 的 `Java 21 migration gates` workflow 相同；本记录是本地 Docker 验证，不声称已运行远端 Actions。CI 每次从 checkout 和容器内空 tmpfs Maven 仓库开始，不恢复 Maven/build-output cache，失败时也上传全部报告。

Maven 3.10.0、Temurin 21.0.12.1、Boot BOM 3.5.16 及各插件/镜像锁定于 `shadow/pom.xml`、`shadow/harness/versions.env`。实际工具链见 [toolchain.txt](toolchain.txt)。Java 审计工具以 `--release 21 -Xlint:all -Werror` 编译。新增 workflow 通过 actionlint 1.7.7 检查，见 [actionlint.txt](actionlint.txt)。

## 结果

| 验证 | 结果与原始证据 |
| --- | --- |
| 空缓存全 reactor `clean verify` | 57 项测试，0 失败、错误、跳过；[build.log](build.log) |
| 当前全部 11 个 JAR 模块 | 自动读取 effective reactor，构建、convergence、依赖树、生产/测试边界、许可证/SHA-256、重复类、资源与制品扫描、`jdeps` 全部通过；[模块清单](ci/reactor.tsv)、[闸门汇总](ci/gates-summary.txt) |
| 依赖准入 | 135 项外部依赖覆盖生产和测试范围；逐模块 `ci/*/licenses-checksums.tsv`，匹配已提交的依赖锁 |
| 正常/违规 fixture | 27 项通过；[汇总](ci-fixtures-summary.txt)、[逐项诊断](ci-fixtures/) |
| #03 配置/启停 | 转换、健康检查、重启读取代表性归档、TERM/PID/端口释放、原文件 checksum 不变；[结果](config-lifecycle-summary.txt) |
| #04 SPI | Mock storage/auth 仅测试可见，sio/siov1/gdas/s3 共享 classpath 的实际操作和关闭通过；[结果](provider-smoke.txt) |
| #05 打包 Web | 登录、页面、multipart、下载及静态资源字节对比通过；[静态对比](web-static-baseline-comparison.txt)、[TERM/端口](web-process-stop.txt)、[进程清理](web-process-cleanup-status.txt) |
| #06 真实 HTTP | 独立 32 项测试，0 失败、错误、跳过；TLS/Mock mission/认证/有限重试/幂等/响应丢失重放通过；[测试日志](http-targeted-tests.log)、[合同结果](http-contract-summary.txt)、[重放结果](http-run-summary.txt) |
| Docker 清理 | `run_status=0`、`cleanup_status=0`，本轮容器、网络、卷和证书容器均无残留；[结果](cleanup-status.txt) |
| 双轴 code-review | 规范 0 项、规格 0 项未解决发现；[审查与修正记录](review.md) |

负例覆盖缺失资源、OSGi 字节码/依赖、Spring DM XML、主/嵌套制品的 Mock 泄漏、非批准测试范围的 Mock、未声明的嵌套 JAR、未知依赖/许可证、校验和变化、测试依赖进入生产范围、普通及多版本 JAR 重复类、未解析依赖、跳过或非阻断的 Enforcer execution、EPL/LGPL 候选许可证、convergence 违规和低版本 release。负例必须返回非零并包含预期诊断，不能将命令不存在或其它无关错误算作阻断成功。

完整 effective POM、Surefire XML/text、原始对象存储/HTTP/进程日志保留在完整报告目录，并由 workflow 上传；本目录保留构建、逐模块审计、负例和重放/清理证据。

## 范围与后续接入

这是对当前公共闭包和四项前置验证的持续闸门，不是完整生产应用、制品或发行验收。此前 #01/#03 的真实历史数据及人工验收状态保持其原记录。后续票加入 reactor 模块时会自动加入同一审计范围，并须扩展自身行为重放及依赖准入；规则和明确的测试例外见 [CI policy](../../../ci-policy.md)。

旧根 Tycho/P2 入口继续独立保留到 #23；重建旧版使用固定旧 tag 和旧脚本，不能给新 reactor 输入其输出。版本与旧基线入口见 `shadow/toolchain-evidence.md`。
