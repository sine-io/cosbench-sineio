#!/usr/bin/env bash
set -Eeuo pipefail

report_dir=/reports/controller-driver-process
runtime_dir=$(mktemp -d /tmp/cosbench-controller-driver.XXXXXX)
driver_pid=
controller_pid=
mkdir -p "$report_dir" "$runtime_dir/conf" "$runtime_dir/scripts" \
    "$runtime_dir/controller/launch" "$runtime_dir/controller/workspace" \
    "$runtime_dir/driver/missions"

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
    'token=absent-from-logs-and-archive' \
    > "$report_dir/summary.txt"
