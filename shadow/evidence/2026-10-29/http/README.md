# Ticket 06 HTTP Contract Evidence

Replay from the repository root:

```sh
shadow/harness/run.sh /tmp/cosbench-scheme3-http -- \
  /bin/bash /workspace/shadow/harness/http-contract.sh
```

The final Java 21 Docker run is recorded in `build.log`, `http-targeted-tests.log`, `http-contract-summary.txt`, and `http-run-summary.txt`. The clean shadow reactor passed, including configuration lifecycle, provider/MinIO smoke, web integration, dependency tree, and artifact audits. The targeted HTTP suite passed 32 tests with no failures. `cleanup-status.txt` records `run_status=0`, `cleanup_status=0`, and no remaining containers, networks, volumes, or certificate-generation containers.

The contract tests cover token-only service authentication, request identity and payload fingerprinting, concurrent duplicate execution, replayed failure results, conflict handling, TLS trust and rejection, retry bounds, shared pool limits, pool-acquisition timeout accounting, idle eviction while a request is active, and stale Driver-instance rejection after restart. `http-dependency-tree.txt` records resolved HTTP module dependencies.

The real `COSBDriverService` runs behind the Spring HTTPS endpoint with Mock auth/storage and an explicit generated test truststore. The test submits, replays, authenticates, launches, completes, closes, and queries a mission without page-session cookies; a late submit replay after mission completion returns the original response. Toxiproxy truncates the first TLS submit response and is removed during the configured client retry backoff; the retry uses the same identity and payload and leaves exactly one mission. Command and workload deadlines bound instance discovery, requests, retry attempts, and backoff. An instance ID returned by Driver prevents an ambiguous write retry from executing against a restarted process, which returns 409 and an unknown-result diagnostic instead.

MVP service tokens do not expire naturally. Operators rotate the configured secret between workloads; active workloads do not rely on refresh. Idempotency results remain in memory for the Driver process lifetime and are not recovered across restarts. Socket-level accepted-connection counts are not measured by this spike.
