# 方案三开发资料

本目录是方案三 MVP 的唯一开发入口。方案三目标是把 Controller、Driver、core、两侧 Web backend、实际依赖闭包、SineIO 和 S3 迁移为两个 Java 21 Spring Boot 应用，同时保留页面模板、静态资源、页面路由、表单和 Controller 到 Driver 的 `/i/*.command` 合同。

## 文档顺序

1. [开发规格与执行清单](development-spec.md)：开发者、测试和发布人员按此执行。
2. [详细实施方案](detailed-plan.md)：目标结构、阶段、模块和回退策略。
3. [模块与 Provider 盘点](module-inventory.md)：逐模块范围、legacy 边界和依赖热点。
4. [验收矩阵](acceptance-matrix.md)：行为、Web、Provider、Docker 和发布验收。
5. [待决策与已确认约束](open-decisions.md)：已经确认的技术选择和后续升级项。
6. [范围估算](scope-estimate.md)：文件、模块和路径改造量估算。

当前开发票、依赖图、验收责任和修订前编号映射见[执行索引](../../../.scratch/scheme3-migration/README.md)。先完成基线与四项前置验证，再领取其后的迁移票。

## 已确认基线

- Java 21 LTS 构建、测试和运行，`maven.compiler.release=21`。
- Spring Boot 3 + Spring Framework 6 + Jakarta Servlet。
- 移除 Spring DM、OSGi、Equinox、Tycho/P2 和旧 Tomcat 6；使用 Boot embedded Tomcat。
- Controller 和 Driver 是两个独立进程；页面后端随各自进程提供。
- Controller 到 Driver 使用 REST/HTTP，保留现有 `/i/*.command` 合同。
- Provider 使用启动时静态 `ServiceLoader`；生产只包含 SineIO 和 S3，Mock 仅测试。
- Docker 是开发和测试标准环境，MinIO 是对象存储 smoke 环境。

上级目录中的 `runtime-modernization.md`、`spring-dm-migration-cases.md` 和 `java21-virtual-threads-performance.md` 是背景资料，不是方案三的实施合同。
