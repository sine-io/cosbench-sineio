# 12: 交付旧 SDK 的 S3 生产 Provider

**What to build:** 让 s3 在正式 Driver 中通过静态 registry 完成存储任务，保持旧 AWS SDK 的配置、签名和错误语义。

**Blocked by:** 10: 交付 Driver Boot 与完整本地 Mock mission 链路

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 PROV-S3 和其对应章节。

**Inputs:** #04 的 s3 及共存验证、#10 的 Driver/registry、#01 的 S3 基线。

- [x] 迁移 S3 模块的目录/资源/普通 Maven 依赖及生产 descriptor，发现名称仍为 s3，保留旧 AWS SDK 调用和配置约定。
- [x] 覆盖合法/缺字段/非法类型/未知 key 配置以及 endpoint、签名、region/path-style 等既有适用参数，不把 SDK 升级混入迁移。
- [x] 在 MinIO 验证声明支持的 put/get/list/delete、multipart 和 #04 的其他适用操作，比较结果及数据；列明不适用依据，必需失败项不能豁免。
- [x] 认证、超时、HTTP 4xx/5xx 和实际底层错误映射与基线一致；mission close/abort/TERM 后 client 和线程资源被释放。
- [x] 按 #04 冻结方案检查正式 Driver 依赖和隔离关闭行为，输出 production inventory、license/checksum/样例及 CI smoke；#19 再与三种 SineIO 同时装载验证。

## 实现边界

本票补齐必需的 list/multipart，并提供 head 支持供结果验证；#04 中继承 NoneStorage 的空 list/multipart 是能力缺口，不计作通过。list 输出 Driver 使用的 UTF-8 换行 key，multipart 使用 SDK v1 的 initiate/upload/complete，失败时 abort，空对象走普通 PUT。restore 在旧 S3 没有实现或配置，不宣称支持；`aws_region` 是 SineIO 专用键，旧 S3 固定 `S3SignerType` 且未读取 region。本票保留 #04 已验证的共享 AWS SDK v1 `1.12.767`，不新增 SDK 升级。

## 实现与本地验收

2026-11-02：生产 `cosbench-s3` Maven 模块及 StorageAPI descriptor 已加入正式 Driver；配置、签名、路径风格、HTTP 状态/cause、timeout、connection refusal 和 dispose 通过公共 API 测试。MinIO 实际验证 put/get/list/head/delete、空对象及大于 5 MiB 的 multipart 数据完整性；Driver mission close/abort 与活动 mission 上 TERM 释放均通过。全 reactor Java 21 Docker `clean verify` 共 131 项测试通过，17 个模块的依赖、许可证、校验和、重复类与制品闸门通过，完整 CI 重放 `run_status=0`、`cleanup_status=0`，本轮容器、网络、卷及证书容器均清理。

实现说明见 [S3 production provider](../../../shadow/s3-provider.md)，可重放命令、配置样例、生产制品清单、license/checksum、MinIO/Driver 测试和 CI 报告见 [验收证据](../../../shadow/evidence/2026-11-02/s3-provider/README.md)。Driver 与 SineIO 三模式的组合复验归 #19。
