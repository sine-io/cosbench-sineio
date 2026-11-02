# #13 Driver HTTP 命令验收证据

验收使用 Java 21 Docker harness、生成的临时 TLS 证书、Mock auth/storage、MinIO 与 Toxiproxy。报告保留 Driver HTTP 合同与故障测试、Driver 真实进程重启/停机、依赖/制品门禁和容器清理结果；配置、日志及响应没有包含服务 Token。

## 重放

在报告目录为空的 checkout 中运行：

```sh
bash ci/verify-shadow.sh /tmp/cosbench-driver-13
```

Harness 先运行全 reactor Java 21 `clean verify`、依赖/制品审计与配置生命周期检查，再依次运行 Controller、Driver、SineIO Driver、Web 和 HTTP 合同进程测试。固定容器、Java、Maven 与 MinIO/Toxiproxy 版本见 [toolchain.txt](toolchain.txt) 和仓库 `shadow/harness/versions.env`。

## 结果

| 验证 | 结果与证据 |
| --- | --- |
| 全 reactor | Java 21 Docker `clean verify` 通过；[build.log](build.log)，Driver app 测试：[Surefire](tests/cosbench-driver-app/) |
| Driver HTTPS 命令合同 | 六个真实 HTTPS 测试通过，覆盖八个 `/i` 命令、旧版完整响应、Mock mission 生命周期、Provider login/错误/超时、Token 与页面 Cookie 隔离、并发/失败/晚到 replay、identity 边界、关闭拒绝及 TLS；[测试日志](driver-http-targeted-tests.log) |
| 认证和请求重试 | 完整 HTTP spike reactor 的 37 个测试通过；验收 harness 定向运行其中 35 个认证、指纹、幂等、客户端及真实 Driver HTTP 测试，包括 Toxiproxy 响应丢失后同键重试和单次 mission 执行；[定向测试日志](http-targeted-tests.log)、[Surefire 报告](tests/cosbench-http-spike/) |
| 真实进程 | Driver 以 HTTPS 启动两次，检查缺少实例标识、重启后旧实例写请求返回 unknown-result 409、TERM 退出和端口释放；[汇总](driver-process/summary.txt)、[结果](driver-process/results.txt) |
| Provider/资源清理 | Driver HTTP 关闭时拒绝新命令并释放 mission、Provider、worker 与 Web 端口；[Driver 进程日志](driver-process/)、[SineIO 进程结果](sineio-driver-process/summary.txt) |
| 依赖与产物门禁 | 18 个 reactor 模块通过依赖收敛、许可证/校验和、重复类、`jdeps` 与制品检查；[门禁汇总](ci/gates-summary.txt)、[Driver HTTP 模块审计](ci/cosbench-driver-web/)、[Driver app 审计](ci/cosbench-driver-app/) |
| Docker 清理 | `run_status=0`、`cleanup_status=0`；容器、网络、卷和证书临时容器均无残留；[结果](cleanup-status.txt) |

响应基线来自冻结的旧 Driver `/i` fixtures 和历史实现运行结果。对比只归一化 mission/PID、日志、采样延迟及直方图尺寸；完成的固定 noop mission 仍严格断言 `sampleCount=10`、`totalSampleCount=10`、`byteCount=640`。Trigger 保留旧版逐行追加 LF、完整 stdout 与 scripts 子目录路径行为，同时拒绝越出配置 scripts 根目录的路径。

幂等结果仅保留在 Driver 进程内存中，随唯一写请求数和响应体积增长；不承诺重启恢复。写请求必须携带 authenticated ping 返回的 Driver instance，实例不匹配时返回结果未知的 409，不在新进程盲目重放。
