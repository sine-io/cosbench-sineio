# #07 公共模块迁移证据

实现提交：`af8f37f`。对照起点：`9e062cd5522dac6390c15a9185d333a142bb3093`。范围与后续责任见 [#07](../../../../.scratch/scheme3-migration/issues/07-shared-module-migration.md)。本次公共模块实现不代表 #03 缺失的真实历史 archive/workspace/mission fixture 已获验收；该外部证据和 #03–#06 的人工验收仍保留。

## 重放

在仓库根目录运行；报告目录必须为空。`run.sh` 内所有 Docker 命令均带 `sudo`，退出路径清理本轮容器、网络、卷和临时证书。

```sh
shadow/harness/run.sh /tmp/cosbench-shared-modules -- /bin/bash -c \
  '/bin/bash /workspace/shadow/harness/web-spike-process.sh && /bin/bash /workspace/shadow/harness/http-contract.sh'
```

主构建使用容器内空的 tmpfs Maven 仓库执行 `mvn -B -ntp -Dmaven.repo.local=/tmp/maven-repository -f /workspace/shadow/pom.xml clean verify`，之后安装同一 reactor 供逐模块依赖审计使用。Web/HTTP 重放在另一空缓存 Java 21 容器内执行，脚本自行构建并安装所需公共依赖。

工具链锁定于 [versions.env](../../../harness/versions.env) 和 [parent POM](../../../pom.xml)。实际 Java/Maven/OS 版本见 [toolchain.txt](toolchain.txt)；Docker 版本见 [docker-version.txt](docker-version.txt)。本轮使用 Maven 3.10.0、Temurin Java 21.0.12.1、Boot 3.5.16，全部模块继承 release=21。

## 公共产物

七个普通 JAR 的 Maven group 为 `com.intel.cosbench.shadow`，版本均为 `0.1.0-SNAPSHOT`。每项记录独立 dependency tree、JAR listing、SHA-256 和重复类结果；索引见 [shared-module-artifacts.txt](shared-module-artifacts.txt)。

| 模块 | 产物 artifactId | 内容 |
| --- | --- | --- |
| cosbench-api | cosbench-api-shadow | 原 Storage/Auth SPI、context、静态 ServiceLoader registry；无具体 SDK 类型 |
| cosbench-config | cosbench-config-shadow | 原 INI/XML/Castor 配置模型与 mapping、#03 YAML/转换合同 |
| cosbench-castor | cosbench-castor-shadow | 原 Castor properties 资源 |
| cosbench-core | cosbench-core-shadow | bench/model/protocol/service 接口及公用工具 |
| cosbench-http | cosbench-http-shadow | 原 Apache HTTP helper 和异常合同 |
| cosbench-log | cosbench-log-shadow | 原日志接口和 Log4j adapter，排除 OSGi Activator |
| cosbench-log4j | cosbench-log4j-shadow | 原 Log4j properties 资源 |

旧根 reactor 继续使用 `dev/`；新公共模块全部采用标准 `src/main/java`、`src/main/resources`、`src/test/java`。spike 通过公共 JAR 复用这些类，不再编译公共 `dev/` 源码。Castor mapping 只从 config resources 打包一次。Controller/Driver/Web/Provider 专用源码仍由其后续迁移票负责。

## 验证结果

最终重放通过：[run-summary.txt](run-summary.txt) 为 `status=passed`；[cleanup-status.txt](cleanup-status.txt) 为 `run_status=0`、`cleanup_status=0`，容器、网络、卷、证书容器均无残留。

| 验证 | 结果与证据 |
| --- | --- |
| 空缓存全 reactor clean verify | 57 项测试，无失败、错误或跳过；[build.log](build.log) |
| 公共 JAR/资源 | class major=65、8 份 Castor mapping 资源；[packaged-runtime.txt](packaged-runtime.txt)、[resource-list.txt](resource-list.txt) |
| 模块依赖和重复类 | 七模块及 runtime/provider 均通过；逐模块 `*-dependency-tree.txt`、`*-duplicate-class-audit.txt`、`*-artifact.sha256` |
| #03 配置/启停 | Controller/Driver 转换、健康、代表性归档重启读取、TERM 端口/PID 清理、原数据 checksum 不变；[config-lifecycle-summary.txt](config-lifecycle-summary.txt) |
| #04 SPI | Mock storage/auth 与 sio/siov1/gdas/s3 共享 classpath 的操作和关闭通过；[provider-smoke.txt](provider-smoke.txt)、[provider-artifact-boundary.txt](provider-artifact-boundary.txt) |
| #05 打包 Web | 真实 ControllerService 登录/页面/multipart/下载通过，静态响应与旧资源字节相同；[web-process.log](web-process.log)、[web-static-baseline-comparison.txt](web-static-baseline-comparison.txt) |
| Web 关闭 | TERM 退出 143、端口释放、临时运行目录删除；[web-process-stop.txt](web-process-stop.txt)、[web-process-cleanup-status.txt](web-process-cleanup-status.txt) |
| #06 独立 HTTP 重放 | 32 项测试通过；真实 TLS/Mock mission/认证/重试/幂等与响应丢失场景保持；[http-run-summary.txt](http-run-summary.txt)、[http-targeted-tests.log](http-targeted-tests.log)、[http-contract-summary.txt](http-contract-summary.txt) |

兼容性测试覆盖旧 workload XML 的解析/序列化/再解析、Jackson 1.x protocol 字段、Castor mapping 资源、HTTP URL 编码与方法、日志占位符及 dispose 后停止写入。既有 YAML/INI 转换、SPI discovery/init/operation/dispose 与错误诊断、Controller 页面/multipart、真实 DriverService HTTPS/Mock mission、Toxiproxy 响应丢失重试/幂等测试继续使用同一批公共 JAR。

Java 21 下 Castor 1.3.3 XML 写入仍需 `--add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED`；公共 config 测试及 Web 重放保留此已验证兼容参数，没有替换 serializer。排除 Castor 旧 StAX API（由 JDK 提供）和未使用的 Spring 集成；Boot 消费方排除 Commons Logging 原实现，使用 Boot 自带 Spring JCL。应用 Bean 在 Controller service 关闭后 dispose 系统日志管理器，公共 SPI 保留原 lifecycle 接口。

前序诊断发现并修正：缺版本的 SLF4J dependency-management 条目、单模块审计未安装 sibling/parent POM、Castor 两份 StAX API 和 Commons Logging/Spring JCL 重复类。失败轮次均确认清理成功；本记录的最终报告对应修正后的完整重放。

## 后续整合边界

#09/#10 负责完整应用和日志生命周期；#10 将公共 registry 接入正式 Driver factory service；#11/#12 迁入生产 Provider 并扩展专用操作；#13 整合 Driver HTTP handler/认证/幂等；#14 接入已验证 Controller 客户端；#15–#17 扩展 Web；#18 扩展配置与命令；#08 负责持续 convergence/jdeps/许可证闸门。#07 保持这些验证可重放，不提前迁入完整应用或升级 SDK/serializer。
