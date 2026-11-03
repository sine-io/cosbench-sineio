#!/usr/bin/env bash
set -Eeuo pipefail

report_dir=/reports/controller-driver-process
runtime_dir=$(mktemp -d /tmp/cosbench-controller-driver.XXXXXX)
driver_pid=
controller_pid=
mkdir -p "$report_dir" "$runtime_dir/conf" "$runtime_dir/scripts" \
    "$runtime_dir/controller/launch" "$runtime_dir/controller/workspace" \
    "$runtime_dir/driver/missions" "$runtime_dir/tmp"

cleanup_process() {
    local name=$1 pid=$2
    [[ -n $pid ]] || return 0
    if kill -0 "$pid" 2>/dev/null; then
        kill -TERM "$pid" 2>/dev/null || true
        for ((attempt = 0; attempt < 100; attempt++)); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 0.1
        done
        if kill -0 "$pid" 2>/dev/null; then
            printf '%s did not stop after TERM\n' "$name" >&2
            kill -KILL "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
            return 1
        fi
    fi
    wait "$pid" 2>/dev/null || true
}

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    set +e
    cleanup_process Controller "$controller_pid" || status=1
    cleanup_process Driver "$driver_pid" || status=1
    [[ ! -f $runtime_dir/controller.log ]] || cp "$runtime_dir/controller.log" "$report_dir/controller.log"
    [[ ! -f $runtime_dir/driver.log ]] || cp "$runtime_dir/driver.log" "$report_dir/driver.log"
    [[ ! -d $runtime_dir/controller/archive ]] || cp -R "$runtime_dir/controller/archive" "$report_dir/archive"
    [[ ! -d $runtime_dir/driver/missions ]] || cp -R "$runtime_dir/driver/missions" "$report_dir/missions"
    rm -rf -- "$runtime_dir"
    printf 'replay_status=%s\nprocesses_remaining=none\n' "$status" > "$report_dir/cleanup.txt"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

wait_for_workload_state() {
    local workload_id=$1 expected_state=$2 output_file=$3
    for ((attempt = 0; attempt < 200; attempt++)); do
        curl --silent --show-error --fail --max-time 2 \
            "http://127.0.0.1:$controller_port/controller/_probe/workloads/$workload_id" \
            > "$output_file"
        if grep -q "\"state\":\"$expected_state\"" "$output_file"; then
            return 0
        fi
        sleep 0.05
    done
    printf 'workload %s did not reach %s\n' "$workload_id" "$expected_state" >&2
    cat "$output_file" >&2
    return 1
}

repo=/workspace
maven_repo=/tmp/maven-repository
driver_port=19486
controller_port=19487
test_password=$(<"$COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE")
export COSBENCH_HTTP_SERVICE_TOKEN
export COSBENCH_TLS_STORE_PASSWORD_FILE="$COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE"

mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f "$repo/shadow/pom.xml" \
    -DskipTests install > "$report_dir/reactor-install.log" 2>&1
mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f "$repo/shadow/pom.xml" \
    -pl cosbench-driver-app -DincludeScope=test -Dmdep.outputFile=/tmp/controller-driver-driver.classpath \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath \
    > "$report_dir/driver-classpath.log" 2>&1
mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f "$repo/shadow/pom.xml" \
    -pl cosbench-controller-app -DincludeScope=test -Dmdep.outputFile=/tmp/controller-driver-controller.classpath \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath \
    > "$report_dir/controller-classpath.log" 2>&1

mkdir -p "$runtime_dir/driver-test-classes/com/intel/cosbench/api/mock" \
    "$runtime_dir/driver-test-classes/com/intel/cosbench/testing/driver" \
    "$runtime_dir/driver-test-classes/META-INF/services"
cp "$repo/shadow/cosbench-driver-app/target/test-classes/com/intel/cosbench/api/mock/"*.class \
    "$runtime_dir/driver-test-classes/com/intel/cosbench/api/mock/"
cp "$repo/shadow/cosbench-driver-app/target/test-classes/com/intel/cosbench/testing/driver/DriverProcessProbe"*.class \
    "$runtime_dir/driver-test-classes/com/intel/cosbench/testing/driver/"
printf '%s\n' 'com.intel.cosbench.api.mock.MockAuthFactory' \
    > "$runtime_dir/driver-test-classes/META-INF/services/com.intel.cosbench.api.auth.AuthAPIFactory"
cp "$repo/shadow/cosbench-driver-app/target/test-classes/META-INF/services/com.intel.cosbench.api.storage.StorageAPIFactory" \
    "$runtime_dir/driver-test-classes/META-INF/services/"
driver_cp="$runtime_dir/driver-test-classes:$repo/shadow/cosbench-driver-app/target/classes:$(< /tmp/controller-driver-driver.classpath)"
controller_cp="$repo/shadow/cosbench-controller-app/target/test-classes:$repo/shadow/cosbench-controller-app/target/classes:$(< /tmp/controller-driver-controller.classpath)"
cp "$repo/.scratch/scheme3-migration/baseline/fixtures/trigger-noop.sh" \
    "$runtime_dir/scripts/baseline-trigger.sh"

cat > "$runtime_dir/conf/driver.yml" <<YAML
schemaVersion: 1
role: driver
name: cross-process-driver
listen:
  host: 127.0.0.1
  port: $driver_port
  contextPath: /driver
paths:
  mission: $runtime_dir/driver/missions
  logs: $runtime_dir/driver/system.log
auth:
  token:
    env: COSBENCH_HTTP_SERVICE_TOKEN
  tls:
    enabled: true
    keyStore: file:/run/secrets/http_test_keystore
provider:
  name: mock
logging:
  level: DEBUG
  file: $runtime_dir/driver/system.log
shutdown:
  gracePeriod: 10s
YAML

cat > "$runtime_dir/conf/controller.yml" <<YAML
schemaVersion: 1
role: controller
name: cross-process-controller
listen:
  host: 127.0.0.1
  port: $controller_port
  contextPath: /controller
paths:
  workspace: $runtime_dir/controller/workspace
  archive: $runtime_dir/controller/archive
  logs: $runtime_dir/controller/system.log
web:
  maxUploadBytes: 4096
drivers:
  - name: driver1
    url: https://127.0.0.1:$driver_port/driver
auth:
  token:
    env: COSBENCH_HTTP_SERVICE_TOKEN
  tls:
    enabled: true
    trustStore: file:/run/secrets/http_test_truststore
http:
  pool:
    maxConnections: 8
    maxPerRoute: 4
    idleTimeout: 5s
  timeouts:
    connect: 2s
    read: 5s
    command: 10s
    workload: 1m
  retries:
    maxAttempts: 3
    backoff: 100ms
logging:
  level: INFO
  file: $runtime_dir/controller/system.log
shutdown:
  gracePeriod: 10s
YAML

SERVER_SSL_KEY_STORE_PASSWORD="$test_password" java \
    -Djava.io.tmpdir="$runtime_dir/tmp" \
    --add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED \
    -cp "$driver_cp" com.intel.cosbench.app.driver.DriverApplication \
    --cosbench.config="$runtime_dir/conf/driver.yml" \
    --cosbench.providers.expected.storage=mock --cosbench.providers.expected.auth=mock \
    '--spring.main.sources=com.intel.cosbench.testing.driver.DriverProcessProbe$ProbeConfiguration' \
    > "$runtime_dir/driver.log" 2>&1 &
driver_pid=$!
for ((attempt = 0; attempt < 200; attempt++)); do
    if curl --silent --fail --max-time 1 --cacert "$COSBENCH_HTTP_TEST_CERT_FILE" \
        "https://127.0.0.1:$driver_port/driver/health" > "$report_dir/driver-health.json"; then
        break
    fi
    kill -0 "$driver_pid" 2>/dev/null || { cat "$runtime_dir/driver.log"; exit 1; }
    sleep 0.1
done
test -s "$report_dir/driver-health.json"
grep -q '"accepting":true' "$report_dir/driver-health.json"

java --add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED \
    -Djava.io.tmpdir="$runtime_dir/tmp" \
    -cp "$controller_cp" com.intel.cosbench.app.controller.ControllerApplication \
    --cosbench.config="$runtime_dir/conf/controller.yml" \
    '--spring.main.sources=com.intel.cosbench.testing.controller.ControllerProcessProbe$ProbeConfiguration' \
    > "$runtime_dir/controller.log" 2>&1 &
controller_pid=$!
for ((attempt = 0; attempt < 200; attempt++)); do
    if curl --silent --fail --max-time 1 "http://127.0.0.1:$controller_port/controller/health" \
        > "$report_dir/controller-health.json"; then
        break
    fi
    kill -0 "$controller_pid" 2>/dev/null || { cat "$runtime_dir/controller.log"; exit 1; }
    sleep 0.1
done
test -s "$report_dir/controller-health.json"

cat > "$runtime_dir/workload.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<workload name="controller-http-long" trigger="baseline-trigger.sh()">
  <auth type="mock" config="delay=0;errors=0" />
  <storage type="mock" config="size=64;delay=50;errors=0" />
  <workflow>
    <workstage name="main">
      <work name="read" workers="1" runtime="30">
        <operation type="read" ratio="100" config="containers=c(1);objects=c(1)" />
      </work>
    </workstage>
  </workflow>
</workload>
XML

curl --silent --show-error --fail --max-time 5 \
    --cookie-jar "$runtime_dir/page-session.cookies" \
    -H 'Content-Type: application/xml' --data-binary "@$runtime_dir/workload.xml" \
    "http://127.0.0.1:$controller_port/controller/_probe/workloads?fire=true" \
    > "$report_dir/workload-submit.json"
workload_id=$(sed -nE 's/.*"id":"([^"]+)".*/\1/p' "$report_dir/workload-submit.json")
test -n "$workload_id"
grep -q '"state":"PROCESSING"' "$report_dir/workload-submit.json"
grep -q '"sessionTimeoutSeconds":5' "$report_dir/workload-submit.json"
grep -q 'JSESSIONID' "$runtime_dir/page-session.cookies"

curl --silent --show-error --fail --max-time 2 \
    --cookie "$runtime_dir/page-session.cookies" \
    "http://127.0.0.1:$controller_port/controller/_probe/session" \
    > "$report_dir/page-session-created.json"
grep -q '"present":true' "$report_dir/page-session-created.json"
grep -q "\"workloadId\":\"$workload_id\"" "$report_dir/page-session-created.json"

finished=0
sleep 12
curl --silent --show-error --fail --max-time 2 \
    --cookie "$runtime_dir/page-session.cookies" \
    "http://127.0.0.1:$controller_port/controller/_probe/session" \
    > "$report_dir/page-session-state.json"
grep -q '"present":false' "$report_dir/page-session-state.json"
curl --silent --show-error --fail --max-time 2 \
    "http://127.0.0.1:$controller_port/controller/_probe/workloads/$workload_id" \
    > "$report_dir/workload-state-after-session-expiry.json"
grep -q 'PROCESSING' "$report_dir/workload-state-after-session-expiry.json"
sleep 19
for ((attempt = 0; attempt < 500; attempt++)); do
    curl --silent --show-error --fail --max-time 2 \
        "http://127.0.0.1:$controller_port/controller/_probe/workloads/$workload_id" \
        > "$report_dir/workload-state.json"
    if grep -q 'FINISHED' "$report_dir/workload-state.json"; then
        finished=1
        break
    fi
    if grep -q 'FAILED\|TERMINATED\|ERROR' "$report_dir/workload-state.json"; then
        cat "$report_dir/workload-state.json" >&2
        cat "$runtime_dir/controller.log" >&2
        cat "$runtime_dir/driver.log" >&2
        exit 1
    fi
    sleep 0.1
done
[[ $finished == 1 ]]

curl --silent --show-error --fail --max-time 3 \
    "http://127.0.0.1:$controller_port/controller/_probe/archive/$workload_id" > "$report_dir/workload-archive.json"
grep -q 'baseline-trigger-ok' "$runtime_dir/controller/archive/$workload_id-controller-http-long/scripts.log"
grep -q 'controller-http-long' "$runtime_dir/controller/archive/run-history.csv"
grep -q 'mission .* has been authed successfully' "$runtime_dir/driver.log"
grep -q 'mission .* has been executed successfully' "$runtime_dir/driver.log"
grep -q 'mission .* has been closed successfully' "$runtime_dir/driver.log"

cat > "$runtime_dir/abort-workload.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<workload name="controller-http-abort">
  <auth type="mock" config="delay=0;errors=0" />
  <storage type="mock" config="size=64;delay=50;errors=0" />
  <workflow>
    <workstage name="abort-stage">
      <work name="read" workers="1" runtime="60">
        <operation type="read" ratio="100" config="containers=c(1);objects=c(1)" />
      </work>
    </workstage>
  </workflow>
</workload>
XML

curl --silent --show-error --fail --max-time 5 \
    -H 'Content-Type: application/xml' --data-binary "@$runtime_dir/abort-workload.xml" \
    "http://127.0.0.1:$controller_port/controller/_probe/workloads?fire=true" \
    > "$report_dir/abort-workload-submit.json"
abort_workload_id=$(sed -nE 's/.*"id":"([^"]+)".*/\1/p' "$report_dir/abort-workload-submit.json")
test -n "$abort_workload_id"
grep -q '"state":"PROCESSING"' "$report_dir/abort-workload-submit.json"
abort_mission_id=
mission_launched=0
for ((attempt = 0; attempt < 100; attempt++)); do
    curl --silent --show-error --fail --max-time 2 \
        "http://127.0.0.1:$controller_port/controller/_probe/workloads/$abort_workload_id/missions" \
        > "$report_dir/abort-workload-missions.json"
    abort_mission_id=$(sed -nE 's/.*"missionIds":\["([^"]+)"\].*/\1/p' \
        "$report_dir/abort-workload-missions.json")
    if [[ -n $abort_mission_id ]]; then
        curl --silent --show-error --fail --max-time 2 --cacert "$COSBENCH_HTTP_TEST_CERT_FILE" \
            "https://127.0.0.1:$driver_port/driver/_probe/missions/$abort_mission_id" \
            > "$report_dir/driver-running-mission.json"
        if grep -q '"state":"LAUNCHED"' "$report_dir/driver-running-mission.json"; then
            mission_launched=1
            break
        fi
    fi
    sleep 0.1
done
[[ $mission_launched == 1 ]]

curl --silent --show-error --fail --max-time 5 -X POST \
    "http://127.0.0.1:$controller_port/controller/_probe/workloads/$abort_workload_id/cancel" \
    > "$report_dir/abort-workload-cancel.json"
cancelled=0
for ((attempt = 0; attempt < 300; attempt++)); do
    curl --silent --show-error --fail --max-time 2 \
        "http://127.0.0.1:$controller_port/controller/_probe/workloads/$abort_workload_id" \
        > "$report_dir/abort-workload-state.json"
    if grep -q 'CANCELLED' "$report_dir/abort-workload-state.json"; then
        cancelled=1
        break
    fi
    sleep 0.1
done
[[ $cancelled == 1 ]]
grep -q 'mission .* has been requested to abort' "$runtime_dir/driver.log"
mission_aborted=0
for ((attempt = 0; attempt < 100; attempt++)); do
    curl --silent --show-error --fail --max-time 2 --cacert "$COSBENCH_HTTP_TEST_CERT_FILE" \
        "https://127.0.0.1:$driver_port/driver/_probe/missions/$abort_mission_id" \
        > "$report_dir/driver-aborted-mission.json"
    if grep -q '"state":"ABORTED"' "$report_dir/driver-aborted-mission.json"; then
        mission_aborted=1
        break
    fi
    sleep 0.1
done
[[ $mission_aborted == 1 ]]

controller_url="http://127.0.0.1:$controller_port/controller"
web_fixture="$repo/shadow/harness/fixtures/controller-web-actions.xml"
curl --silent --show-error --fail --max-time 3 --cookie-jar "$runtime_dir/web.cookies" \
    "$controller_url/logon.html" > "$report_dir/web-logon.html"
grep -q 'name="j_username"' "$report_dir/web-logon.html"
login_status=$(curl --silent --show-error --max-time 3 --cookie "$runtime_dir/web.cookies" \
    --cookie-jar "$runtime_dir/web.cookies" --output "$report_dir/web-login.response" \
    --write-out '%{http_code}' --data 'j_username=anonymous&j_password=cosbench' \
    "$controller_url/j_security_check")
[[ $login_status == 302 ]]

page_status=$(curl --silent --show-error --max-time 5 --cookie "$runtime_dir/web.cookies" \
    --cookie-jar "$runtime_dir/web.cookies" --output "$report_dir/web-submit.html" \
    --write-out '%{http_code}' -F "config=@$web_fixture;type=application/xml" \
    "$controller_url/submit-workload.do")
[[ $page_status == 200 ]]
page_workload_id=$(grep -oE 'workload.html\?id=w[0-9]+' "$report_dir/web-submit.html" \
    | head -n 1 | cut -d= -f2)
test -n "$page_workload_id"
grep -q 'your workload has been accepted' "$report_dir/web-submit.html"
wait_for_workload_state "$page_workload_id" PROCESSING "$report_dir/web-workload-state.json"

curl --silent --show-error --fail --max-time 3 --cookie "$runtime_dir/web.cookies" \
    "$controller_url/index.html" > "$report_dir/web-index.html"
grep -q 'name="cancelIds"' "$report_dir/web-index.html"
grep -q 'name="resubmitIds"' "$report_dir/web-index.html"
grep -q "checkMe('$page_workload_id','AllActive')" "$report_dir/web-index.html"
curl --silent --show-error --fail --max-time 3 --cookie "$runtime_dir/web.cookies" \
    "$controller_url/workload.html?id=$page_workload_id" > "$report_dir/web-workload.html"
grep -q 'workload-state-processing state' "$report_dir/web-workload.html"
curl --silent --show-error --fail --max-time 3 --cookie "$runtime_dir/web.cookies" \
    "$controller_url/matrix.html?type=histo" > "$report_dir/web-matrix.html"
grep -q 'Performance Matrix' "$report_dir/web-matrix.html"
curl --silent --show-error --fail --max-time 3 --cookie "$runtime_dir/web.cookies" \
    "$controller_url/config.html" > "$report_dir/web-config.html"
grep -q 'action="config-workload.do"' "$report_dir/web-config.html"
curl --silent --show-error --fail --max-time 3 --cookie "$runtime_dir/web.cookies" \
    "$controller_url/advanced-config.html" > "$report_dir/web-advanced-config.html"
grep -q 'action="advanced-config-workload.do"' "$report_dir/web-advanced-config.html"

curl --silent --show-error --fail --max-time 3 \
    "$controller_url/cli/index.action?username=anonymous&password=cosbench" \
    > "$report_dir/cli-index.txt"
grep -q '^Drivers:' "$report_dir/cli-index.txt"
curl --silent --show-error --fail --max-time 3 \
    "$controller_url/cli/workload.action?username=anonymous&password=cosbench&id=$page_workload_id" \
    > "$report_dir/cli-workload.txt"
grep -q "^$page_workload_id$(printf '\t').*PROCESSING" "$report_dir/cli-workload.txt"
grep -q 'workload-state-processing state' "$report_dir/web-workload.html"

cli_submit_status=$(curl --silent --show-error --max-time 5 --output "$report_dir/cli-submit.txt" \
    --write-out '%{http_code}' -F "config=@$web_fixture;type=application/xml" \
    "$controller_url/cli/submit.action?username=anonymous&password=cosbench")
[[ $cli_submit_status == 200 ]]
cli_submit_id=$(sed -nE 's/^Accepted with ID: (w[0-9]+)$/\1/p' "$report_dir/cli-submit.txt")
test -n "$cli_submit_id"
cli_config_status=$(curl --silent --show-error --max-time 5 --output "$report_dir/cli-config.txt" \
    --write-out '%{http_code}' -F "config=@$web_fixture;type=application/xml" \
    "$controller_url/cli/config.action?username=anonymous&password=cosbench")
[[ $cli_config_status == 200 ]]
cli_config_id=$(sed -nE 's/^Accepted with ID: (w[0-9]+)$/\1/p' "$report_dir/cli-config.txt")
test -n "$cli_config_id"
wait_for_workload_state "$cli_submit_id" QUEUING "$report_dir/cli-submit-state.json"
wait_for_workload_state "$cli_config_id" QUEUING "$report_dir/cli-config-state.json"

page_second_status=$(curl --silent --show-error --max-time 5 --cookie "$runtime_dir/web.cookies" \
    --cookie-jar "$runtime_dir/web.cookies" --output "$report_dir/web-submit-second.html" \
    --write-out '%{http_code}' -F "config=@$web_fixture;type=application/xml" \
    "$controller_url/submit-workload.do")
[[ $page_second_status == 200 ]]
page_second_id=$(grep -oE 'workload.html\?id=w[0-9]+' "$report_dir/web-submit-second.html" \
    | head -n 1 | cut -d= -f2)
test -n "$page_second_id"
wait_for_workload_state "$page_second_id" QUEUING "$report_dir/web-second-state.json"

page_third_status=$(curl --silent --show-error --max-time 5 --cookie "$runtime_dir/web.cookies" \
    --cookie-jar "$runtime_dir/web.cookies" --output "$report_dir/web-submit-third.html" \
    --write-out '%{http_code}' -F "config=@$web_fixture;type=application/xml" \
    "$controller_url/submit-workload.do")
[[ $page_third_status == 200 ]]
page_third_id=$(grep -oE 'workload.html\?id=w[0-9]+' "$report_dir/web-submit-third.html" \
    | head -n 1 | cut -d= -f2)
test -n "$page_third_id"
wait_for_workload_state "$page_third_id" QUEUING "$report_dir/web-third-state.json"

reorder_status=$(curl --silent --show-error --max-time 5 --cookie "$runtime_dir/web.cookies" \
    --cookie-jar "$runtime_dir/web.cookies" --output "$report_dir/web-reorder.html" \
    --write-out '%{http_code}' --data "id=$page_third_id&neighid=$page_second_id&up=yes" \
    "$controller_url/index.html")
[[ $reorder_status == 200 ]]
grep -q 'class="high-light"' "$report_dir/web-reorder.html"
third_row=$(grep -nF "checkMe('$page_third_id','AllActive')" "$report_dir/web-reorder.html" | head -n 1 | cut -d: -f1)
second_row=$(grep -nF "checkMe('$page_second_id','AllActive')" "$report_dir/web-reorder.html" | head -n 1 | cut -d: -f1)
[[ -n $third_row && -n $second_row && $third_row -lt $second_row ]]

reject_reorder_status=$(curl --silent --show-error --max-time 5 --cookie "$runtime_dir/web.cookies" \
    --cookie-jar "$runtime_dir/web.cookies" --output "$report_dir/web-running-reorder.html" \
    --write-out '%{http_code}' --data "id=$page_workload_id&neighid=$page_third_id&up=no" \
    "$controller_url/index.html")
[[ $reject_reorder_status == 200 ]]
if grep -q 'class="high-light"' "$report_dir/web-running-reorder.html"; then
    printf '%s\n' 'running workload reorder unexpectedly succeeded' >&2
    exit 1
fi
third_row=$(grep -nF "checkMe('$page_third_id','AllActive')" "$report_dir/web-running-reorder.html" | head -n 1 | cut -d: -f1)
running_row=$(grep -nF "checkMe('$page_workload_id','AllActive')" "$report_dir/web-running-reorder.html" | head -n 1 | cut -d: -f1)
[[ -n $third_row && -n $running_row && $running_row -lt $third_row ]]

curl --silent --show-error --max-time 5 --cookie "$runtime_dir/web.cookies" \
    --cookie-jar "$runtime_dir/web.cookies" --output "$report_dir/web-cancel-queued.html" \
    --data "cancelIds=${page_second_id}_${page_third_id}&cancel=yes" "$controller_url/index.html"
wait_for_workload_state "$page_second_id" CANCELLED "$report_dir/web-cancel-queued-state.json"
wait_for_workload_state "$page_third_id" CANCELLED "$report_dir/web-cancel-third-state.json"
cli_cancel_status=$(curl --silent --show-error --max-time 5 --output "$report_dir/cli-cancel.txt" \
    --write-out '%{http_code}' "$controller_url/cli/cancel.action?username=anonymous&password=cosbench&id=$cli_submit_id")
[[ $cli_cancel_status == 200 ]]
grep -q "^$cli_submit_id$(printf '\t')" "$report_dir/cli-cancel.txt"
wait_for_workload_state "$cli_submit_id" CANCELLED "$report_dir/cli-cancel-state.json"
cli_cancel_config_status=$(curl --silent --show-error --max-time 5 --output "$report_dir/cli-cancel-config.txt" \
    --write-out '%{http_code}' "$controller_url/cli/cancel.action?username=anonymous&password=cosbench&id=$cli_config_id")
[[ $cli_cancel_config_status == 200 ]]
wait_for_workload_state "$cli_config_id" CANCELLED "$report_dir/cli-cancel-config-state.json"

page_cancel_status=$(curl --silent --show-error --max-time 5 --cookie "$runtime_dir/web.cookies" \
    --output "$report_dir/web-cancel.html" --write-out '%{http_code}' \
    "$controller_url/cancel-workload.do?id=$page_workload_id")
[[ $page_cancel_status == 200 ]]
wait_for_workload_state "$page_workload_id" CANCELLED "$report_dir/web-cancel-state.json"

resubmit_status=$(curl --silent --show-error --max-time 5 --cookie "$runtime_dir/web.cookies" \
    --cookie-jar "$runtime_dir/web.cookies" --output "$report_dir/web-resubmit.html" \
    --write-out '%{http_code}' --data "resubmitIds=$page_workload_id&resubmit=yes" \
    "$controller_url/index.html")
[[ $resubmit_status == 200 ]]
resubmitted_id=$(sed -nE 's/.*id="checkbox-(w[0-9]+)" name="ActiveWorkload".*/\1/p' \
    "$report_dir/web-resubmit.html" | tail -n 1)
test -n "$resubmitted_id"
[[ $resubmitted_id != "$page_workload_id" ]]
grep -q 'controller-web-actions' "$report_dir/web-resubmit.html"
wait_for_workload_state "$resubmitted_id" PROCESSING "$report_dir/web-resubmit-state.json"
curl --silent --show-error --max-time 5 --cookie "$runtime_dir/web.cookies" \
    --output "$report_dir/web-cancel-resubmitted.html" \
    "$controller_url/cancel-workload.do?id=$resubmitted_id"
wait_for_workload_state "$resubmitted_id" CANCELLED "$report_dir/web-cancel-resubmitted-state.json"
grep -q 'controller-web-actions' "$runtime_dir/controller/archive/$resubmitted_id-controller-web-actions/workload-config.xml"

printf '<workload' > "$runtime_dir/invalid-workload.xml"
archive_count_before=$(find "$runtime_dir/controller/archive" -mindepth 1 -maxdepth 1 -type d -name 'w*' | wc -l)
invalid_status=$(curl --silent --show-error --max-time 5 --output "$report_dir/cli-invalid-xml.txt" \
    --write-out '%{http_code}' -F "config=@$runtime_dir/invalid-workload.xml;type=application/xml" \
    "$controller_url/cli/submit.action?username=anonymous&password=cosbench")
[[ $invalid_status == 200 ]]
if grep -q 'Accepted with ID:' "$report_dir/cli-invalid-xml.txt"; then
    printf '%s\n' 'invalid workload XML was accepted' >&2
    exit 1
fi
head -c 8192 /dev/zero > "$runtime_dir/oversized-workload.xml"
oversized_status=$(curl --silent --show-error --max-time 5 --output "$report_dir/cli-oversized-upload.txt" \
    --write-out '%{http_code}' -F "config=@$runtime_dir/oversized-workload.xml;type=application/xml" \
    "$controller_url/cli/submit.action?username=anonymous&password=cosbench")
[[ $oversized_status -ge 400 ]]
archive_count_after=$(find "$runtime_dir/controller/archive" -mindepth 1 -maxdepth 1 -type d -name 'w*' | wc -l)
[[ $archive_count_before == "$archive_count_after" ]]
if find "$runtime_dir/tmp" -type f -print -quit | grep -q .; then
    printf '%s\n' 'multipart temporary files remained after request completion' >&2
    find "$runtime_dir/tmp" -type f -print >&2
    exit 1
fi

if grep -F -q -- "$COSBENCH_HTTP_SERVICE_TOKEN" "$runtime_dir/controller.log" \
    "$runtime_dir/driver.log" "$runtime_dir/controller/system.log" \
    "$runtime_dir/driver/system.log" "$report_dir/workload-archive.json"; then
    printf '%s\n' 'service token leaked into a process log or workload archive' >&2
    exit 1
fi

printf '%s\n' \
    'status=passed' \
    'processes=separate-controller-and-driver' \
    'transport=https-bearer-service-token' \
    'workload=submit-login-launch-query-trigger-close' \
    'page-session=expired-while-workload-processing' \
    'long-running-state=processing-then-finished-after-session-expiry' \
    'archive=workload-log-and-trigger-output-present' \
    'cancel=controller-issued-driver-abort;driver-mission=ABORTED' \
    'web=authenticated-login-pages-submit-query-cancel-and-history-resubmit' \
    'web-order=queued-reordered;running-reorder-rejected' \
    'cli=index-submit-config-workload-cancel' \
    'multipart=invalid-xml-rejected;oversized-rejected;archive-unchanged;temps-cleaned' \
    'token=absent-from-logs-and-archive' \
    > "$report_dir/summary.txt"
