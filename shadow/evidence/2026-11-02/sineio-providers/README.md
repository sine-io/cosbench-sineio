# #11 SineIO 生产 Provider 实现与验收证据

实现起点为 `f969165701a0364b8785a995b315302467005791`。生产 Provider、Driver 装配及本地验收已完成。使用者于 2026-11-02 确认真实 endpoint 上的 restore/prefetch/range 将在开发完成后手动验证，不要求作为 #11 的开发完成条件。下方证据没有声称真实 endpoint 已验证；工单转为 `ready-for-human`，供使用者后续手动验收。

## 重放

```sh
bash ci/verify-shadow.sh /tmp/cosbench-sineio-11
bash shadow/harness/test-sineio-archive-replay.sh /tmp/cosbench-sineio-archive-failures
```

首条命令使用固定的 Java 21/Maven/MinIO/Toxiproxy 镜像与空 Maven 缓存，运行全 reactor、依赖与制品闸门及进程重放。第二条使用 Java 21 Docker 中的受控 HTTP peer，专门证明归档 runner 不会在错误/超时/中断时假通过。版本见 [versions.env](versions.env) 和 [toolchain.txt](toolchain.txt)。本机完整报告分别在 `/tmp/cosbench-scheme3-ticket11-final` 和 `/tmp/cosbench-ticket11-archive-failures-verified`；本目录保留可复核证据。

后续手动真实归档验收可使用 [fixture 模板](../../../harness/fixtures/sineio-archive.properties.example) 和 [严格 runner](../../../harness/sineio-archive-replay.sh)，具体参数见 [实现说明](../../../sineio-providers.md)。当前未运行真实 endpoint 验收。

## 结果

| 验证 | 实际结果与证据 |
| --- | --- |
| 全 reactor | 114 项测试通过，0 失败、错误、跳过；[build.log](build.log) |
| 生产 SPI 与配置 | `sio`、`siov1`、`gdas` 从生产 JAR 发现；空配置、合法配置、未知 key、非法数字/布尔及 dispose 后错误类型通过；[Provider 测试](tests/cosbench-sineio/) |
| MinIO 数据操作 | 三模式 create/delete bucket、put/get/list/head/delete、重复 multipart 上传逐字节一致；SIO 两模式 partial range/prefetch 数据一致；[Provider 测试](tests/cosbench-sineio/) |
| HTTP 与 SDK 错误 | 400/403/404/409/500/503、实际 MinIO 无效凭据、拒绝连接、慢响应保留原始 COSBench 分类及 SDK cause；受控 peer 验证 restore 天数、metadata、签名、prefetch/range 请求优先级；[Provider 测试](tests/cosbench-sineio/) |
| SPI 资源 | 外部 HTTP peer 观察到 dispose 后 keep-alive 连接 EOF；重复 dispose 无异常；[红/绿证据](tdd/) |
| Driver mission | 三模式真实读取成功、结果/字节数正确，mission close 和 abort 后当前 JVM 的存储连接归零；[Driver 测试](tests/cosbench-driver-app/) |
| 实际 Boot 进程 | 正常 executable 无 probe 启动/停止通过；只额外加载两个测试 probe 类时生产 registry 恰为三个模式、无 Mock；每模式实际 MinIO mission 运行中 TERM 后退出 143、mission executor 终止、端口可重用；[汇总](sineio-driver-process/summary.txt)、[退出结果](sineio-driver-process/results.txt)、[无 probe 验证](driver-executable-summary.txt) |
| SDK/打包 | AWS SDK v1 `1.12.767`、v2 `2.26.8`；16 个模块依赖收敛、许可证/SHA-256、重复类、jdeps 与制品闸门通过；生产 Driver 包含 SineIO，无 Mock、spike、legacy 或源码 JAR；[闸门](ci/gates-summary.txt)、[Provider](ci/cosbench-sineio/)、[Driver](ci/cosbench-driver-app/) |
| 归档 runner 失败边界 | 缺 fixture、可读对象、慢 restore、轮询越界、忽略 Range、中断均失败；失败及中断容器清理通过；[缺 fixture](archive-missing-fixture/)、[失败回放](archive-failure-cases/)；这些不计为真实归档成功 |
| 完整 CI 清理 | run_status=0、cleanup_status=0，容器/网络/卷/证书容器无残留；[清理](cleanup-status.txt)；开发容器与临时凭据清理见 [development-cleanup.txt](development-cleanup.txt) |
| 真实归档/厂商后端 | **延期人工验证，未声称通过**：使用者决定开发完成后手动验证 restore 及厂商 endpoint 的 SIO prefetch/range；本票的代码及本地 CI 完成不依赖该验证 |

迁移保留旧目录作为回退输入，新增标准 Maven source/resource layout。除空白规范化，生产源码的行为改动仅为 `sio` 显式关闭自建 Apache HTTP client。真实 SDK 的 TERM 回放另暴露 worker 可能因 SDK 消耗中断后继续循环，Driver abort 因此先标记 worker finished 再中断。原失败日志与修复后结果保留在 `tdd/` 和进程报告中。

提交源码/构建输入校验和见 [source.sha256](source.sha256)，实际验证工作区输入见 [verified-working-tree.sha256](verified-working-tree.sha256)，迁入来源见 [source-provenance.tsv](source-provenance.tsv)，双轴审查见 [review.md](review.md)。Provider spike POM 已有的 Surefire `failIfNoTests=false` 配置作为用户既有改动保留、未纳入本票提交；该值与父 POM 相同，两份校验和明确保留这一输入差别。
