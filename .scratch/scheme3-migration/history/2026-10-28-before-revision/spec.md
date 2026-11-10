# 方案三：Java 21 / Spring Boot 运行时迁移规格

Status: ready-for-agent

## Problem Statement

COSBench 当前以 Tycho、OSGi/Equinox、Spring DM 和旧 Tomcat runtime 组织 Controller、Driver、core、Web backend 及 Provider。构建依赖大量隐藏在 OSGi manifest、P2 元数据和 module-local JAR 中，Web backend 依赖旧 Servlet namespace 和 OSGi Web context，Provider 通过 OSGi service 装配。这样的运行时和构建方式增加了 Java 21、容器化开发、依赖审计、故障诊断和持续维护的成本。

本次迁移需要在保持当前发行版行为等价的前提下，重写或迁移 Controller、Driver、core、两侧 Web backend、必要依赖闭包、SineIO 和 S3。现有 FreeMarker 模板、静态资源、页面结构、URL、表单、Controller CLI 页面和 Driver `/i` 命令合同需要继续工作。新发行版不再依赖 Spring DM、OSGi、Equinox、Tycho/P2 或旧 Tomcat 6；其它 Provider 保留为旧版 legacy 归档，不扩大本次支持范围。

## Solution

建立普通 Maven reactor 和两个薄的 Spring Boot 3 executable application：Controller app 和 Driver app。应用使用 Java 21 LTS 运行，采用 Spring Framework 6、Jakarta Servlet 和 Boot 管理的 embedded Tomcat 10.1；不再单独维护 `cosbench-tomcat`。活跃业务模块保持原有领域职责，优先复用可迁移的 Spring XML Bean 和 FreeMarker 配置，仅在 Jakarta/Boot 兼容性要求处改写 backend adapter 和生命周期装配。

Controller 与 Driver 继续作为两个独立进程运行。Controller 到 Driver 保留现有 `/i/ping.command`、`submit.command`、`login.command`、`launch.command`、`query.command`、`close.command`、`abort.command` 和 `trigger.command` 的路径、字段、状态码和错误语义。服务间使用独立于页面 Session 的 HTTPS/TLS 与可配置长生命周期 Token；压测期间 token 不自然过期。HTTP 使用连接池和 keep-alive，按连接、读取、命令和 workload 分层配置超时；仅对明确可重试的网络失败和 5xx 做有限重试，写操作带 request ID/idempotency key。

Provider 改为启动时静态 `ServiceLoader` registry。生产发行包只包含 SineIO 的 `sio`、`siov1`、`gdas` 和旧 S3 的 `s3`；Mock 只在测试 profile/classpath 使用。先验证 SineIO 与 S3 共享 classpath；只有真实 smoke 证明冲突时才增加启动时静态 child classloader。新增、删除 Provider 需要重新打包和重启，不提供运行期热安装/卸载。

旧配置通过配置迁移命令转换为版本化 `conf/controller.yml` 和 `conf/driver.yml`，新命令负责启动、停止和配置转换。开发、集成测试和真实对象存储 smoke 使用 Java 21 Docker 环境与 MinIO；生产镜像与开发镜像分离，生产镜像不含 Mock、legacy、源码、P2、Equinox 或开发工具。

## User Stories

1. As a COSBench operator, I want to start Controller as a Java 21 Spring Boot process, so that the benchmark controller runs without Equinox or Spring DM.
2. As a COSBench operator, I want to start Driver as a separate Java 21 Spring Boot process, so that workloads continue to execute without the old Tomcat/OSGi runtime.
3. As a maintainer, I want a normal Maven reactor, so that active dependencies are explicit, reproducible, and inspectable.
4. As a maintainer, I want the active reactor to contain only the agreed Controller, Driver, core, Web, SineIO, S3, and necessary dependency closure, so that unrelated Provider SDKs do not enlarge the migration risk.
5. As a maintainer, I want old non-target Provider source and metadata retained in a legacy archive, so that old releases remain diagnosable without making those Providers part of the new product.
6. As an operator, I want Controller and Driver to retain their separate process boundaries, so that each service can be started, stopped, monitored, and rolled back independently.
7. As a Controller client, I want the existing Driver `/i` command paths and request fields to remain valid, so that existing Controller behavior and workload automation continue to work.
8. As a Controller client, I want `/i` responses, status codes, error semantics, and state transitions to remain compatible, so that scripts and workload state handling do not require a protocol rewrite.
9. As a benchmark operator, I want page sessions to remain separate from service-to-service authentication, so that UI authentication changes do not interrupt Driver calls.
10. As a benchmark operator, I want service authentication to remain valid for the complete workload, so that long-running pressure tests do not disconnect when a token expires.
11. As an administrator, I want internal Controller-to-Driver traffic to support HTTPS/TLS, so that service credentials and workload control data are protected in transit.
12. As an administrator, I want credentials to come from environment variables, Docker secrets, or protected configuration, so that tokens are not written to images, logs, workload files, or source control.
13. As a benchmark operator, I want persistent HTTP connections and a bounded connection pool, so that long workloads do not fail because of avoidable connection churn.
14. As a benchmark operator, I want connection, read, command, and workload timeouts to be independently configurable, so that a slow object operation does not look like a dead service.
15. As a benchmark operator, I want only transient network failures and explicitly retryable 5xx responses retried a finite number of times, so that temporary failures recover without multiplying writes.
16. As a benchmark operator, I want write requests to carry a request ID or idempotency key, so that a retry cannot silently create duplicate workload actions.
17. As an operator, I want TERM and the stop command to stop accepting new work before closing workers and clients, so that shutdown produces a diagnosable and clean state.
18. As a maintainer, I want Spring XML Bean definitions that remain compatible to be reusable, so that the migration minimizes behavior-changing rewrites.
19. As a Web user, I want the Controller pages to retain their URLs, forms, views, FreeMarker templates, and static resources, so that daily operations do not require a new UI.
20. As a Web user, I want the Driver pages and command forms to retain their URLs, views, and response behavior, so that mission operations remain familiar.
21. As a Web user, I want login, multipart upload, download, CSV export, and Prometheus endpoints to continue working, so that existing operational workflows remain complete.
22. As an operator, I want Controller pages and Driver commands served by the corresponding Boot process, so that there is no hidden OSGi Web extender or separate legacy Tomcat deployment.
23. As a maintainer, I want Servlet and Spring integrations migrated to Jakarta/Spring 6, so that the Web backend runs on the supported Boot 3 runtime.
24. As an operator, I want the Controller to load the new versioned YAML configuration, so that ports, drivers, archive paths, concurrency, logging, and Web settings are explicit.
25. As an operator, I want the Driver to load the new versioned YAML configuration, so that ports, mission paths, authentication, storage, concurrency, and Web settings are explicit.
26. As an operator, I want a configuration migration command to convert supported legacy configuration values, so that an existing installation can be upgraded without manually recreating every setting.
27. As an operator, I want the original configuration retained and conversion failures reported, so that migration is reversible and diagnosable.
28. As an operator, I want stable start, stop, and configuration commands, so that automation does not need to know Spring Boot internals.
29. As a maintainer, I want application-layer bytecode targets chosen per module while runtime remains fixed at Java 21, so that behavior-neutral libraries can minimize unnecessary source changes.
30. As a maintainer, I want the Java 21 toolchain and target decision enforced by Maven, so that local and CI builds do not silently use different language levels.
31. As a Driver operator, I want the registry to discover SineIO `sio`, `siov1`, and `gdas` at startup, so that all supported SineIO modes remain available.
32. As a Driver operator, I want the registry to discover S3 at startup, so that existing S3 workloads remain available.
33. As a test author, I want Mock storage and auth factories available only in test profile/classpath, so that integration tests can run without polluting production packages.
34. As a maintainer, I want duplicate Provider names, missing service descriptors, missing dependencies, and factory initialization failures to identify the Provider, so that startup failures are actionable.
35. As a Provider implementer, I want factory initialization, one operation, and disposal exercised, so that HTTP clients, worker threads, and native resources are released correctly.
36. As an operator, I want SineIO and S3 put/get/list/delete and multipart behavior validated against MinIO where applicable, so that the new packaging does not hide SDK or classpath failures.
37. As a maintainer, I want old Jackson 1.x, Castor, AWS SDK, and SineIO SDK upgrades kept separate from this migration, so that wire-contract and storage behavior changes remain independently reviewable.
38. As a maintainer, I want production artifacts to exclude Mock, legacy Providers, source, P2, Equinox, and development tooling, so that the release boundary is explicit.
39. As a developer, I want a Java 21 Docker development environment, so that local builds and tests reproduce CI assumptions.
40. As a tester, I want a MinIO-backed Docker smoke environment, so that real object-storage behavior is checked without requiring production credentials.
41. As an operator, I want restarts to preserve workspace, archive, mission, logs, and configuration directory semantics, so that historical results remain readable.
42. As an operator, I want an installation in an empty directory to start and stop cleanly, so that packaging errors are caught before release.
43. As a release engineer, I want a production image and a separate development/test image, so that test tools and MinIO support cannot leak into production.
44. As a release engineer, I want a previous release archive and rollback procedure, so that a failed migration can be reversed without deleting data.
45. As a maintainer, I want an active-source and artifact scan proving no OSGi runtime dependency remains, so that the migration is complete rather than merely wrapped.

## Implementation Decisions

- The supported migration boundary is Controller, Driver, core, both Web backends, their active dependency closure, SineIO (`sio`, `siov1`, `gdas`), S3 (`s3`), and Mock for tests only. Other Providers, Manta, extensions, and the old Tomcat runtime remain in a legacy archive and are not referenced by the new reactor or production package.
- The new build is a normal Maven reactor with standard source/resource/test layout. Existing OSGi manifests, PDE build metadata, P2 inputs, and bundle-local classpaths are migration evidence only; the active build must declare its dependencies explicitly.
- The runtime consists of two thin Spring Boot 3 composition roots, one for Controller and one for Driver. Spring Framework 6, Jakarta Servlet, and Boot-managed embedded Tomcat 10.1 are used. Spring DM, Spring OSGi, Equinox, Tycho/P2, and standalone old Tomcat 6 are removed from the active runtime and build.
- Controller and Driver business services stay in their existing domain modules. Composition roots own application bootstrap, configuration, lifecycle hooks, Web runtime registration, and exit-code behavior; they do not absorb workload/mission logic.
- Existing Spring XML Bean definitions are reused where compatible. OSGi service/list/reference contexts are replaced by ordinary Spring Bean wiring in the application context.
- Existing FreeMarker templates, static resources, view names, URLs, route mappings, form fields, CLI handlers, downloads, CSV, and Prometheus behavior are preserved. Web changes are backend and runtime migration, not a frontend redesign.
- The Controller-to-Driver `/i` protocol is an external compatibility contract. Paths, fields, status codes, response fields, error classes/semantics, and state transitions are preserved.
- Service-to-service authentication is independent from page sessions. The default is HTTPS/TLS with a configurable long-lived token. A short-lived token is allowed only with transparent refresh that cannot interrupt an active workload. Secrets are injected from protected runtime sources.
- The HTTP client uses pooling and keep-alive, separate connection/read/command/workload timeouts, finite retries for explicit transient failures, and request IDs/idempotency keys on writes. Provider and application concurrency limits are validated at startup.
- Provider discovery uses startup-time static `ServiceLoader`. Registry objects own discovery, unique-name validation, sorting, diagnostics, factory lookup, and lifecycle disposal; business services depend on factory interfaces rather than calling `ServiceLoader` directly.
- SineIO and S3 first share the application classloader. A startup-time static child classloader is introduced only if a reproducible SineIO/S3 conflict is demonstrated by dependency analysis and smoke tests. Runtime hot installation/uninstallation is out of scope.
- Java 21 is the build, test, and runtime environment. Module bytecode targets may use the lowest compatible release to reduce behavior-neutral source churn, but the exact per-module values must be locked by the first Maven shadow-build ticket and enforced in Maven/CI.
- New configuration is versioned YAML for Controller and Driver. A migration command converts supported legacy configuration and preserves the source file. New start/stop/configuration commands are the final contract; old commands may remain only as temporary wrappers if compatibility testing requires them.
- Development and integration testing use Java 21 Docker images and MinIO. Docker-created containers, networks, and temporary resources are removed after tests. Production images exclude Mock, legacy, source, P2, Equinox, and developer tooling.
- Dependency upgrades that can change behavior remain separate: Jackson 1.x to 2.x, Castor to Jackson XML/JAXB, AWS SDK major upgrades, SineIO SDK major upgrades, and HTTP/serialization changes that alter the `/i` wire contract.

## Testing Decisions

- Tests verify observable behavior at three high seams: (1) process-level Controller/Driver Boot applications, (2) the Controller-to-Driver `/i` HTTP contract, and (3) Provider SPI through object-storage operations. Internal class names and framework wiring are not test contracts.
- Before broad migration, capture a versioned baseline of page HTML, form submissions, multipart behavior, CLI output, CSV, downloads, Prometheus samples, `/i` requests/responses, status transitions, directories, logs, exit codes, and minimal Mock/SineIO/S3 workloads.
- The first technical spikes must prove a representative Controller page and multipart handler on Boot 3/Jakarta/FreeMarker, a Driver `/i` handler with token/pooling/retry/idempotency, ServiceLoader discovery and lifecycle for Mock/SineIO/S3, dependency coexistence of SineIO/S3, and legacy-config conversion/start-stop behavior.
- Maven tests cover explicit dependency resolution, standard resource loading, service descriptors, configuration validation, and the selected module bytecode targets. A clean build must work without Tycho participation in the active reactor.
- Process tests start each application as a real Java 21 process, exercise health/start/stop, verify exit codes and port release, and assert graceful shutdown of workers, HTTP clients, Provider clients, and logging.
- HTTP contract tests compare old and new request/response fixtures for every `/i` command, including malformed inputs, authentication failures, timeouts, retry boundaries, duplicate request IDs, and state transitions. Page and CLI tests compare external output and downloads rather than controller implementation details.
- Provider tests assert discovery, unique names, missing descriptors/dependencies, configuration errors, init/operation/dispose order, supported operations, error mapping, and production/test package boundaries. Mock runs in tests; SineIO and S3 run against MinIO smoke fixtures.
- Docker tests build separate development/test and production images, run MinIO-backed smoke, verify the production image does not contain excluded artifacts, test empty-directory installation and restart recovery, and clean all resources afterward.
- Performance comparison uses the same hardware and workload after the baseline is captured. It records throughput, p50/p95/p99, failure rate, CPU, heap, and threads; thresholds are locked from baseline rather than invented before measurement.
- Final completion requires clean Maven verify, both applications start/stop, page and `/i` contracts pass, SineIO/S3 MinIO smoke passes, production artifact scans pass, Docker image and rollback exercises pass, and workspace/archive/mission migration or continued-read behavior is diagnosed.

## Out of Scope

- Rewriting FreeMarker templates, static assets, page layout, page URLs, form semantics, or user-facing UI design.
- Migrating non-target Providers, Manta, `ext/adaptor`, or the old Tomcat runtime into the new active reactor or production package.
- Runtime hot installation/uninstallation of Providers or an external Provider directory protocol.
- OAuth2/OIDC as the MVP service-authentication mechanism.
- Automatic deletion, reordering, or destructive conversion of existing workspace, archive, mission, logs, or configuration data.
- Jackson 1.x to 2.x, Castor replacement, AWS SDK major upgrade, SineIO SDK major upgrade, or any serialization/HTTP upgrade that changes the established wire contract.
- Making the new release support Java 8 or Java 17 runtimes; runtime support is fixed at Java 21.
- Reimplementing workload, mission, scheduler, archive, or Provider business behavior when it can remain behind the existing public interfaces.

## Further Notes

- The authoritative implementation context is `docs/migration/scheme3/`; its development specification, detailed plan, module inventory, acceptance matrix, open decisions, and scope estimate should be updated if implementation discovers a behavior-changing deviation.
- The repository currently has a Tycho root reactor and many Eclipse/PDE modules. The first build ticket is therefore a fact-finding and shadow-build slice, not a cosmetic POM rename.
- The local issue tracker stores this spec and its tickets under `.scratch/scheme3-migration/`. Tickets must be implemented blockers-first and remain independently verifiable.
