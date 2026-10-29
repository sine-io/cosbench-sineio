# 04: 验证全部 factory 的 SPI 生命周期与 SDK 共存

**What to build:** 在新工具链中启动发现 Mock、全部 SineIO 模式及 S3，完成真实操作和关闭，提前确认共享 classpath 是否可用。

**Blocked by:** 02: 建立最小 Java 21 影子构建与 Docker/MinIO 环境

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 GATE-SPI 和其对应章节。

**Inputs:** #01 的 Provider 能力/操作基线、#02 的 Maven/Docker/MinIO harness；沿现有 factory 接口测试，不等待完整 Driver 或生产打包。

- [x] 在有限验证程序中通过静态 ServiceLoader 发现 mock storage/auth、sio、siov1、gdas、s3；记录各 factory 类、descriptor 和依赖。Mock 只出现在测试 classpath。
- [x] 对每个 factory 分别验证合法/缺失/非法配置、init、至少一个声明支持的 operation、dispose；Mock auth 验证真实认证生命周期，四个生产 storage 使用 MinIO 或明确兼容的受控 endpoint。
- [x] 全部生产 factory 同时加载并依次操作，保留 dependency tree、重复类报告、SDK 来源和实际 smoke，证明隔离环境中的成功不是唯一证据。
- [x] 共享 classpath 验证成功，未复现冲突，因此不需要 child classloader；SPI 类型边界保持为 StorageAPI/AuthAPI。
- [x] 逐 factory 固定后续操作/错误矩阵的适用项，包括 put/get/list/delete、multipart、未知配置、403/404、连接失败、500、超时、关闭后调用和各自额外支持行为；S3 继承的空 list/multipart no-op 有源码和运行证据，未覆盖的专有行为列入后续票。
- [x] 验证缺 descriptor、缺依赖、重名、初始化错误的诊断及释放行为；缺 descriptor 的目标名称由显式期望清单检查。
- [x] 输出全部六类注册的结果、命令、配置 fixture、资源关闭证据、共存方案和未解决问题；保持 SDK 大版本和现有调用语义。

证据见 [Provider SPI 验证记录](../../../shadow/evidence/2026-10-29/provider-spi/README.md)。MinIO 与受控 HTTP fixture 覆盖 S3 兼容路径及错误映射；无效凭据 GET 和 createContainer 对全部 production provider 都映射为 COSBench `StorageException`。legacy S3 原先使用的 AWS SDK v1 `doesBucketExist` 会将 HTTP 403 解释为 bucket 已存在；为满足认证错误映射，本票改用 `headBucket`，仅在 404 时创建，并保留 301 的既有存在语义。SineIO/GDAS 专有 restore、prefetch、range 行为仍留给 #11/#12 的专用 endpoint 回归，不记为本票已验证。

## Comments

- 2026-10-29: Java 21 容器 harness 的共享 classpath smoke 发现全部六类注册并依次操作四个生产 storage；Mock auth/storage 仅位于测试 classpath。固定了全部 factory 的缺省/非法配置、未知 key、403/404、连接拒绝、HTTP 500、慢响应超时及 dispose 后调用的实际行为；S3 bucket 认证错误映射已修正并由 MinIO smoke 覆盖。Mock 的空 dispose 后仍可 login/get，生产 Provider 的具体异常类型也被断言。AWS SDK v1/v2 共存通过，无重复类，因此不引入 child classloader。依赖树、SDK 来源、descriptor 边界、关闭与 Docker 清理结果见验证记录。
