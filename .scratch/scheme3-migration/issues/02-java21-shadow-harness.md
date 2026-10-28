# 02: 建立最小 Java 21 影子构建与 Docker/MinIO 环境

**What to build:** 让开发者以一个可重放入口构建代表性模块并连接 MinIO，为后续验证提供共同工具链和环境。

**Blocked by:** 01: 冻结旧版行为、性能基线与回退制品

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 BUILD-01、ENV-01 和其对应章节。

**Inputs:** #01 的固定基线、重放方式及旧构建入口；本票只建立构建骨架和一个代表性基础模块，不要求迁完活跃闭包。

- [x] 新建普通 Maven 影子 reactor，选一个现有基础模块验证标准 source/resource/test layout、资源读取及普通 JAR；新构建不调用 Tycho/P2。
- [x] 在影子 reactor 的代表性模块强制 Java 21 构建/测试/运行和 `maven.compiler.release=21`，并把同一规则登记为后续每个迁入模块的 CI policy；根据官方兼容说明固定 Boot 3.x patch/BOM、Maven/插件及镜像版本，记录可重复的版本证据。
- [x] 从干净 checkout 和空 Maven 缓存完成代表性模块 clean verify，记录产物、资源清单、依赖树、重复类初查和本地 JAR 来源/checksum/license。
- [x] 保留旧构建的固定 checkout/完整归档；标准目录移动只发生于本票的代表性模块，根入口切换和旧元数据清理由 #23 承担。
- [x] 提供可复用 Java 21 Docker/MinIO harness，固定测试凭据、bucket/对象准备、健康检查、证书/secret 注入和报告出口；可独立启动 Provider 测试，不依赖生产应用。
- [x] 用实际 MinIO put/get/delete 探针验证环境；提供为 HTTP 验证注入断连/响应丢失/超时的测试接入方式及独立测试证书，避免后续重复搭环境。
- [x] 所有 Docker 调用带 sudo；正常退出、测试失败和中断都保留日志并清理本轮容器/网络/临时资源，已有用户数据和外部资源保留。

## Comments

- 2026-10-28: User authorized proceeding with the available #01 artifacts while keeping BASE-01/PERF-01 acceptance open; this does not mark #01 passed. Agreed test seams are the Java 21 clean Maven build and a scripted MinIO put/get/delete smoke.
- 2026-10-28: Added the isolated `shadow/` reactor and `cosbench-config` XML-resource slice, parent Java 21/release=21/Maven policy, pinned Boot BOM/plugins/images, per-module CI policy, packaged JAR runtime probe, dependency/resource/checksum/license reports, MinIO + Toxiproxy Compose harness, independent test certificates/secrets, and cleanup on success/failure/interruption. See [toolchain evidence](../../../shadow/toolchain-evidence.md).
- 2026-10-28: Pre-review end-to-end replay `/tmp/cosbench-scheme3-02-pre-review-20261028` passed the Maven test, packaged runtime Java 21/class-major-65 check, all eight mapping resources, dependency duplicate-class scan, real MinIO put/get/delete, and three observable HTTP fault probes. Enforcer rejected JDK 8/Maven 3.9.9 and `maven.compiler.release=17`.
- 2026-10-28: Final clean detached checkout at `9f4ec4ac366fb0e468fc8557040260892449cb6f` passed the complete empty-cache harness in `/tmp/cosbench-scheme3-02-final-clean2-20261028`: `run_status=0`, `cleanup_status=0`, no remaining project or certificate containers/network/volume. A direct TERM during a long custom test returned `143` and cleanup `0`. [Versioned reports](../../../shadow/evidence/2026-10-28/README.md) include build, artifact/resource/dependency/license/checksum, runtime, negative-policy, MinIO, HTTP-fault and cleanup evidence. [Standards and Spec review](../../../shadow/review.md) passed after the two reported cleanup/diagnostic gaps were fixed. Status is ready-for-human for ticket 02 acceptance; #01's acceptance gate remains open.
