# Controller Web Jakarta Spike

Replay from the repository root:

```bash
shadow/harness/run.sh shadow/evidence/2026-10-29/web-jakarta -- /bin/bash /workspace/shadow/harness/web-spike-process.sh
```

The Docker harness ran the Java 21 shadow reactor `clean verify` and then launched the packaged Boot 3.5.16 JAR. All three `ControllerWebSpikeTest` tests passed. They exercise the real `COSBControllerService`, Jakarta session login and failure, FreeMarker index rendering, CSS/JavaScript/PNG routes, multipart CLI authentication/errors, the 10 MiB upload boundary, and authenticated config/log downloads. The Maven log also records the existing config, runtime, and provider checks.

The process smoke saves HTTP statuses, bodies, and redacted headers under `web-http/`. It compares the served CSS, ECharts JavaScript, and header PNG byte-for-byte with their legacy source resources. It submits the #01 `mock-noop.xml`, verifies the accepted ID and archived config/log downloads, sends TERM, and confirms exit status 143, port 19088 release, and temporary-directory removal. The process starts in that temporary directory; Surefire starts in the module `target` directory, so legacy `start`/`stop` markers do not pollute the checkout. Docker `cleanup-status.txt` reports `cleanup_status=0` with no remaining harness containers, networks, or volumes.

The workload fixture intentionally configures no Driver. Submission and archival use the real controller service, then the workload reaches `TERMINATED` with the expected `no free driver available` diagnostic. The full Controller-to-Driver chain belongs to later tickets. On Java 21, Castor 1.3.3 serialization requires `--add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED`; the flag is scoped to this spike's Surefire test and process launch.

Key retained files include `build.log`, `web-dependency-tree.txt`, `web-artifact.sha256`, `web-artifact-contents.txt`, `web-fixtures/`, `web-http/`, `web-process.log`, `web-process-stop.txt`, `web-process-cleanup-status.txt`, `web-static-baseline-comparison.txt`, and `cleanup-status.txt`.
