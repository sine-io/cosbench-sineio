# 03 --- 目标架构设计

## 1. 目标

在不改变 COSBench 核心测试语义的前提下，使用标准 Java 运行时、Maven
构建和显式组件装配替代 Eclipse PDE/OSGi 的必要性部分。

这是目标方向，不是要求在审计前照图直接搬移代码。模块最终边界须由源码依赖图确定。

## 2. 目标组件

``` mermaid
flowchart TD
    CLI["启动入口 / 配置"] --> APP["ControllerApplication 或 DriverApplication"]
    APP --> LIFE["Runtime / 生命周期管理"]
    LIFE --> CFG["配置与 Workload 模型"]
    LIFE --> SVC["应用服务"]
    SVC --> EXEC["Driver 执行引擎"]
    EXEC --> SPI["StorageAdapter SPI"]
    SPI --> S3["S3 适配器"]
    SPI --> SIO["SineIO 适配器"]
    SPI --> OTHER["其他适配器"]
    EXEC --> METRICS["本地统计聚合"]
    METRICS --> REPORT["周期性结果上报"]
    REPORT --> CTRL["Controller 结果聚合与报告"]
```

Controller 和 Driver 仍是独立进程。它们之间现有的 HTTP/API
协议优先保持兼容。

## 3. Maven 模块候选

建议的逻辑模块（最终根据审计调整）：

-   `cosbench-parent`：依赖版本、插件版本、编译参数和统一构建。
-   `cosbench-common`：公共模型、错误类型、时间/配置工具。
-   `cosbench-workload`：workload XML 解析、内部工作负载模型。
-   `cosbench-adaptor-spi`：存储操作与适配器契约。
-   `cosbench-adaptor-s3`：S3 适配器。
-   `cosbench-adaptor-sineio`：SineIO 特有适配器与操作。
-   `cosbench-adaptor-*`：经审计仍需支持的其他适配器。
-   `cosbench-driver-core`：worker 调度、执行、限流和统计。
-   `cosbench-driver-app`：Driver 入口、服务和生命周期。
-   `cosbench-controller-core`：任务协调、Driver 管理、结果聚合。
-   `cosbench-controller-app`：Controller 入口和 HTTP 层。
-   `cosbench-distribution`：发行包装配、启动脚本和配置。
-   `cosbench-testkit`：mock、测试数据和兼容性测试支持。

不必一开始就拆成所有这些模块。先以现有 Bundle
依赖图确定合理边界；避免模块过多、循环依赖或为了"架构整洁"进行无价值移动。

## 4. 构建系统

### 目标

-   Maven 负责编译、测试、依赖管理和发行包构建。
-   构建在干净环境可重复执行。
-   CI 不需要人工操作 IDE。
-   版本号由一个权威来源管理。
-   发行包生成后自动检查文件、依赖和脚本权限。

### 过渡

Tycho 1.7.0/JDK 8 已验证可构建当前 30 个非 Manta bundles；可暂时使用 Tycho
作为命令行构建桥梁。但这只是移除 IDE 交互的过渡步骤，不能自动视为已移除
OSGi。

### 发行包

优先维持用户熟悉的脚本、配置、端口和目录约定。先让构建系统生成现有兼容发行包；新目录结构可以作为单独版本变更讨论。

## 5. 替换 OSGi 的组件装配

### 5.1 标准入口

Controller 和 Driver 各有明确的 `main`
入口。入口负责读取配置、创建运行时、启动服务，并在退出时关闭资源。

### 5.2 显式依赖注入

优先用构造函数注入和工厂表达依赖，不默认引入大型 DI
框架。依赖关系应该在代码中可见，避免全局服务定位器。

### 5.3 生命周期

定义可测试的生命周期： - 创建； - 配置校验； - 初始化依赖； -
启动服务； - 接收任务； - 停止接收任务； - 完成或取消在途操作； -
关闭客户端/连接池； - 刷新最终统计； - 退出。

### 5.4 适配器发现

若只需要启动时发现适配器，优先评估 `java.util.ServiceLoader`
或显式注册表。适配器契约应具有明确版本兼容策略和资源关闭约定。

Java SPI 不是 OSGi 动态服务的完全等价物： - SPI 通常在启动时发现实现； -
不自动提供运行时热安装/卸载； - 不自动提供 Bundle 级类加载隔离； -
不自动管理服务动态上下线。

若源码审计发现上述能力是产品必要要求，应重新评估插件架构，而不是强行用
SPI 替代。

替换前必须把“启动时发现”“多个依赖版本共存”“Bundle 级类加载隔离”分别列成可验证需求。测试应使用存在重复传递依赖的代表性适配器验证 factory 发现、类型可见性和关闭行为；没有证据证明隔离不再需要时，不能把 SPI 视为 OSGi 的等价替代。

## 6. 模块依赖规则

-   公共模型不得依赖 Controller/Driver 应用层。
-   存储适配器依赖适配器 SPI，不依赖 Driver 内部实现。
-   Controller 不执行存储数据面请求。
-   执行引擎不依赖具体报告 UI。
-   统计模型与协议实现解耦。
-   发行模块只负责装配，不放业务逻辑。
-   尽量避免循环依赖。
-   不因移除 OSGi 而改变请求语义、对象大小、重试或统计。

## 7. JDK 与技术选择边界

Java 21 是候选目标，须在依赖审计、构建和测试稳定后单独评估；不得将 JDK 升级、OSGi 替换与性能优化合并验收，详见 ADR-007。Spring Boot、JPMS、微服务、fat JAR 和运行时热插拔都不是默认要求；只有需求或源码证据支持时再采用。
