# 00 --- 背景、已核实事实与初始决策

## 1. 背景

目标是现代化 `sine-io/cosbench-sineio`。用户决定暂不使用 Go 重写，保留
Java，重点解决旧构建流程、OSGi/Equinox
运行时复杂度、模块耦合、测试和发布可重复性问题。

本项目是存储性能基准测试工具，不是普通 CRUD Web
应用。压测请求生成、操作语义、计时范围、错误统计、重试和数据字节数都是正确性要求。

## 2. 已核实事实

以下信息已从公开仓库文档/配置核实：

1.  `BUILD-sineio.md` 要求 JDK 1.8，并说明 Eclipse 4.27.0
    是已知可用版本；发布流程包括更新 `VERSION`、运行
    `version_unify.sh`、清理旧 JAR、通过 Eclipse PDE 导出插件，再执行
    `pack-sineio.sh`。
2.  `BUILD.md` 描述了旧目录结构：
    -   `dev/`：COSBench 插件工程；
    -   `dist/main`：OSGi launcher；
    -   `dist/osgi`：OSGi bundles/运行时；
    -   `dist/osgi/libs`：第三方库；
    -   `dist/osgi/plugins`：COSBench 插件。
3.  `BUILD.md` 说明 Controller 和 Driver
    是两个主要执行程序，历史配置中使用 OSGi Framework、Bundle Start
    Level 和 Eclipse/OSGi 系统属性。
4.  `release/conf/.controller/config.ini` 和
    `release/conf/.driver/config.ini` 包含
    `osgi.bundles`、`osgi.framework`、`osgi.startLevel`、`eclipse.ignoreApp`
    等配置。Driver 配置列出多个适配器 Bundle。
5.  `pack-sineio.sh` 读取 `VERSION`，调用
    `pack.sh`，重命名发行目录并生成
    `cosbench-$VERSION-sineio.tar.gz`。`pack.sh` 复制
    `release/*`、`dist/*`、Docker 文件和发行文档。
6.  README 列出了 SineIO 分支的特殊操作、特性和 SDK
    版本差异。因此，不能在重构中只验证基础 S3 PUT/GET。

来源： -
https://github.com/sine-io/cosbench-sineio/blob/a99954458d84e3aa46d3882bae9e969128204da1/BUILD-sineio.md -
https://github.com/sine-io/cosbench-sineio/blob/a99954458d84e3aa46d3882bae9e969128204da1/BUILD.md -
https://github.com/sine-io/cosbench-sineio/blob/a99954458d84e3aa46d3882bae9e969128204da1/release/conf/.controller/config.ini -
https://github.com/sine-io/cosbench-sineio/blob/a99954458d84e3aa46d3882bae9e969128204da1/release/conf/.driver/config.ini -
https://github.com/sine-io/cosbench-sineio/blob/a99954458d84e3aa46d3882bae9e969128204da1/pack-sineio.sh -
https://github.com/sine-io/cosbench-sineio/blob/a99954458d84e3aa46d3882bae9e969128204da1/pack.sh -
https://github.com/sine-io/cosbench-sineio/blob/a99954458d84e3aa46d3882bae9e969128204da1/README.md

## 3. 尚未确认的事项

以下均为审计任务，不应预设答案：

-   首次审计的分支/HEAD/工作区快照见 audit/repository-state.md；任务执行时必须在 Docker 容器内重新检查当前状态。
-   所有 Eclipse
    `.project`、`.classpath`、`.settings`、`MANIFEST.MF`、`build.properties`
    的实际分布。
-   `BundleActivator`、`BundleContext`、OSGi Service
    Registry、Declarative Services、反射和动态类加载的使用范围。
-   Spring OSGi 相关 JAR 是否仍被源码实际使用，还是仅遗留在发行目录。
-   各 Bundle 的 Import-Package、Export-Package、Require-Bundle
    和版本约束。
-   依赖的准确版本、来源、许可证、已知漏洞和 JDK 兼容性。
-   Controller 与 Driver 的完整启动/停止顺序、端口和 HTTP API。
-   XML workload 配置解析、操作注册、统计聚合和结果报告的代码路径。
-   现有自动化测试的覆盖范围与可运行性。
-   是否能在不使用 Eclipse 的情况下先构建现有 OSGi bundles。
-   目标 Java 版本的可行性。Java 21 只是候选目标，不是已确认兼容的承诺。
-   各发行包是否必须保持旧目录布局、脚本和外部接口完全兼容。

## 4. 决策与执行边界

架构决策及状态以 [05-architecture-decisions.md](05-architecture-decisions.md) 为准，候选设计见 [03-target-architecture.md](03-target-architecture.md)。授权、Docker-only、工作区保护和停止条件以 [01-codex-execution-contract.md](01-codex-execution-contract.md) 为准；阶段状态见 [04-migration-roadmap.md](04-migration-roadmap.md)。
