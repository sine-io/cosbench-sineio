# 02 --- 源码审计清单

## 1. 目标

形成一份可追溯的"现状架构图"，回答： - 如何构建？ - 如何启动 Controller
和 Driver？ - OSGi API 实际在哪里使用？ - 模块间依赖是什么？ -
适配器如何发现和初始化？ - workload 如何解析和执行？ -
统计结果如何生成？ - 发行包依赖哪些文件？ - 哪些行为必须保持兼容？

审计完成前不得执行全面移除 OSGi 的操作。

## 2. 仓库状态与工具链

在仓库根目录执行并记录输出：

``` bash
git status --short
git branch --show-current
git rev-parse HEAD
java -version
javac -version
mvn -version
```

如果工具不存在，记录"未安装"，不要立即安装或升级。

识别项目文件：

``` bash
find . -type f \( \
  -name 'MANIFEST.MF' -o \
  -name 'build.properties' -o \
  -name '.project' -o \
  -name '.classpath' -o \
  -name 'config.ini' -o \
  -name 'pom.xml' -o \
  -name 'build.gradle' -o \
  -name '*.launch' \
\) -print
```

## 3. OSGi/Eclipse 使用点

使用 `rg`（若不可用，使用 `grep -R`）：

``` bash
rg -n --glob '*.java' \
  'BundleActivator|BundleContext|BundleReference|ServiceReference|ServiceRegistration|FrameworkUtil|ServiceTracker|BundleWiring|Bundle\\.class|org\\.osgi\\.' .

rg -n --glob '*.java' \
  'getBundleContext|registerService|getServiceReference|getService\\(|ungetService|installBundle|start\\(|stop\\(' dev ext

rg -n \
  'osgi\\.bundles|osgi\\.framework|osgi\\.startLevel|eclipse\\.ignoreApp|org\\.eclipse\\.osgi|spring-osgi|Bundle-Activator|Import-Package|Export-Package|Require-Bundle' \
  dev dist release
```

注意：这些搜索会产生误报。逐个打开上下文，区分： - 生产代码； -
测试代码； - 仅在配置/打包中出现； - 注释或文档； - 未被引用的遗留文件。

为每个使用点记录：
`文件 | 符号/配置 | 当前作用 | 替代方式 | 风险 | 测试方法`。

## 4. Bundle 清单和依赖图

对每个 `dev/*/META-INF/MANIFEST.MF` 记录： - Bundle-SymbolicName； -
Bundle-Version； - Bundle-Activator； - Import-Package； -
Export-Package； - Require-Bundle； - Fragment-Host（如存在）； -
DynamicImport-Package（如存在）。

对每个 `dev/*/build.properties` 记录： - source..； - output..； -
bin.includes； - source bundles / resources； - 非 Java 资源。

输出： 1. Bundle 清单表； 2. Bundle 依赖有向图； 3. 环依赖清单； 4. 每个
Bundle 的目标 Maven 模块候选； 5. 不能直接迁移的资源或打包规则。

不要仅根据目录名称推断模块边界。

## 5. Controller 和 Driver 启动路径

检查： - `release/conf/.controller/config.ini` -
`release/conf/.driver/config.ini` -
`release/start-controller.sh`、`start-driver.sh`、`start-all.sh`、停止脚本（以实际文件名为准） -
`dist/main` 中的启动器和参数 - Tomcat 配置及 HTTP 端口 - Controller 与
Driver 的 HTTP 路径/协议 - 进程退出、信号处理、PID 文件和日志初始化

记录启动顺序、关键系统属性、端口、工作目录、配置路径、停止流程和依赖。

输出 Controller 与 Driver 两张启动序列图（Mermaid）。

## 6. 存储适配器审计

检查所有 adapter/storage 相关 Bundle 和实现类。记录： - workload 中的
storage `type` 如何映射到适配器； - 适配器注册/发现机制； -
适配器初始化配置； - 认证、endpoint、TLS、region 等参数； - 每种 storage
支持的 work/operation； - 各适配器的 SDK 及版本； - 特殊功能与 SineIO
扩展； - 资源关闭行为； - 失败、超时和重试行为。

至少要检查 README 中声明的操作和特性。不能只验证 S3 的基础 PUT/GET。

输出"适配器兼容性矩阵"，并区分： - 源码已确认； - 文档声称但尚未测试； -
已知缺陷； - 本次迁移必须保留； - 可以未来废弃但当前不删。

## 7. Workload 与统计语义

追踪一条完整链路： XML/配置解析 → 工作负载模型 → workstage/operation
分配 → worker 调度 → 存储请求 → 单操作结果 → Driver 聚合 → Controller
聚合 → 报告。

记录： - `workers`、`totalOps`、`runtime`、ratio 等字段语义； -
对象大小与对象命名生成； - 预热阶段与正式统计阶段； - 逻辑操作与 HTTP
请求的关系； - 重试是否重复计数； - 延迟起止时间； -
吞吐量的字节口径； - 失败和超时统计； - 分位数算法； - 多 Driver
聚合逻辑； - 结果报告字段。

如果某项无法从源码确定，明确标记"待测试确认"。

## 8. 依赖与 JDK 兼容性

从 `dist/osgi/libs`、`dist/osgi/plugins`、MANIFEST、源码 import、SDK
元数据、许可证文件中建立依赖清单。

字段建议：
`artifact | version | usage | direct/transitive | license | current evidence | target-JDK risk | upgrade path`.

重点审计： - AWS SDK Java v1/v2； - Spring 及 Spring OSGi； -
Tomcat/Servlet； - Apache HttpClient/HttpCore； - XML/JSON/序列化库； -
日志库； - 反射/内部 JDK API； - TLS/加密组件； - 许可证和 NOTICE 义务。

不要仅凭 JAR
文件名判断依赖是否仍被实际使用。检查源码、MANIFEST、运行时路径及测试。

## 9. 构建和测试基线

构建、启动和测试只能在符合 01 执行约定的 Docker 容器中运行，使用固定镜像 digest 和 sudo docker；不得用宿主机工具代替。

先检查脚本再执行。记录： - 原版构建命令； - 是否依赖 Eclipse 导出后的
JAR； - 构建是否下载外部依赖； -
是否需要网络、特定操作系统或额外命令； - 构建产物清单； - 启动 smoke
test； - 可运行的单元/集成测试； - 目前无法自动验证的功能。

若构建失败，保存原始错误和退出码。不要为使构建通过而先删除依赖或修改业务代码。

## 10. 必须交付的审计报告

在 `cosbench-modernization-docs/audit/` 创建：

-   `repository-state.md`
-   `build-baseline.md`
-   `osgi-usage-inventory.md`
-   `bundle-inventory.md`
-   `runtime-startup-flow.md`
-   `adapter-compatibility-matrix.md`
-   `workload-and-metrics-semantics.md`
-   `dependency-and-jdk-compatibility.md`
-   `risks-and-recommendations.md`

所有结论附路径、符号和行号。文档中区分事实、推断和未确认事项。
