# 方案三迁移改造范围估算

状态：方案三实施前的范围估算。方案三现已进入最终验收阶段；本文数字描述规划时的源码布局和预期工作量，不代表当前工作树或最终变更范围。文中“当前仓库”均指迁移前的源码基线。

本文保留方案二对比章节，并以方案三 MVP 的数字作为开发估算基线。方案三范围为 Controller、Driver、core、实际依赖闭包、两侧 Web backend、SineIO/S3；保留 FreeMarker/静态资源/页面合同。两者支持范围不同，文件数不能直接作为同范围的投入对比。数字用于规划工作量，不是最终变更文件清单。

## 1. 估算口径

本估算中的“最小化改动”有以下含义：

- 保留 Controller、Driver、workload/mission 生命周期和 benchmark operation 语义，不主动引入虚拟线程或新的业务能力。
- 保留现有 workload XML、Controller CLI、Controller 到 Driver 的 `/i/*.command` 协议、Web 路径、认证、归档、日志/配置下载和 Prometheus 指标行为。
- 方案二沿用当前根 `pom.xml` 的 30 个 Tycho bundle 估算；方案三 MVP 的新发行版包含 Controller、Driver、core、两侧 Web backend、SineIO、S3 及必要基础模块，Mock 仅用于测试。其余 Provider、Manta、ext 和旧 Tomcat 源码归档到 `legacy/osgi/`，不进入新构建；对应旧版发行包是唯一的可运行支持。
- “完整完成”包括兼容测试、干净构建、启动/停止、Docker/发布和回退检查，不只统计能编译的源码。
- 数量不包含 `release/javadoc` 等生成文档；已提交的 P2/发行包二进制会单独说明。

当前仓库的几个基数：

- 根 Tycho reactor 有 30 个模块；开发目录另有独立的 Manta 模块。
- `dev/` 和 `ext/` 共 32 个 OSGi manifest，33 个 `build.properties`/PDE 构建元数据文件。
- 当前仓库共盘点 16 个根 Provider、2 个 ext Provider 和 1 个独立 Manta；方案三只改造 SineIO、S3，Mock 仅测试，其余保留 legacy。
- Controller Web 和 Driver Web 合计 47 个 Java 类、46 个模板/静态资源文件。前者包含 20 个页面 Controller 和 6 个 CLI handler，后者包含 11 个页面 Controller 和 10 个 Driver 命令 handler。

## 2. 方案二：保留 OSGi，拆出独立 Web

方案二保留 Equinox、Controller/Driver 核心逻辑和 bundle 级类加载隔离；Spring DM 服务装配改为 DS，新增引擎侧 HTTP API adapter，两个 UI 变成独立 Java 21 应用。UI 只能访问版本化 DTO，不能继续依赖 `ControllerService`、`DriverService` 或 `WorkloadInfo` 等引擎内部类型。[方案二定义](../runtime-modernization.md#方案二osgi-引擎--独立-java-21-web-应用)

### 2.1 必改模块和文件

| 区域 | 路径 | 改造内容 | 大致规模 |
|---|---|---|---:|
| Controller 装配 | `dev/cosbench-controller` | `META-INF/spring/controller-context.xml`、`controller-osgi-context.xml` 改为 DS 生命周期；调整 `COSBControllerServiceFactory`，必要时调整 `COSBControllerService`；更新 MANIFEST/POM/build metadata | 约 8~15 个文件 |
| Driver 装配 | `dev/cosbench-driver` | `driver-context.xml`、`driver-osgi-context.xml` 改为 DS；调整 `COSBDriverServiceFactory`、`COSBDriverService`、`COSBAuthAPIService`、`COSBStorageAPIService` 的多 provider 绑定和排序 | 约 10~18 个文件 |
| 公共契约 | `dev/cosbench-core`、`dev/cosbench-api` | 保留 `ControllerService`、`DriverService`、`StorageAPIFactory`、`AuthAPIFactory` 语义；仅在 DTO/协议需要时增加导出或兼容类型 | 约 0~8 个文件 |
| 适配器 DS | 根发行版的 16 个 provider 模块 | 每个 `META-INF/spring/plugin-context.xml` 替换为 DS component descriptor 或注解；更新 MANIFEST/build metadata。SineIO 的 3 个 storage factory、Mock 的 auth/storage factory 都必须完整注册 | 约 45~65 个文件 |
| 外部适配器 | `ext/adaptor/abc-auth`、`ext/adaptor/abc-stor` | 若承诺兼容 custom adaptor，同样迁移 service descriptor；否则在迁移说明中明确暂缓 | 约 5~8 个文件 |
| 引擎 API | 新增 Controller API bundle、Driver API bundle，建议增加共享 wire DTO 模块 | 提供 `/api/v1`、错误 DTO、文件流、认证、限制、生命周期；通过 DS 引用核心 service，不依赖实现类 | 新增约 4~5 个模块、30~55 个源码/测试文件 |
| Controller 兼容路由 | 原 `dev/cosbench-controller-web/src/.../handler` | 迁移 6 个 handler，继续支持 `/cli/index.action`、`submit.action`、`config.action`、`workload.action`、`cancel.action` | 6 个 Java 类加路由配置 |
| Driver 兼容路由 | 原 `dev/cosbench-driver-web/src/.../handler` | 迁移 10 个 handler，继续支持 `/i/ping.command`、`submit.command`、`login.command`、`launch.command`、`query.command`、`close.command`、`abort.command`、`trigger.command` | 10 个 Java 类加路由配置 |
| Controller UI | 新增 `web/controller-ui` Maven 应用 | 迁移 20 个页面 Controller；加入 `HttpControllerClient`、Web view model、认证和 fake client；复用 21 个模板、8 个资源和静态入口 | 约 35~50 个新/改文件 |
| Driver UI | 新增 `web/driver-ui` Maven 应用 | 迁移 11 个页面 Controller；加入 `HttpDriverClient`、Web view model、认证和 fake client；复用 11 个模板、3 个资源和静态入口 | 约 25~40 个新/改文件 |
| Web 公共层 | `dev/cosbench-core-web` | `AbstractController`、异常处理和 Servlet/Spring 依赖迁入普通 Web 应用，或在迁移周期内保留为回退模块 | 约 3~8 个文件 |
| 旧 Web/Tomcat | `dev/cosbench-controller-web`、`dev/cosbench-driver-web`、`dev/cosbench-tomcat` | 从 `config.ini` 和最终发行包移除；迁移期间可保留旧包用于回退 | 约 3 个模块，包含删除/停用文件 |
| 配置和启动 | `release/conf/.controller/config.ini`、`.driver/config.ini`、Tomcat XML、`release/start-*`、`cosbench-start.sh`、`dev/cosbench-dev.*` | 去掉 Spring DM Web extender/Tomcat 绑定，加入 DS/API/UI 端口、后端 URL、认证和停止逻辑 | 约 15~25 个文件 |
| 构建、Docker、发布 | `pom.xml`、`pack*.sh`、`ci/build-tycho.sh`、`.github/workflows/*`、`ci/docker/*`、`BUILD*.md` | Tycho 继续构建 OSGi；UI 独立 Maven reactor；镜像和归档同时发布引擎与 UI；基础 JRE 改为 Java 21 | 约 15~30 个文件 |

根发行版中需要处理的 16 个 provider 模块是：`cosbench-ampli`、`cosbench-cdmi-base`、`cosbench-cdmi-swift`、`cosbench-ecs`、`cosbench-gcs`、`cosbench-httpauth`、`cosbench-keystone`、`cosbench-librados`、`cosbench-mock`、`cosbench-openio`、`cosbench-oss`、`cosbench-s3`、`cosbench-scality`、`cosbench-sineio`、`cosbench-swauth`、`cosbench-swift`。`cosbench-manta` 不在根 Tycho reactor 中，需要单独决定是否纳入；`ext/adaptor/abc-auth` 和 `ext/adaptor/abc-stor` 属于外部适配器。

### 2.2 数量结论

- 新增模块：约 4~5 个，通常是 2 个 API adapter、1 个共享 wire DTO 模块、2 个独立 UI 应用；也可以把 DTO 合并进 adapter，减少 1 个模块。
- 需要实质审阅或改写的 Java 类：约 60~100 个。主要来自 47 个旧 Web 类、核心 service 装配、API/DTO/client 和测试；storage operation、worker、operator、generator 热路径大部分不动。
- DS descriptor、manifest、Spring context、配置和脚本：约 60~100 个文件。
- 包含新增 UI、兼容层、测试、发布配置以及旧模块删除/停用的最终源码 diff：约 150~220 个文件。
- `build/p2/repository` 和 `dist/osgi` 中已提交的依赖包会删除 Spring DM/Tomcat 6、加入 DS/HTTP server 依赖，二进制变更另计。

如果只做 SineIO 的第一条垂直链路，可以先转换 `cosbench-sineio`、`cosbench-mock` 和实际启用的认证模块，适配器注册部分约为完整范围的三分之一；这不能作为所有发行版 provider 已完成的结论。

### 2.3 方案二明确不需要大规模改动的部分

- `dev/cosbench-controller` 的 workload 调度、归档、exporter 和 tasklet 逻辑原则上保留。
- `dev/cosbench-driver` 的 `agent/`、`operator/`、`generator/`、`MissionHandler` 和 storage API 调用循环原则上保留。
- 各存储适配器的 Java SDK 调用实现原则上保留，只改 service 注册和生命周期元数据。
- `dev/cosbench-log` 的 OSGi activator 在方案二可以暂留；它不是方案二的第一批必改项。

## 3. 方案三：普通 Java 应用与受限 Provider 范围

方案三 MVP 把 Controller、Driver、core、两侧 Web backend、SineIO、S3 和构建体系转成普通 Java 应用。两个 app 使用 Spring Boot 3/Spring 6/Jakarta；保留可迁移的 Spring XML Bean、FreeMarker、静态资源、页面路由和表单。页面不重做，只迁移 Java Web backend 和容器装配；Controller 到 Driver 的 `/i/*.command` 链路保持不变。非目标 Provider 的源代码及 OSGi metadata 移到 `legacy/osgi/`，不进新 reactor/发行包；旧归档继续提供旧支持。源码与配置采用标准 Maven/YAML/新命令。关键工作量来自 Maven 依赖图、Jakarta Web backend、组合根、配置转换和发布入口，而不是页面重写或业务算法重写。[方案三实施步骤](detailed-plan.md)

### 3.1 必改模块和文件

| 区域 | 路径 | 改造内容 | 大致规模 |
|---|---|---|---:|
| 活跃 Maven reactor | 根 `pom.xml`、约 14 个现有核心/Web/Provider 模块 POM、2 个新 app POM | 只保留 API、核心、配置、Controller/Driver/Web backend、日志、SineIO/S3；Mock 在 test profile；Tycho/P2 移除，Spring Boot 依赖和 Java toolchain 显式化 | 约 20~30 个构建文件 |
| 活跃源码布局 | 选定模块的 `src/`、`WEB-INF/`、测试目录 | 改为 `src/main/java`、`src/main/resources`、`src/test/java`；包名和业务模块边界不动 | 约 450 条源码/资源路径调整，主要是 rename |
| legacy 源码归档 | 13 个非目标根 Provider、Manta、ext、旧 Tomcat | 移到 `legacy/osgi/providers/` 或 `legacy/osgi/runtime/`；保留 OSGi metadata 和代码，不转普通 JAR，不进入 reactor/发行包 | 当前约 304 条已跟踪路径移动，其中约 107 个 Java 源文件；不计为代码改写 |
| 活跃 OSGi/PDE 元数据 | 活跃模块的 `META-INF/MANIFEST.MF`、`build.properties`、root `pom.xml` | 用普通 Maven dependency/resource 配置取代活跃模块元数据；legacy 中相同文件保留 | 约 30~40 个删除/修改文件 |
| Spring 组合根和日志 | Controller/Driver Spring context、`cosbench-log`、新增两个 app | 保留兼容 bean 定义；增加 main、YAML 配置、日志初始化、shutdown 和直接 service wiring；移除活跃 Spring DM/OSGi wiring | 约 25~40 个文件 |
| Provider SPI/registry | `cosbench-api`、`cosbench-sineio`、`cosbench-s3`、Driver service | 生产静态 SPI 注册 SineIO 三个 factory 和 S3 一个 factory；Mock 两个 factory 只放 test profile；registry 管理名称、错误和生命周期 | 约 12~22 个源码/descriptor/test 文件 |
| Jakarta Web backend | Controller Web、Driver Web、Core Web、`web.xml`、Spring XML/FreeMarker | 迁移 47 个 Controller/Driver Web 类和 3 个 core-web 类中必要的 Servlet API、Spring 6/Jakarta 配置；保留模板、静态资源、路由、表单和响应，不做页面重写 | 约 50~75 个内容变更文件，模板大多复用 |
| 新配置和命令 | `release/conf/*`、`release/start-*`、`pack*.sh`、Docker、CI、开发脚本 | 新 `controller.yml`/`driver.yml`、旧配置转换、新启动/停止命令；更新打包、运行时镜像和构建说明 | 约 25~45 个文件 |
| SineIO/S3 依赖 | 两个活跃 Provider、现有本地 JAR、依赖树 | 保留 SDK 行为并显式化 Maven 依赖；先尝试共享 classpath，仅在验证到冲突后增加静态启动隔离 | 约 10~25 个 POM/打包/测试文件 |

当前候选新 reactor 约有 14 个现有模块：`cosbench-api`、`cosbench-castor`、`cosbench-config`、`cosbench-controller`、`cosbench-controller-web`、`cosbench-core`、`cosbench-core-web`、`cosbench-driver`、`cosbench-driver-web`、`cosbench-http`、`cosbench-log`、`cosbench-log4j`、`cosbench-s3`、`cosbench-sineio`，以及两个 app 模块；Mock 仅 test profile。`cosbench-cdmi-util` 依赖闭包需由普通 Maven compile 最终确认；无活跃调用时随 CDMI 代码进入 legacy。`cosbench-tomcat`、其他 Provider、Manta 和 ext 不进入新 reactor。

### 3.2 数量结论

- 新增模块：2 个 Spring Boot app；registry 放在 Driver application/core 中，避免单独加模块。
- 实质内容修改：粗估约 **190~300 个文件**，集中在 Maven/依赖、Web backend Jakarta、组合根、SPI、YAML/配置转换、脚本/Docker/CI 和测试；47 个 Controller/Driver Web 类需要逐个编译和行为验证，但页面模板和静态资源大多只做路径/资源装配调整，不做重写。
- 测试：新增约 25~45 个单元/集成/契约用例，重点验证配置转换、Web 合同、静态 registry、生命周期和 SineIO/S3 smoke。
- 路径调整另计：约 450 条活跃源代码/资源路径改为标准 Maven layout；约 304 条 legacy 路径归档移动（约 107 个 Java 文件）。Git 通常能识别纯移动，不能将这些 rename 当作逻辑改写。
- 已提交 P2/`dist/osgi` 二进制与旧发行物删除/归档单独计数；若也计入文件 diff，最终路径变更会明显高于 180~280 个内容修改文件。

### 3.3 方案三不会自动变小的部分

- UI 虽然可以本地注入 `ControllerService`/`DriverService`，仍需从 Spring DM Web、Tomcat 6 和 `javax.servlet` 迁到 Boot 3/Spring 6/Jakarta；用户已选择这条现代栈，因此它是已确认工作，不是可选项。
- Controller 到 Driver 的 HTTP 命令协议仍需兼容，`dev/cosbench-controller/src/.../tasklet` 和 `dev/cosbench-driver-web/src/.../handler` 的响应字段、状态码和错误语义不能顺手重写。
- SineIO 与旧 S3 从 bundle classloader 迁到 Maven classpath 后仍需检验旧 AWS SDK、Jackson 和 HTTP client；其他 Provider 通过保留在 `legacy/osgi/` 限制在旧版运行，不纳入这次冲突矩阵。
- 新版本的行为等价只针对确认的支持范围；不能将其描述为所有旧发行版 Provider 都已迁移。legacy Provider 仍由旧版归档运行。

## 4. 方案二与方案三对比

| 项目 | 方案二 | 方案三 |
|---|---:|---:|
| 核心业务重写 | 低；主要是 DS 生命周期和服务注册 | 中；组合根、SPI registry、日志和依赖装配都要改 |
| Web Java 改造 | 约 47 个类，增加远程 client/DTO | 约 47 个类，改为本地 service 注入，仍需现代 Web 栈迁移 |
| 新模块 | 约 4~5 个 | 2 个 Spring Boot app |
| provider 改造 | 主要是 DS descriptor/metadata | `ServiceLoader` 注册、普通 Maven 依赖和 classpath 冲突 |
| OSGi/Tycho | 保留并继续维护 | 新 reactor/发行包移除；legacy 源码和元数据保留 |
| 内容改动估算 | 约 150~220 个文件（原全 Provider 范围） | 约 190~300 个文件；另有约 450 条活跃目录路径调整和约 300 条 legacy 路径移动 |
| 关键风险 | DS 动态绑定、HTTP DTO、UI/引擎拆分 | Jakarta Web、SineIO/S3 依赖冲突、配置转换和新运行入口 |
| benchmark 热路径 | 变化最小 | 业务代码可保留，但共享 classpath/SDK 变化需要重新验收 |

方案二原估算仍按完整根 Provider 集合计算，方案三按已确认的较窄发行范围计算，因此上表文件数量不可直接比较效率。方案三仍是构建、Web、应用装配和发行结构的完整迁移，但 Provider 仅迁移 SineIO/S3，其他源码归档保留。

## 5. 估算中的已确认约束与验证变量

Java 21 构建/测试/运行、所有活跃模块 release=21，以及保留旧版完整归档和配置用于回退均已确认。支持范围、Spring Boot 3/Jakarta、静态 ServiceLoader、新 YAML/命令和 legacy 边界不再作为开放选择。

实际工作量仍受前置验证结果影响：SineIO/S3 是否需要静态隔离、多少旧 Spring adapter 必须替换、旧配置中有哪些不可自动映射字段。由对应 spike 给出证据并更新估算；这些变量不改变已确认的范围。

执行拆分见[23 张 tickets 与映射](../../../.scratch/scheme3-migration/README.md)。新增验证、CI 和性能票用于明确责任，不增加 Provider 或产品功能范围。
