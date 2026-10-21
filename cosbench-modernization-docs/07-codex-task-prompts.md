# 07 — 可直接交给 Codex 的阶段任务提示词

所有路径均相对于仓库根目录。Phase 1A 的 Tycho 构建/发行包流程见 `09-tycho-build-and-package.md`；Phase 3 收口结果见 `10-phase3-tycho-closeout.md`。用户报告 Phase 1B 和 Phase 2 手工测试通过。下列已完成阶段提示词仅保留历史范围，不是待执行任务。Phase 4 及之后仍需按阶段审阅并取得明确授权；所有构建、调试、启动与测试必须遵守 01 的 Docker-only 约束。

## Prompt 0：静态审计（报告已生成）

Phase 0 静态源码审计报告位于 cosbench-modernization-docs/audit/。此提示词记录的是当时的静态审计任务；当前 Tycho 构建和打包状态见 `09-tycho-build-and-package.md`。完整运行基线和测试护栏后来由用户报告手工测试通过；本提示词不代表后续阶段授权。

## Prompt 1A：构建工具验证与安全 PoC（历史提示词；收窄目标已完成）

本提示词已由当前收窄的 Tycho 构建/打包任务取代，不要重复执行。操作步骤见 `09-tycho-build-and-package.md`。Phase 1A 本身不自动授权后续阶段；Phase 1B/2 当前状态见上文，Phase 3 已收口，Phase 4 以后仍需单独授权。

## Prompt 1B：完整发行包与运行路径基线（已完成；用户报告手工测试通过）

原计划范围包括 Controller/Driver 启停、Equinox/Spring OSGi、HTTP/CLI 路径和代表性 workload。当前状态按用户确认标记完成；详细用例、日志和结果未附在本提示词中。此节仅作状态记录，不是执行指令。

## Prompt 2：补齐测试护栏（已完成；用户报告手工测试通过）

原计划范围包括配置/workload、适配器契约、核心操作、HTTP/API、错误/超时/重试、统计和发行包启动。当前状态按用户确认标记完成；详细用例、日志和结果未附在本提示词中。此节仅作状态记录，不是执行指令。

## Prompt 3：Tycho 多模块构建收口（已完成；历史提示词）

~~~text
本阶段已按单独授权完成，结果见 `10-phase3-tycho-closeout.md`。不要重复执行。Manta 维持独立构建；Equinox/Spring OSGi 及 Controller/Driver 运行方式和业务语义保持不变。
~~~

## Prompt 4：移除 OSGi（前置阶段验收并获单独授权后）

~~~text
只有测试护栏和模块迁移均通过审阅且用户明确授权后才执行。依据 cosbench-modernization-docs/audit/osgi-usage-inventory.md、cosbench-modernization-docs/audit/runtime-startup-flow.md 和 cosbench-modernization-docs/05-architecture-decisions.md 中已审阅的 ADR，替换 Controller/Driver 的 OSGi 运行时。开始前列出每个 OSGi API/配置的替代方式、服务发现及类加载隔离测试。建立标准 Java 入口、显式组件装配和生命周期管理；仅在启动时发现足够时采用 Java SPI。保持现有适配器、workload、HTTP API、结果格式、重试、计时和统计语义。所有测试在 Docker 容器中运行。必须证明生产运行路径不再依赖 OSGi，并提供回归证据和回滚方案。
~~~

## Prompt 5：JDK 升级（OSGi 迁移通过并获单独授权后）

~~~text
只有 OSGi 迁移通过审阅且用户明确授权后才执行。根据 cosbench-modernization-docs/audit/dependency-and-jdk-compatibility.md，独立评估 Java 21 LTS 迁移。先输出兼容矩阵和升级计划。逐项验证 SDK、TLS、反射、XML、序列化、日志和运行脚本。使用固定 digest 的 Docker 环境运行全量测试、发行包 smoke test 和性能对照。若关键依赖不兼容，先报告替换方案。
~~~

## Prompt 6：性能优化（架构稳定并获单独授权后）

~~~text
只有架构阶段通过审阅且用户明确授权后才执行。只根据 cosbench-modernization-docs/06-validation-and-benchmark-plan.md 的可重复基准数据优化压测引擎。先定位瓶颈，每次只改一个热点。不得减少真实 payload、漏记失败请求、缩短计时区间或改变重试语义来提高数字。提供优化前后 RPS、有效吞吐、p50/p95/p99、错误率、CPU、内存和 GC 对比。所有测试在同一固定 digest 的 Docker 环境中运行；无可重复收益时撤销优化或说明其非性能价值。
~~~
