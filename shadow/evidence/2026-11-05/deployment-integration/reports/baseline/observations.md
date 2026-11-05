# Captured observations

All timings and dynamic identifiers below are examples from one disposable run of the official `0.6.0.2-sineio` release archive on 2026-10-28 UTC. Replays must store their own raw headers, bodies, status codes, timestamps, and logs under the output directory.

## Runtime and artifact

| Item | Observed value |
| --- | --- |
| Release tag / source commit | `0.6.0.2-sineio` / `cd18daf2f6f97f97409f4709d00bbd7389676d6` |
| Archive SHA-256 | `1421f48a55673e42edd73c7b09a610b2b71ef94c70d46db3fb74dd3c34d22a06` |
| Archive size | 72,922,000 bytes; 70 MiB compressed |
| Container base | `eclipse-temurin:8-jre-jammy`, image digest `sha256:48a8129eda9d0c8a81c86d1837c125b63a5ee8558ba9565b6066516ae72a8d47` |
| JVM | Temurin `1.8.0_504` |
| OS / architecture | Ubuntu 24.04.4 host, x86_64; COSBench container Ubuntu 22.04 Jammy, linux/amd64 |
| CPU / memory | 2 vCPU, AMD EPYC 7K62; host reports 1.9 GiB RAM and 4 GiB swap |
| Container limits | each Controller/Driver container uses `--cpus=1`, `--memory=768m`, `--pids-limit=512`; host VM cgroup reported 2 CPUs and 2,014,720 KiB memory |
| Filesystem | 50 GiB root volume, about 8.2 GiB free at capture time |
| Runtime package | Java 8 JRE; no `jcmd` or `jstat` executable in the release image |
| Mock workload | 1 Driver, 1 worker, 10 `read` operations, 64-byte object, `delay=0`, `errors=0` |

The downloaded archive passed `sha256sum -c` against the SHA-256 file published beside the release. The private/unauthenticated GHCR manifest lookup returned `denied`, so the archive was used with the pinned Ubuntu/Temurin base to build the local temporary runtime image.

## Replay harness verification

The harness was checked with `BASELINE_RUNS=1 BASELINE_OPS=100`. The Controller accepted CLI submit/config and page submit workloads as `w1`, `w2`, and `w3`, and an advanced matrix submission as `w4`; each reached `FINISHED`. The long `w5` fixture was cancelled and reached `CANCELLED`. For `w1`, the page response exposed stage `s1-main`; workload, stage, timeline, timeline CSV, download-log, and download-config requests all returned `200`, and the archive contains CSV, XML, log, and histogram files. The selected Controller/Driver CSS, JavaScript and PNG resources returned non-empty bodies with their legacy MIME types. The page config endpoint returned an XML attachment, and the advanced form returned the accepted workload in the submission list. Prometheus captures had the legacy `text/plain; version=0.0.4` content type, workload labels and operation samples. The Driver performance row reported 100 operations, 6,400 bytes, and `100%` success.

Cleanup removed both containers and the temporary image and verified that neither remained (`run_status=0`, `cleanup_status=0`). With the pinned release wrapper and its bash-compatible `/bin/sh`, both legacy stop scripts returned `0`; stop output and statuses remain in the result directory.

The default `BASELINE_RUNS=3 BASELINE_OPS=10000` replay reported 10,000 samples and 640,000 bytes at 100% success in every measured run. It also passed the versioned semantic JSON checks for Driver ping, missing mission, and invalid mission submission. The captured Driver elapsed times were 1,551, 1,530, and 1,548 ms, with throughputs of 10,799, 17,730, and 17,544 ops/s; inclusive p50/p95/p99 histogram buckets were `0~10 ms` in each run. These are evidence for variance review, not a frozen pass threshold. The final post-workload Prometheus scrape contained state, operation count, byte count, latency, throughput, bandwidth, and success-ratio metrics for the completed Mock workloads.

## HTTP behavior

| Boundary | Request | Observed response |
| --- | --- | --- |
| Driver page | `GET /driver/index.html` | `200`, `text/html;charset=UTF-8`, FreeMarker HTML |
| Driver submit page | `GET /driver/submit.html` | `200`, `text/html;charset=UTF-8` |
| Driver mission page | `GET /driver/mission.html` | `200`, `text/html;charset=UTF-8` |
| Driver workers page | `GET /driver/workers.html` | `200`, `text/html;charset=UTF-8` |
| Driver CSS | `GET /driver/resources/cosbench.css` | `200`, `text/css`, 3,559 bytes in this run |
| Driver ping | `POST /driver/i/ping.command`, body `0` | `200`, JSON `{code:200,succ:true,name:"127.0.0.1:18088",address:"http://127.0.0.1:18088/driver",timeStamp:<dynamic>}` |
| Driver missing mission | `POST /driver/i/query.command`, body `missing` | `404`, `{"code":404,"succ":false,"error":"mission not found"}` |
| Driver empty ping | `POST /driver/i/ping.command`, empty body | `400`, `Bad Request`; JSON error includes a dynamic servlet object identity |
| Driver invalid mission | `POST /driver/i/submit.command`, missing required `workers` | `400`, `{"code":400,"succ":false,"error":"must specify 'workers' for a mission"}` |
| Controller index | `GET /controller/index.html` | `200`, HTML and a dynamic `JSESSIONID` cookie |
| Controller CLI index | `GET /controller/cli/index.action?username=anonymous&password=cosbench` | `200`, text listing `driver1` at `http://127.0.0.1:18088/driver`, zero active workloads |
| Controller Prometheus | `GET /controller/prometheus` | `200`, `text/plain; version=0.0.4;charset=utf-8`; empty body before a workload |
| Driver trigger | `POST /driver/i/trigger.command`, safe `baseline-trigger.sh` | `200`, `{"pid":"0","scriptLog":"baseline-trigger-ok\n","code":200,"succ":true}` |

The three Docker-host-network start attempts used during diagnosis also found two harness caveats. The Docker entrypoint's combined `CONTROLLER=true,DRIVER=true` branch printed a Driver bundle startup `[ERROR]` while the Driver process later listened on port 18088; the independently started Controller and Driver processes both served their health pages. A GET request to the body-based ping command returned `400`; the command requires a POST body containing the Controller timestamp.

## Mock mission and archive

The Controller CLI upload accepted the fixed Mock workload as `w1`; the run moved from `PROCESSING` to `FINISHED` and produced the archive under `archive/w1-baseline-noop/`. The Driver created mission `MB80784573A`, reached `ACCOMPLISHED`, and reported:

| Metric | Value |
| --- | ---: |
| Operations | 10 |
| Successful ratio | 100% |
| Bytes | 640 |
| Average response time | 0.2 ms |
| Published throughput | 2,000 ops/s |
| Archive workload CSV | 541 bytes |
| Archive stage CSV | 223 bytes |
| Archive histogram CSV | 1,027,836 bytes |

Archive CSVs contain wall-clock timestamps and workload IDs. Histogram output is a large time-dependent artifact; compare parsed operation summaries rather than the raw histogram file.

The direct Driver lifecycle also covers submit, login, launch, query, close, abort, and trigger. A normal mission closes successfully. In the abort scenario the abort request and query are captured, then the subsequent close request returns `409` with `no mission handler`; this is preserved as a legacy error case.
