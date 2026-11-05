# Deployment integration (INT-01, #19)

Run the complete integration entry from the host:

```sh
bash ci/verify-shadow.sh /tmp/cosbench-scheme3-19
```

The report directory must be empty. Docker commands use `sudo`. The harness
builds the entire effective shadow reactor on the pinned Java 21/Maven image
with an empty container-local Maven cache. It runs the dependency, license,
checksum, duplicate-class, `jdeps`, resource and production-boundary gates,
then the existing configuration, process, Web, HTTP and operator replays.
The root legacy build is unchanged.

`deployment-cleanup-tests.sh` then checks a required but unavailable MinIO
endpoint and interrupts an accepted production workload. Both attempts must
fail, retain their requests/state and application logs, remove their application
and probe containers, and leave no successful integration summary. MinIO is
restored before the successful attempt. Every attempt gets fresh application
mounts in the enclosing harness's private temporary directory.

`deployment-integration.sh` runs the executable Controller and Driver JARs in
separate containers on that same Docker network:

| Combination | Observable checks |
| --- | --- |
| Controller only | Real `/controller/health`, configured listener and Java process, clean TERM |
| Driver only | Real HTTPS `/driver/health`, TLS requirement, unauthenticated `/i` rejection |
| Controller + Driver | Authenticated TLS commands issued by Controller CLI; all four production workloads |
| Controller + two Drivers | Both Drivers participate in workloads; independent processes and mission mounts; isolated Driver stop |
| Restart on the same mounts | Archived workload page, unchanged workspace markers, mission files and application logs |

Configuration files, executable JARs, formal commands and Docker secrets are
mounted read-only. Each process has its own writable workspace/archive/mission/
log mount. PID files use a separate ephemeral `/run/cosbench` tmpfs. Container
inspection records the mounts, health, host PID and process command; stopping
checks the actual exit code and absence of a host process. Ports are checked on
the Compose network without reserving additional host ports.

The deployment containers use the pinned development image with only the
packaged applications and their launch commands mounted into them. Production
image construction and release/rollback acceptance remain #21/#22.

## Provider evidence

Each Driver starts with exactly the production `gdas,s3,sio,siov1` registrations
on the shared application classpath. No child loader is needed. Mock is present
only in the existing test-classpath replays; the production artifact gate rejects
Mock classes and registrations in either executable's nested libraries.

For **each factory**, both deployment combinations submit a workload through
`/controller/cli/submit.action`, poll `/cli/workload.action` to `FINISHED`, and
check the public Prometheus report for exact write/read/list/head/multipart/
delete counts, a 100% success ratio and transferred byte counts. Each workload
creates and deletes its own bucket, reads ordinary and multipart objects, and
must leave no active Driver mission. The two Driver combination distributes two
workers across both Drivers; their mission files and health are retained.

`both/providers.tsv` and `multi-driver/providers.tsv` record each factory's
workload and close result. The existing `provider-smoke.txt`, Surefire reports
for SineIO/S3/Driver, and `sineio-driver-process/` provide full data-integrity,
configuration/error mapping, client disposal, close/abort and active-mission
TERM checks on the same build. No unavailable required endpoint or skipped
operation is accepted as evidence. S3 restore remains unsupported. The #11
decision to defer real archive-backend restore/prefetch/range to manual
validation still applies; MinIO and controlled peers are not described as that
backend.

## Reports and replay boundaries

`integration-acceptance.tsv` maps every applicable stable acceptance ID through
INT-01 to concrete commands and report paths. `integration-summary.txt` is
successful only after the complete run, Docker cleanup and required evidence
checks pass. Incomplete and failed runs remain visibly unsuccessful.

The deployment report includes image digests, application SHA-256 values,
source commit/diff fingerprint, scenario configurations and their SHA-256
values, health, requests, states, operation counts, mount/process inspections,
restart history and cleanup results. Full build/audit/test reports remain next
to it. Before deleting temporary secrets, the outer harness redacts their
actual values from **all retained text**, including failure/interruption logs
and archived workload configurations. Configuration fingerprints identify the
original input; redacted XML/configurations are diagnostic copies.

Success, failure and signals stop the current attempt before the enclosing
Compose project is removed. Only the project-scoped containers, network,
ephemeral MinIO test volume and private temporary credentials/data are removed.
Reports and shared images/resources are retained. The next invocation starts
with a fresh Maven repository, certificate, network and test data.
