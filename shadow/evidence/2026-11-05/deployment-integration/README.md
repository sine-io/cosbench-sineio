# #19 Deployment Integration Evidence

The complete host replay ran with:

```sh
bash ci/verify-shadow.sh /tmp/cosbench-scheme3-19-complete
```

It completed with `run_status=0`, `cleanup_status=0`, no project containers,
networks, volumes or certificate containers remaining, and all 20 indexed
acceptance IDs passed (BASE-01 is indexed as consumed evidence; its original
ticket's remaining acceptance items are not closed here). The empty-cache
Java 21 reactor had 21 modules and 178 tests across 34 Surefire XML reports,
with no failures, errors or skips. The
dependency, duplicate-class, `jdeps`, license, production-boundary and
configuration gates all passed. See [integration acceptance](reports/index/integration-acceptance.tsv),
[summary](reports/index/integration-summary.txt), [CI gate results](ci/gates-summary.txt),
[reactor](ci/reactor.tsv), and the machine-readable [test reports](tests/).

The replay used Java 21.0.12.1 / Maven 3.10.0 in the digest-pinned images in
[versions.env](reports/versions.env). It tested the packaged Controller and
Driver JARs as separate processes in the development image; this does not
claim production image/package acceptance owned by #21. Artifact hashes,
configuration hashes, source revision and the source-diff fingerprint are in
[deployment provenance](reports/).

Controller-only, Driver-only, Controller plus one Driver, and Controller plus
two Drivers all passed live health, process, port, TLS/authentication, mount,
and stop checks. The both and multi-Driver scenarios each ran `sio`, `siov1`,
`gdas`, and `s3` through the public Controller CLI. All eight workloads
finished with the exact write/read/list/head/multipart/delete counts, 100%
success, and no active missions. See [single-Driver results](reports/both/providers.tsv)
and [two-Driver results](reports/multi-driver/providers.tsv). The production
classpath registers all four factories together and excludes Mock; Mock
lifecycle coverage remains in the test-classpath suite. The existing SineIO/S3
provider suite separately records configuration errors, operation failures,
and disposal behavior.

Restart against the same mounts preserved workspace markers, archives, mission
files and logs, and loaded the completed history through the authenticated
archive page. The negative replay stopped required MinIO and rejected the
workload; the interrupt replay returned 143 after an accepted workload.
Both retained state/log evidence and removed their application and probe
containers. [Cleanup scenarios](cleanup-tests/summary.txt), [deployment summary](reports/summary.txt),
and [runtime cleanup](reports/cleanup-status.txt) record the outcomes. Four
generated credential values were scrubbed from 1,428 retained UTF-8 files;
the sanitized request XML copies parse successfully. See [redaction status](reports/index/report-redaction.txt).

The two-axis review used fixed point `9948b2c5bfaab2c81ea99a33446f3d95d872e65f`.
It found one Standards issue and two Spec issues, all fixed and rechecked; the
review record is [here](review.md).

The #11 deferral for real archive-backend restore, prefetch and range remains
manual validation. This replay does not substitute MinIO for that backend or
close the historical production data and rollback acceptance owned by #22.
