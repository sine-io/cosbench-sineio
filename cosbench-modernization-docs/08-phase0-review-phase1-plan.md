# 08 — Phase 0 审计评审与 Phase 1 实施计划

历史计划（编写于 2026-10-20）。此后用户授权并完成了收窄的 Tycho 构建/导出和打包验证；操作步骤与构建结果以 `09-tycho-build-and-package.md` 为准。用户报告 Phase 1B 与 Phase 2 手工测试通过，Phase 3 已于 2026-10-21 收口（见 `10-phase3-tycho-closeout.md`）。本文保留当时规划的测试范围，不包含用户手工测试的原始记录，也不授予 Phase 4 及之后工作的执行权限。

目标仓库：`sine-io/cosbench-sineio`

## 1. 评审结论

建议先评审并单独授权 Phase 1A 构建 PoC。本文本身不授予执行权限。本轮文档修订不授权源码、构建配置、CI 或运行路径变更；OSGi 替换、JDK/SDK 升级、业务重写和统计语义调整均未获授权。

### 核心发现

1. 生产 Java 代码直接使用 OSGi API 的位置有限，但运行时依赖 Spring OSGi 服务发现、OSGi WebApplicationContext、Equinox start-level 和 Tomcat Activator。
2. 没有标准根 Maven/Gradle 构建体系；构建依赖 Eclipse PDE 导出。首次审计环境的 Maven、Ant、Gradle 探测失败；这只是历史工具探测，未完成真实构建。
3. Java 8 是现有基线；Java 21 兼容性尚未证明，必须单独升级和验收。
4. 统计语义包含逻辑操作计数、成功样本吞吐量、成功字节带宽、桶式延迟分位数、多层聚合和空快照补偿，必须建立回归保护。
5. 存储适配器功能不对称；SineIO 的 multipart、restore、head、range/prefetch 等能力必须纳入兼容性矩阵。

## 2. 证据摘要

### 构建与仓库
- 32 个 MANIFEST/Bundle；33 个 `build.properties`；473 个 Java 源文件。
- 只有 Manta 模块带 POM，且其构建方式与 PDE 模块不同。
- 发行包由 PDE 导出、Equinox launcher、`dist/osgi/libs`/`plugins` 和打包脚本共同组成。
- `pack-sineio.sh` 会执行 `rm -rf $VERSION`；`version_unify.sh` 会修改 MANIFEST 版本。必须先隔离副作用。
- 首次审计记录的是历史 Windows 工作区状态；详见 audit/repository-state.md。当前 Docker 快照为 refactor/cosbench-restructure / a99954458d84e3aa46d3882bae9e969128204da1，根目录 cosbench-modernization-docs/ 未跟踪，必须保留。

详见 `audit/repository-state.md`、`audit/build-baseline.md`、`audit/bundle-inventory.md`。

### OSGi 运行时
- Java 直接 OSGi API 使用点集中于日志 Activator、Tomcat Activator/注册、Manta Activator 等。
- Spring OSGi XML 导出/收集 `StorageAPIFactory`、`AuthAPIFactory`、`DriverService`、`ControllerService`。
- Controller/Driver web 使用 `OsgiBundleXmlWebApplicationContext`。
- Equinox 启动器和 start-level 配置属于实际运行路径。

详见 `audit/osgi-usage-inventory.md`、`audit/runtime-startup-flow.md`。

### 功能与指标
- workload 的 `storage type` 通过 factory 精确字符串匹配。
- 适配器功能不对称；源码中存在 API 方法不代表真实后端行为已验证。
- throughput 是成功逻辑样本/秒；bandwidth 是成功样本字节/统计窗口；分位数来自桶式直方图。
- auth retry、401 re-login、timeout、worker snapshot compensation 等行为需要测试保护。

详见 `audit/adapter-compatibility-matrix.md`、`audit/workload-and-metrics-semantics.md`。

## 3. Phase 1A：构建工具验证与安全 PoC

### 目标

在不改变 OSGi 运行架构、业务行为和用户工作树的前提下，用 Docker 验证无 Eclipse GUI 的命令行构建 PoC。

### 执行环境与隔离

- 所有命令使用 sudo docker；不得使用宿主机 Java/Maven。
- 使用记录了不可变 digest 的镜像。记录镜像、架构、JDK、构建工具、target platform、依赖来源和校验和。
- 将当前源码只读挂载；复制到容器临时目录后构建。输出和日志写到单独挂载的结果目录。
- 使用 --rm 或只清理本任务创建的容器。不得停止或删除既有用户容器、数据卷或宿主机文件。

### PoC 范围

- 评估 Maven/Tycho 和其他合适的命令行构建路线，不预设 Maven/Tycho 一定可行。
- 至少覆盖一个普通 Bundle、含嵌入式 JAR 的 Bundle、一个 fragment 和 Manta 特殊模块。
- 检查生成的 MANIFEST、Bundle-ClassPath、资源和依赖是否保留 PDE 语义。
- 从同一 commit 做两次干净构建，比较文件清单、MANIFEST 和产物哈希；字节级不可复现时定位时间戳、顺序、权限或其他差异。
- 当前 dist/osgi/plugins 只有 5 个 Equinox/Felix 框架 JAR，没有 COSBench 插件 JAR。它不能作为完整旧版包。只有提供旧发行包版本/commit 和 SHA-256 后才做新旧包对比；否则明确标记“基线缺失/不可比较”。

### 允许

- 检查构建工具和依赖来源。
- 在容器临时副本中创建一次性 PoC 构建文件；如需改动项目正式构建文件、CI 或用户工作树，停止并报告，等待单独授权。
- 输出构建日志、临时包清单和 SHA-256；更新审计文档。

### 禁止

- 启动 Controller/Driver、运行 workload 或宣称功能/性能兼容。
- 删除或改写 Equinox、OSGi、Spring OSGi、业务源码、适配器、MANIFEST 或发行配置。
- 升级 JDK/SDK，改变 HTTP/XML/workload/统计语义。
- 运行 pack-sineio.sh 或 version_unify.sh 操作用户工作树。

## 4. Phase 1B：发行包与运行路径复现

**后续状态更新：已完成（用户报告手工测试通过）。** 以下条目保留当时规划的测试范围；本文件未收录相应用例、日志或结果明细。

只有 Phase 1A 通过审阅并且用户明确授权后才能执行。它与 Phase 1A 分开验收。

- 在 Docker 容器中使用隔离发行目录和独立配置；容器端口需显式映射，不能触碰宿主机现有进程或其他容器。
- 验证 Equinox resolver、Spring OSGi factory list、Tomcat 端口、Driver command endpoint 和 Controller CLI。
- 使用确认过版本/校验和的发行基线；缺少旧包时不伪造新旧比较。
- 运行最小 S3、SineIO（含 mwrite/restore/head）和 Swift/Keystone workload；区分 mock、模拟服务和真实后端。
- 复现 .driver/config.ini 中 httpauth 路径转义问题；保存配置、日志、命令、退出码、请求和报告 golden files。
- 未连接真实后端、依赖凭据或 native rados 的项目标记未验证。
- 结束时停止并删除本任务创建的容器和临时网络；不触碰既有容器、卷和用户文件。

## 5. Phase 1A 验收标准

- [ ] 在固定 Docker 镜像 digest 中完成 PoC；记录 commit、工具链、依赖来源、命令、退出码和日志。
- [ ] 普通 Bundle、嵌入式 JAR Bundle、fragment 和 Manta 模块均有结果。
- [ ] 两次干净构建的清单、MANIFEST、Bundle-ClassPath 和哈希差异可复核；任何不可复现差异有解释。
- [ ] 用户工作树没有被构建污染；临时容器已清理。
- [ ] 旧发行包有版本/commit 和 SHA-256，或明确写明基线缺失且不做新旧比较。
- [ ] 没有启动服务、运行 workload、改业务语义或越权修改正式构建配置。
- [x] Phase 1B 后续取得单独授权；用户报告手工测试通过。Phase 1A 验收本身不替代 Phase 1B 验收。

## 6. 交给 Codex 的 Phase 1A 提示词

~~~text
只有在用户本轮明确授权 Phase 1A 后才执行。读取仓库根目录 cosbench-modernization-docs/ 下的架构、执行约定、审计清单、路线图和 audit/ 全部报告；所有构建与测试遵守 Docker-only 约束。

本阶段只验证命令行构建 PoC。先在最终报告中列出实施计划和范围，然后在授权范围内继续执行；不要仅提交计划后停住。任何超出本次授权的改动必须停止并报告。

开始前：
1. 在 Docker 容器中记录 git status、branch、HEAD；保留所有未跟踪或已修改用户文件。
2. 使用固定 digest 镜像。源码只读挂载，复制到容器临时工作目录；生成物写到独立结果目录。
3. 不运行 pack-sineio.sh、version_unify.sh 或任何对用户工作树有删除/原地改写副作用的命令。
4. 记录镜像 digest、架构、JDK、构建工具版本、依赖仓库及 artifact 校验和。

技术约束：
- 保留 Equinox、OSGi、Spring OSGi、Controller/Driver 运行方式和业务语义。
- 不升级 JDK/SDK，不改业务逻辑、workload、适配器、HTTP API 或统计语义。
- 评估 Maven/Tycho 或其他方案；在 PoC 中覆盖普通 Bundle、嵌入式 Bundle-ClassPath、fragment 和 Manta 特殊模块。
- 不为构建通过而删除 MANIFEST、依赖或资源。
- 当前 dist/osgi/plugins 缺少 COSBench 插件 JAR，不得冒充完整旧发行包。只有获得明确的旧包版本/commit 与 SHA-256 后才比较；否则报告基线缺失。
- 从同一 commit 执行两次干净构建，比较产物清单、MANIFEST、Bundle-ClassPath 和哈希，并解释非确定性差异。
- 临时 PoC 成功前不改正式构建文件、CI 或用户工作树；若必须改动，停止并请求新的授权。

交付：
1. PoC 结果和构建路线建议；
2. 固定镜像 digest、工具链、依赖来源/校验和；
3. 每条命令、退出码、日志及两次构建差异；
4. 旧发行包基线的版本和 SHA-256，或明确标记缺失；
5. 用户工作树差异检查、临时容器清理结果；
6. 未解决风险和回滚方法。

以上是当时 Phase 1A 提示词的范围限制，后续用户已单独授权并报告 Phase 1B、Phase 2 手工测试通过；Phase 3 已完成收口。Phase 4 及之后仍需审阅和单独授权。
~~~

## 7. 当前决策

本文件在 2026-10-20 修订时的授权记录仅覆盖 cosbench-modernization-docs/ 文档修订。之后用户报告 Phase 1B 和 Phase 2 手工测试通过，并单独授权完成 Phase 3。阶段提示词本身不授予权限；Phase 4 及之后工程工作仍须以当轮用户明确授权为准。OSGi 移除、JDK/SDK 升级和性能优化不包含在 Phase 3 授权中。
