# COSBench SineIO 架构现代化

> 截至 2026-10-21：Tycho 1.7.0/JDK 8 的 Bundle 构建、导出和发行包生成已完成。Phase 3 多模块构建收口已完成，验收报告见 `10-phase3-tycho-closeout.md`。用户报告 Phase 1B 与 Phase 2 手工测试通过；具体测试用例和原始记录尚未随文档归档。

## 目标

保留 Java、COSBench/SineIO 压测能力和现有外部行为，分阶段验证可重复构建、运行时架构替换及后续 JDK 升级。不得把静态审计当作运行验收，也不得在无基线时宣称性能提升。

## 阅读顺序

1. `01-codex-execution-contract.md`：授权、证据、Docker-only 和工作区保护规则。
2. `00-context-and-decisions.md`、`05-architecture-decisions.md`、`03-target-architecture.md`：背景、决策和候选架构。
3. `09-tycho-build-and-package.md`：Tycho 构建、旧包核对、导出到 `dist/osgi/plugins` 和安全打包操作指南。
4. `04-migration-roadmap.md`：阶段与门槛；完整 Phase 1B 范围的历史计划见 `08-phase0-review-phase1-plan.md`。
5. `06-validation-and-benchmark-plan.md`：兼容性和性能验证要求。
6. `audit/`：源码审计证据；`02-source-audit-checklist.md` 可用于复查。
7. `07-codex-task-prompts.md`：后续阶段提示词。

## 阶段状态

| 阶段 | 状态 |
|---|---|
| Phase 0 静态审计 | 报告已生成 |
| Tycho 构建/导出与 tar 打包（当前收窄目标） | 已完成；用户报告手动解包后程序可用，操作方法见 `09-tycho-build-and-package.md` |
| Phase 1B 运行/功能基线 | 已完成；用户报告手工测试通过 |
| Phase 2 测试护栏 | 已完成；用户报告手工测试通过 |
| Phase 3 Tycho 多模块构建收口 | 已完成；30 个 Bundle 构建、元数据/资源核对通过，详见 `10-phase3-tycho-closeout.md` |
| Phase 4 OSGi 运行时替换 | 下一项路线图任务；未开始，须单独审阅和授权 |
| Phase 5–6 | 未开始；须按路线图逐阶段审阅和授权 |

阶段完成不自动授权下一阶段。提示词和本文档也不构成执行授权。

## 已核实的关键边界

- 源码盘点为 32 个 Bundle/MANIFEST、33 个 `build.properties`、473 个 Java 源文件。当前根目录 Tycho POM 管理 30 个非 Manta Bundle；Manta 保持独立 Maven/PDE 处理。
- 30 个 Tycho Bundle 与 `旧包/plugins` 中 30 个 `cosbench-*` Eclipse JAR 文件名对应；两种构建产物并非字节级相同。旧 JAR 目录是比较基线，不能把它与本次 Tycho 输出混为一份发行物。
- Java 21 兼容性仍未证明；未来性能优化仍需使用可复现基线。Phase 1B/2 手工测试结果为用户报告，详细用例与原始记录尚未归档。
- 项目构建、调试和测试必须在固定 digest 的 Docker 容器内完成，使用 `sudo docker`；不得使用宿主机 Java/Maven。Tycho 操作流程见 `09-tycho-build-and-package.md`，隔离规则见执行约定。

首次审计环境快照仅作历史记录；任务开始时须重新检查工作区。打包脚本存在删除或原地改写副作用，构建与打包应在隔离副本中运行。
