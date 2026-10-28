# Scenario index

This index follows [acceptance matrix sections 3-7](../../../docs/migration/scheme3/acceptance-matrix.md). `Captured` means a stable input fixture and an observed run exist. `Replay` means the shell harness captures the response on each run. `Pending` means no acceptance result is claimed yet.

## Controller pages and actions

| Route / action | Input | Expected observation | Evidence |
| --- | --- | --- | --- |
| `/controller/index.html`, `/logon.html`, `/logonError.html` | GET; session cookie is dynamic | status, view name, login form and cookie/session semantics | index and login flow replayed; login error semantics pending |
| `/matrix.html`, `/submit.html`, `/config.html`, `/advanced-config.html` | GET; then original form fields and fixed Mock workload | HTML/form fields, validation errors, accepted ID and queued/running state | pages replayed; valid config and advanced Mock submissions replayed; form variants pending |
| `/submit-workload.do`, `/config-workload.do`, `/advanced-config-workload.do` | multipart Mock upload or URL-encoded page fields | accepted/error view, generated XML or accepted ID, no partial archive on invalid XML | fixed submit upload, config XML generation, and advanced Mock submission replayed; all combinations and invalid XML pending |
| `/workload.html`, `/cancel-workload.do` | ID from accepted long-running Mock fixture | state, cancellation result, archive/log output and clean worker termination | workload page and CLI cancel replayed; page cancel pending |
| `/download-log.do`, `/download-config.do` | completed workload ID | status, filename/content type, bytes and redaction | replayed for `w1`; content normalization and redaction review pending |
| `/stage.html`, `/timeline.html`, `/timeline.csv`, `/prometheus` | completed Mock archive | stable columns/metric names, content type, dynamic timestamp normalization | replayed with real `w1/s1-main`; Prometheus status/content type/labels/samples asserted; CSV fixture review pending |
| static CSS, JS, PNG, welcome/error pages | GET | status/content type and checksum or normalized body | selected Controller/Driver CSS, ECharts/chart JS, PNG, welcome and login-error routes replayed with non-empty MIME checks; full resource/error matrix pending |

## Controller CLI

The five external actions are index, submit, config, workload, and cancel. Credentials are the documented default fixture `anonymous:cosbench`; never put real user credentials into this directory. `submit` and `config` use `fixtures/mock-noop.xml`; `cancel` uses `fixtures/long-noop.xml` and a dynamic workload ID. Record the HTTP status and body, then normalize generated IDs and dates before comparing.

| Route | Method / inputs | Expected behavior | Evidence |
| --- | --- | --- | --- |
| `/controller/cli/index.action` | GET, username/password | driver and active workload text | Captured, replayed |
| `/controller/cli/submit.action` | multipart `config` | accepts and fires workload, returns `Accepted with ID: ...` | Captured, replayed |
| `/controller/cli/config.action` | multipart `config` | accepts and fires configured workload | Captured, replayed |
| `/controller/cli/workload.action` | GET, `id` | stable ID/date/state tab fields | Captured, replayed |
| `/controller/cli/cancel.action` | POST, `id` | cancels then returns workload state | Captured, replayed |

## Driver pages and `/i`

| Route | Input | Expected behavior | Evidence |
| --- | --- | --- | --- |
| `/driver/index.html`, `/submit.html`, `/mission.html`, `/workers.html` | GET; optional mission ID | page model/view and status | Captured, replayed |
| `/submit-mission.do` | multipart mission XML | submit response and mission ID | Pending |
| `/perform-login.do`, `/launch-mission.do`, `/close-mission.do`, `/abort-mission.do`, `/run-trigger.do` | mission ID or safe trigger fixture | state transition, report/log and script output | Pending; direct `/i` covers mission lifecycle |
| `/download-log.do` | mission ID | filename/content type/bytes | Pending |
| `/i/ping.command` | POST body: controller timestamp | `200`, driver name/address and dynamic timestamp | Captured, replayed |
| `/i/submit.command` | POST mission XML | `200`, generated mission ID; invalid/missing required field is `400` | Captured, replayed |
| `/i/login.command` | POST mission ID | auth success/failure fields and state | Captured, replayed |
| `/i/launch.command` | POST mission ID | launch response, interval and state | Captured, replayed |
| `/i/query.command` | POST mission ID | reports, histogram, versions and running state | Captured, replayed |
| `/i/close.command` | POST completed mission ID | worker report, result state, error statistics and log | normal close captured and replayed; close-after-abort returns legacy `409` |
| `/i/abort.command` | POST active long mission ID | abort response, final state, worker shutdown and log | abort and follow-up query captured and replayed |
| `/i/trigger.command` | POST `enableTrigger,baseline-trigger.sh,1` | safe script output; no remote shell/host operation | Captured, replayed |

Mission IDs, workload IDs, timestamps, servlet identity text, histogram values, and report metrics are dynamic. Compare codes, stable JSON fields, state names, operation totals, success ratio and required response fields rather than raw bodies.

## Configuration boundaries

| Case | Controller | Driver | Evidence required |
| --- | --- | --- | --- |
| Default config file missing | file lookup falls back to `/etc/cosbench/controller.conf`; empty config then lacks required `controller.drivers` | file lookup falls back to `/etc/cosbench/driver.conf`; empty config is allowed and defaults name/URL to `N/A`, mission dir to `log/mission` | Process exit code, startup log and filesystem changes |
| Explicit config path missing | `cosbench.controller.config` selects explicit path; parser currently creates an empty config when path is missing | `cosbench.driver.config` selects explicit path; parser currently creates an empty config when path is missing | Launch with the system property and capture diagnostics |
| Empty config file | required `controller.drivers` lookup fails | defaults above are applied | Process exit code, stderr/logs and created directories |
| Missing required fields | missing `controller.drivers`; driver fields have defaults in this release | `name`, `url`, `mission_dir` default to `N/A`, `N/A`, `log/mission` | Compare actual loaded config and health response |
| Illegal value | malformed INI is wrapped as `cannot load <service> configuration`; numeric `drivers`/`concurrency` parsing | malformed INI and numeric/range parser behavior | Exact exception, log and process exit code |

These last-column outcomes are source-derived expectations and still need isolated process fixtures. The replay saves the versioned config references and the generated `controller.conf`/`driver_1.conf` snapshots. Controller and Driver factory code chooses the explicit `cosbench.{controller,driver}.config` property first, then local config paths, then `/etc/cosbench/...`; the legacy service factory accepts an absent file as an empty configuration.

## Provider baseline

The release package contains `sio`, `siov1`, `gdas`, and `s3` provider sample configurations, including `release/conf/config-samples/sio-config-smoke-test.xml` and the S3/SineIO sample XML files. A valid endpoint and credentials are needed to turn these into passing operation/error baselines. Endpoint availability, auth errors, timeout behavior, 4xx/5xx mappings, operation support, multipart behavior, and resource cleanup remain `Pending`; the placeholder credentials in release samples are not usable credentials and do not count as a pass.
