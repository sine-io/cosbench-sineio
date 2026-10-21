# Workload and metrics semantics — Phase 0

## 完整执行链路（已确认）

1. `XmlConfig` wraps a path/stream (`dev/cosbench-config/src/com/intel/cosbench/config/XmlConfig.java:29-40`). Castor resolver uses an `Unmarshaller` and class loader to parse the stream/file (`dev/cosbench-config/src/com/intel/cosbench/config/castor/CastorConfigResolver.java:32-77`); `CastorWorkloadResolver.java:30-52` resolves/validates workload and `CastorMissionResolver.java` resolves mission.
2. `Workload` defaults auth/storage to `none` (`dev/cosbench-config/src/com/intel/cosbench/config/Workload.java:36-46`) and propagates default auth/storage into stages when absent (`:128-132`).
3. `COSBControllerService.submit` creates `WorkloadContext` (`dev/cosbench-controller/src/com/intel/cosbench/controller/service/COSBControllerService.java:115-145`); `StageRunner.runStage` performs submit → auth → launch → query → close and merges report (`dev/cosbench-controller/src/com/intel/cosbench/controller/service/StageRunner.java:124-161,195-250`).
4. Controller tasklets POST `driver.url + "/i/" + command + ".command"` (`dev/cosbench-controller/src/com/intel/cosbench/controller/tasklet/AbstractHttpTasklet.java:55-75`). Driver web maps `*.command` (`dev/cosbench-driver-web/WEB-INF/web.xml:35-50`) to `COSBDriverService` commands; service creates a mission/handler on submit and runs async login/launch (`dev/cosbench-driver/src/com/intel/cosbench/driver/service/COSBDriverService.java:83-196`).
5. `MissionHandler.init` resolves mission, creates operators/picker/workers/executor (`dev/cosbench-driver/src/com/intel/cosbench/driver/service/MissionHandler.java:91-214`). Storage/auth types go through `COSB*APIService` factory matching (see adapter report).
6. `WorkAgent` divides total ops/bytes by total workers (`dev/cosbench-driver/src/com/intel/cosbench/driver/agent/WorkAgent.java:141-160`), chooses operation by ratio (`:194-198`), and invokes an operator. Operator-to-API calls are direct: `Preparer.java:113`/`MPreparer.java:114` create container; `Writer.java:96`/`FileWriter.java:170` create object; `MWriter.java:98`/`MFileWriter.java:169` multipart; `Reader.java:87` get object; `Restorer.java:85` restore; `Header.java:86` head; `Lister.java:81` list; `Deleter.java:74` delete object; `Cleaner.java:110,159` delete container/list. `LocalWriter.java:43-45,100-124` is a separate local-file path and has no storage API call in its implementation (**已确认源码形态，文件 I/O 结果仍待运行测试**).

## Workload fields and timing

- `workers`: `MissionHandler.createWorkers` creates one `WorkerContext` per worker (`MissionHandler.java:176-193`); `WorkAgent` divides `mission.totalOps`/`totalBytes` by `mission.totalWorkers` (`WorkAgent.java:154-155`). Integer division means remainder/rounding behavior must be tested for non-divisible totals (**已确认 code, 待验证 expected user contract**).
- `runtime`, `rampup`, `rampdown`: `WorkAgent` sets `begin`, `end = begin + runtime*1000`, `timeout = end + rampdown*1000` (`WorkAgent.java:147-160`); mission executor timeout is rampup+runtime+rampdown+60 (`MissionHandler.java:314-338`).
- `ratio`: operation picker is populated from `Operation.getRatio()` (`MissionHandler.java:161-164`); random operation choice is in `WorkAgent.java:194-198`. Zero/invalid sum validation is not proven here (**待验证**).
- object naming/size: operator contexts and pickers are created from operation config (`MissionHandler.java:122-169`); concrete container/object/size generators live under `dev/cosbench-driver/src/com/intel/cosbench/driver/util` and `generator`; sample configs use `u(...)`, `r(...)`, `c(...)` (e.g. `release/conf/config-samples/gdas-config-sample.xml:10-19`).
- logical operation vs HTTP requests: one operator callback represents one logical COSBench operation; adapter SDK may issue multiple HTTP requests (multipart, auth, SDK retries). No universal 1:1 HTTP counter exists (**已确认 architecture, 待后端 wire measurement**).

## Sample/report counting semantics

- `Mark` fields distinguish successful and total counts (`dev/cosbench-core/src/com/intel/cosbench/bench/Mark.java:38-41`). `addSample` increments successful sample only when sample is successful but always increments `totalSampleCount` (`:148-167`); `addOperation` similarly tracks successful/total operations (`:170+`).
- `WorkAgent` keeps current interval marks and global runtime marks. Samples are always added to current; global marks only when operation/sample timestamp lies in runtime window (`WorkAgent.java:203-218`). Snapshot marks are converted and current marks cleared (`:220-237`); final report uses runtime marks and first/last runtime sample boundaries (`:239-270`).
- `Metrics.convert(Mark, window)` sets ratio = successful/total, average times divided by successful samples, throughput = successful samples / window × 1000, bandwidth = successful bytes / window × 1000 (`dev/cosbench-core/src/com/intel/cosbench/bench/Metrics.java:210-228`). **Confirmed units:** throughput is logical successful samples/sec; bandwidth uses bytes accumulated by successful samples; window is milliseconds.
- `Aggregator` sums sample/total counts, worker count and throughput/bandwidth; average response/transfer is weighted by successful samples; latency histograms merge then recalculate (`dev/cosbench-core/src/com/intel/cosbench/bench/Aggregator.java:50-112`). Controller stage aggregation uses `StageRunner.java:158-181`; driver report merge uses `MissionContext.java:111-128` and `ReportMerger.java:41-61`.
- Histogram buckets/counters are in `Counter.java:29-76`; `Histogram.recalcPercentiles` supports 60/80/90/95/99/100 and chooses the first cumulative bucket at threshold (`Histogram.java:103-143`). Exact interpolation/rounding is bucket-based, not raw latency percentile.
- `MissionContext` defaults `histogram=true`, reads mission config key `histogram`, and builds latency from operator counters (`MissionContext.java:59-60,111-128`).
- Empty/lagging worker snapshots are compensated by multiplying throughput/bandwidth by `runlen+1` (`WorkerContext.java:123-157`); this is a compatibility-sensitive correction and must be retained/tested.

## Report and export path

- On workload stop, `COSBControllerService.workloadStopped` calls the archiver (`dev/cosbench-controller/src/com/intel/cosbench/controller/service/COSBControllerService.java:370-379`). `SimpleWorkloadArchiver.archive/doArchive` writes `run-history.csv`, per-run workload/latency/stage CSV, `workload-config.xml`, `workload.log`, `scripts.log`, `workloads.csv`, per-driver CSV and per-worker CSV (`dev/cosbench-controller/src/com/intel/cosbench/controller/archiver/SimpleWorkloadArchiver.java:65-115,119-170,214-325`). The configured root is `archive` (`:40-60` and `release/conf/controller.conf:5`).
- The controller web exposes `.csv` and `/prometheus` (`dev/cosbench-controller-web/WEB-INF/web.xml:27-40`). `PrometheusController` emits operation count, bytes, average response/transfer/process time, throughput, bandwidth and success ratio (`dev/cosbench-controller-web/src/com/intel/cosbench/controller/web/PrometheusController.java:141-181`); active workloads use the last snapshot while stopped workloads use the final report (`:289-300`). System properties `prometheus.export.workloads.active/history/archived` and `prometheus.export.timeout` control selection (`:196-230`).

## Failure/retry/error semantics

- Auth retry is separate from operation counting: `retry` controls login agent attempts (`MissionHandler.java:46-48,170-172`; `Agents.java:24-27`). A 401 can invalidate auth and trigger re-login (`AbstractOperator.java:168-183`), while operation failures go through `errorStatisticsHandle` (`AbstractOperator.java:125-166`).
- Error statistics are keyed by a target/stack trace map (`dev/cosbench-core/src/com/intel/cosbench/bench/ErrorStatistics.java:25+`); old message parsing is fragile and the 401 detection is substring based (`AbstractOperator.java:175-178`).
- `WatchDog` schedules timeout at the computed mission timeout (`dev/cosbench-driver/src/com/intel/cosbench/driver/agent/WatchDog.java:46-72`); `MissionHandler` converts timeout to mission abort (`:327-338`). Whether a backend SDK retry duplicates HTTP attempts while preserving one logical sample is **待测试确认**.

## 需要保留的语义测试

1. Non-divisible `totalOps`/workers, zero runtime, and rampup/rampdown boundaries.
2. Failed sample vs failed operation counts, 401 re-login, timeout, and abort.
3. Snapshot blank-version compensation and histogram on/off (`histogram` key).
4. Multi-worker and multi-driver weighted averages, throughput/bandwidth sum, percentile bucket merge.
5. SineIO range/prefetch/multipart/restore/head and `storage_class`/`restore_days` configs; README claims are not accepted as tests (`README.md:28-52`).
