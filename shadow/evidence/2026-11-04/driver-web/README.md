# Driver Web Acceptance (#17)

The Driver Boot application serves the legacy FreeMarker pages and static resources alongside the authenticated `/i` command API. Page handlers use the same `DriverService`; page login and browser cookies do not provide service-command authentication. Driver templates use HTML output auto-escaping for mission and error data.

The real HTTPS Driver/Mock contract covers `/driver/`, `index.html`, `submit.html`, `mission.html`, `workers.html`, CSS/PNG resources, multipart mission submission, provider login, launch/close/abort, trigger, log download, error pages, and all eight `/i` commands. It verifies page-submitted missions through `/i`, rejects a page cookie as service authentication, checks illegal lifecycle transitions, compares the downloaded log byte-for-byte with the service log, and checks HTML escaping for submitted mission names and exception text. [Surefire result](tests/com.intel.cosbench.app.driver.DriverHttpContractTest.txt) records 13 tests with no failures.

The final validation used Maven 3.10.0 and Java 21.0.12.1:

```sh
sudo bash ci/verify-shadow.sh /tmp/cosbench-driver-web-verified-1793782582
```

The complete CI entry passed: reactor clean verify, 20-module dependency/artifact gates, provider MinIO smoke, and Controller/Driver/Web/HTTP process checks. The Driver application suite passed 34 tests, including the 13 HTTP contract tests; Controller passed 26 tests and the HTTP adapter spike passed 41 tests. The Driver process replay passed startup, restart, stale-write rejection, token redaction, and TERM cleanup. [Process summary](driver-process/summary.txt), [stop results](driver-process/results.txt), and [process cleanup](driver-process/cleanup.txt) record those results.

The harness finished with `run_status=0` and `cleanup_status=0`; no containers, networks, volumes, or certificate containers remained. The Driver process replay reported `processes_remaining=none`. See [CI summary](ci-summary.txt), [run summary](run-summary.txt), [Docker cleanup status](cleanup-status.txt), and [module gate results](gates-summary.txt). The legacy Driver web source contains no JavaScript asset; its CSS and PNG resources were migrated from the available resource set.
