# 方案三模块与 Provider 盘点

状态：方案三实施前的静态盘点，记录旧版 OSGi 仓库结构，供迁移方案回溯。方案三现已进入最终验收阶段，本文不描述当前工作树；文中“当前”均指盘点时的迁移前仓库状态。盘点时的重写范围为 Controller、Driver、core、两侧 Web backend、实际依赖闭包、SineIO 和 S3；页面模板、静态资源和页面行为保留，不做前端重写。Mock 仅用于测试。其他 Provider、Manta、ext 和旧 Tomcat 源码归档到 `legacy/osgi/`，不参加新构建；旧版发行包是这些组件唯一的可运行支持。

## 1. 构建和源码基数

| 项目 | 当前数量 | 说明 |
|---|---:|---|
| 根 Tycho reactor 模块 | 30 | 根 `pom.xml` 中的 `dev/cosbench-*` 模块 |
| `dev/` 下 POM | 31 | 30 个根模块 + 独立构建的 `cosbench-manta` |
| `ext/` 下 POM | 0 | `abc-auth`/`abc-stor` 目前只有 PDE 元数据和源码 |
| OSGi manifest | 32 | 根模块 30 + ext 2；Manta 通过 maven-bundle-plugin 生成，不在仓库中有同名 manifest |
| PDE `build.properties` | 33 | `dev/` 31 + `ext/` 2 |
| Spring context XML | 29 | Controller/Driver、Web、Provider、Manta 和 ext 的全部上下文 |
| Provider `plugin-context.xml` | 19 | 根发行版 16 + Manta 1 + ext 2 |
| `dev/` Java 源码 | 460 | 包含独立 Manta 源码；不含生成的 `release/javadoc` |
| `ext/` Java 源码 | 10 | 两个示例扩展各 5 个类 |
| Web Java 源码 | 50 | Controller Web 26、Driver Web 21、Core Web 3 |
| 模块内嵌 JAR | 173 | 其中 Provider 目录 167 个；SineIO 单模块占绝大多数 |
| 现有测试 Java | 3 | 当前集中在 `cosbench-librados/test`，其它模块几乎没有自动化测试 |

方案三 MVP 不应把 460 个 Java 类全部当作重写目标。约 354 个活跃范围 Java 类需要纳入编译/依赖审计，其中约 50 个 Web 类需要做 Jakarta/Spring backend 适配；其余约 304 个为非 Web 核心/依赖闭包类。页面模板、静态资源和业务算法优先保持原样；实际改动集中在 POM、组合根、Web context/filter、Provider 发现、日志/启动和依赖冲突处理。

## 2. 根 reactor 模块清单

| 模块 | 当前角色 | 方案三处理 |
|---|---|---|
| `cosbench-ampli` | Amplidata storage Provider | 移动到 `legacy/osgi/providers/`；保留 OSGi 源码/元数据，不进入新构建 |
| `cosbench-api` | Storage/Auth SPI、Context、异常 | 普通基础 JAR；保留接口 |
| `cosbench-castor` | Castor fragment | 普通 Castor 依赖模块；删除 Fragment-Host |
| `cosbench-cdmi-base` | CDMI storage Provider/client | 移动到 `legacy/osgi/providers/`；旧版发行包支持 |
| `cosbench-cdmi-swift` | CDMI Swift storage Provider | 移动到 `legacy/osgi/providers/`；旧版发行包支持 |
| `cosbench-cdmi-util` | CDMI utility | 随 CDMI Provider 移到 `legacy/osgi/providers/`；如活跃依赖审计证明仍需要，再纳入新 reactor |
| `cosbench-config` | XML/INI 配置 | 普通基础 JAR；保留配置语义 |
| `cosbench-controller` | Controller 领域服务、调度、归档 | 普通业务 JAR；组合根外置 |
| `cosbench-controller-web` | Controller 页面和 CLI handler | 普通 Maven Web backend；保留 FreeMarker、静态资源、路由和表单，改 Jakarta/Spring wiring |
| `cosbench-core` | benchmark/model/protocol/service 接口 | 普通基础 JAR；热路径优先不改 |
| `cosbench-core-web` | Web 基类和异常 | 普通 Web 基础 JAR；只改 Servlet/Spring 适配 |
| `cosbench-driver` | Driver、mission、agent/operator | 普通业务 JAR；接收 registry factory list |
| `cosbench-driver-web` | Driver 页面和 `/i` command handler | 普通 Maven Web backend；保留页面和协议，改 Jakarta/Spring wiring |
| `cosbench-ecs` | ECS storage Provider | 移动到 `legacy/osgi/providers/`；本次不迁移 SDK |
| `cosbench-gcs` | GCS storage Provider | 移动到 `legacy/osgi/providers/`；本次不迁移 SDK |
| `cosbench-http` | HTTP client utility | 普通基础 JAR；保留请求行为 |
| `cosbench-httpauth` | HTTP auth Provider | 移动到 `legacy/osgi/providers/`；本次不迁移 |
| `cosbench-keystone` | Keystone auth Provider | 移动到 `legacy/osgi/providers/`；本次不迁移 |
| `cosbench-librados` | Ceph/Librados storage Provider | 移动到 `legacy/osgi/providers/`；本次不迁移 native/JNA |
| `cosbench-log` | 日志接口和实现 | 普通日志 JAR；移除 Activator |
| `cosbench-log4j` | Log4j fragment | 普通日志实现或合并到 log；删除 Fragment-Host |
| `cosbench-mock` | Mock storage + auth Provider | 仅 test profile/classpath；不打入生产包 |
| `cosbench-openio` | OpenIO storage Provider | 移动到 `legacy/osgi/providers/`；本次不迁移本地 SDK |
| `cosbench-oss` | Aliyun OSS storage Provider | 移动到 `legacy/osgi/providers/`；本次不迁移 SDK |
| `cosbench-s3` | 旧 AWS S3 storage Provider | 普通 Provider JAR；保留旧 SDK 直到契约通过 |
| `cosbench-scality` | Scality/Sproxyd storage Provider | 移动到 `legacy/osgi/providers/`；本次不迁移 |
| `cosbench-sineio` | SIO/SIOv1/GDAS storage Provider | 普通 Provider JAR；三项 production SPI 注册都必须保留 |
| `cosbench-swauth` | Swift auth Provider | 移动到 `legacy/osgi/providers/`；本次不迁移 |
| `cosbench-swift` | Swift storage Provider | 移动到 `legacy/osgi/providers/`；本次不迁移 |
| `cosbench-tomcat` | OSGi Tomcat loader/register | 移动到 `legacy/osgi/runtime/`；新应用由 Spring Boot embedded container 托管 |

## 3. 独立和外部模块

| 模块 | 当前状态 | 方案三最小处理 |
|---|---|---|
| `dev/cosbench-manta` | 独立 Maven `bundle` 构建，6 个 Java 类，含 OSGi Activator | 移动到 `legacy/osgi/providers/`；保留原 POM/Activator/metadata，不参加新构建 |
| `ext/adaptor/abc-auth` | 无 Maven POM，5 个 Java 类，auth `plugin-context` | 移动到 `legacy/osgi/providers/ext/`；保留源码和 OSGi metadata，不新增 Maven/SPI |
| `ext/adaptor/abc-stor` | 无 Maven POM，5 个 Java 类，storage `plugin-context` | 移动到 `legacy/osgi/providers/ext/`；保留源码和 OSGi metadata，不新增 Maven/SPI |

## 4. Provider 注册清单

当前 OSGi 注册共 22 项：根发行版 19 项、Manta 1 项、ext 2 项。下表是仓库现状清单；只有 SineIO 三项和 S3 一项进入 production SPI，Mock 两项只进入测试 classpath，其余注册只留在 `legacy/osgi/`。

| 类型 | 名称 | Factory 类 | 当前模块 | 本次处理 |
|---|---|---|---|---|
| storage | `ampli` | `com.intel.cosbench.api.amplistor.AmpliStorageFactory` | `cosbench-ampli` | legacy，仅旧版发行包运行 |
| storage | `cdmi` | `com.intel.cosbench.api.cdmi.base.CDMIStorageFactory` | `cosbench-cdmi-base` | legacy，仅旧版发行包运行 |
| storage | `cdmi_swift` | `com.intel.cosbench.api.cdmiswift.CDMIStorageFactory` | `cosbench-cdmi-swift` | legacy，仅旧版发行包运行 |
| storage | `ecs` | `com.emc.vipr.cosbench.ECSStor.ECSStorageFactory` | `cosbench-ecs` | legacy，仅旧版发行包运行 |
| storage | `gcs` | `com.intel.cosbench.api.GcsStor.GcsStorageFactory` | `cosbench-gcs` | legacy，仅旧版发行包运行 |
| auth | `httpauth` | `com.intel.cosbench.api.httpauth.HttpAuthFactory` | `cosbench-httpauth` | legacy，仅旧版发行包运行 |
| auth | `keystone` | `com.intel.cosbench.api.keystone.KeystoneAuthFactory` | `cosbench-keystone` | legacy，仅旧版发行包运行 |
| storage | `librados` | `com.intel.cosbench.api.librados.LibradosStorageFactory` | `cosbench-librados` | legacy，仅旧版发行包运行 |
| storage | `mock` | `com.intel.cosbench.api.mock.MockStorageFactory` | `cosbench-mock` | test profile，不进入生产包 |
| auth | `mock` | `com.intel.cosbench.api.mock.MockAuthFactory` | `cosbench-mock` | test profile，不进入生产包 |
| storage | `openio` | `com.intel.cosbench.api.openio.OpenIOStorageFactory` | `cosbench-openio` | legacy，仅旧版发行包运行 |
| storage | `oss` | `com.intel.cosbench.api.oss.OSSStorageFactory` | `cosbench-oss` | legacy，仅旧版发行包运行 |
| storage | `s3` | `com.intel.cosbench.api.S3Stor.S3StorageFactory` | `cosbench-s3` | production SPI；旧 AWS SDK 保持并验证 |
| storage | `sproxyd` | `com.scality.cosbench.api.SproxydStorageFactory` | `cosbench-scality` | legacy，仅旧版发行包运行 |
| storage | `sio` | `com.intel.cosbench.api.sio.SIOStorageFactory` | `cosbench-sineio` | production SPI；AWS SDK v2 |
| storage | `siov1` | `com.intel.cosbench.api.sio.SIOStorageFactoryV1` | `cosbench-sineio` | production SPI；AWS SDK v1 |
| storage | `gdas` | `com.intel.cosbench.api.gdas.GdasStorageFactory` | `cosbench-sineio` | production SPI；AWS SDK v1 |
| auth | `swauth` | `com.intel.cosbench.api.swauth.SwiftAuthFactory` | `cosbench-swauth` | legacy，仅旧版发行包运行 |
| storage | `swift` | `com.intel.cosbench.api.swift.SwiftStorageFactory` | `cosbench-swift` | legacy，仅旧版发行包运行 |
| storage | `manta` | `com.joyent.manta.cosbench.MantaStorageFactory` | `cosbench-manta` | legacy，仅旧版发行包运行 |
| auth | `abcauth` | `com.abc.api.abcAuth.AbcAuthFactory` | `ext/adaptor/abc-auth` | legacy，仅旧版发行包运行 |
| storage | `abcstor` | `com.abc.api.abcStor.AbcStorageFactory` | `ext/adaptor/abc-stor` | legacy，仅旧版发行包运行 |

生产发行包的 SineIO/S3 Provider JAR 使用 `META-INF/services/*Factory`。Mock 的 descriptor 只由测试构建提供。SineIO 不能只登记 `sio`：`siov1` 和 `gdas` 也是本次支持项，缺少任何一个都会改变现有配置可用性。

## 5. 本次迁移范围

| 范围 | 模块/注册项 | 本次处理 |
|---|---|---|
| 正式 Provider | `cosbench-sineio`：`sio`、`siov1`、`gdas` | 普通 Maven JAR、静态 `ServiceLoader`、SineIO workload smoke |
| 正式 Provider | `cosbench-s3`：`s3` | 普通 Maven JAR、静态 `ServiceLoader`、S3 配置/operation smoke |
| 测试 Provider | `cosbench-mock`：`mock` storage/auth | 只进入 test profile/classpath，用于 registry 和端到端测试，不打入生产发行包 |
| 应用层 | `cosbench-controller`、`cosbench-driver`、两个 Web 模块、`core-web` | Spring Boot 3/Spring 6/Jakarta；保留模板、静态资源、路由、表单和 `/i` 协议，改后端装配 |
| 公共基础 | `api`、`castor`、`config`、`core`、`http`、`log`、`log4j` 等 | 普通 Maven 化；以活跃模块的编译依赖闭包最终确认 |
| legacy Provider | 其余 13 个根 Provider、Manta、`ext/adaptor` | 源码和旧 OSGi metadata 保留于 `legacy/osgi/providers/`；不进入新 reactor/新发行包，只有旧版归档可运行 |
| legacy runtime | `cosbench-tomcat` | 源码和 metadata 保留于 `legacy/osgi/runtime/`；新应用使用 Boot embedded server |

## 6. OSGi 和 Spring 资源清单

### 5.1 活跃模块与 legacy 的 OSGi 入口边界

- 当前统计的 32 个 `META-INF/MANIFEST.MF` 中，活跃模块的 manifest 最终由 Maven module/dependency 取代，迁移中可作为依赖清单参考；legacy Provider/runtime 的 manifest 保留；
- 当前统计的 33 个 `build.properties` 中，活跃模块的配置由标准 Maven layout 取代；legacy 目录中的文件保留；
- `dev/cosbench-log/src/com/intel/cosbench/log/osgi/Activator.java`：日志初始化移入 app main；
- 活跃源码中的 `org.osgi.*` import、`Bundle-Activator`、`Fragment-Host`、`Require-Bundle` 和 `Bundle-ClassPath` 删除或替代；`legacy/osgi/` 中原样保留；
- `cosbench-tomcat` 和 Manta 的 activator/register 源码移动到 legacy，不改造成新应用组件。

### 5.2 Spring context 迁移

| 上下文 | 当前作用 | 方案三处理 |
|---|---|---|
| `controller-context.xml` | 创建 Controller factory 和 service | 保留普通 bean；由 app parent context 加载 |
| `controller-osgi-context.xml` | 发布 `ControllerService` OSGi service | 删除；由同一 ApplicationContext 暴露 bean |
| `driver-context.xml` | 创建 auth/storage service 和 Driver factory | 保留普通 bean；factory list 来自 registry |
| `driver-osgi-context.xml` | OSGi list/reference/service | 删除；由普通 bean wiring 替代 |
| Controller Web 2 个 `*-web-context.xml` | 页面和视图 | 保留路由、handler 和模板 |
| Controller `controller-handler-context.xml` | `/cli/*.action` | 保留路由和 handler |
| Controller `controller-web-osgi-context.xml` | `<osgi:reference controller>` | 改为 parent bean 或删除 |
| Driver Web 2 个 `*-web-context.xml` | 页面和视图 | 保留路由、handler 和模板 |
| Driver `driver-handler-context.xml` | `/i/*.command` | 保留路由和 handler |
| Driver `driver-web-osgi-context.xml` | `<osgi:reference driver>` | 改为 parent bean 或删除 |
| SineIO/S3 `plugin-context.xml` | Bean + `<osgi:service>` | 保留可迁移 bean 定义，删除 OSGi service 并增加 production SPI descriptor |
| Mock `plugin-context.xml` | Mock bean + `<osgi:service>` | 只为测试 profile 增加 test SPI descriptor，不打包进生产归档 |
| 其他 Provider/Manta/ext `plugin-context.xml` | Bean + `<osgi:service>` | 移入 legacy 原样保留，不增加 SPI 或新发行支持 |

## 7. 高风险依赖热点

当前 Provider 目录包含约 167 个内嵌 JAR。只有 SineIO 和 S3 属于本次依赖验收范围；其他行是 legacy 盘点，不迁入新 classpath：

| 模块 | 主要风险 | 首选处理 |
|---|---|---|
| `cosbench-sineio` | AWS SDK v1/v2、Netty、Jackson、Log4j/SLF4J、source JAR 大量共存 | 先验证共享 classpath；有可复现冲突时才用静态 child classloader；先不升级 SDK |
| `cosbench-s3` | AWS SDK 1.10、Jackson 1/2、HTTP client、AspectJ | 与 SineIO 做依赖树和契约测试；只有冲突复现时再隔离 |
| `cosbench-gcs` | 老 Google client、Guava、Jackson、HTTP client | legacy；不迁入新 classpath |
| `cosbench-ecs` | Jersey 1、VIPR client、旧 HTTP/JDOM | legacy；不迁入新 classpath |
| `cosbench-oss` | Aliyun SDK、HTTP/JDOM/commons 旧版本 | legacy；不迁入新 classpath |
| `cosbench-openio` | 本地 openio API/Gson | legacy；不迁入新 classpath |
| `cosbench-librados` | JNA/rados native | legacy；不迁入新 classpath |

对本次活跃的 SineIO/S3，先用 Maven dependency tree 和 smoke 验证共享 classpath；只有出现真实冲突才增加静态 child classloader。依赖升级、SDK 替换不是移除 OSGi 的自动步骤。legacy Provider 的 classloader 风险保留在旧版支持说明，不在本次解决。
