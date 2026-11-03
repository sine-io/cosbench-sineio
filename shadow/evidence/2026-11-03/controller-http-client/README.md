# #14 Controller HTTP Client Evidence

Replay the full Java 21 Docker verification and process suite with:

```sh
bash ci/verify-shadow.sh /tmp/cosbench-14-finalrun12
```

The recorded run passed reactor `clean verify`, all 18 module dependency and artifact gates, configuration lifecycle checks, and all process replays. The HTTP spike reactor ran 41 tests with no failures; the Controller process suite ran 23 tests with no failures. See [build.log](build.log), [gates-summary.txt](gates-summary.txt), [maven-audit.log](maven-audit.log), and [Surefire reports](tests/).

The two-process replay starts independent Controller and Driver JVMs and completes a 30-second Mock workload over authenticated HTTPS. It captures a real `JSESSIONID`, confirms the linked page session is live, then verifies that the session expires after its five-second idle timeout while the workload remains `PROCESSING`; the workload later reaches `FINISHED` with its trigger output and archive intact. A second active mission is cancelled through Controller, and the replay observes Controller `CANCELLED` plus Driver `ABORTED`. It also verifies Driver authentication/execution/close and checks that the service token is absent from process logs and workload data. The captured requests, session state, mission states, logs, and archive are under [controller-driver-process](controller-driver-process/).

Transport coverage includes stable write identity after a lost response, bounded retry and command/workload deadlines, authentication and TLS failures, sequential HTTP connection reuse, idle endpoint-client eviction, and protection of active calls during idle eviction. The HTTP contract and fault replay summaries are [http-contract-summary.txt](http-contract-summary.txt) and [http-run-summary.txt](http-run-summary.txt).

The overall run reports `run_status=0` and `cleanup_status=0`; no containers, networks, volumes, or certificate containers remained. The process replay reports `replay_status=0` and no remaining Controller or Driver processes in [cleanup-status.txt](cleanup-status.txt) and [process cleanup](controller-driver-process/cleanup.txt).
