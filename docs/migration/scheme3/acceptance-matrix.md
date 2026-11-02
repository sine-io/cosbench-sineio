# 方案三兼容性测试与验收矩阵

状态：开发验收合同；场景归属已落实到 tickets，运行证据待实施产生。重写 Controller、Driver、core、两侧 Web backend、实际依赖闭包、SineIO/S3；FreeMarker 模板、静态资源、页面结构、路由和表单保留，不做前端重写。Mock 仅在 test profile/classpath 使用，不打入生产包。其他 Provider、Manta、ext 和旧 Tomcat 源码保留在 `legacy/osgi/`，旧版归档是唯一可运行支持。阶段 0 先采集旧版本基线，后续每个迁移批次都执行同输入对比。

## 1. 验收规则

方案三的验收对象是可观察行为，不是内部类名或框架。每条用例记录：

- 旧版本提交、迁移版本提交、JDK/OS/容器和 Provider 组合；
- 配置文件、workload XML、请求样例和期望结果的版本；
- HTTP 状态码、响应字段、状态转换、文件产物、日志关键字和退出码；
- 失败时的错误类型、重试次数、超时和清理结果；
- 是否允许因底层 SDK/Servlet 实现变化而有差异，以及差异的审批记录。

功能合同必须精确匹配；性能合同先以同硬件基线测量，不能用单次运行的吞吐波动判定。推荐在相同 workload 下比较吞吐、p50/p95/p99、失败率、CPU、堆和线程数，阈值在阶段 0 采集后锁定。

## 2. 迁移阶段闸门

| 阶段 | 进入条件 | 必须通过 | 产物 |
|---|---|---|---|
| 0 基线 | 当前版本可启动 | Mock/noop、SineIO 和 S3 最小 workload、所有公开路由样例、配置/目录/退出码记录 | 基线归档、请求/响应样例、Provider 表 |
| 前置验证 | 基线、最小工具链与 Docker/MinIO 可重放 | 配置/生命周期、全部 factory、代表性 Web、带认证和幂等的 HTTP 四项通过 | #03–#06 的合同、命令、fixture 和通过证据 |
| 1 Maven | 四项前置验证通过 | 各批次 clean verify、资源完整、无 Tycho 参与新构建；完整闭包随应用/Web/Provider 批次形成 | 依赖矩阵、普通 JAR、持续 CI 闸门 |
| 2 组合根 | 普通 JAR 可加载 | Controller/Driver 启停、Mock workload、线程/日志关闭 | 两个 app 入口、启动日志 |
| 3 Web backend | app context 可装配 | 页面、CLI、`/i`、登录、下载、Prometheus、静态资源和 FreeMarker 输出与旧版一致 | Web backend smoke 报告 |
| 4 Provider | registry 可发现 | 每项纳入范围的 Provider 名称、配置错误、至少一个 operation smoke | Provider registry 报告 |
| 5 发布 | 应用、Provider、部署矩阵和性能对比通过 | 实际生产包空目录安装、Docker、重启恢复、stop 清理、旧版回退 | 发布归档、镜像、回退记录 |
| 6 收缩 | 阶段 0-5 全部通过 | 新 reactor、活跃源码和发行物无 OSGi runtime/P2/Tycho 依赖；legacy 明确排除且不被新构建引用 | 最终 tag、依赖扫描 |

## 3. Controller 行为矩阵

| 场景 | 输入 | 旧版行为要保留 | 检查点 |
|---|---|---|---|
| 新配置启动 | `conf/controller.yml`、archive 目录 | 读取 name/url/drivers/concurrency/log/archive 等有效值 | 端口、日志、PID、退出码 |
| 旧配置转换 | 当前 Controller INI/配置目录 | 转换为版本化 YAML，保留每个受支持配置的有效值和路径 | 转换报告、默认值对照、无损字段检查 |
| 配置边界 | 默认文件缺失、显式路径缺失、空配置、缺必需字段、非法值 | 分别保留基线实测的默认值或失败语义 | 不把所有缺配置情形归为同一退出结果 |
| 提交 workload | 合法 XML 或 multipart CLI | 返回 workload id，状态为 queued | id 格式、错误字段、日志 |
| 非法 workload | XML 缺字段/非法 operation | 返回原有失败语义，不创建半成品 archive | HTTP/CLI 状态、目录清理 |
| 触发执行 | `fire`/提交后执行 | 通过 Driver `/i` 链路，保留顺序和并发语义 | 请求顺序、超时、重试、状态 |
| 查询 | active/history/archive | 返回同一状态集合和字段 | 页面、CLI、CSV 与 service 结果一致 |
| 调整顺序 | up/down、邻居 id | queued workload 可调整，运行中拒绝 | order、future、日志 |
| 取消 | active workload id | 终止 processor 并归档结果 | 状态、线程、日志、archive |
| 重提 | history workload id | 用原 XML 创建新 workload | 配置来源、新 id、错误 |
| 下载 | workload log/config | 读取原路径并返回下载内容 | 文件名、长度、权限、脱敏 |
| Prometheus | `/prometheus` | 指标名称/标签/数值语义保持 | HTTP、内容类型、样本字段 |
| 停止 | TERM/脚本 stop | HTTP 端口释放，线程池退出 | 无残留进程/句柄 |

## 4. Driver 行为矩阵

| 场景 | 输入 | 旧版行为要保留 | 检查点 |
|---|---|---|---|
| 新配置启动 | `conf/driver.yml` | 读取 name/url/log/mission_dir 等有效值 | 端口、日志、PID、退出码 |
| 旧配置转换 | 当前 Driver INI/配置目录 | 转换为版本化 YAML，保留认证、存储、端口和 mission 路径 | 转换报告、默认值对照、无损字段检查 |
| ping | `/i/ping.command` | 返回 driver info 和存活状态 | 字段、状态码、响应时间 |
| submit | `/i/submit.command` + XML | 创建 mission，返回 id | id 格式、状态、配置错误 |
| login | `/i/login.command` | 创建 auth API 并反映失败状态 | auth name、错误分类、dispose |
| launch | `/i/launch.command` | 启动 agent/worker，保留并发 | worker 数、状态、日志 |
| query | `/i/query.command` | 返回 active/history mission | 字段、排序、归档 |
| trigger | `/i/trigger.command` | 按 workload trigger 执行 | 时间/次数/错误语义 |
| close | `/i/close.command` | 关闭 mission 和资源 | storage/auth dispose、线程退出 |
| abort | `/i/abort.command` | 中止 mission，保持状态转移 | agent 结束、错误分类、文件清理 |
| 不可达/超时 | 模拟网络失败 | Controller 获得原有失败状态 | timeout、retry、错误信息 |
| 服务认证 | 缺失/非法 Token、仅页面 cookie、合法服务凭据 | 独立于页面 Session 及 mission Provider login | 合法凭据按旧业务合同执行；新增拒绝语义按 #06 fixture |
| 幂等 | 相同写身份、并发重复、响应丢失、同键不同内容、跨重启旧实例重试 | 进程生命周期保留已处理结果，不重复 mission 动作；新 Driver 实例拒绝旧实例写并报告结果未知 | 请求约定、返回结果、失败/晚到重放和实例变更诊断按 #06 冻结合同；原结果不跨重启恢复 |
| 停止 | TERM/脚本 stop | 停止 HTTP、executor、Provider client | 无残留线程/端口 |

## 5. Web 和 CLI 契约

### 5.1 Controller 页面/操作

至少覆盖以下路径和表单。页面和模板不改写，检查重点是 backend 迁移后的 model、view、filter 和资源加载结果：

- `/controller/index.html`、`/logon.html`、`/logonError.html`；
- `/matrix.html`、`/submit.html`、`/submit-workload.do`；
- `/config.html`、`/config-workload.do`、`/advanced-config.html`、`/advanced-config-workload.do`；
- `/workload.html`、`/cancel-workload.do`、`/download-log.do`、`/download-config.do`；
- `/stage.html`、`/timeline.html`、`/timeline.csv`、`/prometheus`；
- `/cli/index.action`、`/cli/submit.action`、`/cli/config.action`、`/cli/workload.action`、`/cli/cancel.action`；
- CSS、JavaScript、PNG、welcome page 和 FreeMarker error pages。

检查登录 cookie/session、`CliLoginFilter`、multipart 上传限制、下载权限、HTML/CSV 内容类型、错误页面和 reverse proxy 下的 context path。重排和重提通过原页面表单验收；CLI 保留 index/submit/config/workload/cancel。页面请求超时或 Session 结束后，已接受的后台 workload 仍继续运行、可查询并可正常完成。

### 5.2 Driver 页面/命令

至少覆盖以下路径和表单。页面和模板不改写，检查重点是 backend 迁移后的 model、view、filter 和资源加载结果：

- `/driver/index.html`、`/submit.html`、`/mission.html`、`/workers.html`；
- `/submit-mission.do`、`/perform-login.do`、`/launch-mission.do`、`/close-mission.do`、`/abort-mission.do`、`/download-log.do`、`/run-trigger.do`；
- `/i/ping.command`、`/i/submit.command`、`/i/login.command`、`/i/launch.command`、`/i/query.command`、`/i/close.command`、`/i/abort.command`、`/i/trigger.command`；
- CSS、JavaScript、PNG、welcome page 和 FreeMarker error pages。

命令 handler 必须仍通过 `DriverService`，不能在 Web 层直接创建 storage/auth client。这样可以让 HTTP contract test 和本地 Web test 穿过同一个 service seam。

## 6. Provider registry 矩阵

每个 Provider 至少执行四类测试：发现、配置、生命周期、最小 operation。发现测试不要求访问真实存储；operation smoke 要在可用的真实或兼容 endpoint 上运行。

| 测试 | 通过条件 |
|---|---|
| 发现 | registry 返回期望名称；同名 factory 失败并指出两个类；缺少依赖失败并指出 Provider |
| 配置 | 最小合法配置可创建 API；缺字段、非法类型、未知 key 的行为与旧版一致 |
| 生命周期 | `init`、operation、`dispose` 顺序正确；关闭后再次调用得到原有错误类型 |
| operation | create/list/get/put/delete 等该 Provider 宣称支持的操作与基线一致 |
| 错误映射 | endpoint、认证、超时、HTTP 4xx/5xx、native 错误能映射到 COSBench 异常 |
| 装载 | 静态 `ServiceLoader` 在启动时发现 SineIO/S3；缺 descriptor、重复名称、工厂初始化失败均有明确诊断；Mock 只在测试运行时发现 |
| 依赖隔离 | 默认共享 classpath；只有 SineIO/S3 smoke 证明存在冲突时，才验收静态 child loader 的 SPI 边界及关闭行为 |
| 发布 | SineIO/S3 JAR、lib、license、checksum 和配置样例随生产归档完整；Mock 和 legacy Provider 不在归档 |

PROV-S3 的必需 list/multipart 由 #12 补齐实际 SDK v1 实现，不能沿用 #04 记录的空结果/no-op 作为通过依据。list 输出 UTF-8 换行对象 key；multipart 验证数据、完成及失败 abort，空对象采用普通 PUT；head 用于对象验证。旧 S3 没有 restore 实现或 region 配置，二者列为不适用并保留源码依据。SDK 版本沿用 #04 冻结的共享 v1 `1.12.767`，签名仍为 `S3SignerType`。

本次垂直链路覆盖：Controller/Driver/core 业务、两侧页面 backend 和测试 classpath 上的 `mock` storage/auth，以及生产包中的 SineIO `sio`/`siov1`/`gdas` 和 S3 `s3`。生产归档必须不包含 Mock。Swift、GCS、OSS、ECS、OpenIO、Scality、CDMI、Librados、Manta 和 ext 不在本次新发行范围，旧版归档继续提供对应的旧运行支持。

## 7. 构建、容器和运行测试

### 7.1 构建

- `mvn -B -ntp clean verify`：空缓存/干净 checkout 可重复；
- 活跃依赖树无未声明 runtime 依赖、重复 class 或 OSGi/Tycho/P2 坐标；root reactor 不引用 `legacy/osgi/`；
- 两个 app 都产出可启动归档，SineIO/S3 descriptor、license 和 checksum 完整；Mock 只在 test profile 可见；
- Java 21 toolchain 与所有活跃模块统一 `maven.compiler.release=21` 在日志和制品中可验证；
- `mvn dependency:tree`、duplicate classes、`jdeps` 和许可证检查作为 CI 闸门。

### 7.2 运行

- 空目录安装后只复制 release 需要的文件，启动不依赖源码树、P2 repository 或 Eclipse workspace；
- 新的 `bin/cosbench-controller`、`bin/cosbench-driver` 和 `bin/cosbench-stop` 能启动/停止进程；旧命令只在迁移兼容期可作为薄包装器；
- 新命令在正常、异常和重复执行时都幂等；多个 Driver 的端口递增规则保持；
- 进程收到 TERM 后，HTTP server、worker executor、Provider client、日志 manager 都能关闭；
- 重启后 archive/history 可读取，旧 workload/archive 目录无需手工重排；旧配置通过迁移工具生成新 YAML，不默默忽略未知或不可映射字段。

### 7.3 Docker/CI

- Java 21 runtime 镜像从空层构建；不复制 `dist/osgi`、Equinox launcher、P2、legacy 或 Mock；
- Controller-only、Driver-only、both 和多个 Driver 场景都有 health check；
- CI 的 test profile 至少执行 Mock workload；正式镜像不包含 Mock。SineIO/S3 release pipeline 执行各自真实或兼容 endpoint smoke；
- 镜像停止不会留下子进程；日志、archive、mission 目录按现有挂载语义工作；
- 旧版本归档和配置可挂载到新镜像，回退演练可复现。

## 8. 性能与资源对比

方案三不以重写 benchmark 热路径为目标，但共享 classpath、Web runtime 和 Provider SDK 可能改变启动和资源行为。阶段 0 固定以下指标：

- workload 完成数、成功/失败数、每 operation 计数；
- 吞吐、p50/p95/p99、错误率、重试次数；
- Controller/Driver CPU、堆、线程数、FD、Provider HTTP connection pool；
- 启动耗时、首次 operation 延迟、停止耗时和残留线程；
- Docker 镜像大小、JAR 数量和启动日志体积。

开发/测试镜像包含测试工具和 MinIO 编排支持；生产镜像不包含 Mock、legacy Provider、源码、P2、Equinox 或开发工具。服务间认证使用独立测试证书或 Docker secret，不能复用生产凭据。

迁移完成前至少进行三次同参数运行；功能结果必须一致，性能阈值由阶段 0 的基线和产品期望共同确定。若出现回归，先定位到 Web、依赖版本、classloader 或业务代码，再决定是否允许优化，不用“换框架”掩盖差异。

<a id="ticket-ownership"></a>

## 9. 验收责任与证据

下表是稳定验收 ID 的定义与唯一责任表；细节以本矩阵相应章节及开发规格为准。[执行索引](../../../.scratch/scheme3-migration/README.md)提供每票链接、阻塞边和旧票映射。首责票实现并提交可重放证据，复验票组合既有成果。每行必需场景均须有输入版本、命令、期望结果、产物及实际结果；不可用或跳过不计通过。

| 验收 ID | 必须交付的行为/证据 | 首责票 | 复验/消费票 |
| --- | --- | --- | --- |
| BASE-01 | 第 1–6 节旧版公开行为、配置边界、Mock/noop 和四个生产 factory 场景索引；基线 archive 与重放命令 | #01 | #03–#07、#19、#22 |
| BUILD-01 | Java 21/release=21、固定 Boot 3.x BOM、Maven/插件、单模块标准 layout/资源的空缓存影子构建 | #02 | #07、#08、#23 |
| ENV-01 | 可复用 Docker/MinIO、确定性测试数据/证书、失败日志与成功/失败清理 | #02 | #03–#06、#19、#22 |
| GATE-CFG | 版本化 YAML、字段/默认值/优先级/范围/secret 合同；代表性旧配置转换、真实 service 启停/TERM、旧 archive 读取 | #03 | #07、#09、#10、#18 |
| GATE-SPI | Mock storage/auth 与 sio/siov1/gdas/s3 各自 discovery/init/operation/dispose，共享 classpath/MinIO 共存证据 | #04 | #07、#10、#11、#12 |
| GATE-WEB | 真实 ControllerService、代表性页面/登录/multipart/下载/静态资源在 Boot/Jakarta 可用 | #05 | #07、#15–#17 |
| GATE-HTTP | 真实 DriverService/Mock 写命令与查询，Token/TLS/池化/超时/重试/幂等及实例重启诊断；冻结新增服务认证与幂等合同 | #06 | #07、#13、#14 |
| CORE-01 | 公共模块、SPI/model/protocol/XML/log/HTTP 兼容，公共模块批次独立 clean verify 与生命周期行为 | #07 | #08–#14 |
| CI-01 | 已迁入闭包的空缓存 verify、dependency tree/convergence、重复类、jdeps、许可证与污染扫描作为失败即阻断的流水线 | #08 | #19、#21、#23 |
| APP-C | Controller 真正 service 装配、配置语义、健康检查、停止新任务及 grace period 内资源关闭 | #09 | #14、#15、#18、#19 |
| APP-D | Driver 装配测试 registry，完整本地 Mock mission 生命周期、健康检查及停止时 dispose/线程退出 | #10 | #13、#17–#19 |
| PROV-SIO | sio/siov1/gdas 分别验证配置、支持的 operation/multipart、错误和释放，生产打包资料齐全 | #11 | #19、#21、#22 |
| PROV-S3 | s3 兼容旧 SDK/配置，支持的 operation/multipart、错误和释放，生产打包资料齐全 | #12 | #19、#21、#22 |
| HTTP-D | 所有八条 /i 命令、业务失败、独立服务认证、进程内幂等结果保存与并发/晚到重放 | #13 | #14、#17、#19 |
| HTTP-C | Controller 调度链路、Token/Session 分离、池化/空闲回收、分层超时、有限重试及响应丢失后恢复 | #14 | #15、#18、#19 |
| WEB-C1 | Controller 登录、提交/配置/高级配置/矩阵、查询/取消/排序/重提及五类 CLI 合同 | #15 | #16、#19 |
| WEB-C2 | Controller 下载、stage/timeline/CSV/Prometheus、错误/welcome/静态资源、proxy context path 与后台任务不中断 | #16 | #19、#22 |
| WEB-D | Driver 页面/表单/下载/静态资源与 /i 在同进程共存，本地调用 DriverService | #17 | #19、#22 |
| OPS-01 | 全量配置转换、四个正式命令、正常/异常/重复执行、PID/端口规则、多 Driver、旧数据读取及开发入口 | #18 | #19、#22、#23 |
| INT-01 | Controller-only、Driver-only、both、多 Driver；Mock 与四个生产 factory 的端到端矩阵、健康/挂载/停止/清理 | #19 | #20、#22、#23 |
| PERF-01 | 第 8 节基线指标、采样方法、参数和比较阈值 | #01 | #20 |
| PERF-02 | 同硬件/参数至少三次迁移版对比，功能计数一致、回归定位和阈值结论 | #20 | #22、#23 |
| PKG-01 | 独立生产归档/镜像、Java runtime、允许的 Provider/依赖、license/checksum/样例及排除扫描 | #21 | #22、#23 |
| REL-01 | 实际生产制品空目录安装/健康/MinIO/重启/旧数据/回退演练、旧包保留；README/BUILD/适配器指南一致 | #22 | #23 |
| FINAL-01 | 根 Maven 入口切换、legacy 归档、活跃 OSGi/Tycho/P2 清理；对最终重建制品重跑受影响验收并关联全部证据 | #23 | 最终发行 |

#03–#06 是范围有限的技术验证；#07 的阻塞边强制四项全部通过。它们可迁入所需的最小现有业务依赖，但不能把全部应用/Web/Provider 迁移隐含在验证票内。后续每票将已经验证的适配整合到唯一生产实现，移除重复的临时适配。

#08 初次只对已迁入的模块及前置验证运行 CI；后续票加入模块时同时扩展流水线，不等待最终集成才发现构建污染。#23 负责收缩后的复验和证据完整性，发现漏项时回到首责票处理，不以笼统的“所有验收通过”代替缺失交付。
