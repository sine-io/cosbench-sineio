# REL-01: production release and rollback

Issue: [#22](../../../../.scratch/scheme3-migration/issues/22-release-rollback-validation.md).
Date: 2026-11-09. Implementation base:
`f2cb0a81883151b9eceac9182ca88d0c720bc893`.
The fixed archive/image, deployment combinations, production Provider work,
old-data conversion/restart and complete-package rollback passed. See the
machine-readable [result](reports/summary.json),
[checksums](reports/package-checks.json), and [cleanup](reports/cleanup-status.json).

The exact invocation from the repository root was:

```sh
python3 shadow/packaging/validate-release.py \
  --archive out/release-validation-22/candidate-final/cosbench-0.6.0.2-java21.tar.gz \
  --legacy-archive out/release-validation-22/rollback/cosbench-0.6.0.2-sineio.tar.gz \
  --report out/release-validation-22/accepted
```

The complete retained local report is `out/release-validation-22/accepted/`.
It contains application logs, health/state/exit reports, workload fixtures,
Prometheus responses, page/CSV/download responses, original/copied data and
both installation trees. Only compact redacted evidence is versioned here.
The earlier failed attempts and focused checks remain under
`out/release-validation-22/`; they are not counted as a successful release.

## Fixed inputs

The accepted production archive is `cosbench-0.6.0.2-java21.tar.gz`, SHA-256
`f901fa096366294b8e1b5628d27185f50873ce2b1364ff47f32bd76962cafec3`.
An independent rebuild was byte-identical. [Image identities](reports/images.json)
record the actual production image built only from the extracted archive and
the complete Java 8 rollback image; shared base images remain available.

| Executable application | SHA-256 | Comparison |
| --- | --- | --- |
| Controller | `8c3e0b21176f9a6f6b3d2903ee66a7ef7c99167cf0db0316eb06ed3747aedd83` | Identical to #19 |
| Driver | `adfa693b4f57030272cd8bdc60ac315679f7c80fbe1aa2481f36d5fbb9573bc2` | Identical to #19 and #20 |
| Operator CLI | `897c2305dca69cb16aaa55f21fb1f78bec5a9e3325029f0f20cee3e621adb4d7` | Identical to #19 |

These are whole executable JAR hashes, including every nested runtime
dependency. Packaging changed the handover documents, not the applications
or dependency closure. The runner rejects a changed candidate before creating
runtime resources; affected #19/#20 checks must be repeated before accepting
new hashes. The [#19 inputs](inputs/integration-artifacts.sha256),
[#20 record](inputs/performance-profile.txt) and [three-run samples](inputs/performance.csv)
are retained independently of untracked worktree files.

#20 is a Driver-only migrated-runtime profile: one warmup, three measured
runs, mean 44,208.75 ops/s, population CV 4.48%. It makes no legacy comparison,
production-image throughput claim or SLO assertion. Since the Driver JAR is
identical, this ticket does not fabricate a second performance result.

## Actual production acceptance

Applications received release files, read-only YAML/secrets and isolated
writable data copies. They had no source or test classpath, P2 or Eclipse.
The archive ran its executable applications using the pinned Java 21 Maven
image as a Java host. The actual production image uses its Java 21 JRE.
Both used explicit host UID/GID overrides for bind-mount ownership.
The workload probe is external HTTP tooling, never an application dependency.
Archive and image have separate fresh data directories.

| Artifact | Controller only | Driver only | Both | Multiple Drivers | Restart/history |
| --- | --- | --- | --- | --- | --- |
| Production archive | Passed | Passed | Passed | Passed | Passed |
| Production image | Passed | Passed | Passed | Passed | Passed |

The [deployment records](reports/deployments.json) contain actual health,
exit code, PID and stop timing for each role. Startup discovered exactly
`gdas, s3, sio, siov1`; production contamination checks rejected Mock/test/OSGi
content. Stopping one Driver left the other healthy. Stops used the packaged
command, required the service drain log, process/PID disappearance and host
listener release, including a live S3 mission at shutdown. Exit 143 records
normal handled SIGTERM; OOM/forced process termination did not count as a pass.

Both artifacts ran each Provider with one and two Drivers: 16 finished
workloads. Each used real MinIO and performed init, write, read, list, head,
multipart write/read/delete and dispose. Per Provider/workload, expected
operation totals were write 2, read 4, list 2, head 2, mwrite 2, delete 4, at
100% success. Write/read/mwrite bytes were 128 / 10,485,962 / 10,485,834.
See [operation counts](reports/providers.tsv) and [byte counts](reports/provider-bytes.tsv).
Driver health confirmed zero active missions after each completed workload.

The [HTTP contract results](reports/http-contracts.json) cover all eight `/i`
commands with TLS, Bearer authentication, Driver identity and request identity.
A direct production S3 mission returned 10 successful samples and 640 bytes;
the stored 64 zero bytes matched SHA-256
`f5a5fd42d16a20302798ef6ed309979b43003d2320d9f0e8ea9831a92759fb4b`.
Repeated submit reused its result; trigger and abort were exercised.
Controller/Driver pages, stage/timeline/CSV, config/log downloads, Prometheus,
page login and all five CLI actions were checked against actual workload IDs.

## Old data and rollback

The retained complete previous archive is version `0.6.0.2-sineio`, source
`cd18daf2f6f97f97409f4709d00bbd7389676d6`, SHA-256
`1421f48a55673e42edd73c7b09a610b2b71ef94c70d46db3fb74dd3c34d22a06`.
It generated a real completed old-binary Mock workload with archive, mission
logs and original INI. This is an isolated binary-compatibility fixture;
no customer dataset was supplied. Mock was confined to this previous release
and the pre-existing test suite, never the migrated production image.

The production CLI converted copies of the old Controller/Driver INI. Both
unmodified conversion output and explicit Docker listen/Driver DNS adjustments
are [recorded](reports/migration/docker-adjustments.json). The new processes
read old history/details/config/log downloads before and after restart;
mission logs remained readable with matching bytes. Original files did not
change. After stopping the new processes, the complete previous distribution
started with the retained original INI, read the same history and downloads,
and stopped again. The legacy Driver wrapper that rewrites INI was bypassed
using its original `cosbench-start.sh` entry.

See [rollback result](reports/rollback/summary.json) and
[original config/archive/workspace/mission hashes](reports/old-data-integrity.json).
Legacy `org.eclipse.osgi` caches normally regenerate and system logs append;
[cache changes](reports/rollback/runtime-cache-changes.json) are recorded
separately from preserved user data. The legacy stop wrapper's three-second
poll can return 1 while the JVM is still draining. Its raw return is retained
in [commands/exit codes](reports/commands.log); the runner independently
required all legacy Java processes to exit within 40 seconds before continuing.
Mission logs survive on disk; RAM mission history/idempotency state does not
recover across restarts. Rollback does not merge results created separately
by the new version. Keep the old complete archive and original snapshot until
the migration window ends. Follow the [operator runbook](../../../../docs/release-rollback.md).

## Additional verification and cleanup

The [reactor result](reactor-tests.json) contains 35 suites / 179 tests, with
zero failures, errors or skips. All 21 modules passed the
[dependency/duplicate/jdeps/license gates](reports/ci-gates-summary.txt).
Packaging contract tests, archive checksum/contamination scan and deterministic
rebuild passed. [Negative checks](reports/negative-checks.json) confirmed that
a mismatched candidate was rejected and SIGTERM exit 143 cleaned its resources.

`bash ci/verify-shadow.sh out/release-validation-22/full-suite` completed its
Maven, HTTP, process, operator, failure/interruption and deployment checks.
The existing CI wrapper nevertheless returned **1** during final cleanup:
its volume-deletion policy conflicts with the user's requirement to retain
data volumes. A task-owned holder prevented deletion of its unique MinIO
volume; it was removed without `-v` after all CI containers had disappeared.
The [raw CI summary](reports/full-suite-integration-summary.txt) and
[raw cleanup status](reports/full-suite-cleanup-status.txt) retain that failure;
the 20 acceptance IDs have no missing reports. They are not relabeled as a
clean CI pass. The [safe cleanup record](reports/safe-ci-cleanup.json) confirms
no remaining CI containers/networks, preserves the data volume, and retains
its protected runtime directory because it contains archives and mission logs.

The release runner itself exited 0 and removed all its uniquely named
containers, network, image tags and temporary secrets. Reports, all data
copies, MinIO data, rollback archives and shared resources remain. No global
prune or shared temporary-directory cleanup was performed. Supported scope
is `sio`, `siov1`, `gdas`, `s3`; vendor archive/restore/prefetch/range backend
validation remains the previously agreed manual work. Jackson 1.x, Castor,
SDK major upgrades and #23's root-build/OSGi removal are separate tickets.

The [Standards and Spec reviews](review.md) have zero unresolved findings in
each axis.
