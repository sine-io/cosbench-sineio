# 方案三开发规格与执行清单

状态：开发前冻结规格。本文把方案三文档转成可执行的开发输入；若实现需要偏离，必须先更新本目录文档和验收矩阵。

## 1. 交付物

- `cosbench-controller-app`：Java 21 Spring Boot 可执行应用，提供 Controller 页面和 Controller API/CLI backend。
- `cosbench-driver-app`：Java 21 Spring Boot 可执行应用，提供 Driver 页面和 `/i/*.command` backend。
- 普通 Maven reactor：Controller、Driver、core、Web backend、必要基础模块、SineIO、S3。
- 生产包：不含 Mock、legacy Provider、Equinox、OSGi、Tycho/P2、源码和旧 Tomcat 6。
- 测试包/profile：包含 Mock，并通过 Docker MinIO 执行对象存储 smoke。

## 2. 实施顺序

1. #01 冻结旧版本行为、配置边界、性能/资源基线和回退制品。
2. #02 建立 Java 21/release=21 工具链、单模块 Maven 影子构建和可复用 Docker/MinIO 环境，固定 Boot 3.x BOM。
3. #03–#06 验证配置转换/生命周期/旧 archive、全部目标 Provider、代表性 Web 页面与 multipart、带认证/重试/幂等的 HTTP 路径；四项均为 #07 的显式 blockers。
4. #07 分批迁移公共基础，#08 建立持续 CI 审计闸门；旧构建由固定的基线 checkout/归档提供，新影子构建保持可独立验证。
5. #09–#18 按依赖图完成两个组合根、测试 registry、生产 Provider、HTTP、两侧 Web 和完整运维命令。每票只承担其模块批次和外部行为，标准目录迁移随所属批次完成。
6. #19 完成部署组合和端到端回归，#20 完成三次同参数性能/资源对比，#21 生成独立生产包/镜像。
7. #22 验收实际生产制品、旧数据读取与回退；#23 切换根构建入口并清理活跃 OSGi/Tycho/P2，重建并复验受影响的最终制品，保留 legacy 和历史回退包。

票的唯一当前列表、依赖、旧票映射见[执行索引](../../../.scratch/scheme3-migration/README.md)。编号按依赖排序；可领取所有 blockers 已通过的票，不要求逐号串行。

## 3. API 与压测不中断要求

- Controller 到 Driver 保留 `/i/ping.command`、`submit.command`、`login.command`、`launch.command`、`query.command`、`close.command`、`abort.command`、`trigger.command` 的路径、字段、状态码和错误语义。
- 服务间认证不使用页面 Session。默认使用 HTTPS/TLS + 可配置长生命周期 Token；Token 不得在 workload 运行期间自然过期。若使用短期 access token，必须无感刷新。
- HTTP 使用连接池、keep-alive 和空闲连接回收。连接、读取、命令、workload 超时分别配置；池上限、空闲回收、Provider 并发和重试范围由统一 YAML 校验。
- 只对连接失败、临时网络错误和明确的 5xx 做有限重试；Controller 为同一次写操作保留 request id/idempotency key，Driver 在进程生命周期内保存已处理结果。Driver 返回每进程实例 ID，Controller 写操作携带最近确认的实例 ID；新进程拒绝旧实例写重试并返回结果未知诊断，避免将歧义操作当作新写执行。原结果不跨重启持久化。HTTP 验证冻结身份传递、并发/晚到重放、同键不同内容、失败结果和响应丢失后的语义。
- `/i/login.command` 保留 mission 的 Provider 认证职责；Driver 的服务 Token 验证独立执行，页面 cookie 不能代替服务凭据。旧业务响应合同与新增认证/幂等响应分别留 fixture。
- 页面请求超时不能取消后台 workload。停止时先停止新任务，再在 grace period 内关闭请求、worker、Provider client 和日志。

## 4. 配置与命令

最终配置文件为 `conf/controller.yml` 和 `conf/driver.yml`，至少覆盖：监听地址和端口、Driver 地址、Token/TLS 引用、workspace/archive/mission 目录、连接池、超时、重试、并发、日志、Provider 和 Web 设置。

最终命令为：

- `bin/cosbench-controller`
- `bin/cosbench-driver`
- `bin/cosbench-stop`
- `bin/cosbench-config-migrate`

旧配置原文件保留，由迁移工具转换；旧命令只允许作为短期包装器。#03 先固定 schema version、默认值、优先级、范围及 secret 引用，再供两个 app 消费。默认文件缺失、显式路径缺失、空配置、缺必需字段和非法值分别按 #01 实测结果迁移。#18 完成全量字段映射、命令正常/异常/重复执行幂等、多 Driver 端口递增及开发脚本入口。

## 5. 依赖处理边界

本轮必须移除 Spring DM、OSGi、Equinox、Tycho/P2、旧 Tomcat 6 和 `javax.servlet`。Boot 管理 embedded Tomcat 10.1/Servlet 6。

本轮暂不升级并登记为后续任务：Jackson 1.x 到 2.x、Castor 到 Jackson XML/JAXB、AWS SDK 到 2.x、SineIO SDK 大版本、会改变 `/i` wire contract 的 HTTP/序列化库。日志优先使用 Boot 默认 Logback；若兼容性要求保留 Log4j2，则作为普通 Maven 依赖。

## 6. Docker 验收

- 开发/测试镜像使用 Java 21，包含测试工具和 MinIO 编排支持。
- 生产镜像与开发镜像分开构建，不含 Mock、legacy、源码、P2、Equinox 或开发工具。
- MinIO 覆盖 put/get/list/delete、multipart、认证、超时和错误映射 smoke。
- Docker 命令使用 `sudo`；测试结束清理本轮创建的容器、网络和临时资源。

## 7. 开发前阻塞项

以下四项必须在大规模迁移前通过；写好验证票不等于验证通过：

- #03：版本化 YAML、代表性旧配置转换、新命令最小启动/停止、TERM grace period 和旧 archive 读取。使用真实 service 配置/归档路径，原数据保留。
- #04：Mock storage/auth、SineIO sio/siov1/gdas、S3 s3 的 ServiceLoader discovery、init、一次 operation、dispose；同时完成共享 classpath 依赖树、重复类和 MinIO smoke。必要的静态隔离必须有复现冲突和复验结果。
- #05：一个 Controller 页面及 multipart CLI handler 在 Boot 3/Jakarta/FreeMarker 下渲染、登录、上传、下载和静态资源正常，使用真实 ControllerService seam。
- #06：代表性 Driver `/i` 写命令及查询经真实 DriverService/Mock，在 HTTPS/Token、连接池、分层超时、有限重试和幂等条件下通过契约测试。

每项输出版本化 fixture、重放命令、配置/依赖合同、生成物和失败诊断；#07 逐项消费其通过证据，才开始扩展迁移。代表性验证中的源代码复制或临时适配由后续所属模块票整合，生产归档不打入 spike 或测试工具。

## 8. 完成门槛

只有同时满足以下条件才算方案三完成：普通 Maven `clean verify` 通过；两个 Java 21 应用可启动/停止；页面和 `/i` 合同通过；SineIO/S3 MinIO smoke 通过；生产包无活跃 OSGi/Tycho/P2/Mock/legacy；Docker 镜像和回退演练通过；旧配置、workspace、archive 和 mission 数据可诊断迁移或继续读取；同参数至少三次性能/资源对比满足基线阈值；部署组合、命令幂等及 CI 审计通过。各项首责票与复验票见[验收责任表](acceptance-matrix.md#ticket-ownership)。
