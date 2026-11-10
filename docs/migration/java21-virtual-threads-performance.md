# Java 21 虚拟线程对 COSBench 压测性能的预研

调研日期：2026-10-27。这里把“Java 21 新的并发模式”主要解释为已正式交付的**虚拟线程（Virtual Threads）**。OSGi DS 替换、JDK 8 → 21 的 JVM 变化和虚拟线程并发模型是三项不同变量；本文不把它们合并成一个性能结论。

## 1. 结论

**Java 21 + 虚拟线程可能让 COSBench Driver 以较低的平台线程资源承载更多阻塞 worker，但不能据此预期同样 worker 数下单操作更快或吞吐必然提高。**OpenJDK JEP 444 明确说明虚拟线程不是更快的线程；它们的目标是在大量、非 CPU 密集且经常等待的任务下提升可扩展性和潜在吞吐。Oracle 的 Java 21 指南也区分“scale（更多并发 / 潜在吞吐）”和“speed（更低延迟）”。[JEP 444](https://openjdk.org/jeps/444)、[Java 21 虚拟线程指南](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)

对应到 COSBench：

- **固定 worker 数比较：**如果每个 worker 继续运行原来的同步操作循环，只将 worker 的平台线程换成虚拟线程，预期吞吐和延迟接近原值，可能小幅波动；没有依据承诺正向提升。
- **增加 worker 数比较：**如果 Driver 目前受平台线程数量、原生线程栈或线程切换压力限制，虚拟线程可能帮助继续增加并发，从而在存储端、网络和 Driver CPU 尚有余量时提高总吞吐。这是扩大压测并发容量，不是让单个 I/O 变快。
- **低并发或 CPU 已饱和：**虚拟线程预计没有明显收益；CPU 密集任务增加线程数本来也不会突破核心数带来的并行上限。
- **端到端压测：**目标存储、SDK 连接池、连接建立、数据生成、operation picking、指标统计或 Driver CPU 可能先成为瓶颈。必须与同机实测分开判断。

Java 21 中 `StructuredTaskScope` 是另一项并发 API，但 JEP 453 将其作为 Preview 交付；它改善相关任务的组织和取消语义，不应与虚拟线程混为一谈，也没有理由把它当成吞吐优化来试点。[JEP 453](https://openjdk.org/jeps/453)

## 2. COSBench 当前并发结构

Driver 的主要压测任务是一组长生命周期 worker，而不是“每个操作新建线程”：

1. [`MissionHandler.createExecutor()`](../../dev/cosbench-driver/src/com/intel/cosbench/driver/service/MissionHandler.java) 根据 workload worker 数创建 `Executors.newFixedThreadPool(workers)`；每个 `WorkAgent` 作为一个长任务提交给该池。
2. 创建 worker context 时，每个 worker 获取自己的 `StorageAPI` 和 `AuthAPI`，并把对象保存在 [`WorkerContext`](../../dev/cosbench-driver/src/com/intel/cosbench/driver/model/WorkerContext.java)。
3. [`WorkAgent.performOperation()`](../../dev/cosbench-driver/src/com/intel/cosbench/driver/agent/WorkAgent.java) 在循环中选择 operation，再由 [`Writer`](../../dev/cosbench-driver/src/com/intel/cosbench/driver/operator/Writer.java) / [`Reader`](../../dev/cosbench-driver/src/com/intel/cosbench/driver/operator/Reader.java) 通过 `Session.getApi()` 调用同步 storage API。
4. [`COSBDriverService`](../../dev/cosbench-driver/src/com/intel/cosbench/driver/service/COSBDriverService.java) 的 cached pool 承担 mission 控制操作，不是每个对象 I/O 的 executor。第一轮虚拟线程试点应只改 Driver worker executor，避免同时改变控制面与工作负载语义。

这类“一个虚拟线程运行一个长时间 worker 任务”的迁移与 JDK 建议的 virtual-thread-per-task 模式相符。无需把每次读写或每个内部步骤重新包装为新的虚拟任务，也不应把虚拟线程再放进线程池。Java 21 的 [`Executors.newVirtualThreadPerTaskExecutor()`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html) 每个 task 启动一个虚拟线程，API 明确该 executor 创建的线程数没有上限；COSBench 必须继续用 workload worker 配置、**全局跨 mission worker 配额**和存储端容量控制总并发，不能把取消平台线程成本误解为取消负载上限。若多个任务竞争受限资源，应在资源边界使用有界 admission control / semaphore，而不是依赖虚拟线程数量做流量控制。[JEP 444：Do not pool virtual threads](https://openjdk.org/jeps/444)

目前的样例 workload 中有 `workers="8"`，但实际 workload 可配置不同数量。是否有可观收益，应先看真实 Driver 上的 worker 数、平台线程数、CPU 和连接等待，而不能只根据虚拟线程理论判断。[workload 样例](../../release/conf/config-samples/noop-config.xml)

## 3. 哪些变化可能影响压测

| 维度 | 可能变化 | 对 COSBench 的判断 |
| --- | --- | --- |
| 固定并发下的 operation 延迟 | 虚拟线程不会令 I/O、SDK 或 Java 代码本身执行更快 | 不应把 p50 / p99 下降作为默认预期；切换线程模型也可能引入调度开销或抖动 |
| 可承载并发数 | 被阻塞的虚拟线程可让出 carrier，不必每个阻塞 worker 都独占 OS 线程 | 主要潜在收益点；当前样例 8 worker 不足以证明平台线程已成限制，需沿高并发梯度测试 |
| Driver CPU / GC | 更多并发会让 Driver 处理更多请求、任务对象和采样；虚拟线程仍是堆对象 | CPU/GC 可能先于线程数成为上限，不应只测 RSS 或 OS 线程数 |
| SDK / HTTP 连接 | 虚拟线程不增加 SDK connection pool、服务端容量或网络带宽 | 提高 worker 时要同时观察 SDK 连接池排队、socket、服务端饱和和错误率 |
| 内存 / 连接 | 可降低大量平台线程的 OS 栈和相关开销，但 worker context、SDK client、缓冲区、operation 和虚拟线程仍占堆 / native memory | 本项目 `MissionHandler` 每 worker 创建一个 API，SIO factory 每次返回新 `SIOStorage`；每个实例初始化自己的同步 AWS SDK v2 `S3Client` / Apache HTTP client，`max_connections` 默认值为 50。因此提高 worker 数也会增加 client 与连接池数量，配置的每 client 上限并不等于 Driver 的统一全局连接上限。需测连接池、socket 和总内存，不能把平台线程节省直接等同于 Driver 总内存下降。[MissionHandler](../../dev/cosbench-driver/src/com/intel/cosbench/driver/service/MissionHandler.java)、[SIO factory](../../dev/cosbench-sineio/src/com/intel/cosbench/api/sio/SIOStorageFactory.java)、[SIOStorage](../../dev/cosbench-sineio/src/com/intel/cosbench/api/sio/SIOStorage.java)、[SIO 常量](../../dev/cosbench-sineio/src/com/intel/cosbench/client/sio/SIOConstants.java) |
| 阻塞锁与 pinning | Java 21 中虚拟线程在 `synchronized` 临界区或 native / foreign 调用内发生阻塞时会 pin 到 carrier；频繁、长时间 pinning 可降低扩展性 | 必须在目标 JDK 21 与 SDK 组合上用 JFR / pinned-thread trace 检查。不能用 Java 24 的改进推断 Java 21 的行为。[JEP 444](https://openjdk.org/jeps/444)、[JEP 491（JDK 24）](https://openjdk.org/jeps/491) |

### 3.1 本仓库中需要重点检查的代码

- [`AuthAgent.tryLogin()`](../../dev/cosbench-driver/src/com/intel/cosbench/driver/agent/AuthAgent.java) 在 `synchronized(AuthCachePool.getInstance())` 内执行缓存未命中后的 `authApi.login()`。登录可能进行网络 I/O，因此在 Java 21 虚拟线程下需检查 carrier pinning；该路径主要位于初次认证 / 重认证阶段，不能自动推断为 steady-state 存储 I/O 的热点。
- [`ObjectPicker.pickObjPath()`](../../dev/cosbench-driver/src/com/intel/cosbench/driver/util/ObjectPicker.java) 每次生成对象路径都会进入短时 `synchronized` 区域。锁内目前没有网络 I/O，因此主要风险是多个 worker 的锁竞争；改用虚拟线程不会消除这段串行区。应结合高 worker 压测和 CPU/JFR profile 观察。
- 所有 adapter 的 SDK 内部同步锁、native 调用和阻塞方式需要按实际启用的存储类型检查。仅从 COSBench Java 源码无法保证第三方库没有 pinning 或线程局部资源假设。
- [`COSBDriverService`](../../dev/cosbench-driver/src/com/intel/cosbench/driver/service/COSBDriverService.java) 当前用 `newCachedThreadPool()` 调度 login / launch。控制面不是第一轮改动目标；虚拟化它会改变并发控制请求的容量和语义，应另做测试。

OpenJDK JEP 444 为 JDK Flight Recorder 提供 `jdk.VirtualThreadPinned` 事件，并提供 `-Djdk.tracePinnedThreads=full` / `short` 诊断属性。应在 JDK 21 实验中启用诊断，检查 pinning 持续时间和调用栈，而不能根据代码存在 `synchronized` 就直接判定每条路径都会 pin。[JEP 444：Pinned virtual threads 与诊断](https://openjdk.org/jeps/444)

## 4. 建议的渐进验证

### 4.1 分离 JDK 升级和线程模型

| 组别 | JDK | worker 线程 | 要回答的问题 |
| --- | --- | --- | --- |
| A | 迁移前实际部署的 JDK（先记录） | 平台线程 | 建立迁移前吞吐、延迟和资源基线 |
| B | JDK 21 | 平台线程 | 隔离 JIT、GC、运行时与依赖升级造成的变化 |
| C | 同一 JDK 21 | 虚拟线程 | 隔离线程实现变化；固定与 B 相同的 worker 数 |
| D | 同一 JDK 21 | 虚拟线程与平台线程均逐步增加 worker | 比较两种模型的最大稳定负载能力；此处改变了负载强度，须与固定 worker 数结果分开报告 |

COSBench 当前 bundle manifests 普遍声明 `Bundle-RequiredExecutionEnvironment: JavaSE-1.8`，根构建使用 Tycho 1.7.0。该声明是 bundle 兼容性要求，不足以单独确认当前生产环境实际运行的 JDK 版本。直接调用 Java 21 的虚拟线程 API 前，要先确认 Tycho / OSGi target platform 能编译并打包该 API，并更新 bundle execution environment 与运行时要求；不要只换启动用的 JDK 就认为已经完成 Java 21 兼容性迁移。[Driver manifest](../../dev/cosbench-driver/META-INF/MANIFEST.MF)、[根 POM](../../pom.xml)

### 4.2 负载和指标

先只改 `MissionHandler.createExecutor()`：平台线程与虚拟线程各提交同样数量的 `WorkAgent`，保持 API client 数、worker 数、operation mix 和代码路径一致。再做 worker 数梯度，逐步增加并发并预先设置对存储 endpoint 的安全上限。建议在隔离测试环境中使用 `8 → 32 → 128 → 512 → ...` 的递增阶梯，若目标是验证 JEP 所述的高并发可扩展性，再考虑达到数千并发；每一步确认错误率、连接池等待、CPU 和存储端余量后再继续。

实验阶段：

1. **mock 装配 / 本地调度：**从 `release/conf/config-samples/noop-config.xml` 启用 mock，设置 `delay=0;printing=false;doStats=false` 观察 Worker loop / 数据生成成本；另测有阻塞等待的 mock 配置。Mock 不代表真实 SDK / socket 行为，只适合筛查线程调度和本地 CPU 上限。
2. **真实同步 I/O：**选一个受支持且可重复的 adapter，例如当前同步 `SIOStorage` + 固定 S3 endpoint。保留相同 SDK 版本、HTTP client 配置、连接池、对象大小和服务端；记录客户端连接等待与目标端吞吐，避免把后端瓶颈归因于线程模型。
3. **单独测认证阶段：**认证 cache hit / miss、重试和并发登录应独立统计，以暴露 `AuthAgent` 里的同步网络调用和潜在 pinning；不要把 login 时间混在 steady-state object I/O 结果里。

每个场景交替运行平台线程 / 虚拟线程，充分预热后重复至少五轮，报告原始轮次及中位数 / 离散程度。记录 ops/s、p50/p95/p99、错误数、Driver CPU、GC pause / allocation、heap / RSS、平台线程与虚拟线程数量、连接池排队 / timeout、目标端 CPU 与网络；在虚拟线程组记录 JFR pinned events。固定 worker 数的差值回答“线程模型是否改变了同一负载下的性能”；逐步加 worker 的曲线回答“哪种模型能支持更大的并发负载”，不要混成一个“Java 21 提升率”。

## 5. 预研结论与实施建议

先升级到受支持的 JDK 21，并在平台线程模型下建立新基线；随后仅在 Driver 的 worker executor 做小范围虚拟线程实验。若当前常用 worker 数较低且 Driver CPU / 线程资源未成为限制，保留平台线程更简单，虚拟线程对吞吐未必有实际帮助。若要向非常高的阻塞 worker 数扩展，且当前被平台线程和线程栈限制，再验证虚拟线程，同时设置全局 worker / connection 配额。

此次仅完成代码与官方资料预研，没有修改 executor 或执行 benchmark，因此没有 COSBench 的实际性能差值。虚拟线程即使可提高负载生成能力，也不代表存储服务自身性能提高；结果要同时说明负载生成器能力与目标系统容量。

## 参考资料

- [JEP 444: Virtual Threads（JDK 21）](https://openjdk.org/jeps/444)
- [Java 21 Virtual Threads Guide](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)
- [Java 21 `Executors.newVirtualThreadPerTaskExecutor()` API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html)
- [JEP 453: Structured Concurrency (Preview)](https://openjdk.org/jeps/453)
- [JEP 491: Synchronize Virtual Threads without Pinning（JDK 24）](https://openjdk.org/jeps/491)
- [Java 21 JFR `jdk.VirtualThreadPinned` event](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/jdk/jfr/events/VirtualThreadPinnedEvent.html)
