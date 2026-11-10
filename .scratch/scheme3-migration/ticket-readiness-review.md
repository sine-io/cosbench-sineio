# 方案三 tickets 开发就绪复核

复核日期：2026-10-28。对象：修订前工作树中的方案三全部 7 份文档、spec、15 张 tickets，以及用于确认现有行为的配置、Web 路由和 handler 源码。代码 HEAD 为 `75e2439`。下文旧编号与行号均指该次检查时的版本；旧 spec 和票已链接到快照，方案文档链接用于查看修订后的合同。

**结论（修订前）：主干范围已覆盖，可以启动基线工作；当时 tickets 尚不足以作为无需补充规格、可一路完成方案三的执行合同。** 主要问题是字节码目标冲突、前置技术验证没有进入任务图、部分行为要求错配，以及验收责任集中在最终收尾票。

本报告的主体记录修订前检查；当时未运行 Maven、应用、Docker 或 MinIO，不能据此宣称 Boot/Jakarta 或 SDK 共存已验证。2026-10-28 的后续修订已统一 spec、方案文档和 tickets；实际 spike 仍须由 #03–#06 产生通过证据。

**修订结果（2026-10-28）**：当前执行图已改为 23 张票。Java 21 与所有活跃模块 `maven.compiler.release=21` 已统一；#03–#06 分别承接配置、Provider、Web、HTTP 四项前置验证并阻塞 #07；#08 提前接入持续构建审计；页面重排/重提归页面票，CLI 保留五类既有入口；性能基线/三次对比、服务认证/幂等、后台任务不中断、多 Driver 和生产制品均已分配首责票。当前图和旧票映射见[执行索引](README.md)。

**修订静态校验**：23 张票、143 条验收项、25 个验收 ID；编号、标题、阻塞引用、执行索引和首责表一致。依赖无环，#23 覆盖其余全部票，#07 及后续票均受四项 spike 阻塞；Driver HTTP 不依赖生产 Provider 迁移，Driver 页面不依赖 Controller client。142 处本地链接及锚点有效，历史 15 张票和 spec 的 16 个快照文件内容保持原样。此结果只证明文档/任务图一致，尚无运行验收结论。

**已确认的覆盖与结构**

- 15 张票合计 79 条验收项，编号和依赖引用有效，依赖图无环。
- Maven、公共模块、两个 Boot 应用、registry、SineIO/S3、HTTP、两侧 Web、配置命令、Docker/CI、生产包、回退、OSGi 清理均有归属。
- SineIO 的 sio/siov1/gdas、S3、Mock 仅测试、legacy 不进生产、保留页面合同等范围基本一致。
- 每票独立文件、What to build、Blocked by、Status 和验收项符合 to-tickets 的基本格式。ready-for-agent 与存在 blockers 可以并存；当前唯一无依赖票为 #01。
- 这是既有 spec/tickets 的复核，适合定向修订 to-spec → to-tickets 的产物。按 ask-matt 的路由，不需要把自行生成的 tickets 再作为原始外部需求进入 triage。

**1. P1：统一 Java 字节码目标后再执行 #02**

[方案入口](../../docs/migration/scheme3/README.md)第 16 行和[详细方案](../../docs/migration/scheme3/detailed-plan.md)第 259 行明确统一 maven.compiler.release=21；[旧 spec](history/2026-10-28-before-revision/spec.md)第 51、82 行和 [#02](history/2026-10-28-before-revision/issues/02-maven-shadow-reactor.md)第 10 行却要求按模块选择最低兼容目标。

上游也存在残留矛盾：[open-decisions](../../docs/migration/scheme3/open-decisions.md)第 72 行已确认 release=21，第 80、134 行仍称待定；scope-estimate 第 120 行仍保留相同待决策项。执行者可分别照不同文本做出互斥实现。

建议以开发入口已确认的统一 release=21 为准，同步修正 spec、#02 和上游残留的“待锁定/低版本 target”文本。#02 还应负责固定 Boot 3.x 补丁/BOM 与 Java 21 工具链，不把已确认的目标重新交给实现者选择。

**2. P1：把必做 spike 变成真实的前置闸门**

[development-spec](../../docs/migration/scheme3/development-spec.md)第 60–68 行明确要求大规模迁移前验证：代表性页面与 multipart、带认证/池化/重试/幂等的 Driver HTTP、全部目标 factory 生命周期、SineIO/S3 classpath 共存、配置转换与启停及旧 archive。[旧 spec](history/2026-10-28-before-revision/spec.md)第 91 行也保留了这项要求。

当前任务图在 #02 开始整个活跃闭包和标准目录迁移，随后才在 #07 验证生产 Provider、#08/#09 验证 HTTP、#10 验证页面、#12 验证配置。没有一张票将这些代表性验证定义为扩展迁移的前置结果，且可复用的 MinIO/集成环境位于 #13。

建议拆出最小工具链/验证环境和范围有限的技术验证，再将相关批次显式阻塞于验证结果。每个验证记录命令、fixture、依赖树、产物和失败日志。不要简单把完整 #10/#12 提前，否则会把依赖循环带进新图；需要提取代表性子集。

**3. P1：#10 把页面操作误写成了 CLI 能力**

[#10](history/2026-10-28-before-revision/issues/10-controller-web.md)第 11 行要求“Controller CLI actions”支持 reorder 和 resubmit。现有 [CLI 路由](../../dev/cosbench-controller-web/WEB-INF/spring/controller-handler-context.xml)只有 index、submit、config、workload、cancel；[IndexPageController](../../dev/cosbench-controller-web/src/com/intel/cosbench/controller/web/IndexPageController.java)第 49–74 行在页面请求中处理 up/neighid 和 resubmitIds。

按此验收项实施会诱导新增 CLI 合同，偏离本次保留现有 URL/表单/流程的边界。建议明确：CLI 保留现有五类入口；页面保留排队调整和历史重提，并分别对照既有请求与结果验收。

**4. P1：将“压测不中断”的要求分配到可执行验收项**

方案的明确要求尚未完整落到负责实现的票：

| 已确认要求 | 当前缺口 | 应承接的票 |
| --- | --- | --- |
| 页面请求超时不取消后台 workload，见 development-spec 第 32 行 | #10 没有对应验收；仅启动健康检查或 HTTP 请求成功不能证明这一点 | #09/#10：请求超时或页面 Session 结束后，后台任务仍能继续查询并正常完成 |
| Driver 在进程生命周期保存已处理结果，见 open-decisions 第 106 行 | #08 只要求相同 identity 不重复操作，没有明确保存期限及返回既有结果 | #08/#09：固定身份传递约定与进程内重放行为，验证响应丢失后重试不重复执行 |
| 服务间 Token 不复用页面 Session，见 development-spec 第 29 行 | #09 有客户端要求；#08 的 authentication failures 未区分服务认证与 Provider login | #08：明确 Driver 服务认证边界，并验证 page cookie 不能代替服务凭据 |
| HTTP 空闲连接回收、Provider 并发和重试上限进入 YAML 并校验，见 open-decisions 第 106 行 | #09 有池化/超时，#12 有概括配置列表，空闲回收等未形成明确验收 | #09/#12：有效值、越界值、关闭和长时间空闲后继续工作 |

源码中的 [/i/login handler](../../dev/cosbench-driver-web/src/com/intel/cosbench/driver/handler/LoginHandler.java)调用 DriverService.login 执行 mission 的 Provider 认证；不能把这条已有命令当成新增服务 Token 认证已被覆盖的证据。

幂等键同值不同请求、并发重试和失败结果的处理可在前置 HTTP 验证中锁定为具体合同；这属于需要明确的实现决策，不代表现有文档已经承诺跨重启持久化。

**5. P2：性能、运维和构建验收过度依赖 #15 兜底**

[#15](history/2026-10-28-before-revision/issues/15-remove-osgi-finalize.md)第 9 行引用全部验收矩阵，意味着这些要求并非完全不在最终范围内。但缺少早期产物和逐项责任，会把采集基线、补实现和补测试都推到本应负责收缩的最后一票。

| 方案要求与出处 | tickets 当前状态 | 最小补充 |
| --- | --- | --- |
| acceptance-matrix 第 15、134–146 行：阶段 0 固定性能/资源基线，迁移完成前至少三次同参数对比 | #01 未要求性能数据或阈值；#13/#14 未要求重复对比，spec 第 97 行有概括要求 | #01 记录参数、环境、吞吐、分位延迟、失败/重试、CPU/堆/线程/FD/连接池等并锁定比较标准；#13 执行至少三次同参数比较；#15 检查结果 |
| acceptance-matrix 第 122、129 行：命令正常/异常/重复执行幂等，多 Driver 端口递增，单侧/双侧/多 Driver 健康检查 | #12/#13/#14 只笼统要求启动两应用、健康检查和停止 | #12 明确命令语义、退出码、重复执行和多实例端口；#13/#14 覆盖各部署组合 |
| acceptance-matrix 第 112–116 行：干净 checkout/空缓存构建，dependency tree、duplicate classes、jdeps、license 作为 CI 闸门 | #02 有依赖和重复类报告；#13 未把这些报告定义成持续阻断条件 | #02 固定空缓存重现方式；#13 让依赖/重复类/jdeps/许可证检查失败能阻断流水线 |
| detailed-plan 第 208、211、335 行：开发脚本、构建说明、适配器指南与运行方式同步 | #15 只明确 release notes | #12 负责开发命令入口；#14/#15 负责 README、构建/Provider 指南及失效入口检查 |
| development-spec 第 66 行、acceptance-matrix 第 93–104 行：每个目标 factory 的发现、配置、生命周期和 operation | #07 明确四个名称的发现，但 operation 写作适用项，未列出各 factory 的验证归属 | #07/#13 建立按 sio/siov1/gdas/s3 划分的操作与错误矩阵，列明适用项和未通过原因 |

性能阈值应由基线和产品期望确定；本次复核不虚构百分比。#01 对不可用 endpoint 记录原因是合理的，后续不能据此把对应发布 smoke 当成通过。

**6. P2：优化阻塞边并限定每票规模**

当前分层顺序是：01 → 02 → 03 → {04,05} → 06 → 07 → 08 → 09 → {10,11} → 12 → 13 → 14 → 15。分层由现有 Blocked by 计算，不代表要求同一层所有票同时完工才推进。

- #08 的验收主要是 Mock 驱动的 HTTP 合同，却必须等待完整 #07 生产 Provider smoke。建议将基础 HTTP 合同依赖收敛为 Driver + Mock；生产 Provider 经 HTTP 的回归放入后续集成检查。
- #11 的页面通过本地 DriverService 工作，且 #08 已承接命令 backend，没有已写明的交付理由要求它等待 Controller client #09。保留跨进程验证作为集成闸门即可。
- #04/#05 要读配置并管理 grace period，#09 要校验超时等配置，但统一 YAML 契约在 #12，且 #12 等待两侧 Web。应先提供基础配置契约，再让 #12 完成转换、运维命令和旧数据兼容；否则各票容易各写一套临时配置。
- #07 已需要 MinIO，#13 才提供可复用环境。提前交付基础 Docker/MinIO harness，后面的 #13 聚焦完整矩阵、CI 和资源清理。
- #02 同时覆盖整个活跃闭包、目录、资源、依赖审计和旧构建保留，#03 又做公共模块 Maven 化，边界有重叠。应区分影子构建框架、各批次机械迁移及最终收缩，并说明每批保持构建可用的验证命令。

to-tickets 允许跨仓机械重构采用 expand–contract；因此公共模块票或构建票并不因缺少页面就自动不合格。这里需要修订的是每批范围和独立验证条件。#10 也宜按代表性页面验证、其余页面/CLI 兼容等可验证结果拆分，避免一张票承担整套 Web 迁移和全部回归。

**补充：配置缺失与无效配置需要精确区分**

[#04](history/2026-10-28-before-revision/issues/04-controller-boot.md)第 11 行将 missing 和 invalid configuration 一并定义为启动失败；[acceptance-matrix](../../docs/migration/scheme3/acceptance-matrix.md)第 35 行要求保留缺失/空配置的默认语义。[AbstractServiceFactory](../../dev/cosbench-core/src/com/intel/cosbench/service/AbstractServiceFactory.java)第 46–53 行在文件不存在时先创建空配置，之后各业务配置项还会执行自己的校验。

建议 #01 固定“默认文件不存在、显式配置路径不存在、空配置、缺必需字段、非法值”的实际结果，#04/#05/#12 按该表分别验收。静态源码只能确认先构造空配置，不能据此断言无配置的 Controller 最终一定启动成功。

**逐票处理建议**

| Ticket | 覆盖内容 | 修订重点 |
| --- | --- | --- |
| 01 | 行为基线、回退制品 | 可先启动；补性能/资源基线、配置边界、可重放端到端命令及完整场景索引 |
| 02 | Java 21、Maven、依赖 | 先统一 release=21；缩小影子构建首批范围，明确空缓存及旧构建保留方式 |
| 03 | 公共模块、SPI 边界 | 与 #02 去重；明确消费这些产物的可验证路径 |
| 04 | Controller 启停 | 固定配置缺失语义；验收停止接收新任务、在途任务和资源关闭 |
| 05 | Driver 启停 | 依赖基础配置契约；以真实 mission/worker 检查停止行为 |
| 06 | Mock registry | 保留；向后续 HTTP 提供可复用的测试 Provider 和生命周期验证 |
| 07 | 生产 Provider | 共存验证前移；按四个 factory 明确操作、配置和错误覆盖 |
| 08 | Driver HTTP | 基础合同解除对完整 #07 的强依赖；明确服务认证和幂等生命周期 |
| 09 | Controller client | 明确与 #08 共用的认证/请求身份约定、空闲回收和重试验证 |
| 10 | Controller Web/CLI | 先提取 Web spike；纠正 CLI 重排/重提；补页面超时不取消任务 |
| 11 | Driver Web | 检查对 #09 的不必要依赖；保留与 /i 共存及完整页面行为测试 |
| 12 | 配置、命令、旧数据 | 基础 YAML 和配置验证前移；补命令名称、幂等、多 Driver 和开发入口 |
| 13 | Docker/MinIO/CI | 环境基础提前；补性能、部署组合、构建审计闸门和失败清理 |
| 14 | 生产包、安装、回退 | 保留；验收真实生产制品的 Provider smoke，并同步使用/构建说明 |
| 15 | 最终收缩、扫描 | 保留；作为前面证据的最终闸门，避免集中补做未分配的实施工作 |

每票还应明确其继承的 spec/验收章节或稳定场景 ID、需读取的 blocker 产物，以及可观察的通过条件。引用合同来源即可，无需在票里罗列易过时的实现路径。现有票没有直接标明这些来源，独立上下文的实现者需要额外推断。

上述建议已落实为当前 spec 与 23 张 tickets；只有 #01 无 blocker，其余票需等待依赖证据。实际实现仍应从 #01 开始，按执行索引的 frontier 推进，并在 #03–#06 通过后再扩大迁移范围。
