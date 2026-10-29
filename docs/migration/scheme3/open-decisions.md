# 方案三待决策清单

状态：MVP 主要方向已确认。页面和模板保留、Web backend 迁移的边界已确认；Java 21、Docker/MinIO 测试、REST/HTTP 合同和依赖升级边界均已确认；已确认项作为本次方案三的输入，不再作为实现前置问题。

## D1. 支持范围

**已确认：** 新发行版支持 Controller、Driver、Web，以及 SineIO（`sio`、`siov1`、`gdas`）和旧 S3（`s3`）Provider；Mock 仅在测试构建、registry 和端到端验证中使用，不进入生产包。其它根 Provider、Manta、`ext/adaptor` 和旧 Tomcat 源码/OSGi metadata 保留在 `legacy/osgi/`，只有旧版发行包提供可运行支持。

**选择影响：**

- 选择当前发行版范围：最少的依赖冲突和真实 endpoint smoke，最快得到可运行版本；
- 将所有 Provider 都迁入新包会扩大依赖、许可和 endpoint smoke 范围；当前决策明确不做这项工作；
- 选择先迁移 Controller/Driver/Web：只能称为分阶段技术版本，不能称为完整方案三。

**状态：** 已确认。后续文档和验收只把上述范围称为本次方案三的行为等价范围。

## D2. 是否保留 Spring

**已确认：** 使用 Spring Boot 3 作为两个应用的启动和打包层，底层采用 Spring Framework 6/Jakarta；Controller/Driver 页面继续迁移到新 app，保留 FreeMarker 模板、静态资源、页面结构、路由和表单，只改 Java Web backend、Spring wiring、Servlet namespace 和容器配置。

方案三不再使用 Spring DM、Spring OSGi、Equinox、Tycho/P2 或旧 Tomcat 6。若采用 Spring Boot Web starter，Tomcat 由 Boot 作为 embedded runtime 管理，不需要单独维护 `cosbench-tomcat`、Catalina OSGi 注册或旧 Tomcat XML；Jetty/Undertow 仅作为需要时的替代实现。

**选择影响：**

- 保留 Spring：现有 `controller-context.xml`、`driver-context.xml`、Web bean wiring、FreeMarker 和大量 setter 注入可复用，页面不需要重做，Java backend 改动最少；
- 完全移除 Spring：需要重写 47 个 Web 类的 handler 装配、DispatcherServlet/视图初始化和配置，改动量、测试面和风险都会显著增加；
- 先保留旧 Spring API：可以作为过渡，但最终仍需单独锁定支持的 Spring/Web 版本。

**状态：** 已确认。这里的“保留 Spring”具体指 Spring Boot 3 + Spring Framework 6，不保留 Spring DM 或任何 OSGi Web context；两侧 Web backend 随 app 一起迁移。

## D3. Provider 装载和隔离

**已确认：** 启动时静态 `ServiceLoader`，新增/删除 Provider 需要重新打包和重启，不支持运行期热安装/卸载。SineIO/S3 的依赖冲突先通过 Maven 依赖树和 smoke 暴露，必要时再做静态依赖隔离。

真实对象存储 smoke 使用 Docker 中的 MinIO；生产 SineIO/S3 endpoint、凭据和 CI secret 不写入仓库。MinIO 只用于兼容性和回归验证，不改变发行版 Provider 支持范围。

**选择影响：**

- SineIO 与 S3 共享 classpath：装载实现最简单，但两边 AWS SDK、Jackson 和 HTTP client 版本需要通过依赖树和真实 smoke 验证；
- 若共享 classpath 存在可复现冲突，再加随应用发布、启动时创建的 Provider child classloader；
- 外部 Provider 目录：本次不做；会额外要求 API 版本、来源信任、权限、错误诊断和升级说明；
- 运行期热安装/卸载：不是 `ServiceLoader` 的直接替代，需要重新定义生命周期和 classloader 回收，超出最小改动范围。

**状态：** 已确认。接受“启动时加载 + 重启生效”。

## D4. Web namespace 和框架版本

**已确认：** 直接迁移到 Jakarta Servlet/Spring 6，使用 Spring Boot 3；接受 Web 源码和部署配置增加，同时保持现有 URL、表单和响应合同。

**选择影响：**

- 保持 `javax.servlet`：可以复用现有 import、`web.xml`、controller、filter 和 FreeMarker 配置，源码改动最少；
- 直接 Jakarta/Spring 6：长期支持面更清晰，但会影响所有 Servlet import、Web 依赖、XML schema、容器版本和安全配置；
- 两个 Web 栈都做 spike：前期多一小段验证，但能用真实页面/上传/下载结果决定，而不是凭依赖名猜兼容性。

**状态：** 已确认。Jakarta 迁移属于本次方案三 Web 范围，不再作为后续可选阶段。

## D5. 目录、配置和启动入口

**已确认：** 同时整理为标准 `src/main/java`、`src/main/resources`、`src/test/java`，统一新配置格式和新命令；旧入口只在迁移窗口内保留包装器。

**选择影响：**

- 保留入口：兼容现有自动化和运维脚本，POM 配置略复杂但迁移风险最低；
- 一次性整理 `src/main/java`/新配置：文档更现代，但会产生大量无行为价值的路径和脚本 diff；
- 新旧入口并存：可给用户迁移期，但增加发布和回退测试。

**状态：** 已确认。最终合同是新目录、版本化 YAML 和新命令；旧配置通过 `cosbench-config-migrate` 转换。旧命令包装器只作为可选的短期兼容层，不是新发行版合同。

## D6. Java 21 字节码目标

**已确认：** Java 21 LTS 用于构建、测试和运行，所有活跃模块统一 `maven.compiler.release=21`。低版本 bytecode 只存在于历史归档，不再作为本次实施选择。

**实施责任：** 工具链票根据官方系统要求锁定 Boot 3.x 补丁/BOM、Maven 和插件版本，并留下 Java 21 构建与启动证据；后续 CI 阻止版本漂移。不自动升级到 Boot 4。

版本核对入口为 [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)。具体补丁值以 #02 实施时核对的官方说明与验证结果固定。

**状态：** 已确认。此前保留的“按模块选择最低 target”建议已撤销。

## D7. 其他 Provider 的源码与支持边界

**已确认：** 除 SineIO 和 S3 外，其他根 Provider、Manta、`ext/adaptor` 不进入新发行包；Mock 只用于测试。非目标源码和 OSGi 元数据移到 `legacy/osgi/` 并保留，旧版发行包是它们唯一的可运行支持。

**选择影响：**

- 活跃构建边界缩到 Controller/Driver/Web、SineIO、S3 和所需基础模块；不迁移无关 Provider 的依赖树；
- legacy 目录保留源码和原 OSGi metadata，但不进入 root reactor 或 Docker/发行包；
- 新发行说明需明确旧包仍支持 legacy Provider，新包不支持这些 Provider。

**状态：** 已确认。后续若恢复某个 Provider 支持，作为单独迁移批次重新评估。

## D8. 本轮暂不升级的依赖

**已确认：** 以下依赖先保持行为兼容，记录为后续独立升级项，不与方案三基础迁移混批：Jackson 1.x 到 2.x、Castor XML 到 Jackson XML/JAXB、旧 AWS SDK 到 AWS SDK for Java 2.x、SineIO SDK 大版本升级，以及可能改变 wire contract 的 HTTP/序列化库升级。每项后续都必须有独立契约、真实对象存储 smoke 和回退方案。

## D9. 服务间 REST 认证与长时间压测

**已确认：** Controller 到 Driver 使用独立服务间认证，不复用页面 Session；默认采用 HTTPS/TLS + Token。压测期间 Token 不得自然过期；若采用过期 access token，必须有无感 refresh 机制，不能中断正在运行的 workload。Token 只从环境变量、Docker secret 或受保护配置读取，不写入 workload、日志或镜像。

服务间 HTTP 使用连接池和 keep-alive；连接、读取、命令和 workload 超时分层配置。仅对连接失败、临时网络错误和明确的 5xx 做有限重试，写操作使用 request ID/idempotency key 防止重复执行。停止时先停止接收新任务，再在 grace period 内等待 worker、HTTP 请求、Provider client 和日志系统关闭。

生产环境由反向代理或网关承担对外 TLS；Controller 到 Driver 的内部链路也支持 HTTPS。开发和 Docker smoke 使用独立测试证书。MVP 优先使用可配置长生命周期 Token，不引入 OAuth2/OIDC 服务；若后续采用短期 access token，必须增加无感 refresh。

HTTP 连接池、空闲连接回收、Provider 并发和重试上限全部进入 YAML 配置，并在启动时校验范围。Controller 为写操作生成 request ID/idempotency key 并跨重试保留，Driver 在进程生命周期内保存已处理结果。#06 验证并冻结身份作用域、并发/晚到重放、同键不同内容和失败结果的规则。MVP 不承诺跨重启恢复幂等原响应；Driver 对每进程发布实例 ID，拒绝携带旧实例 ID 的写请求并诊断为结果未知，避免新进程重复执行歧义写。若需持久去重和恢复原响应，另行明确合同与数据方案。

## D10. 发布与运维合同

**已确认：** 新版本仍为两个独立 Spring Boot 进程，Controller/Driver 页面随对应进程提供，Controller 通过 REST/HTTP 调用 Driver。保留 workspace、archive、mission、日志和配置目录语义；新版本不自动删除或重排旧数据，旧配置通过迁移工具生成 YAML 并保留原文件。

生产镜像与开发镜像分开构建。生产镜像不包含 Mock、legacy Provider、源码、P2、Equinox 或开发工具；开发镜像用于测试工具和 MinIO 编排。生产镜像仅包含 Java 21 runtime、应用、必要依赖、配置模板和证书挂载点。

## 3. 必做技术验证

[开发规格](development-spec.md)第 7 节定义四项前置验证；[执行索引](../../../.scratch/scheme3-migration/README.md)将其落实为 #03 配置/生命周期、#04 Provider 共存、#05 Web、#06 HTTP。#02 先提供最小 Java 21 Maven/Docker/MinIO 环境；#03–#06 全部通过后，#07 才能展开公共模块迁移。

验证使用有限的现有业务依赖和真实 service/SPI，不要求先完成两个生产应用或全部页面。每项保留命令、fixture、依赖树、生成物、日志及已验证的合同。失败或必需 endpoint 不可用时，相关 blocker 保持未通过；后续批次不得把假设当作已验证事实。

实现参考：[Spring MVC reference](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc.html)、[JDK 21 ServiceLoader](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/ServiceLoader.html)、[Maven standard layout](https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html)。以实际锁定版本的兼容结果为准。

## 4. 当前暂定结论

主方案按以下已确认值继续细化：

- Controller、Driver、core、两侧 Web backend、SineIO、S3 为新发行版支持范围；FreeMarker/静态资源/页面合同保留，Mock 仅用于测试且不进入生产归档；其他 Provider/Manta/ext 在 `legacy/osgi/`，只有旧版发行包提供运行支持；
- Spring Boot 3 + Spring Framework 6/Jakarta，移除 Spring DM/OSGi；
- 启动时静态 `ServiceLoader`，冲突 Provider 先做依赖验证；
- Web 保持既有 URL/表单/响应合同，Servlet namespace 迁到 Jakarta；
- 活跃源码整理到标准 Maven 目录，配置统一为版本化 YAML，并提供旧配置转换命令；使用新启动/停止命令；
- 两个薄 app/composition-root 模块，业务模块不大规模合并；
- JDK 21 构建、测试、运行和统一 release=21 已确认；补丁版本、YAML 字段映射及 HTTP 新增认证/幂等细节由前置验证留下可运行合同。
