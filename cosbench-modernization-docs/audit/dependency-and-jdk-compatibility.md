# Dependency and JDK compatibility — Phase 0

## 结论边界

当前主机是 Java 21.0.4，但仓库证据只承诺 Java 8：绝不能把“能看到 Java 21”当作兼容性结论。30 个 dev MANIFEST 的 `Bundle-RequiredExecutionEnvironment: JavaSE-1.8`（例如 `dev/cosbench-s3/META-INF/MANIFEST.MF:8`），两个 ext bundle 要求 JavaSE-1.6（`ext/adaptor/abc-auth/META-INF/MANIFEST.MF:7`, `abc-stor:7`）；`BUILD-sineio.md:13-17` 明确 JDK/compiler 1.8；Manta POM `:57-59` 的 `java.min.version=1.8`。Java 21 兼容性属于 **待验证**，并应在独立升级阶段处理。

## 关键依赖清单

| artifact / 版本证据 | 用途与直接性 | 当前运行路径/源码证据 | Java 21 风险 | 升级路径（仅建议） |
|---|---|---|---|---|
| Equinox launcher `1.6.400.v20210924-0641` / framework `3.18.100.v20220817-1601` | 直接运行时 | `dist/main`, `dist/osgi`; `release/cosbench-start.sh:47`; config.ini `:79` | framework/launcher 与旧 bundle headers、start-level 配合未测 | 先封存可启动发行包，再在 Phase 1 做可重复构建；不要先换 framework |
| Spring `3.0.5.RELEASE` | 直接 web/bean/context | `.controller/.driver/config.ini:45-53`; MANIFEST imports；web.xml OSGi context | Java 21 module/reflection/旧 Servlet API 风险高 | 保留运行时，先建立 context smoke test，再逐步升级 |
| Spring OSGi `2.0.0.M1` | 直接服务发现/web context | config.ini `:54-58`; XML `spring-osgi.xsd`；web.xml `:17-18` | 极旧 extender 与现代 JDK/Servlet/Tomcat 未证实 | Phase 1 仅构建/启动；替换属于 Phase 4 |
| Tomcat/Catalina `6.0.16`, Servlet `2.5` | embedded web | config.ini `:18`, `:9`; `controller/driver-tomcat-server.xml:3-9`; MANIFEST imports | Java 21 + old servlet container unsupported risk；TLS/ciphers/HTTP behavior | 做端口、auth、路径、shutdown 回归；再评估升级 |
| Apache HttpClient 4.1.3/4.3.6/4.5.x，HttpCore 4.1.4/4.3.3/4.4.x | Controller→Driver、adapter HTTP | config.ini `:43-44`; adapter Bundle-ClassPath；`AbstractHttpTasklet.java:55-75` | 同一进程多版本 classpath/OSGi wiring；TLS defaults变化 | 按 bundle 分隔并做 wire/timeout/TLS 测试 |
| AWS SDK Java v1 `1.10.76`（S3） | S3 adapter | `cosbench-s3/MANIFEST.MF:9-31`; `S3Storage.java:29-67` | AWS SDK v1 已维护结束（README `:12-20`）；现代 JDK/TLS与依赖冲突 | 只在兼容测试后替换；SineIO v1/v2 分别验证 |
| AWS SDK Java v1 `1.12.767` + v2 `2.26.8` | SineIO/SIOV1/GDAS | `cosbench-sineio/MANIFEST.MF:15+`; SIO config `:88-185`; README `:50-51` | v1/v2、SLF4J/Log4j/Netty 双栈和嵌入类路径冲突 | 先锁定类加载/SDK 请求行为；按 adapter 独立升级 |
| Google client/Jackson old | GCS adapter | `cosbench-gcs/MANIFEST.MF:25+`; `gcs-config-sample.xml:6` | JAXB/旧 Jackson 与 Java 21 classpath/module风险 | 替换前做 JSON、auth、TLS 回归 |
| Jersey 1.18.3 / VIPR object-client | ECS adapter | `cosbench-ecs/MANIFEST.MF:14-29`; `ECSStorage.java:125-126` | 使用 `com.sun.jersey` client property + 全局 system properties；现代 JDK/TLS风险 | 先隔离全局属性、验证 HTTP client/read timeout |
| Castor `1.2`, Jackson 1.4.2, JDOM 1.1, Commons old | XML/JSON/config | config.ini `:35-38`; `CastorConfigResolver.java:32-77`; `cosbench-castor` fragment | Java 21 反射、XML parser、fragment host wiring | 先保留解析输出基线；再替换且做 XML golden files |
| Log4j 1.2.15/1.2.17 + Log4j2 bridge 2.23.1 (SineIO) + SLF4J 1.5.6/2.0.9 | logging | config.ini `:30,40-42`; `cosbench-log4j` fragment; SineIO MANIFEST `:15+`; `LogFactory.java:29-54` | 多代日志 API/bridge 混用、初始化顺序、类加载冲突；`Class.forName(...).newInstance()` | 先验证日志级别/文件/console，再分阶段升级 |
| JNA 3.5.2 + `rados.jar` | Ceph native adapter | `cosbench-librados/MANIFEST.MF:9+`; `dev/cosbench-librados/src/com/intel/cosbench/api/librados/LibradosStorage.java:75-195` | native library/CPU/JDK ABI未知 | 在目标 OS 实机做 native smoke test；不可凭 Java 21 推断 |
| Manta client 2.0.0 / Maven Bundle plugin 3.0.1 | Manta adapter | `dev/cosbench-manta/pom.xml:106-154,159-181` | POM 依赖 CosBench 0.4.1.0 provided，与当前 0.6.0.1 bundle API 可能不一致 | 单独构建并比对 manifest/服务发现；不要混入主 reactor |
| Commons/Java EE APIs (`javax.servlet`, JAXB/JAX-WS etc.) | 运行时兼容层 | config.ini `:2-16`; core-web/controller-web MANIFEST imports | Java 21 已不再提供多项 Java EE API，虽发行包自带旧 JAR，可能与现代容器冲突 | 明确 provided/embedded 边界，先做 classloader dump |

## 源码级 JDK / 内部 API 风险

- **已确认：** `dev/cosbench-log/src/com/intel/cosbench/log/LogFactory.java:29-54` 默认 Log4j manager，并用 `Class.forName(logmgr).newInstance()`；Java 21 的反射访问/异常语义需要测试。
- **已确认：** `dev/cosbench-driver-web/src/com/intel/cosbench/driver/handler/TriggerHandler.java:166-168` 通过 `getDeclaredField("pid")` 和 `setAccessible(true)` 读取 `Process.pid` 私有字段；这是 JDK 内部实现依赖，Java 21 强封装下可能失败。
- **已确认：** `dev/cosbench-ecs/src/com/emc/vipr/cosbench/ECSStor/ECSStorage.java:125-126` 写入 `com.sun.jersey.client.property.*` 全局 system properties；并非 Java 标准 API，且影响进程内其它请求。
- **推断：** Castor/JAXB/Jackson 旧反射、Spring 3 bean introspection、Servlet 2.5 与 Java 21 module boundaries 可能在启动或解析阶段失败；目前没有 Java 21 构建/运行证据。

## TLS、许可证与供应链

- 适配器从源码读取 endpoint、timeout、proxy、region、`no_verify_ssl` 等；SineIO 的 `no_verify_ssl` 明确在 `SIOStorage.java:115-116`，ECS 的 endpoint/HTTP client 在 `ECSStorage.java:79-87`。是否验证证书、TLS版本及 SDK 默认重试需真实服务测试。
- 发行脚本把 `LICENSE`, `NOTICE`, `3rd-party-licenses.pdf`, `pkg.lst` 一并复制（`pack.sh:31-47`）；嵌入 SDK/源代码 JAR（SineIO manifest `:15+` 含 `*-sources.jar`）的许可证/NOTICE 逐项核对尚未完成，标记 **待验证**。
- `README.md:12-20` 宣布 AWS SDK v1 维护结束/结束支持；这是升级动因，不是已经完成的兼容性证据。

## 建议的证据顺序

1. 用 Java 8/Eclipse PDE 复原已知发行包并保存 Equinox resolver、web、factory list 日志。
2. 在同一发行包上用 Java 21 做“只启动/解析/关闭”探测，单独标记失败点；不改依赖掩盖错误。
3. 逐 adapter 验证 endpoint/TLS/auth/timeout/close 和 SDK 实际请求，再决定升级；最后才评估 Java 21/第三方库联合升级。
