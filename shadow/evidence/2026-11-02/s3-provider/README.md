# #12 Legacy S3 Production Provider Evidence

Implementation started at `9d9e3965570e48329ca5492c00c05b4d56a3e304`. The provider is in the normal Maven reactor and production Driver dependency graph. The ticket is `ready-for-human`; deployment-wide coexistence with all SineIO modes remains assigned to #19.

## Replay

```sh
bash ci/verify-shadow.sh /tmp/cosbench-s3-ticket12-final
```

The replay uses the pinned Java 21/Maven/MinIO/Toxiproxy Docker setup in [versions.env](../../../harness/versions.env). It runs the full reactor, dependency and artifact audits, MinIO smoke tests, executable Driver checks, active-mission TERM replay for all four production storage types, and the continuous harness checks. The run completed with `status=passed`, `run_status=0`, and `cleanup_status=0`; no containers, networks, volumes, or certificate containers remained.

## Results

| Verification | Result and evidence |
| --- | --- |
| Full reactor | 132 tests passed with zero failures, errors, or skips; [build.log](build.log) and [toolchain.txt](toolchain.txt) |
| S3 provider API | 15 S3 tests passed: config/defaults, registry discovery, signature and path-style request, HTTP 400/403/404/409/500/503 cause mapping, timeout, connection refusal, multipart abort on failed part, and HTTP pool release; [test reports](tests/cosbench-s3/) |
| MinIO operations | put/get/list/head/delete, empty-object PUT, multipart over 5 MiB with byte equality, and cleanup; also exercised by the shared provider smoke; [S3 tests](tests/cosbench-s3/) and [provider smoke](provider-smoke.txt) |
| Driver mission | completed mission, abort, and factory-level multipart/list work through the production `s3` factory; [Driver reports](tests/cosbench-driver-app/) |
| Active Driver process | registry contained `gdas,s3,sio,siov1`, no Mock; real S3 MinIO mission received TERM, exited 143 in 2 seconds, stopped its executor, and released the listener; [S3 process log](sineio-driver-process/s3.log), [health](sineio-driver-process/s3-health.json), [results](sineio-driver-process/results.txt), [summary](sineio-driver-process/summary.txt) |
| Production artifact | `cosbench-s3` descriptor present, Mock/test resources absent, Driver executable includes the S3 module; [S3 inventory](ci/cosbench-s3/jar-contents.txt), [Driver inventory](ci/cosbench-driver-app/jar-contents.txt), [boundary report](provider-artifact-boundary.txt) |
| Dependency isolation | AWS SDK v1 `1.12.767` shared with SineIO and SDK v2 `2.26.8` coexist on the shared classpath; duplicate-class and `jdeps` audits passed; [S3 dependency tree](s3-dependency-tree.txt), [provenance](provider-sdk-provenance.txt), [duplicate-class audit](ci/cosbench-s3/duplicate-classes.txt) |
| License and checksums | resolved S3 dependency licenses/checksums and provider/Driver artifact checksums are recorded in [S3 license/checksum inventory](ci/cosbench-s3/licenses-checksums.tsv), [S3 artifact checksum](provider-artifact.sha256), [dependency checksums](s3-dependency-checksums.txt), and [Driver checksum](ci/cosbench-driver-app/artifact.sha256) |
| Shared CI gates | all 17 modules passed dependency, artifact, license, and duplicate-class gates; [summary](ci/gates-summary.txt) |
| Multipart failure cleanup | the controlled endpoint test first failed with abort disabled (`expected 1, actual 0`), then passed when production abort handling was restored; [red](tdd/abort-red.log), [green](tdd/abort-green.log) |
| Cleanup | `run_status=0`, `cleanup_status=0`, no test containers/networks/volumes or certificate containers left behind; [cleanup status](cleanup-status.txt) |

Configuration defaults and operation limits are documented in [S3 provider](../../../s3-provider.md), with a runnable legacy sample at [s3.properties](../../../cosbench-s3/src/main/resources/examples/s3.properties). The S3 artifact declares Apache-2.0 and packages `META-INF/LICENSE` and `META-INF/NOTICE`, matching the Intel-source license in the repository. Legacy S3 has no archive restore implementation or region setting; neither is claimed. `aws_region` remains SineIO-specific, the legacy `S3SignerType` is retained, and the shared v1 SDK selection remains `1.12.767` from #04.
