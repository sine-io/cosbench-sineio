#!/usr/bin/env bash
set -Eeuo pipefail

repo=/workspace
report=/reports/operator-commands
runtime=$(mktemp -d /tmp/cosbench-operator-commands.XXXXXX)
pid_dir="$runtime/pids"
operator_jar="$repo/shadow/cosbench-operator-cli/target/cosbench-operator-cli-0.1.0-SNAPSHOT.jar"
controller_jar="$repo/shadow/cosbench-controller-app/target/cosbench-controller-app-0.1.0-SNAPSHOT.jar"
driver_jar="$repo/shadow/cosbench-driver-app/target/cosbench-driver-app-0.1.0-SNAPSHOT.jar"
mkdir -p "$report" "$runtime/conf" "$runtime/log" "$runtime/archive" "$runtime/workspace" "$pid_dir"
mkdir -p "$runtime/scripts"
cp "$repo/.scratch/scheme3-migration/baseline/fixtures/trigger-noop.sh" "$runtime/scripts/baseline-trigger.sh"
chmod +x "$runtime/scripts/baseline-trigger.sh"
export COSBENCH_SERVICE_TOKEN=${COSBENCH_HTTP_SERVICE_TOKEN:-operator-command-test-token}
driver_store_password=$(<"$COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE")

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    set +e
    COSBENCH_PID_DIR="$pid_dir" "$repo/bin/cosbench-stop" all --timeout 10 >"$report/cleanup.log" 2>&1
    [[ $? == 0 ]] || status=1
    if [[ -d $runtime/dev-runtime/pids ]]; then
        COSBENCH_PID_DIR="$runtime/dev-runtime/pids" "$repo/bin/cosbench-stop" all --timeout 10 \
            >"$report/dev-cleanup.log" 2>&1 || status=1
    fi
    if [[ -n ${unrelated_pid:-} ]]; then
        kill "$unrelated_pid" 2>/dev/null || true
        wait "$unrelated_pid" 2>/dev/null || true
    fi
    rm -rf -- "$runtime"
    printf 'replay_status=%s\nprocesses_remaining=none\n' "$status" > "$report/cleanup-status.txt"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

expect_status() {
    local expected=$1 actual=0
    shift
    "$@" || actual=$?
    [[ $actual == "$expected" ]] || {
        printf 'expected exit %s, got %s: %s\n' "$expected" "$actual" "$*" >&2
        return 1
    }
}

for command_name in cosbench-controller cosbench-driver cosbench-stop cosbench-config-migrate; do
    "$repo/bin/$command_name" --help > "$report/$command_name-help.txt"
done
expect_status 2 "$repo/bin/cosbench-controller" --config
expect_status 2 "$repo/bin/cosbench-controller" --cosbench.config
expect_status 2 "$repo/bin/cosbench-stop" unknown-service
expect_status 2 "$repo/bin/cosbench-config-migrate"
expect_status 1 "$repo/bin/cosbench-driver" --config "$runtime/missing-driver.yml" \
    --pid-file "$pid_dir/start-failure.pid"
[[ ! -e $pid_dir/start-failure.pid ]]

legacy_source="$runtime/controller.conf"
legacy_yaml="$runtime/controller-migrated.yml"
legacy_report="$runtime/controller-migration.json"
cp "$repo/.scratch/scheme3-migration/baseline/fixtures/controller.conf" "$legacy_source"
source_checksum=$(sha256sum "$legacy_source" | awk '{print $1}')
"$repo/bin/cosbench-config-migrate" --source "$legacy_source" --destination "$legacy_yaml" \
    --report "$legacy_report" > "$report/migration-success.txt"
grep -q '"success": true' "$legacy_report"
[[ $(sha256sum "$legacy_source" | awk '{print $1}') == "$source_checksum" ]]
expect_status 1 "$repo/bin/cosbench-config-migrate" --source "$legacy_source" \
    --destination "$legacy_yaml" --report "$runtime/second-report.json"

cat > "$runtime/driver-template.yml" <<YAML
schemaVersion: 1
role: driver
name: driver1
listen:
  host: 127.0.0.1
  port: 19511
  contextPath: /driver
paths:
  workspace: workspace
  archive: archive
  mission: log/mission
  logs: log/system.log
auth:
  token:
    env: COSBENCH_SERVICE_TOKEN
  tls:
    enabled: true
    keyStore: file:${COSBENCH_HTTP_TEST_KEYSTORE_FILE:-/run/secrets/http_test_keystore}
provider:
  name: mock
logging:
  level: INFO
  file: log/system.log
shutdown:
  gracePeriod: 10s
YAML
"$JAVA_HOME/bin/java" -jar "$operator_jar" expand-drivers --config "$runtime/driver-template.yml" \
    --output-dir "$runtime/conf" --count 2 > "$report/driver-configs.txt"
"$JAVA_HOME/bin/java" -jar "$operator_jar" expand-drivers --config "$runtime/driver-template.yml" \
    --output-dir "$runtime/conf" --count 2 > "$report/driver-configs-repeat.txt"
cat > "$runtime/controller-template.yml" <<YAML
schemaVersion: 1
role: controller
name: command-test-controller
listen:
  host: 127.0.0.1
  port: 19510
  contextPath: /controller
paths:
  workspace: workspace
  archive: archive
  logs: log/controller.log
auth:
  token:
    env: COSBENCH_SERVICE_TOKEN
  tls:
    enabled: true
    trustStore: file:${COSBENCH_HTTP_TEST_TRUSTSTORE_FILE:-/run/secrets/http_test_truststore}
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
    maxAttempts: 2
    backoff: 100ms
logging:
  level: INFO
  file: log/controller.log
shutdown:
  gracePeriod: 10s
YAML
"$JAVA_HOME/bin/java" -jar "$operator_jar" bind-controller --config "$runtime/controller-template.yml" \
    --drivers-dir "$runtime/conf" --count 2 --output "$runtime/conf/controller.yml" \
    > "$report/controller-config.txt"
"$JAVA_HOME/bin/java" -jar "$operator_jar" bind-controller --config "$runtime/controller-template.yml" \
    --drivers-dir "$runtime/conf" --count 2 --output "$runtime/conf/controller.yml" \
    > "$report/controller-config-repeat.txt"
grep -q 'https://127.0.0.1:19511/driver' "$runtime/conf/controller.yml"
grep -q 'https://127.0.0.1:19512/driver' "$runtime/conf/controller.yml"
grep -q 'env: COSBENCH_SERVICE_TOKEN' "$runtime/conf/controller.yml"
! grep -F -q -- "$COSBENCH_SERVICE_TOKEN" "$runtime/conf/controller.yml"
for index in 1 2; do
    "$JAVA_HOME/bin/java" -jar "$operator_jar" inspect --config "$runtime/conf/driver-$index.yml" \
        --role driver --check-port > "$report/driver-$index-inspect.txt"
done

start_driver() {
    local index=$1 port
    port=$((19510 + index))
    if [[ $index == 2 ]]; then
        SERVER_SSL_KEY_STORE_PASSWORD="$driver_store_password" COSBENCH_DRIVER_JAR="$driver_jar" "$repo/bin/cosbench-driver" \
            --cosbench.config="$runtime/conf/driver-$index.yml" --pid-file "$pid_dir/driver-$index.pid" \
            > "$report/driver-$index.log" 2>&1 &
    else
        SERVER_SSL_KEY_STORE_PASSWORD="$driver_store_password" COSBENCH_DRIVER_JAR="$driver_jar" "$repo/bin/cosbench-driver" \
            --config "$runtime/conf/driver-$index.yml" --pid-file "$pid_dir/driver-$index.pid" \
            > "$report/driver-$index.log" 2>&1 &
    fi
    local pid=$!
    for ((attempt = 0; attempt < 200; attempt++)); do
        if curl --silent --fail --show-error --max-time 1 --cacert "$COSBENCH_HTTP_TEST_CERT_FILE" \
            "https://127.0.0.1:$port/driver/health" > "$report/driver-$index-health.json"; then
            grep -q '"accepting":true' "$report/driver-$index-health.json"
            [[ -s "$pid_dir/driver-$index.pid" ]]
            return 0
        fi
        kill -0 "$pid" 2>/dev/null || { cat "$report/driver-$index.log" >&2; return 1; }
        sleep 0.1
    done
    cat "$report/driver-$index.log" >&2
    return 1
}

printf '99999999\n' > "$pid_dir/driver-1.pid"
start_driver 1
driver_one_pid=$(<"$pid_dir/driver-1.pid")
expect_status 1 "$repo/bin/cosbench-driver" --config "$runtime/conf/driver-1.yml" \
    --pid-file "$pid_dir/driver-1.pid"
[[ $(<"$pid_dir/driver-1.pid") == "$driver_one_pid" ]]
kill -0 "$driver_one_pid"
expect_status 1 "$repo/bin/cosbench-driver" --config "$runtime/conf/driver-1.yml" \
    --pid-file "$pid_dir/driver-1-second.pid"
[[ ! -e $pid_dir/driver-1-second.pid ]]
ln -s "$pid_dir/driver-1.pid" "$pid_dir/driver-3.pid"
expect_status 1 "$repo/bin/cosbench-stop" driver --instance 3 --pid-dir "$pid_dir"
[[ -L $pid_dir/driver-3.pid ]]
kill -0 "$driver_one_pid"
rm -- "$pid_dir/driver-3.pid"
printf '99999999\n' > "$pid_dir/stale-target.pid"
ln -s "$pid_dir/stale-target.pid" "$pid_dir/driver-99.pid"
expect_status 1 "$repo/bin/cosbench-driver" --config "$runtime/conf/driver-1.yml" \
    --pid-file "$pid_dir/driver-99.pid"
[[ -L $pid_dir/driver-99.pid && $(<"$pid_dir/stale-target.pid") == 99999999 ]]
rm -- "$pid_dir/driver-99.pid" "$pid_dir/stale-target.pid"
start_driver 2
[[ -d "$runtime/log/mission/driver1" && -d "$runtime/log/mission/driver2" ]]
[[ -f "$runtime/log/driver1/system.log" && -f "$runtime/log/driver2/system.log" ]]
COSBENCH_CONTROLLER_JAR="$controller_jar" "$repo/bin/cosbench-controller" \
    --config "$runtime/conf/controller.yml" --pid-file "$pid_dir/controller.pid" \
    > "$report/controller.log" 2>&1 &
controller_pid=$!
for ((attempt = 0; attempt < 200; attempt++)); do
    if curl --silent --fail --show-error --max-time 1 http://127.0.0.1:19510/controller/health \
        > "$report/controller-health.json"; then
        break
    fi
    kill -0 "$controller_pid" 2>/dev/null || { cat "$report/controller.log" >&2; exit 1; }
    sleep 0.1
done
grep -q '"accepting":true' "$report/controller-health.json"
[[ -s "$pid_dir/controller.pid" ]]
for driver_name in driver1 driver2; do
    for ((attempt = 0; attempt < 100; attempt++)); do
        grep -q "The driver $driver_name .* is reachable" "$runtime/log/controller.log" && break
        sleep 0.1
    done
    grep -q "The driver $driver_name .* is reachable" "$runtime/log/controller.log"
done
expect_status 1 "$repo/bin/cosbench-controller" --config "$runtime/conf/controller.yml" \
    --pid-file "$pid_dir/controller-second.pid"

controller_url=http://127.0.0.1:19510/controller
controller_cookies="$runtime/controller.cookies"
curl --silent --show-error --fail --max-time 2 --cookie-jar "$controller_cookies" \
    "$controller_url/logon.html" > "$report/controller-logon.html"
login_status=$(curl --silent --show-error --max-time 2 --cookie "$controller_cookies" \
    --cookie-jar "$controller_cookies" --output "$report/controller-login.response" \
    --write-out '%{http_code}' --data 'j_username=anonymous&j_password=cosbench' \
    "$controller_url/j_security_check")
[[ $login_status == 302 ]]
submit_status=$(curl --silent --show-error --max-time 5 --cookie "$controller_cookies" \
    --cookie-jar "$controller_cookies" --output "$report/controller-busy-submit.html" \
    --write-out '%{http_code}' -F "config=@$repo/shadow/harness/fixtures/controller-web-actions.xml;type=application/xml" \
    "$controller_url/submit-workload.do")
[[ $submit_status == 200 ]]
grep -q 'your workload has been accepted' "$report/controller-busy-submit.html"
sleep 0.2
COSBENCH_PID_DIR="$pid_dir" "$repo/bin/cosbench-stop" controller --timeout 10 \
    > "$report/stop-controller-busy.txt"
[[ ! -e $pid_dir/controller.pid ]]
if curl --silent --fail --max-time 1 http://127.0.0.1:19510/controller/health >/dev/null 2>&1; then
    printf 'controller listener remained reachable after busy stop\n' >&2
    exit 1
fi
grep -q 'Controller stopped: executor terminated' "$runtime/log/controller.log"

COSBENCH_CONTROLLER_JAR="$controller_jar" "$repo/bin/cosbench-controller" \
    --config "$runtime/conf/controller.yml" --pid-file "$pid_dir/controller.pid" \
    > "$report/controller-idle.log" 2>&1 &
controller_pid=$!
for ((attempt = 0; attempt < 200; attempt++)); do
    if curl --silent --fail --max-time 1 http://127.0.0.1:19510/controller/health \
        > "$report/controller-idle-health.json"; then
        break
    fi
    kill -0 "$controller_pid" 2>/dev/null || { cat "$report/controller-idle.log" >&2; exit 1; }
    sleep 0.1
done
grep -q '"accepting":true' "$report/controller-idle-health.json"
[[ -s "$pid_dir/controller.pid" ]]
COSBENCH_PID_DIR="$pid_dir" "$repo/bin/cosbench-stop" controller --timeout 10 \
    > "$report/stop-controller-idle.txt"
[[ ! -e $pid_dir/controller.pid ]]
if curl --silent --fail --max-time 1 http://127.0.0.1:19510/controller/health >/dev/null 2>&1; then
    printf 'controller listener remained reachable after idle stop\n' >&2
    exit 1
fi

COSBENCH_PID_DIR="$pid_dir" "$repo/bin/cosbench-stop" driver --instance 1 \
    > "$report/stop-driver-1.txt"
expect_status 0 "$repo/bin/cosbench-stop" driver --instance 1
printf '99999999\n' > "$pid_dir/driver-99.pid"
COSBENCH_PID_DIR="$pid_dir" "$repo/bin/cosbench-stop" driver --instance 99 \
    > "$report/stop-stale-driver.txt"
[[ ! -e $pid_dir/driver-99.pid ]]
if curl --silent --fail --max-time 1 --cacert "$COSBENCH_HTTP_TEST_CERT_FILE" \
    https://127.0.0.1:19511/driver/health >/dev/null 2>&1; then
    printf 'stopped Driver 1 still accepts requests\n' >&2
    exit 1
fi
curl --silent --show-error --fail --max-time 1 --cacert "$COSBENCH_HTTP_TEST_CERT_FILE" \
    https://127.0.0.1:19512/driver/health > "$report/driver-2-after-driver-1-stop.json"
grep -q '"accepting":true' "$report/driver-2-after-driver-1-stop.json"
COSBENCH_PID_DIR="$pid_dir" "$repo/bin/cosbench-stop" driver > "$report/stop-drivers.txt"

mkdir -p "$pid_dir/unrelated"
unrelated_pid_file="$pid_dir/unrelated/controller.pid"
bash -c 'exec -a "$1" sleep 30' _ \
    "fake --role=controller-test --cosbench.pid-file=$unrelated_pid_file" &
unrelated_pid=$!
printf '%s\n' "$unrelated_pid" > "$unrelated_pid_file"
expect_status 1 "$repo/bin/cosbench-stop" controller --pid-file "$unrelated_pid_file"
kill -0 "$unrelated_pid"
kill "$unrelated_pid"
wait "$unrelated_pid" 2>/dev/null || true
unrelated_pid=

dev_runtime="$runtime/dev-runtime"
run_dev() {
    env -u COSBENCH_TLS_STORE_PASSWORD_FILE COSBENCH_DEV_RUNTIME="$dev_runtime" \
        /bin/bash "$repo/dev/cosbench-dev.sh" "$@"
}

run_dev --no-build --drivers 2 > "$report/dev-start.log" 2>&1
curl --silent --show-error --fail --max-time 2 http://127.0.0.1:19088/controller/health \
    > "$report/dev-controller-health.json"
for port in 18088 18089; do
    curl --silent --show-error --fail --max-time 2 --cacert "$dev_runtime/conf/dev-server.crt" \
        "https://127.0.0.1:$port/driver/health" > "$report/dev-driver-$port-health.json"
done
run_dev stop driver --no-build > "$report/dev-stop-driver.log" 2>&1
if curl --silent --fail --max-time 1 --cacert "$dev_runtime/conf/dev-server.crt" \
    https://127.0.0.1:18088/driver/health >/dev/null 2>&1; then
    printf 'development Driver remained reachable after stop driver\n' >&2
    exit 1
fi
curl --silent --show-error --fail --max-time 2 http://127.0.0.1:19088/controller/health \
    > "$report/dev-controller-after-driver-stop.json"
run_dev restart controller --no-build > "$report/dev-restart-controller.log" 2>&1
curl --silent --show-error --fail --max-time 2 http://127.0.0.1:19088/controller/health \
    > "$report/dev-controller-after-restart.json"
run_dev start driver --no-build > "$report/dev-start-driver.log" 2>&1
for port in 18088 18089; do
    curl --silent --show-error --fail --max-time 2 --cacert "$dev_runtime/conf/dev-server.crt" \
        "https://127.0.0.1:$port/driver/health" > "$report/dev-driver-$port-after-start-health.json"
done
run_dev stop all --no-build > "$report/dev-stop-all.log" 2>&1
[[ ! -e $dev_runtime/pids/controller.pid && ! -e $dev_runtime/pids/driver-1.pid \
        && ! -e $dev_runtime/pids/driver-2.pid ]]
printf 'status=passed\nmigration=non-destructive\nstart_failure=clean\nstale_pid=removed\nduplicate_start=refused\nport_conflict=refused\nmultiple_drivers=connected\ninstance_paths=isolated\nspecified_instance_stop=isolated\nbusy_and_idle_stop=passed\ndev_start_stop_restart=passed\nrepeated_stop=passed\nunrelated_pid=refused\n' \
    > "$report/summary.txt"
