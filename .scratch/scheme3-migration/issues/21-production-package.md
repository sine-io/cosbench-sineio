# 21: 生成独立生产归档与 Java 21 镜像

**What to build:** 生成只含支持范围的可安装生产制品，同时保持开发/测试镜像独立，供发布验证使用。

**Blocked by:** 11: 交付 sio、siov1、gdas 生产 Provider; 12: 交付旧 SDK 的 S3 生产 Provider; 16: 迁移 Controller 导出、指标与 Web 边界行为; 17: 迁移 Driver 页面与命令共存; 18: 完成配置转换、正式命令与多 Driver 运维

**Status:** completed

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 PKG-01 和其对应章节。

**Inputs:** 各正式应用/Web/Provider/命令的产物及 #08 的审计规则；此票可与完整集成/性能验证并行，制品在 #22 通过前不视为发布验收完成。

- [x] 生产归档包含两个可执行 Boot app、支持的 runtime 依赖、四个 Provider factory、版本化配置模板、正式命令、workloads/配置样例、证书挂载说明和 license/checksum。
- [x] 生产镜像使用 Java 21 runtime，开发/测试工具和 MinIO 编排留在独立镜像；归档说明 Java 21 要求，镜像从干净构建上下文生成。
- [x] 扫描归档/JAR 嵌套内容和镜像，排除 Mock、legacy、源码/source JAR、spike、开发工具、P2、Equinox、OSGi/Spring DM/旧 Tomcat 运行依赖。
- [x] 打包脚本消费普通 Maven 制品，不复制旧 OSGi 发行目录或 Eclipse workspace；必要本地 JAR 的来源/license/checksum 可追踪。
- [x] 同一输入可重建一致的产物清单与 checksum，给出 app/依赖/SPI 清单和安装最小文件集合，CI 包含包装及污染检查。
- [x] 用生产制品执行最小安装、进程 health/stop 探针；完整 MinIO、部署组合、历史数据和回退演练由 #22 使用本票制品验证。

## 完成证据

2026-11-09：实现并验证 [生产打包证据](../../../shadow/evidence/2026-11-09/production-package/README.md)。`mvn package -DskipTests` 在固定 Java 21 Maven 镜像中成功生成 22 个模块的普通 Maven 制品；随后生产归档的确定性重建、归档/JAR/镜像污染扫描、Java 21 runtime 检查，以及空安装 Driver/Controller health/stop 探针全部通过。完整 MinIO、部署组合、历史数据和回退仍由 #22 消费本票制品验收。
