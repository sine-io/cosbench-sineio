# Spring DM 项目与产品的升级案例调研

调研日期：2026-10-27。本文以官方产品文档、维护者提交、历史版本源码为依据，回答“真正使用过 Spring DM 的项目后来如何升级，以及为什么”。本轮只覆盖能够核对到一手材料的样本，不以样本数量推断整个行业的选择。

与本项目的改造设计配合阅读：[COSBench 运行栈现代化改造方案](runtime-modernization.md)。这里的历史案例只能说明某条迁移路径曾经实施过；是否支持本项目目标 Java 21、Jakarta Servlet 和现代 Spring，仍需另行验证。

## 1. 先给结论

核实到的主要路线是**保留 OSGi，改用 Blueprint 或同源的 Gemini Blueprint**：Spring DM 自身的 v2 代码迁入 Eclipse 成为 Gemini；Apache Camel 和 Red Hat Fuse 引导用户从 Spring DM 转向 OSGi Blueprint；Atlassian 插件框架把 Spring DM extender 换成 Gemini Blueprint，并保留旧接口的兼容层。Atlassian 后续又提供了 Spring XML / Spring Scanner 到 Spring Java configuration 的应用配置迁移路线，但这一步仍然使用 Spring、OSGi 和 Gemini API，不能当成退出 OSGi 或改用 DS 的证据。逐项来源见下文。

这修正了一个容易产生的印象：**DS 是可以评估的改造目标，但本轮证据不支持“这些 Spring DM 产品普遍都迁到了 DS”**。同样，历史上迁到 Gemini，也不表示今天直接换 Gemini 就完成 Java 21 升级：Eclipse 当前将 Gemini Blueprint 项目标记为已归档。另一方面，Atlassian Plugins `9.0.0` 的 2025 年版本源码已经使用厂商 Gemini `4.0.0-atlassian-4`，并把编译目标设为 Java 21。因此应区分“上游归档”和“厂商继续维护分支”，不能直接断言 Gemini 这条技术路线无法适配 Java 21。[Eclipse 项目当前状态](https://projects.eclipse.org/projects/rt.gemini.blueprint)、[Atlassian 9.0.0 POM](https://api.bitbucket.org/2.0/repositories/atlassian/atlassian-plugins/src/atlassian-plugins-parent-9.0.0/pom.xml)

本轮没有找到足够扎实的“openHAB 1 使用 Spring DM，openHAB 2 再迁到 DS”的证据。相反，openHAB 1.0.0 源码已经包含 DS 服务描述符。它可以作为 DS 的实际使用案例，但不宜作为 Spring DM 到 DS 的成功迁移案例。

## 2. 案例对比

| 项目 / 产品与取样版本 | 能核实的原技术 | 实际升级路线 | 官方原因或直接证据 | 完成程度与证据边界 | 对 COSBench 的参考价值 |
| --- | --- | --- | --- | --- | --- |
| Spring DM v2 → Eclipse Gemini Blueprint（2009 年起，上游沿革） | Spring Dynamic Modules v2 代码和 Spring / OSGi 集成机制 | 同源代码迁入 Eclipse，演进为 OSGi Blueprint 参考实现，保留 Spring DM 配置支持 | SpringSource 官方公告说明贡献参考实现并跟随 Blueprint 标准演进；迁移说明明确代码和功能延续 | 上游代码延续已发生；这是技术源流，不作为独立产品迁移样本；Gemini 项目当前已归档 | 解释为什么历史产品常选 Gemini；不能据此承诺当前维护和 Java 21 支持 |
| Apache Camel 2.18 → 2.19 → 2.20（2017） | `camel-spring-dm`，含 OSGi 专用 Camel context / Spring namespace 集成 | 2.19 在 Karaf features 中默认禁用，2.20 移除；OSGi XML DSL 改用 `camel-blueprint` | 官方明确组件工作不正常、已弃用多时，推荐 Blueprint | 框架模块已实际删除；不代表 Camel 所有应用均已迁移，且不能泛化为所有 OSGi 支持均已移除 | 迁移重点是替换 Spring DM 特有集成，保留 OSGi / 业务逻辑；属于框架案例 |
| Red Hat JBoss Fuse 6.3 → Red Hat Fuse 7.0 | Karaf 中的 Spring DM extender；6.3 已标记弃用 | 保留 OSGi，使用 Blueprint；7.0 不再支持 Spring DM | 6.3 官方说明 Blueprint 已成为 OSGi 标准，可提高服务定义的可移植性；7.0 明确要求用 Blueprint | 产品支持策略已完成切换；不能据此声称所有客户应用均已完成迁移 | 证明保留 OSGi、替换装配容器是一条真实产品路线；Web 支持仍须独立处理 |
| Atlassian Plugins 3.0.0（2013）与 4.0.0（2015）历史源码对比 | Spring DM 1.2.1 + Spring 2.5.6.SEC02 | 保留 Spring / OSGi；extender 换成 Gemini Blueprint，增加旧 Spring DM 接口兼容层 | 旧 / 新 extender 的依赖、Fragment-Host 和框架打包清单均发生对应替换；维护者提交明确处理旧接口兼容以及后续 Spring / Gemini 联动升级 | 是已进入历史发布 tag 的真实框架变更；不能仅凭这两个取样版本确定最早迁移发生在哪个小版本 | 适合说明“保留插件平台、兼容扩展生态”的渐进路线，但采用的是厂商构建版本 |
| Atlassian Plugins 8.2.1 → 9.0.0（2025）后续演进 | Gemini `3.0.0.M01`、Java 17 编译目标、Spring 5.3 导入范围 | 厂商 Gemini `4.0.0-atlassian-4`、Java 21 编译目标、Spring 6.2 导入范围 | 版本化 POM 和 Gemini 发布制品的 SCM 信息直接证实 | 证明框架构建设定继续演进；不是 Jira / Confluence 整套产品或 COSBench 的支持声明 | Gemini 不是技术上只能停在旧 Java；代价是依赖厂商 fork，并需核实补丁获取与维护责任 |
| Atlassian P2 插件配置现代化（2019 发布 JavaConfig 方式；本次核查包含 2025 更新指南） | 插件 Spring XML 的 `osgi` 配置，或 Spring Scanner 注解 / 生成配置 | 应用配置改为 Spring `@Configuration` / `@Bean`；通过辅助库导入、导出 OSGi 服务 | 官方列出的原因是更好的工具支持、更简单可靠的构建工具链、条件化创建组件、减少业务代码与 DI 配置耦合 | 官方提供的可选迁移指南；不代表所有产品 / 插件统一完成；底层仍依赖 Gemini | 证明配置形式和容器替换是不同工作；可减少 XML，但不会自动消除 Spring DM 类运行时职责 |
| openHAB 1.0.0 与当前 OSGi 开发文档 | 1.0.0 已使用 DS，尚未查实其原先采用 Spring DM | 本轮不认定其发生 Spring DM → DS 迁移 | 历史 `MANIFEST.MF` 的 `Service-Component` 与 `OSGI-INF/itemregistry.xml` 证明早期已用 DS | 排除为本问题的迁移案例；可作为 DS 工程参照 | 不应拿“成熟项目用了 DS”替代“本项目能怎样迁过去”的论证 |

表中的证据和链接在下文逐条展开。“产品不再支持旧容器”“框架源码完成替换”和“提供一个迁移指南”是三种不同程度，不能混为一谈。Fuse 集成 Camel，两者不是相互独立的市场样本；表格用于比较路线，不用于计算采用率。

## 3. 上游沿革：Spring DM v2 → Eclipse Gemini Blueprint

### 3.1 发生了什么

SpringSource 在 2009-11-24 的官方公告中说明，Spring Dynamic Modules v2 是 OSGi Blueprint Service 规范的参考实现，其代码将贡献给 Eclipse Gemini Blueprint Service 项目。Gemini 自身的迁移说明进一步确认：Spring DM v2 的代码、问题跟踪和讨论社区迁入 Eclipse；改名后代码和功能保持延续。[SpringSource 当年公告](https://spring.io/blog/2009/11/24/gemini-project-proposal-at-eclipse-org/)、[Gemini 官方源码中的迁移说明](https://github.com/eclipse-gemini/gemini.blueprint/blob/main/docs/src/docbkx/introduction/eclipse-migration.xml)

这里是同源项目的承接，不能把 Gemini 理解成一个完全不同、需要从零改写业务的容器。Gemini 的 namespace handler 配置同时注册旧 `http://www.springframework.org/schema/osgi`、`osgi-compendium` 和 OSGi Blueprint namespace，为兼容旧 Spring DM 配置提供了实际代码证据。但 Java 包名、bundle 依赖以及特有 Web 集成仍须逐项迁移，不应理解为所有旧应用仅换 JAR 即可。[Gemini namespace handler 注册源码](https://github.com/eclipse-gemini/gemini.blueprint/blob/main/core/src/main/resources/META-INF/spring.handlers)

### 3.2 为什么采用这条路线

**官方说明：**将企业 OSGi 规范的参考实现汇集到 Eclipse；Gemini Blueprint 继续跟随 Blueprint 规范演进。这解释了标准化和项目承接的方向。**工程推断：**同源代码和旧 namespace 支持降低了存量 Spring DM 应用的兼容迁移成本；后面的 Atlassian 案例显示产品确实采取过兼容层做法。[SpringSource 公告](https://spring.io/blog/2009/11/24/gemini-project-proposal-at-eclipse-org/)

### 3.3 今天选择 Gemini 时的限制

截至本次调研，Eclipse 项目页明确标记 Gemini Blueprint 为 **Archived**。GitHub 仓库是否设置只读归档标志是另一个状态，不能用仓库仍可访问来证明上游持续提供产品级维护。历史迁移成功也不能代替 Java 21 / Spring / Servlet 支持矩阵；若采用厂商 fork，还需要明确补丁来源和后续维护责任。[Eclipse Gemini Blueprint 项目状态](https://projects.eclipse.org/projects/rt.gemini.blueprint)

**Gemini Blueprint 与 Apache Aries Blueprint 也不能互换称呼。**二者围绕 Blueprint 标准，但 Gemini 还提供 Spring DM 配置兼容；Aries 文档描述的标准 Blueprint 文件位于 `OSGI-INF/blueprint/`，或由 `Bundle-Blueprint` 声明。不能由“都实现 Blueprint”推出 Aries 原样接受本项目的 Spring bean XML、`META-INF/spring`、TCCL 扩展和 Spring DM Web context。[Aries 官方 Blueprint 文档](https://aries.apache.org/documentation/modules/blueprint.html)、[Gemini namespace 注册源码](https://github.com/eclipse-gemini/gemini.blueprint/blob/main/core/src/main/resources/META-INF/spring.handlers)

## 4. Apache Camel：停用 Spring DM 集成，OSGi XML DSL 改用 Blueprint

### 4.1 旧实现和实际移除证据

Camel 2.18 的官方发布 tag 中存在 `components/camel-spring-dm`，包含 `OsgiSpringCamelContext`、Spring namespace handler 和相关工厂类，证明不是仅仅在文档中提及 Spring DM。[Camel 2.18 旧模块源码](https://github.com/apache/camel/tree/camel-2.18.0/components/camel-spring-dm)

Camel 2.19 发行说明指出，该组件已从 Karaf features 中默认禁用，原因是工作不正常且已弃用较长时间；当时 JAR 仍分发，但建议用户使用 OSGi Blueprint。Camel 2.20 发行说明则明确组件已被移除，OSGi XML DSL 应用应使用 `camel-blueprint`。维护者的 `CAMEL-11059` 删除提交与这个时间线相符。[Camel 2.19 发行说明](https://camel.apache.org/releases/release-2.19.0/)、[Camel 2.20 发行说明](https://camel.apache.org/releases/release-2.20.0/)、[2017-07-20 官方删除提交](https://github.com/apache/camel/commit/b0802ae195e78dbd16ce7024e5581bd2f87e8703)

版本口径应以上游明确的 **2.20 移除**为准，不应仅因下游产品使用 Camel 2.21，就把上游移除时间写成 2.21。

### 4.2 为什么，以及不能推出什么

**官方直接原因：**旧组件工作不正常、此前已弃用，已有 Blueprint 替代路径。官方要求迁移的是 OSGi 场景下的 XML DSL 集成，不是重写所有 Camel 路由，也不是要求所有用户退出 OSGi。[Camel 2.20 原文](https://camel.apache.org/releases/release-2.20.0/)

这条历史线也不能简化成“Camel 后来完全取消 OSGi，所以必须改 Boot”：本次访问的 Camel 官方站点仍有 Camel Karaf 4.9.x 文档，说明如何在 Karaf OSGi 容器中运行 Camel。本文不据此概括每个 Camel 4 小版本的支持范围。[Camel Karaf 官方文档](https://camel.apache.org/camel-karaf/4.9.x/index.html)

**对本项目的工程推断：**应先找到依赖 Spring DM 的集成边界再替换，业务逻辑能否保留要逐项验证；不能将删除某个框架集成模块等同于重写整个产品。

## 5. Red Hat Fuse：保留 OSGi，Spring DM → Blueprint

### 5.1 旧栈确实使用过 Spring DM

Red Hat JBoss Fuse 6.3 的《Deploying into Apache Karaf》在依赖注入容器部分同时介绍 Spring extender 和 Blueprint extender，并明确 Spring extender 基于 Spring Dynamic Modules。该文档随后将 Spring DM 标记为弃用，建议需要 OSGi 集成的依赖注入时使用 Blueprint。这不是从“当前产品用了 Blueprint”倒推出的历史迁移。[官方 6.3 部署指南](https://docs.redhat.com/en/documentation/red_hat_jboss_fuse/6.3/html-single/deploying_into_apache_karaf/index)

### 5.2 后来采用哪条路线

Fuse 7.0 发行说明第 5.1 节明确：Spring DM 不再受支持，用户应使用 Blueprint。它同时说明使用 Blueprint XML 并不妨碍使用 Spring Framework 的 Java 库。因此，这是一条“保留 OSGi 服务模型、替换 OSGi 与依赖注入的集成方式”的路线，并非要求把所有 Spring Java 类重写，也不是退出 OSGi。[Fuse 7.0 发行说明：Deprecated and Removed Features](https://docs.redhat.com/en/documentation/red_hat_fuse/7.0/html/release_notes/deprecatedandremoved)

产品支持的切换是明确的，但发行说明不能证明每一个 Fuse 客户都已经迁移。Fuse 7.0 迁移指南还指向旧应用运行的知识库文章，说明存量兼容需求仍然存在；本次访问该知识库正文受到访问限制，本文不采用其内容论证兼容方案。[Fuse 7.0 迁移指南](https://docs.redhat.com/en/documentation/red_hat_fuse/7.0/html-single/migration_guide/index)

### 5.3 为什么这样选择

**官方直接说明的原因：**6.3 文档中的 “Prefer Blueprint over Spring-DM” 一节认为，Blueprint 已成为 OSGi 标准，使 OSGi 服务定义具有更好的未来可移植性；Spring DM 对该标准的形成有贡献，但当时已应视为过时。这是官方给出的标准化和可移植性理由。[官方原文所在小节](https://docs.redhat.com/en/documentation/red_hat_jboss_fuse/6.3/html-single/deploying_into_apache_karaf/index#idm140473121430192)

**对 COSBench 的工程推断：**当产品必须维持 bundle 部署、动态服务注册与跨 bundle 引用时，保留 OSGi、替换装配机制，可以限制迁移范围。这个推断符合 Fuse 路线，但不能替代本项目对服务动态性、生命周期、配置注入和 Web 的逐项验证。

### 5.4 对本项目尤其重要的边界

同一套 Fuse 7.0 发行说明还明确将 Apache Aries Blueprint Web 模块列为不受支持项。因此，“服务装配可迁到 Blueprint”并不能推出“旧 Spring DM Web extender / Spring MVC / Tomcat 的问题同时解决”。COSBench 使用 Web application context 的部分，应继续按现代化方案中独立的 Web 改造工作处理。[Fuse 7.0 发行说明：Unsupported Features](https://docs.redhat.com/en/documentation/red_hat_fuse/7.0/html-single/release_notes/index#apache_aries_blueprint_web_module_is_unsupported)

这也不能作为 2026 年重新选择某个历史 Blueprint 版本的支持背书；应重新核对候选实现当前的 Java / Spring / Servlet 支持范围。

## 6. Atlassian 插件框架：Spring DM → Gemini Blueprint，兼容既有插件

### 6.1 能核实的版本时间线

这里比较的是官方 `atlassian/atlassian-plugins` 仓库中的发布 tag。3.0.0 tag 的目标提交时间是 2013-06-18，4.0.0 是 2015-06-23。这两个版本用于证明前后差异，本文不声称 Gemini 首次引入于 4.0.0。[3.0.0 tag 元数据](https://api.bitbucket.org/2.0/repositories/atlassian/atlassian-plugins/refs/tags/atlassian-plugins-parent-3.0.0)、[4.0.0 tag 元数据](https://api.bitbucket.org/2.0/repositories/atlassian/atlassian-plugins/refs/tags/atlassian-plugins-parent-4.0.0)

| 取样证据 | 3.0.0 旧栈 | 4.0.0 后续栈 |
| --- | --- | --- |
| 根 POM | `springDmVersion=1.2.1`；`springVersion=2.5.6.SEC02` | `geminiBlueprintVersion=2.0.0.BUILD-atlassian-m002`；Spring bundle `4.1.6.RELEASE_1` |
| `atlassian-plugins-osgi-spring-extender/pom.xml` 依赖 | `org.springframework.osgi:spring-osgi-extender` | `org.eclipse.gemini.blueprint:gemini-blueprint-extender` |
| extender 的 `Fragment-Host` | `org.springframework.osgi.extender` | `org.eclipse.gemini.blueprint.extender` |
| 旧插件兼容 | 直接使用 Spring DM API | 导出 `org.springframework.osgi.context`，源码注释明确是旧 Spring DM 的兼容 shim |
| 框架实际装配 | OSGi 模块打包 Spring DM annotation / core / extender | framework-bundles 模块打包 Gemini core / extender 等 |

表格来源：[3.0.0 根 POM](https://api.bitbucket.org/2.0/repositories/atlassian/atlassian-plugins/src/atlassian-plugins-parent-3.0.0/pom.xml)、[3.0.0 extender POM](https://api.bitbucket.org/2.0/repositories/atlassian/atlassian-plugins/src/atlassian-plugins-parent-3.0.0/atlassian-plugins-osgi-spring-extender/pom.xml)、[3.0.0 OSGi 装配 POM](https://api.bitbucket.org/2.0/repositories/atlassian/atlassian-plugins/src/atlassian-plugins-parent-3.0.0/atlassian-plugins-osgi/pom.xml)、[4.0.0 根 POM](https://api.bitbucket.org/2.0/repositories/atlassian/atlassian-plugins/src/atlassian-plugins-parent-4.0.0/pom.xml)、[4.0.0 extender POM](https://api.bitbucket.org/2.0/repositories/atlassian/atlassian-plugins/src/atlassian-plugins-parent-4.0.0/atlassian-plugins-osgi-spring-extender/pom.xml)、[4.0.0 框架 bundle 打包 POM](https://api.bitbucket.org/2.0/repositories/atlassian/atlassian-plugins/src/atlassian-plugins-parent-4.0.0/atlassian-plugins-framework-bundles/pom.xml)。

这组证据同时覆盖依赖、extender 挂接对象、真实分发内容和兼容层，比单独找到一个含 Blueprint 依赖的当前 POM 更能证明迁移发生过。

### 6.2 官方维护者明确做了什么、为什么

2014-09-10 的维护者提交 `1ece6cab...` 明确增加旧 Spring DM `BundleContextAware` 接口的复制版本，并用 BeanPostProcessor 向实现该接口的插件注入 BundleContext。提交说明直接把目标写为支持旧 Spring DM 接口，说明兼容插件是实际实现要求。[官方兼容层提交](https://bitbucket.org/atlassian/atlassian-plugins/commits/1ece6cab305207bbcbb94b282ae0d33e7c7551e7)

2014-10-29 的提交明确联合升级 Spring 和 Gemini；2014-11-23 的合并说明则是更新到 Spring 4 并加入新的 framework bundle。这可以证明后续 Spring 版本升级与 Gemini 集成一起演进，但不能据此独断“最初替换 Spring DM 的唯一原因就是升级 Spring 4”。[Spring / Gemini 升级提交](https://bitbucket.org/atlassian/atlassian-plugins/commits/d0f243787c5aa9e74f558f4cdd6fd4e94ea00e16)、[Spring 4 升级合并](https://bitbucket.org/atlassian/atlassian-plugins/commits/8bc4682eed940db800b5c10ac308ca2b447a5864)

**可以作出的工程推断：**对于已经形成大量插件的产品平台，沿用 Spring / OSGi 结构、替换同源集成实现并提供 API shim，能够减少插件作者同时修改业务代码的压力。兼容层是直接证据；“降低生态迁移成本”是基于该实现的推断，不能写成未经核实的官方原话。

### 6.3 为什么不能照搬到今天

历史 POM 使用的版本包含 `BUILD-atlassian-m002`，已经表明这是 Atlassian 构建，不能当成上游 Eclipse 原版的维护承诺。该历史案例证明了一种厂商维持插件兼容的做法，不证明任意 Gemini 版本今天支持 Java 21、Spring 6/7 或 Jakarta Servlet。这里也没有证据表明 Atlassian 在这次变更中改用了 DS 或移除了 OSGi。

### 6.4 后续证据：厂商分支已加入 Java 21 构建设定

继续核查 2025 年的版本化源码，可以看到同一框架仍在演进：

| 根 POM / tag 中的字段 | Plugins 8.2.1 | Plugins 9.0.0 |
| --- | --- | --- |
| tag 创建时间（UTC） | 2025-03-14 | 2025-06-23 |
| `maven.compiler.release` | `17` | `21` |
| `geminiBlueprintVersion` | `3.0.0.M01` | `4.0.0-atlassian-4` |
| `springOsgiImportVersion` | `5.3` | `6.2` |
| `platform.version` | `7.0.17` | `8.0.0-jakarta-m028` |

来源：[8.2.1 POM](https://api.bitbucket.org/2.0/repositories/atlassian/atlassian-plugins/src/atlassian-plugins-parent-8.2.1/pom.xml)、[8.2.1 tag](https://api.bitbucket.org/2.0/repositories/atlassian/atlassian-plugins/refs/tags/atlassian-plugins-parent-8.2.1)、[9.0.0 POM](https://api.bitbucket.org/2.0/repositories/atlassian/atlassian-plugins/src/atlassian-plugins-parent-9.0.0/pom.xml)、[9.0.0 tag](https://api.bitbucket.org/2.0/repositories/atlassian/atlassian-plugins/refs/tags/atlassian-plugins-parent-9.0.0)。

此外，Gemini `4.0.0-atlassian-4` 的官方发布 POM 将 SCM 指向 `server-platform/gemini-fork`，并标注对应版本 tag。这使“厂商分支”判断有了制品元数据依据，不只是根据版本后缀猜测。该 Git 仓库本次无法公开读取，但官方制品库的 core / extender 源码 JAR 均可下载，分别包含 258 / 62 个 Java 源文件。因此应区分“发布版源码可获取”和“Git 补丁历史及长期维护承诺可公开核实”，不能说厂商分支完全没有公开源码。[Atlassian 官方 Gemini 制品 POM](https://packages.atlassian.com/maven-public/org/eclipse/gemini/blueprint/gemini-blueprint/4.0.0-atlassian-4/gemini-blueprint-4.0.0-atlassian-4.pom)、[core 源码包](https://packages.atlassian.com/maven-public/org/eclipse/gemini/blueprint/gemini-blueprint-core/4.0.0-atlassian-4/gemini-blueprint-core-4.0.0-atlassian-4-sources.jar)、[extender 源码包](https://packages.atlassian.com/maven-public/org/eclipse/gemini/blueprint/gemini-blueprint-extender/4.0.0-atlassian-4/gemini-blueprint-extender-4.0.0-atlassian-4-sources.jar)

**能够得出的结论：**保留 OSGi / Spring、继续维护 Gemini 分支，并升级 Java 编译目标，是确实出现在版本化框架中的路线。**不能得出的结论：**这些 POM 本身不能证明所有集成测试通过、某个 Jira / Confluence 产品完整支持 Java 21，或本项目只替换 Gemini JAR 就能运行。`springOsgiImportVersion` 是包导入范围设定，`platform.version` 还带预发布后缀，也不能把它们当成整套产品正式支持矩阵。

## 7. Atlassian 后续路线：Spring XML / Scanner → Spring Java configuration

### 7.1 这一步究竟换掉了什么

Atlassian 官方将 Spring Java configuration 称为继插件 XML、Spring XML 和 Spring Scanner 之后的第四种组件配置方式，并说明它在 Atlas Camp 2019 发布。已有插件可以把 XML 或注解中隐含的 bean / 服务声明，改成 `@Configuration` 与 `@Bean` 方法。[官方介绍](https://developer.atlassian.com/server/framework/atlassian-sdk/spring-java-config/)

官方 Scanner 转换指南要求移除名称包含 `spring-scanner` 的依赖和构建插件，再显式声明 bean；Spring XML 转换指南要求移除 XML 中的 OSGi 命名空间和 Spring DM / Gemini schema 声明，将 `osgi:reference` 等配置改成 Java 配置方法。后者页面元数据日期为 2025-01-29。[Scanner 转换指南](https://developer.atlassian.com/server/framework/atlassian-sdk/spring-java-config-spring-scanner/)、[Spring XML 转换指南](https://developer.atlassian.com/server/framework/atlassian-sdk/spring-java-config-spring-xml/)

这属于真实可执行的应用配置迁移指南，但官方没有因此宣称所有产品内置模块和第三方插件都已采用同一种配置形式。

### 7.2 底层没有因 JavaConfig 自动变成 DS

官方仍要求引入 Spring 与 OSGi 库；包导入清单明确包含 `org.eclipse.gemini.blueprint.context`、Gemini 的 exporter / importer API，以及 `org.osgi.framework`。Spring XML 转换指南还说明，辅助库没有覆盖的功能可以直接使用 Gemini Blueprint API。因此，移除 XML 中的 Spring DM schema 或移除 Spring Scanner 构建插件，不等于移除底层 OSGi / Gemini 集成。[JavaConfig 必需的包导入](https://developer.atlassian.com/server/framework/atlassian-sdk/spring-java-config-packages/)、[Spring XML 转换指南](https://developer.atlassian.com/server/framework/atlassian-sdk/spring-java-config-spring-xml/)

### 7.3 官方给出的选择原因

官方介绍列出的优点是：代码配置提供更强工具支持；工具链更简单可靠；可以按条件创建组件；减少业务逻辑对依赖注入细节的感知。这些是选择 JavaConfig 的官方原因，不能扩展成“为了摆脱 OSGi”，因为同一指南仍通过 OSGi 导入、导出服务。[官方背景与优点](https://developer.atlassian.com/server/framework/atlassian-sdk/spring-java-config/)

对 COSBench 的参考是：是否保留 Spring、是否保留 OSGi、使用 XML 还是 Java 配置，应分开决策。单纯把 XML 改成 `@Bean` 不会完成 Spring DM Web / 服务生命周期 / bundle 类加载的迁移。

## 8. openHAB：DS 实际使用参照，不能冒充 Spring DM 迁移案例

openHAB 1.0.0 的 core bundle 已有如下明确特征：

- `MANIFEST.MF` 的 `Service-Component` 指向 `OSGI-INF/itemregistry.xml` 等描述符，且导入 `org.osgi.service.component`。
- `itemregistry.xml` 使用 OSGi SCR 命名空间，包含组件实现、服务导出以及动态的 `ItemProvider` 引用和 bind / unbind 方法。

这些是 DS 的直接历史源码证据。[openHAB 1.0.0 manifest](https://github.com/openhab/openhab1-addons/blob/1.0.0/bundles/core/org.openhab.core/META-INF/MANIFEST.MF)、[openHAB 1.0.0 itemregistry 描述符](https://github.com/openhab/openhab1-addons/blob/1.0.0/bundles/core/org.openhab.core/OSGI-INF/itemregistry.xml)

当前 openHAB 开发文档也介绍 OSGi Declarative Services，适合参考组件生命周期、服务注册和动态引用的工程方式。但“旧版已采用 DS”和“新版也用 DS”不能证明“Spring DM 迁移到 DS”；本轮没有查到足够的一手材料建立后面这条因果链，也不据此声称 openHAB 所有历史模块均未使用过 Spring。[openHAB 官方 OSGi 文档](https://www.openhab.org/docs/developer/osgi/osgi.html)

因此，不能拿 openHAB 1 → 2 支持“其他产品普遍都把 Spring DM 换成 DS”的说法。

## 9. 对本项目方案选择的含义

| 待决策问题 | 本轮案例能够支持什么 | 仍需 COSBench 自己验证什么 |
| --- | --- | --- |
| 是否可保留 OSGi，只替换 Spring DM | Fuse 和 Atlassian 历史框架都证明该路线真实存在 | 目标 Java、Spring、OSGi、HTTP 实现的版本组合；实际保留热部署的业务价值 |
| DS 能否作为方案二的服务装配方案 | DS 是成熟 OSGi 服务装配机制；openHAB 提供真实使用源码 | Spring bean 内部装配、动态 `0..n` 引用、退出 / 重入语义、初始化 / 关闭顺序，以及配置绑定的改写 |
| 是否应优先采用 Gemini 作为长期目标 | Atlassian 不仅证明历史兼容迁移，2025 年版本化 POM 还显示厂商分支与 Java 21 编译目标 | Eclipse 上游已归档；厂商补丁源码可获取性、适配完整性与维护责任；构建设定不等于产品支持矩阵 |
| Web 能否与服务装配一起自动替换 | Fuse 的 Blueprint Web 支持边界说明需要单独评估 | `OsgiBundleXmlWebApplicationContext`、Spring MVC、Servlet 命名空间、Tomcat 生命周期绑定与 Web 服务查找 |
| 是否有证据要求本项目迁到 Spring Boot 或 Go | 本轮这些案例不足以推出该要求 | 退出 OSGi 或 Go 重写应依据项目需求、适配器数量、行为回归和维护成本决定 |

就现有方案二而言，DS + 独立 Web 仍可以作为设计候选，但应把依据写为“与 COSBench 职责拆分相匹配”，而不是“这些产品都这样迁过”。方案三和方案四也不能因为上述历史案例被排除；它们解决的是是否继续承担 OSGi / Java 运行栈维护成本的问题。

### 9.1 案例如何对应到本项目代码

| 当前承担的职责与源码证据 | Gemini / 标准 Blueprint 路线需要做什么 | DS 路线需要做什么 |
| --- | --- | --- |
| Driver 的 `osgi:list` 收集 auth / storage factory；适配器用 `osgi:service` 导出。见 [Driver 服务配置](../../dev/cosbench-driver/META-INF/spring/driver-osgi-context.xml)、[SineIO 插件配置](../../dev/cosbench-sineio/META-INF/spring/plugin-context.xml) | Gemini 旧 namespace 支持有利于保留部分配置；若选标准 Blueprint，实现和 XML 改成对应 bean、service、reference-list，明确可选性与动态更新语义 | 将 factory 注册为 DS 服务，多重引用显式 bind / unbind；运行时安装 SCR，构建生成组件描述符，不能仅添加注解 |
| Spring 先执行工厂的 `init-method`，再通过 `factory-method` 创建 Controller / Driver；Driver 工厂还设置 context / API 并调用服务 `init()`。见 [Driver bean 配置](../../dev/cosbench-driver/META-INF/spring/driver-context.xml)、[Driver 工厂](../../dev/cosbench-driver/src/com/intel/cosbench/driver/service/COSBDriverServiceFactory.java)、[Controller bean 配置](../../dev/cosbench-controller/META-INF/spring/controller-context.xml) | 验证候选容器的工厂 / 初始化配置及执行顺序，不能只验服务能注册 | 用显式装配代码和激活 / 停用流程承接工厂初始化、配置读取、线程池和关闭；DS 不解释原 Spring XML |
| 服务导出声明 `context-class-loader="service-provider"`。见 [Driver 服务配置](../../dev/cosbench-driver/META-INF/spring/driver-osgi-context.xml)、[Controller 服务配置](../../dev/cosbench-controller/META-INF/spring/controller-osgi-context.xml) | 核查所选 Gemini 版本的兼容性，或为标准 Blueprint 另行设计必要的 TCCL 边界 | DS 不自动复制该 Spring DM 扩展；核查 SDK / SPI / 反射加载并按需显式管理 TCCL |
| `StorageAPIService` 先按类型找 factory，再取得 API 实例并初始化。见 [存储 API 装配代码](../../dev/cosbench-driver/src/com/intel/cosbench/driver/service/COSBStorageAPIService.java) | 保留 factory / 实例边界和现有 benchmark 行为；验证插件撤回、缺失及重复类型的处理 | 保留 factory 服务与每次创建的 API 实例边界；不能把工作线程使用的客户端直接改成共享 DS singleton；为动态变更设计安全 registry |
| 两个 Web bundle 的 `web.xml` 直接使用 `OsgiBundleXmlWebApplicationContext`；Tomcat bundle 还把 Catalina 服务注册到 OSGi。见 [Controller web.xml](../../dev/cosbench-controller-web/WEB-INF/web.xml)、[Driver web.xml](../../dev/cosbench-driver-web/WEB-INF/web.xml)、[TomcatRegister](../../dev/cosbench-tomcat/src/com/intel/cosbench/tomcat/TomcatRegister.java) | Gemini core / Blueprint 的替换成功不代表这条旧 Web 发布链路成功；独立评估 Spring MVC、容器和 Servlet 版本组合，或拆出 Web | DS 只接管组件 / 服务装配；Web 仍按现有方案二独立改造，不能把 DS 当成 Web extender |

这张表是基于本项目源码的改造分析，不是这些产品对 COSBench 做过的验证。关于 DS 行为的规范依据见 [OSGi Declarative Services](https://docs.osgi.org/specification/osgi.cmpn/8.1.0/service.component.html)，Blueprint 的标准容器职责见 [Aries Blueprint 文档](https://aries.apache.org/documentation/modules/blueprint.html)。

### 9.2 调研后的选型建议

1. **近期目标是减少改动、保留第三方 OSGi 插件：**先做 Gemini 兼容验证，或评估迁到标准 Blueprint。Gemini 配置兼容较接近旧栈，但要解决分支来源与维护责任；标准 Blueprint 则需要真实改写配置，不能假设兼容所有 Spring 扩展。这是受案例支持的过渡方向，尚不是已通过验证的 COSBench 实施方案。
2. **长期保留 OSGi，但愿意移除 Spring 装配依赖：**继续评估现有方案二的 DS + 独立 Web。先迁一个 factory 插件及 Driver 的多重引用，验证动态服务、工厂生命周期和类加载，再扩大范围。选择它的理由应是本项目未来维护目标，而非虚构其他产品的迁移共识。
3. **已确认不需要 bundle 热部署 / 隔离：**方案三的普通 Java 应用仍值得优先比较；不用先为了退出 Spring DM 再长期维护另一个 OSGi 集成框架。是否采用 Spring Boot 是 Web / 应用容器选型，不等同于已经有产品案例证明本项目应如此迁移。
4. **计划转 Go：**上述案例没有为 Go 重写提供直接迁移背书，应继续按现有方案四评估适配器重写、混合部署和 benchmark 精度成本。

所有候选都先以“一种真实存储适配器 + 一条 workload + Controller/Driver 命令链路 + Web 查询”做最小可运行验证，再决定扩大实施。该验证应放在 Docker 中执行，并在结束后清理；本次任务只生成调研文档，没有开展运行时改造或容器调试。

## 10. 改用 OSGi DS 后，对压测性能的预研

### 11.1 结论先行

**目前没有 COSBench 的 DS 对照实测数据，因此不应承诺吞吐会上升或下降某个百分比。**依据当前代码，若 DS 只负责注册 / 绑定 `StorageAPIFactory`、`AuthAPIFactory` 和 Driver 服务，随后仍在任务初始化时为每个 worker 创建 API，并把 API 缓存在 `WorkerContext`，那么 DS 服务解析不在每次存储操作的调用路径中。基于这一结构，steady-state 性能预期应接近原值，DS 本身不应成为每个对象操作的额外查找成本；这只是有代码依据的预测，不是基准结果。

更值得测量的是 Driver 就绪时间、首次 mission 启动时间、JVM 常驻资源，以及插件在 mission 运行中变化时的生命周期影响。如果迁移实现让热循环每次都动态查询 OSGi 服务、引入同步代理或改变 SDK 所需的线程上下文类加载器，则可能改变延迟和吞吐，应避免或单独量化。

### 11.2 当前服务查找与压测热路径

| 阶段 | 仓库中的行为 | DS 迁移对性能的可能影响 |
| --- | --- | --- |
| Driver 创建 | `driver-osgi-context.xml` 从 OSGi `list` 注入 factory；普通 Spring bean 配置组装 `COSBDriverServiceFactory` 并调用 `init()` | DS 描述符解析、引用满足及组件激活会影响启动和 ready 时间；服务导出影响控制面初始化 |
| mission 初始化 | [`MissionHandler.init()`](../../dev/cosbench-driver/src/com/intel/cosbench/driver/service/MissionHandler.java) 创建 worker；每个 `WorkerContext` 通过 `StorageAPIService` 和 `AuthAPIService` 获取对应 API | factory 服务访问和 SDK client 创建成本每个 worker / mission 发生，不是每个 benchmark operation 发生；需保留原来“一 worker 一 API 实例”的语义 |
| worker 工作循环 | [`WorkerContext`](../../dev/cosbench-driver/src/com/intel/cosbench/driver/model/WorkerContext.java) 持有 `StorageAPI` / `AuthAPI` 字段；[`WorkAgent.performOperation()`](../../dev/cosbench-driver/src/com/intel/cosbench/driver/agent/WorkAgent.java) 循环读取 worker 字段；[`Writer`](../../dev/cosbench-driver/src/com/intel/cosbench/driver/operator/Writer.java) / [`Reader`](../../dev/cosbench-driver/src/com/intel/cosbench/driver/operator/Reader.java) 通过 session 获取已缓存的 API 执行 I/O | 若仍使用已创建的 API 引用，DS runtime 不参与每次操作的 registry lookup；若每次操作重新查 DS 服务，则架构改变，需避免 |
| 控制面 | [`COSBDriverService`](../../dev/cosbench-driver/src/com/intel/cosbench/driver/service/COSBDriverService.java) 提交 mission、调度 login / launch / close / abort；Controller 到 Driver 通过 HTTP command | Driver service 引用用于控制请求，不处于每个对象读写的热路径；控制请求吞吐需单独评估，不能混为 storage QPS |

COSBench 中真实读写操作的开销主要来自 storage SDK、网络、服务端响应、数据生成和测量统计。DS 组件引用的绑定发生在组件和 OSGi 服务生命周期边界；当它不在上述 worker 调用循环中时，不会因为操作次数增多而按同一比例增加查找次数。[OSGi DS 规范第 112 章](https://docs.osgi.org/specification/osgi.cmpn/8.1.0/service.component.html) 描述组件激活与服务引用策略，没有给出 DS 相对 Spring DM 的 COSBench 吞吐数字。

### 11.3 性能风险与规避方式

1. **禁止在操作热循环中做服务定位。**DS 引用用于注入 factory / 服务，再将 mission 所需的 `StorageAPI` 实例放入 `WorkerContext`。不要在每个 `operate()`、`createObject()` 或 `readObject()` 调用前执行 `ComponentContext.locateService`、`BundleContext.getService` 或类似 registry 查询。
2. **谨慎选择多重 factory 引用的 policy。**OSGi DS 规范指出，static reference 的服务集合发生变化时可能导致组件 deactivate / reactivate；如果被重建组件承担 Driver 主服务，可能打断运行中的 mission。dynamic policy 可在不 deactivate 组件的情况下调整引用，但 bind / unbind 可能在不同线程到来，组件必须线程安全。建议用 bind / unbind 更新不可变 factory 快照，在新 mission 创建 API 时读取快照；plugin 更新/移除时，不修改现有 worker 正在使用的 API。
3. **明确运行中卸载语义。**`WorkerContext` 保留已实例化的 API 对象。解绑 factory 并不自动证明这些 API 对象还能安全使用；应规定有活动 mission 时禁止、排队或延迟卸载对应 bundle，并核验 API `dispose`、SDK 客户端关闭及类加载器生命周期。
4. **保持 provider TCCL 行为。**当前 Driver 和适配器的 Spring DM 服务声明包含 `context-class-loader="service-provider"`。DS 没有凭注解自动复制这一 Spring DM 扩展语义。SDK 的 `ServiceLoader`、反射或资源发现若依赖 provider TCCL，迁移可能改变初始化和操作结果，甚至间接影响性能；需按现有插件验证并显式设置边界。
5. **区分 lazy activation 与性能收益。**DS 的 delayed component activation 可避免未使用组件提前实例化、减少部分内存占用；它把成本移到首次服务使用点，不代表 COSBench 所有 bundle 的启动必然更快或整体 heap 必然下降。应分别测冷启动、首次 mission 和稳定运行。
6. **避免把 SCR 本身误当作热路径。**DS annotations 在构建时处理并生成组件 XML 描述符；运行时 SCR 解析这些描述并维护引用。这会增加部署元数据和组件管理活动，但 steady-state 是否有调用成本取决于服务对象在应用中的使用方式。[Apache Felix SCR 官方文档](https://felix.apache.org/documentation/subprojects/apache-felix-service-component-runtime.html)

### 11.4 建议的对照实验

实验目的不是给 DS 先设一个预期涨跌值，而是验证“服务装配变化没有改动 benchmark 行为和热路径”。Spring DM 基线与 DS 实验组应尽量只差装配机制，固定相同 Equinox / JDK / JVM 参数、机器、adapter / SDK 版本、对象大小、bucket、网络和存储端；使用相同配置、worker 数、operation mix、运行时长和 metrics 设置。迁移若同时更换 JDK、SDK、Tomcat、HTTP 线程池或 metrics 库，应拆成不同批次。

| 场景 | 记录指标 | 如何解读 |
| --- | --- | --- |
| 冷启动与 Driver ready | 进程启动到 Driver 可响应时间、bundle / component 错误、启动日志 | 识别组件描述符扫描、引用等待和激活顺序的变化 |
| 首次 mission | submit/login/launch 总耗时、每 worker 初始化耗时、client 建立耗时 | 与 steady-state 分开，识别 lazy activation 和 SDK 初始化成本 |
| 本地 mock（先设 `delay=0`） | 多 worker 下 ops/s、延迟、CPU、GC、allocation、threads、heap/RSS | 尽量弱化远端存储波动，观察 Driver 调度、统计、对象分配成本；保留 mock factory 路径并消费写入数据 |
| 固定真实存储读写 | ops/s、p50/p95/p99（若输出可获得）、错误率、Driver CPU / GC、网络与存储端指标 | 判断实际 benchmark workload 的端到端差异；网络噪声和服务端饱和需同时观察 |
| DS provider install / update / uninstall | bind/unbind 时长、Driver 是否重新激活、活动 mission 错误/中断、QPS/延迟短时变化 | 作为生命周期实验单列，禁止并入正常 steady-state 性能分数 |

`release/conf/config-samples/noop-config.xml` 中的 mock 设置是注释掉的示例；启用后仍需注意 `MockStorage` 默认每操作延迟 10 ms。若用它测试 Driver 装配开销，应显式配置 `delay=0;printing=false;doStats=false`，否则 sleep 延迟会掩盖较小的运行时差异。Mock write 会消费输入流，因此可以保留数据生成和读取成本。[Mock 默认值](../../dev/cosbench-mock/src/com/intel/cosbench/api/mock/MockConstants.java)、[Mock 写入实现](../../dev/cosbench-mock/src/com/intel/cosbench/api/mock/MockStorage.java)、[Mock workload 样例](../../release/conf/config-samples/noop-config.xml)。`storage type="none"` 虽可提供快速 core-loop 参照，但 Driver 对 `none` 走硬编码分支，不经过 factory registry，且不会像 mock 那样消费写入流；不能用它单独判定 DS factory 服务绑定的开销。[NoneStorage 特殊分支](../../dev/cosbench-driver/src/com/intel/cosbench/driver/service/COSBStorageAPIService.java)、[NoneStorage 实现](../../dev/cosbench-api/src/com/intel/cosbench/api/storage/NoneStorage.java)。

每组至少预热后重复五轮，并交替运行基线/实验组，报告各轮原始数据、中位数和离散程度；操作数、错误数和测量窗口必须能对齐。可先用无网络的 mock 隔离 Driver 调度，再用真实存储验证端到端结论。不要仅凭一次结果或一个平均 QPS 宣称 DS 更快/更慢。

本阶段只完成源码分析和实验设计，没有运行 benchmark，也没有取得 DS 与 Spring DM 的定量差值。依照仓库的开发调试要求，之后若执行容器化实验，应在 Docker 环境中运行并在实验结束后清理容器。

## 11. 研究边界

- 本文区分“官方原因”和“工程推断”；未找到公开决策记录时，不编造决策理由。
- 历史 tag / 提交证明对应代码已存在，不等于证明该产品所有模块或所有客户都已迁移，也不保证当前版本继续采用同一实现。
- 某个项目当前使用 Blueprint / DS，不能单独作为它过去使用过 Spring DM 的证据；需要旧版本与迁移记录形成链条。
- 本轮没有找到可确认为 Spring DM → DS、Spring DM → Go 的同等强度产品迁移案例；这是本轮证据范围，不能据此推断不存在。
- 性能章节只有源码分析和测试设计，没有 COSBench DS / Spring DM 对照实测，也没有定量性能结论。
