# Risks and recommendations — Phase 0

> 本报告记录 Phase 0 时的审计结论和建议。之后已完成 Tycho 构建/导出及发行打包；用户报告 Phase 1B 和 Phase 2 手工测试通过。当前阶段状态见 `../README.md` 与 `../04-migration-roadmap.md`；本报告不包含这些手工测试的原始记录。

## 现状架构（已确认 + 推断）

这是一个 PDE/Eclipse 打包的 Equinox 运行时：`release/cosbench-start.sh:47` 用 Equinox launcher 启动 `.controller` 或 `.driver` configuration；start-level 先加载 Spring 3/Spring OSGi、Tomcat、config/log/core，再由 Spring OSGi 发布/引用 ControllerService、DriverService、StorageAPIFactory、AuthAPIFactory。Controller 以 `StageRunner` 调度 tasklet，通过 HTTP `driverUrl/i/<command>.command` 驱动 Driver；Driver 将 mission 解析成 worker/operator，按 storage/auth factory 的精确 type 字符串选择适配器；WorkAgent 记录窗口 Mark，Driver/Controller 逐层 merge Metrics/Report。

主要证据：

- Equinox/配置：`release/conf/.controller/config.ini:1-81`, `.driver/config.ini:1-99`。
- 服务图：`dev/cosbench-*/META-INF/spring/*osgi*.xml`，详见 [osgi-usage-inventory.md](osgi-usage-inventory.md)。
- Controller/Driver：`COSBControllerService.java:66-145`, `StageRunner.java:124-250`, `AbstractHttpTasklet.java:55-75`, `COSBDriverService.java:83-196`。
- workload/统计：`MissionHandler.java:91-214`, `WorkAgent.java:141-270`, `Metrics.java:210-228`, `Aggregator.java:50-112`。

## 关键风险登记

| ID | 风险 | 状态/证据 | 影响 | 建议的验证/缓解 |
|---|---|---|---|---|
| R0-1 | 构建不可重复 | 已确认：仅 PDE + 一个独立 Maven POM；mvn/ant/gradle 不可用；`BUILD.md:31-52` | 无法从干净环境重建发行包 | Phase 1 先固定 JDK 8/PDE 或确认 Tycho，不改业务代码 |
| R0-2 | OSGi/Spring OSGi 是运行时核心 | 已确认：XML service/list/reference、Activators、web context | 直接移除会改变发现/启动时序 | Phase 1 只做构建/发行包复现；Phase 4 才替换 |
| R0-3 | `.driver/config.ini` httpauth 转义异常 | 已确认原文 `:79` | 单个 auth bundle 可能未启动 | 用 Equinox console/日志最小复现，记录 resolver 状态 |
| R0-4 | Java 21 不被证据支持 | 已确认：headers JavaSE-1.8/1.6，BUILD 文档 JDK1.8；主机仅 Java21 | 启动、反射、XML、Servlet、SDK/TLS 失败可能性 | 在 Java 8 基线上先做语义回归，再单独 Java21 探测；禁止预先升级 |
| R0-5 | 依赖/类路径冲突 | 已确认：旧 Spring/Http/Jackson/Log4j 与 SineIO AWS v1/v2/Netty 嵌入；`Bundle-ClassPath` 多处 | resolver 或运行时 NoSuchMethod/Linkage/行为差异 | 保存完整 bundle classpath；逐 bundle wire 与 adapter smoke |
| R0-6 | 适配器能力不对称 | 已确认：multipart/restore/head 主要 SIO/SIOV1/GDAS；S3 基础方法无这些 API；README `:28-52` 声称更多特性 | workload 在不同 type 上可能失败或语义不同 | 用矩阵做每个 operation 的明确测试；缺失能力保持显式失败 |
| R0-7 | 统计兼容性脆弱 | 已确认：runtime window、successful/total、blank snapshot compensation、bucket percentile | 重构后指标看似变快但口径改变 | 先冻结 golden workload/report；多 worker/driver 回归 |
| R0-8 | 启停脚本环境强绑定 | 已确认：Linux `nohup/telnet/ps/awk/sed/tar`、相对工作目录、无 PID；`cosbench-stop.sh:29-56` | Windows/容器/CI 启停失败或误杀进程 | Phase 1 记录最小发行包环境契约；暂不替换脚本 |
| R0-9 | 打包脚本有破坏性副作用 | 已确认：`pack-sineio.sh:25-45` `rm -rf`，`version_unify.sh:9-12` 改 manifests | 误删发行目录/改版本，破坏人工改动 | 构建 probe 前先 dry-run；保留脚本不执行 |
| R0-10 | Java 内部/反射 API | 已确认：Process `pid` private reflection；`Class.newInstance`; Jersey system properties | Java21强封装/弃用导致失败 | 单独代码路径测试；不要通过 `--add-opens` 掩盖兼容决策 |
| R0-11 | Manta 混合构建/版本漂移 | 已确认：POM 依赖 CosBench 0.4.1.0 provided，当前 bundles 0.6.0.1；无 repo MANIFEST | 生成 bundle 可能与当前 service API 不匹配 | 单独构建、检查 generated MANIFEST 和 factory service |
| R0-12 | 许可证/嵌入源 JAR 未闭合核对 | 待验证：SineIO Bundle-ClassPath 含 sources，pack 复制 license 文件 | 发布合规与升级成本未知 | 逐 artifact 对 `pkg.lst`/NOTICE/third-party PDF 做清单 |
| R0-13 | 历史仓库状态可能误导后续任务 | 已确认：首次审计是 Windows 快照；当前 Docker 检查为 refactor/cosbench-restructure、HEAD a99954458d84e3aa46d3882bae9e969128204da1、?? cosbench-modernization-docs/ | 恢复历史删除、漏保留当前未跟踪文档 | 每次任务在 Docker 中刷新 status/branch/HEAD；只处理明确授权文件 |

## 可行迁移顺序（只给顺序，不执行）

1. **Phase 0 静态审计**：报告已生成。
2. **Phase 1A Tycho 构建与发行打包**：已完成，操作方法及产物对比见 `../09-tycho-build-and-package.md`。
3. **Phase 1B 运行基线**：用户报告手工测试通过；详细记录未随本报告归档。
4. **Phase 2 测试护栏**：用户报告手工测试通过；详细记录未随本报告归档。
5. **Phase 3 Tycho 多模块构建收口**：已完成，验证结果见 [`../10-phase3-tycho-closeout.md`](../10-phase3-tycho-closeout.md)。
6. **Phase 4 OSGi 替换**：下一项路线图任务，仍须单独审阅和授权；评估标准入口、显式装配、生命周期、adapter registry 和 classloader 边界。
7. **Phase 5 JDK/第三方升级**：后续单独处理 Java 21、AWS SDK、Spring/Tomcat/HttpClient/XML/logging/TLS。
8. **Phase 6 性能优化**：架构稳定后，以统计语义不变和可复现性能数据为前提。

## Phase 1A/1B 验证边界

Phase 1A 只验证构建 PoC：固定 Docker digest、工具链/依赖校验和；覆盖普通 Bundle、嵌入 JAR、fragment 和 Manta；同一 commit 两次干净构建并比较清单、MANIFEST、Bundle-ClassPath 与哈希。撰写本建议时的 dist 缺少 COSBench 插件 JAR，未取得外部旧发行包及 SHA-256 前不得声称完成新旧发行比较。Phase 1A 不启动服务或跑 workload。

Phase 1B 的历史验证范围见 `08-phase0-review-phase1-plan.md`。用户报告手工测试通过；若后续需要复核或补齐证据，应单独明确范围，并在 Docker 临时容器内执行，不得把缺少真实后端的测试表述为真实后端验证。

## 停止点

Phase 0 静态源码审计报告已生成；Phase 1A 构建与打包已完成，Phase 1B 和 Phase 2 按用户报告手工测试通过，Phase 3 已完成收口。Phase 4 是下一项路线图任务，仍须单独授权；后续阶段不会因本报告而自动获批。
