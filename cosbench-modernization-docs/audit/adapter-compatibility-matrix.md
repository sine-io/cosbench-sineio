# Adapter compatibility matrix — Phase 0

## 支持边界：源码、发行、默认加载和实测

下表的 operation 矩阵只说明源码/API 方法存在，不等于适配器已发布或可运行。评审必须分别记录四种状态：源码实现、发行包中有 Bundle、默认启动脚本加载、测试覆盖（并注明 mock/模拟/真实后端）。

当前仓库在 HEAD a99954458d84e3aa46d3882bae9e969128204da1 的 dist/osgi/plugins 只有 5 个 Equinox/Felix 框架 JAR，没有 COSBench 插件 JAR，所以不能据此确认完整发行包。release/start-driver.sh 的默认 Bundle 清单没有 Manta、cosbench-abcauth 或 cosbench-abcstor；它们虽有源码/兼容矩阵条目，却不能标为默认发行支持。其他流程是否注入这些 Bundle 仍待验证。

| 适配器 | 源码存在 | 当前 dist 插件 JAR | 默认 start-driver 清单 | 真实后端验证 |
|---|---|---|---|---|
| S3 / SineIO | 是 | 当前目录未提供 COSBench 插件 JAR | 是 | 未在本次静态审计中运行 |
| Manta | 是 | 否 | 否 | 未验证 |
| ABC Auth / ABC Storage | 是 | 否 | 否 | 未验证 |

矩阵中的“✓”仍只代表源码 API 方法存在。除非发行包和启动路径也有证据，不要将其表述为当前已支持功能。

## 发现/映射机制

**已确认：** workload 的 `storage type` 由 `MissionHandler.createStorageApi` 传入 `COSBStorageAPIService.getStorage(type, config, logger)`（`dev/cosbench-driver/src/com/intel/cosbench/driver/service/MissionHandler.java:204-207`）。`COSBStorageAPIService` 在 `:52-64` 对 `StorageAPIFactory.getStorageName()` 做精确字符串匹配；`none` 走 `NoneStorage`，未知类型抛 ConfigException。Auth 同理：`COSBAuthAPIService.java:52-64`。Factory 列表由 Spring OSGi `driver-osgi-context.xml:11-15` 注入。

## Storage type / 实现 / operation 矩阵

符号：✓ = **源码已确认存在对应 API 方法**；— = 源码中未找到该方法（若 operator 调用则会是 `UnsupportedOperationException`/编译接口实现要求，须进一步测试）；? = 文档声称但本次未连接真实服务验证。`init`/`dispose` 是生命周期，不等同于 workload work。

| `storage type`（factory 证据） | Bundle/实现 | init / dispose | prepare (`createContainer`) | write / filewrite (`createObject`) | mwrite / mfilewrite (`createMultipartObject`) | read (`getObject`) | restore | head | list / cleanup (`getList`/`deleteContainer`) | delete (`deleteObject`) | 特殊配置/SDK证据 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `s3` (`dev/cosbench-s3/src/com/intel/cosbench/api/S3Stor/S3StorageFactory.java:8-14`) | `dev/cosbench-s3/src/com/intel/cosbench/api/S3Stor/S3Storage.java` | ✓ `:29,85` | ✓ `:106` | ✓ `:119` | — | ✓ `:91` | — | — | — | ✓ `:150` | endpoint, username/password, timeout, path-style, proxy `S3Storage.java:29-67`; AWS SDK v1 1.10.76 in MANIFEST `:9-31` |
| `siov1` (`dev/cosbench-sineio/src/com/intel/cosbench/api/sio/SIOStorageFactoryV1.java:35-41`) | `dev/cosbench-sineio/src/com/intel/cosbench/api/sio/SIOStorageV1.java` | ✓ `:75,188` | ✓ `:249` | ✓ `:264` | ✓ `:296` | ✓ `:195` | ✓ `:230` | ✓ `:427` | ✓ `getList:399` + cleanup `:375` | ✓ `:389` | README claim and source; AWS SDK v1 1.12.767; no_verify_ssl/range/prefetch/storage_class/restore_days in config constants |
| `sio` (`dev/cosbench-sineio/src/com/intel/cosbench/api/sio/SIOStorageFactory.java:35-41`) | `dev/cosbench-sineio/src/com/intel/cosbench/api/sio/SIOStorage.java` | ✓ `:88,200` | ✓ `:266` | ✓ `:283` | ✓ `:312` | ✓ `:207` | ✓ `:247` | ✓ `:444` | ✓ `getList:415` + cleanup `:383` | ✓ `:400` | endpoint, timeout, proxy, `no_verify_ssl`, `aws_region`, `storage_class`, `restore_days`, multipart `part_size`, prefetch/range `SIOStorage.java:88-125`; AWS SDK v2 2.26.8 + v1 1.12.767, Netty 4.1.108 in MANIFEST `:15+` |
| `gdas` (`dev/cosbench-sineio/src/com/intel/cosbench/api/gdas/GdasStorageFactory.java:35-41`) | `dev/cosbench-sineio/src/com/intel/cosbench/api/gdas/GdasStorage.java` | ✓ `:67,166` | ✓ `:207` | ✓ `:225` | ✓ `:251` | ✓ `:173` | ✓ `:188` | ✓ `:359` | ✓ `getList:333` + cleanup `:311` | ✓ `:323` | sample keys endpoint/path_style/proxy/part_size: `release/conf/config-samples/gdas-config-sample.xml:9,18` |
| `swift` (`dev/cosbench-swift/src/com/intel/cosbench/api/swift/SwiftStorageFactory.java:25-31`) | `dev/cosbench-swift/src/com/intel/cosbench/api/swift/SwiftStorage.java` | ✓ `:59,119` | ✓ `:173` | ✓ `:193,213` | — (multipart API is not named) | ✓ `:131` | — | — | ✓ `getList:152`, cleanup `:233` | ✓ `:253` | token/storage_url/policy/transfer_rate/timeout `SwiftStorage.java:59-76`; timeout exceptions `:135-160` |
| `openio` (`dev/cosbench-openio/src/com/intel/cosbench/api/openio/OpenIOStorageFactory.java:29-40`) | `dev/cosbench-openio/src/com/intel/cosbench/api/openio/OpenIOStorage.java` | ✓ `init:62`; dispose not declared in scan | ✓ `:132` | ✓ `:144` | — | ✓ `:104` | — | — | ✓ `getList:116` + cleanup `:156` | ✓ `:166` | embedded Gson/OpenIO API (`cosbench-openio` MANIFEST `:25+`) |
| `mock` (`dev/cosbench-mock/src/com/intel/cosbench/api/mock/MockStorageFactory.java:25-31`) | `dev/cosbench-mock/src/com/intel/cosbench/api/mock/MockStorage.java` | ✓ `init:58`, `dispose:84` | ✓ `:124` | ✓ `:133,147` | — | ✓ `:100` | — | — | ✓ `getList:112` + cleanup `:163` | ✓ `:172` | test double; `mock` auth also exported by `dev/cosbench-mock/META-INF/spring/plugin-context.xml:16-22` |
| `ampli` (`dev/cosbench-ampli/src/com/intel/cosbench/api/amplistor/AmpliStorageFactory.java:25-31`) | `dev/cosbench-ampli/src/com/intel/cosbench/api/amplistor/AmpliStorage.java` | ✓ `:57,85` | ✓ `:115` | ✓ `:131,148` | — | ✓ `:97` | — | — | — | ✓ `:181`/container `:165` | host/port/nsroot/policy in `release/conf/config-samples/ampli-config-sample.xml:4,9,19-32` |
| `cdmi` (`dev/cosbench-cdmi-base/src/com/intel/cosbench/api/cdmi/base/CDMIStorageFactory.java:26-32`) | `dev/cosbench-cdmi-base/src/com/intel/cosbench/api/cdmi/base/CDMIStorage.java` | ✓ `:53,101` | ✓ `:126` | ✓ `:143` | — | ✓ `:107` | — | — | — | ✓ `:178`/container `:161` | `release/conf/config-samples/cdmi-base-config-sample.xml:4` |
| `cdmi_swift` (`dev/cosbench-cdmi-swift/src/com/intel/cosbench/api/cdmiswift/CDMIStorageFactory.java:26-32`) | `dev/cosbench-cdmi-swift/src/com/intel/cosbench/api/cdmiswift/CDMIStorage.java` | ✓ `:44,99` | ✓ `:124` | ✓ `:141` | — | ✓ `:105` | — | — | — | ✓ `:176`/container `:159` | sample type `cdmi_swift`: `release/conf/config-samples/cdmi-swift-config-sample.xml:4` |
| `ecs` (`dev/cosbench-ecs/src/com/emc/vipr/cosbench/ECSStor/ECSStorageFactory.java:30-36`) | `dev/cosbench-ecs/src/com/emc/vipr/cosbench/ECSStor/ECSStorage.java` | ✓ `:66-90,156` | ✓ `:194` | ✓ `:218` | — | ✓ `:172` | — | — | — | ✓ `:260`/container `:238` | endpoint/accesskey/secretkey/conn_timeout/read_timeout/namespace/path_style/http_client/smart_client `:66-126`; Jersey system properties `:125-126`; embedded object-client/Jersey/HTTP classpath MANIFEST `:14-29` |
| `gcs` (`dev/cosbench-gcs/src/com/intel/cosbench/api/GcsStor/GcsStorageFactory.java:26-32`) | `dev/cosbench-gcs/src/com/intel/cosbench/api/GcsStor/GcsStorage.java` | ✓ `:55,93` | ✓ `:113` | ✓ `:127` | — | ✓ `:99` | — | — | — | ✓ `:156`/container `:144` | project/json key sample `release/conf/config-samples/gcs-config-sample.xml:6`; Google client/Jackson embedded MANIFEST `:25+` |
| `librados` (`dev/cosbench-librados/src/com/intel/cosbench/api/librados/LibradosStorageFactory.java:46-52`) | `dev/cosbench-librados/src/com/intel/cosbench/api/librados/LibradosStorage.java` | ✓ `:75,123` | ✓ `:151` | ✓ `:177` | — | ✓ `:127` | — | — | — | ✓ `:195`/container `:168` | native/embedded `rados.jar`, JNA 3.5.2 (`MANIFEST.MF:9+`); native host dependency **待验证** |
| `oss` (`dev/cosbench-oss/src/com/intel/cosbench/api/oss/OSSStorageFactory.java:27-33`) | `dev/cosbench-oss/src/com/intel/cosbench/api/oss/OSSStorage.java` | ✓ `:63,110` | ✓ `:136` | ✓ `:150` | — | ✓ `:121` | — | — | — | ✓ `:179`/container `:165` | Aliyun SDK 2.8.1 + HTTP/JDOM embedded (`MANIFEST.MF:9+`); sample `aliyun-config-sample.xml:4` |
| `sproxyd` (`dev/cosbench-scality/src/com/scality/cosbench/api/SproxydStorageFactory.java:41-47`) | `dev/cosbench-scality/src/com/scality/cosbench/api/SproxydStorage.java` | ✓ `:85,148` | ✓ `:180` | ✓ `:197` | — | ✓ `:161` | — | — | — | ✓ `:232`/container `:215` | Scality client; timeout/abort paths source-confirmed |
| `manta` (`dev/cosbench-manta/src/main/java/com/joyent/manta/cosbench/MantaStorageFactory.java:33-39`) | `dev/cosbench-manta/src/main/java/com/joyent/manta/cosbench/MantaStorage.java` | ✓ `:68,252` | ✓ `:114` | ✓ `:143` | — | ✓ `:200` | — | — | — | ✓ `:181`/container `:126` | Manta client 2.0.0, `chunked` key `:68-86`; no repo MANIFEST, Maven-generated bundle |
| `abcstor` (`ext/adaptor/abc-stor/src/com/abc/api/abcStor/AbcStorageFactory.java:25-31`) | `ext/adaptor/abc-stor/src/com/abc/api/abcStor/AbcStorage.java` | ✓ `:40,69` | ✓ `:101` | ✓ `:119` | — | ✓ `:81` | — | — | — | ✓ `:156`/container `:138` | ext Bundle JavaSE-1.6; endpoint/username/password/timeout keys require live test |
| `none` (API constant) | `dev/cosbench-api/src/com/intel/cosbench/api/storage/NoneStorage.java:37,39` | ✓ `init:52`, `dispose:75` | no-op `:114` | no-op `:121,128` | no-op `:136` | no-op `:90` | no-op `:99` | no-op `:157` | no-op `getList:106`/container `:143` | no-op `:150` | explicit fallback `dev/cosbench-driver/src/com/intel/cosbench/driver/service/COSBStorageAPIService.java:59-60` |

`?`/“exact line needs review” intentionally means **待验证**，不是把 README 记载当成源码事实。上表已覆盖 README 的 SineIO comparison：`README.md:28-52` 的 mprepare/mwrite/mfilewrite/restore/head/no_verify_ssl/aws_region/range-prefetch/storage_class/restore_days/SDK/localwrite claims；源码确认 SIO/SIOV1 的方法与配置，真实后端效果仍需 smoke test。

## Auth adapter matrix

| auth type | Factory/Bundle证据 | 初始化/登录参数（源码） | close/timeout |
|---|---|---|---|
| `none` | `NoneAuth`，API fallback `COSBAuthAPIService.java:59-60` | no-op | no-op |
| `mock` | `dev/cosbench-mock/src/com/intel/cosbench/api/mock/MockAuthFactory.java:25-31`; `dev/cosbench-mock/META-INF/spring/plugin-context.xml:16-18` | test double `dev/cosbench-mock/src/com/intel/cosbench/api/mock/MockAuth.java:46-61` | source lifecycle |
| `keystone` | `dev/cosbench-keystone/src/com/intel/cosbench/api/keystone/KeystoneAuthFactory.java:25-31`; `dev/cosbench-keystone/src/com/intel/cosbench/api/keystone/KeystoneAuth.java:68-96` | `auth_url`/`auth_url_alt`, username/password/token, tenant_id/name, service, timeout, region | dispose `:101-103`; timeout maps `AuthTimeoutException:107-112` |
| `swauth` | `dev/cosbench-swauth/src/com/intel/cosbench/api/swauth/SwiftAuthFactory.java:25-31`; `dev/cosbench-swauth/src/com/intel/cosbench/api/swauth/SwiftAuth.java:57-75` | auth URL/alternate, username/password, timeout | client dispose `:89-92` |
| `httpauth` | `dev/cosbench-httpauth/src/com/intel/cosbench/api/httpauth/HttpAuthFactory.java:25-31`; `dev/cosbench-httpauth/src/com/intel/cosbench/api/httpauth/HttpAuth.java:89-102` | `auth_url`, username/password, timeout; BASIC/DIGEST `:123-130` | socket/connect timeout `:145-148`; `abort:163` |
| `abcauth` | `ext/adaptor/abc-auth/src/com/abc/api/abcAuth/AbcAuthFactory.java:25-31` | auth URL/username/password/timeout `ext/adaptor/abc-auth/src/com/abc/api/abcAuth/AbcAuth.java:52-61` | dispose `:67-70` |

## Retry/failure/resource semantics

- Auth retry is config key `retry`, default 0 (`MissionHandler.java:46-48,170-172`); `Agents.newAuthAgent` uses `retry+1` attempts (`dev/cosbench-driver/src/com/intel/cosbench/driver/agent/Agents.java:24-27`).
- A storage operation catches `AuthException` and calls `WorkAgent.reLogin` (`WorkAgent.java:187-200,289-304`); this can re-authenticate without changing the operation's storage type.
- Swift and several HTTP adapters map socket/connect timeout to storage timeout (`SwiftStorage.java:135-160`); mission executor adds `rampup+runtime+rampdown+60` seconds (`MissionHandler.java:314-338`).
- `dispose`/`abort` are explicit for many clients; some adapters only inherit base no-op. Exact remote close behavior needs live-client tests.
- **已知/待验证：** source-level method presence does not prove backend semantics, TLS certificate behavior, retry policy in embedded SDK, or native `rados` availability. Those are Phase 0 compatibility risks, not claims of compatibility.
