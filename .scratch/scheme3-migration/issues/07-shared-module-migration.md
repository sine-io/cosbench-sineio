# 07: 按已验证合同迁移公共模块

**What to build:** 将已验证的公共 API、配置、模型、序列化、日志和 HTTP 基础整合为可被两应用和 Provider 消费的普通 Maven 模块。

**Blocked by:** 03: 验证统一 YAML、配置转换与最小启停链路; 04: 验证全部 factory 的 SPI 生命周期与 SDK 共存; 05: 验证代表性页面和 multipart 在 Boot/Jakarta 可用; 06: 验证带服务认证、重试和幂等的 Driver HTTP 链路

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 CORE-01 和其对应章节。

**Inputs:** #03–#06 四项均须提供通过证据（此处显式列出四个技术闸门）；#02 的代表性模块与构建骨架直接复用，避免重复迁移。

- [ ] 逐项核对四个 spike 的命令、结果和冻结合同；必需项未通过时不展开批量迁移。
- [x] 本票仅迁移 API/config/Castor/core/HTTP/log 等公共依赖闭包，标准化源码/资源/测试目录及显式依赖；Controller/Driver/Web/Provider 源码迁移归各后续票。
- [x] 原 StorageAPI/AuthAPI/factory、model/protocol、workload XML/Castor 语义保持；公共模块只依赖接口，不泄漏具体 SDK 类型。
- [x] 将最小验证中的配置和公共适配整合为唯一可复用实现，保留可运行验证，消除重复临时实现；记录各 spike 后续仍需整合的内容及首责票。
- [x] 普通 Spring Bean 可以消费公共模块，日志由应用生命周期管理；验证 init/使用/dispose、错误诊断和资源关闭，移除对 OSGi 服务装配的活跃依赖。
- [ ] 在 Docker 中对本批次空缓存 clean verify，验证配置转换、协议序列化、资源读取及 public SPI 行为，四项验证仍可重放；每个迁入模块的产物/版本可追踪。

## Comments

- 2026-10-29: 按本次明确请求实现公共模块批次。已核对 #03–#06 的命令、报告和冻结合同；#04–#06 有通过证据，但 #03 仍缺 #01 的真实历史 archive/workspace/mission fixture，四票均待人工验收，因此首项闸门保留未勾选，未将代表性 fixture 记为外部证据通过。
- 2026-10-29: `shadow/` 现在提供 API、config、Castor 资源、core、HTTP、log、Log4j 资源七个普通 Maven JAR。公共源码仅保留原接口与模型，不含具体 SDK 或 OSGi 装配；三个 spike 消费公共 JAR。静态 Provider registry 从 #04 提取到 API 模块；删除 Web 的重复 Log4j 适配和 HTTP 的临时空日志实现。XML mapping 仅来自标准 resources 目录，YAML/INI 转换仍复用 #03 实现。
- 2026-10-29: 旧 Log4j 接口保留，只适配 SLF4J formatter 的返回类型；Spring 管理 Controller service 与系统日志的关闭顺序。Castor 排除旧 StAX API 与未使用的 Spring 集成，Java 21 提供 StAX；Boot 消费方排除 Commons Logging 旧实现并使用已存在的 Spring JCL。Castor XML 写入仍需 #05 冻结的 Java 21 `--add-exports`，未升级 serializer。
- 后续责任：#09/#10 整合完整应用装配和日志生命周期；#10 使用公共 registry 整合 Driver factory service；#11/#12 迁入 Provider 源码并扩展专用 operation；#13 迁入 HTTP handler/认证/幂等；#14 将已验证客户端接入 Controller；#15–#17 扩展 Web；#18 扩展配置与运维命令；#08 建立持续依赖/许可证/jdeps 闸门。当前 spike 内的这些专用适配不作为公共模块再次复制。
