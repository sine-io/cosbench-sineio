# 方案三：Java 21 / Spring Boot 运行时迁移规格

Status: ready-for-agent

修订：2026-10-28。技术基线与方案三开发入口统一；任务依赖、验收归属和旧票映射见[执行索引](README.md)。前置验证尚待执行，ready-for-agent 表示票可在其 blockers 全部通过后领取。

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
29. As a maintainer, I want every active module compiled with maven.compiler.release=21 on Java 21, so that build, test, and runtime use one enforced Java baseline.
30. As a maintainer, I want the Java 21 toolchain and release policy enforced by Maven, so that local and CI builds do not silently use different language levels.
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
- The new build is a normal Maven reactor with standard source/resource/test layout. Start with a shadow reactor and one representative module; expand into bounded module batches only after the prerequisite spikes pass. Preserve the baseline checkout/archive for the old build. Switch the root entry and remove old metadata only after release verification; the new build declares dependencies explicitly and never consumes Tycho/P2 metadata.
- The runtime consists of two thin Spring Boot 3 composition roots, one for Controller and one for Driver. Spring Framework 6, Jakarta Servlet, and Boot-managed embedded Tomcat 10.1 are used. Spring DM, Spring OSGi, Equinox, Tycho/P2, and standalone old Tomcat 6 are removed from the active runtime and build.
- Controller and Driver business services stay in their existing domain modules. Composition roots own application bootstrap, configuration, lifecycle hooks, Web runtime registration, and exit-code behavior; they do not absorb workload/mission logic.
- Existing Spring XML Bean definitions are reused where compatible. OSGi service/list/reference contexts are replaced by ordinary Spring Bean wiring in the application context.
- Existing FreeMarker templates, static resources, view names, URLs, route mappings, form fields, CLI handlers, downloads, CSV, and Prometheus behavior are preserved. CLI retains index/submit/config/workload/cancel; queue reordering and history resubmission remain page actions. Page request timeouts or page-session expiry do not cancel an accepted background workload.
- The Controller-to-Driver `/i` protocol is an external compatibility contract. Paths, fields, status codes, response fields, error classes/semantics, and state transitions are preserved.
- Service-to-service authentication is independent from page sessions. The default is HTTPS/TLS with a configurable long-lived token. A short-lived token is allowed only with transparent refresh that cannot interrupt an active workload. Secrets are injected from protected runtime sources.
- The HTTP client uses pooling, keep-alive and idle-connection reclamation, separate connection/read/command/workload timeouts, finite retries for explicit transient failures, and request IDs/idempotency keys on writes. Pool bounds, idle limits, Provider/application concurrency and retry bounds belong to the shared YAML contract and are validated at startup.
- The Driver authenticates service credentials independently from page cookies and Provider login. The Controller retains a write identity across retries; the Driver retains processed results for its process lifetime. The HTTP spike fixes the header/identity scope, concurrent replay, conflicting payload and failure-result rules, and demonstrates response-loss recovery. Cross-restart deduplication is not promised; ambiguous writes after a Driver restart fail diagnostically rather than being blindly replayed.
- Provider discovery uses startup-time static `ServiceLoader`. Registry objects own discovery, unique-name validation, sorting, diagnostics, factory lookup, and lifecycle disposal; business services depend on factory interfaces rather than calling `ServiceLoader` directly.
- SineIO and S3 first share the application classloader. A startup-time static child classloader is introduced only if a reproducible SineIO/S3 conflict is demonstrated by dependency analysis and smoke tests. Runtime hot installation/uninstallation is out of scope.
- Java 21 is the build, test, and runtime environment; every active module uses maven.compiler.release=21. The toolchain ticket pins the Boot 3.x patch/BOM and Maven/plugin versions using official compatibility requirements and runnable evidence; lower bytecode targets and Boot 4 are outside this migration.
- New configuration is versioned YAML for Controller and Driver. A prerequisite config spike fixes schema version, defaults, precedence, ranges and secret references using a representative conversion/start-stop/archive path. Missing default files, missing explicit paths, empty configuration, missing required fields and invalid values are separate baseline cases. Both composition roots consume this contract. A later operator ticket completes conversion coverage, idempotent commands, multiple Driver ports and recovery without changing original data.
- Development and integration testing use Java 21 Docker images and MinIO. Docker-created containers, networks, and temporary resources are removed after tests. Production images exclude Mock, legacy, source, P2, Equinox, and developer tooling.
- Dependency upgrades that can change behavior remain separate: Jackson 1.x to 2.x, Castor to Jackson XML/JAXB, AWS SDK major upgrades, SineIO SDK major upgrades, and HTTP/serialization changes that alter the `/i` wire contract.

## Testing Decisions

- Tests verify observable behavior at three high seams: (1) process-level Controller/Driver Boot applications, (2) the Controller-to-Driver `/i` HTTP contract, and (3) Provider SPI through object-storage operations. Internal class names and framework wiring are not test contracts.
- Before broad migration, capture a versioned baseline of page HTML, form submissions, multipart behavior, CLI output, CSV, downloads, Prometheus samples, `/i` requests/responses, status transitions, directories, logs, exit codes, and minimal Mock/SineIO/S3 workloads.
- Four prerequisite spikes prove configuration/conversion/lifecycle/archive access, every supported Provider factory and dependency coexistence, a representative Boot/Jakarta page and multipart handler, and the authenticated/retryable/idempotent HTTP path. They use a reusable Docker/MinIO harness, bounded existing business-code dependencies and real public service interfaces. Each produces runnable commands, fixtures, dependency reports and pass/fail evidence. All four gate the first broad migration batch; an unavailable required case is not a pass.
- Maven tests cover explicit dependency resolution, standard resource loading, service descriptors, configuration validation and release=21. Clean-checkout/empty-cache builds and dependency-tree, duplicate-class, jdeps and license checks become CI gates before application batches expand the reactor. Prior test material is the baseline request/workload archive and the small spikes; legacy Provider tests are not evidence that the active scope works.
- Process tests start each application as a real Java 21 process, exercise health/start/stop, verify exit codes and port release, and assert graceful shutdown of workers, HTTP clients, Provider clients, and logging.
- HTTP contract tests compare old and new request/response fixtures for every `/i` command, including malformed inputs, Provider login failures, timeouts and state transitions. New service-auth and idempotency cases use the separately frozen HTTP-spike contract; page cookies cannot authorize service calls. Tests cover concurrent/late replay, lost responses, session expiry, connection-idle recovery and finite retries. Page and CLI tests compare external output and downloads rather than controller implementation details.
- Provider tests assert discovery, unique names, missing descriptors/dependencies, configuration errors, init/operation/dispose order, supported operations, error mapping, and production/test package boundaries. Mock runs in tests; SineIO and S3 run against MinIO smoke fixtures.
- Docker tests build separate development/test and production images, run MinIO-backed smoke for sio/siov1/gdas/s3 individually and together, verify production exclusions, and cover Controller-only, Driver-only, both and multiple Drivers. Tests include normal/error/repeated commands, port allocation, empty installation, old-data reads, restart and rollback; success and failure both clean owned resources while retaining diagnostic reports.
- The baseline ticket preserves the legacy performance probe and available measurements as historical context; legacy throughput variation and thresholds are not acceptance standards. A dedicated performance ticket runs at least three matching trials of the migrated version and records functional counts, throughput, latency, errors/retries, runtime resources, lifecycle timings, artifact measurements, and run-to-run variation. It does not make a cross-version regression claim. Any future product SLO is a separately approved requirement.
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
- The local issue tracker stores this spec and its tickets under `.scratch/scheme3-migration/`. The [execution index](README.md) defines the current 23-ticket graph and old-to-new mapping; the [acceptance ownership table](../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership) assigns stable acceptance IDs. Each ticket reads the spec, its cited contract and blocker evidence before implementation. Final cleanup verifies prior evidence and reruns affected checks after deletion; it does not absorb unassigned implementation work.
