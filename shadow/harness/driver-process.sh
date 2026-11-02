#!/usr/bin/env bash
set -Eeuo pipefail

report_dir=/reports/driver-process
artifact=/workspace/shadow/cosbench-driver-app/target/cosbench-driver-app-0.1.0-SNAPSHOT.jar
runtime_dir=$(mktemp -d /tmp/cosbench-driver-process.XXXXXX)
driver_pid=
mkdir -p "$report_dir" "$runtime_dir/conf" "$runtime_dir/launch" "$runtime_dir/missions" "$runtime_dir/log"

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    if [[ -n $driver_pid ]] && kill -0 "$driver_pid" 2>/dev/null; then
        kill -TERM "$driver_pid" 2>/dev/null || true
        for ((attempt = 0; attempt < 80; attempt++)); do
            kill -0 "$driver_pid" 2>/dev/null || break
            sleep 0.1
        done
        if kill -0 "$driver_pid" 2>/dev/null; then
            kill -KILL "$driver_pid" 2>/dev/null || true
            status=1
        fi
        wait "$driver_pid" 2>/dev/null || true
    fi
    cp -R "$runtime_dir/conf" "$report_dir/fixtures"
    [[ ! -d $runtime_dir/log ]] || cp -R "$runtime_dir/log" "$report_dir/logs"
    rm -rf -- "$runtime_dir"
    printf 'replay_status=%s\nprocesses_remaining=none\n' "$status" > "$report_dir/cleanup.txt"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

test -s "$artifact"
sha256sum "$artifact" > "$report_dir/artifact.sha256"
jar tf "$artifact" > "$report_dir/jar-contents.txt"
grep -q 'BOOT-INF/lib/cosbench-driver-shadow-' "$report_dir/jar-contents.txt"
grep -q 'BOOT-INF/lib/cosbench-sineio-' "$report_dir/jar-contents.txt"
grep -q 'BOOT-INF/lib/cosbench-s3-' "$report_dir/jar-contents.txt"
if grep -Eq '(^|/)com/intel/cosbench/api/mock/|META-INF/services/com.intel.cosbench.api.(auth.AuthAPIFactory|storage.StorageAPIFactory)' \
    "$report_dir/jar-contents.txt"; then
    printf '%s\n' 'Mock classes or service descriptors leaked into the Driver executable' >&2
    exit 1
fi

cat > "$runtime_dir/conf/driver.yml" <<'YAML'
schemaVersion: 1
role: driver
name: Driver executable replay
listen:
  host: 127.0.0.1
  port: 19488
  contextPath: /driver
paths:
  workspace: workspace
  archive: archive
  mission: missions
  logs: log/system.log
auth:
  token:
    env: COSBENCH_HTTP_SERVICE_TOKEN
  tls:
    enabled: true
    keyStore: file:/run/secrets/http_test_keystore
logging:
  level: INFO
  file: log/system.log
shutdown:
  gracePeriod: 5s
YAML

export SERVER_SSL_KEY_STORE_PASSWORD="$(<"$COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE")"

java_command=(java --add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED -jar "$artifact")
expect_failure() {
    local name=$1 diagnostic=$2
    shift 2
    local status=0
    (cd "$runtime_dir/launch" && env -u COSBENCH_CONFIG timeout 15s "${java_command[@]}" "$@") \
        > "$report_dir/$name.log" 2>&1 || status=$?
    [[ $status == 1 ]]
    grep -q "$diagnostic" "$report_dir/$name.log"
}

expect_failure missing-default DEFAULT_FILE_MISSING
expect_failure missing-explicit EXPLICIT_FILE_MISSING --cosbench.config="$runtime_dir/missing.yml"
sed '/  tls:/,/    keyStore:/d' "$runtime_dir/conf/driver.yml" > "$runtime_dir/conf/insecure-driver.yml"
expect_failure tls-disabled 'Driver HTTP requires TLS' --cosbench.config="$runtime_dir/conf/insecure-driver.yml"

start_driver() {
    local name=$1
    (
        cd "$runtime_dir/launch"
        export COSBENCH_CONFIG="$runtime_dir/missing-environment.yml"
        exec "${java_command[@]}" --cosbench.config="$runtime_dir/conf/driver.yml" --server.port=19489
    ) > "$report_dir/$name.log" 2>&1 &
    driver_pid=$!
    for ((attempt = 0; attempt < 200; attempt++)); do
        if curl --silent --fail --max-time 1 --cacert "$COSBENCH_HTTP_TEST_CERT_FILE" https://127.0.0.1:19488/driver/health \
            > "$report_dir/$name-health.json"; then
            grep -q '"accepting":true' "$report_dir/$name-health.json"
            grep -q '"role":"driver"' "$report_dir/$name-health.json"
            return
        fi
        kill -0 "$driver_pid" 2>/dev/null || { cat "$report_dir/$name.log"; return 1; }
        sleep 0.1
    done
    cat "$report_dir/$name.log"
    return 1
}

stop_driver() {
    local name=$1 status=0
    local stopping=$SECONDS
    kill -TERM "$driver_pid"
    for ((attempt = 0; attempt < 70; attempt++)); do
        kill -0 "$driver_pid" 2>/dev/null || break
        sleep 0.1
    done
    if kill -0 "$driver_pid" 2>/dev/null; then
        printf '%s\n' 'Driver did not stop within the configured grace period plus process overhead' >&2
        return 1
    fi
    wait "$driver_pid" || status=$?
    driver_pid=
    [[ $status == 143 ]]
    grep -q 'Driver stopped: mission executor terminated' "$report_dir/$name.log"
    grep -q 'detected supported storage types: gdas, s3, sio, siov1' "$report_dir/$name.log"
    printf '%s_term_exit=%s\n%s_stop_seconds=%s\n' "$name" "$status" "$name" "$((SECONDS - stopping))" \
        >> "$report_dir/results.txt"
    if curl --silent --fail --max-time 1 --cacert "$COSBENCH_HTTP_TEST_CERT_FILE" https://127.0.0.1:19488/driver/health >/dev/null; then
        printf '%s\n' 'Driver listener survived TERM' >&2
        return 1
    fi
}

: > "$report_dir/results.txt"
start_driver startup

endpoint=https://127.0.0.1:19488/driver/i
identity=driver-process-restart-replay
ping_status=$(curl --silent --show-error --max-time 5 --cacert "$COSBENCH_HTTP_TEST_CERT_FILE" --write-out '%{http_code}' \
    -D "$report_dir/startup-ping.headers" -o "$report_dir/startup-ping.json" \
    -H "Authorization: Bearer $COSBENCH_HTTP_SERVICE_TOKEN" \
    -H 'Content-Type: application/octet-stream' \
    --data-binary '1782057600000' "$endpoint/ping.command")
[[ $ping_status == 200 ]]
original_instance=$(awk 'tolower($1) == "x-cosbench-driver-instance:" {gsub("\r", "", $2); print $2}' \
    "$report_dir/startup-ping.headers")
[[ -n $original_instance ]]

missing_instance_status=$(curl --silent --show-error --max-time 5 --cacert "$COSBENCH_HTTP_TEST_CERT_FILE" \
    --write-out '%{http_code}' -o "$report_dir/missing-instance.json" \
    -H "Authorization: Bearer $COSBENCH_HTTP_SERVICE_TOKEN" \
    -H "Idempotency-Key: $identity" -H "X-COSBench-Request-Id: $identity" \
    --data-binary '<mission/>' "$endpoint/submit.command")
[[ $missing_instance_status == 400 ]]
grep -q 'Driver instance is required' "$report_dir/missing-instance.json"

for attempt in first replay; do
    submit_status=$(curl --silent --show-error --max-time 5 --cacert "$COSBENCH_HTTP_TEST_CERT_FILE" --write-out '%{http_code}' \
        -o "$report_dir/startup-$attempt.json" \
        -H "Authorization: Bearer $COSBENCH_HTTP_SERVICE_TOKEN" \
        -H "Idempotency-Key: $identity" -H "X-COSBench-Request-Id: $identity" \
        -H "X-COSBench-Driver-Instance: $original_instance" \
        --data-binary '<mission/>' "$endpoint/submit.command")
    [[ $submit_status == 400 ]]
done
cmp "$report_dir/startup-first.json" "$report_dir/startup-replay.json"
stop_driver startup
start_driver restart

ping_status=$(curl --silent --show-error --max-time 5 --cacert "$COSBENCH_HTTP_TEST_CERT_FILE" --write-out '%{http_code}' \
    -D "$report_dir/restart-ping.headers" -o "$report_dir/restart-ping.json" \
    -H "Authorization: Bearer $COSBENCH_HTTP_SERVICE_TOKEN" \
    -H 'Content-Type: application/octet-stream' \
    --data-binary '1782057600000' "$endpoint/ping.command")
[[ $ping_status == 200 ]]
new_instance=$(awk 'tolower($1) == "x-cosbench-driver-instance:" {gsub("\r", "", $2); print $2}' \
    "$report_dir/restart-ping.headers")
[[ -n $new_instance && $new_instance != "$original_instance" ]]
replay_status=$(curl --silent --show-error --max-time 5 --cacert "$COSBENCH_HTTP_TEST_CERT_FILE" --write-out '%{http_code}' \
    -o "$report_dir/restart-stale-replay.json" \
    -H "Authorization: Bearer $COSBENCH_HTTP_SERVICE_TOKEN" \
    -H "Idempotency-Key: $identity" -H "X-COSBench-Request-Id: $identity" \
    -H "X-COSBench-Driver-Instance: $original_instance" \
    --data-binary '<mission/>' "$endpoint/submit.command")
[[ $replay_status == 409 ]]
grep -q 'Driver instance changed; write result may be unknown' "$report_dir/restart-stale-replay.json"
stop_driver restart
if grep -F -q -- "$COSBENCH_HTTP_SERVICE_TOKEN" "$report_dir/startup.log" "$report_dir/restart.log" \
    "$report_dir/startup-first.json" "$report_dir/restart-stale-replay.json"; then
    printf '%s\n' 'Service token leaked into a Driver log or response' >&2
    exit 1
fi
test -d "$runtime_dir/missions"
test -d "$runtime_dir/log"
test ! -e "$runtime_dir/launch/missions"
test ! -e "$runtime_dir/launch/log"
printf 'status=passed\nhealth=real-driver\nconfig-precedence=passed\ntls-required=passed\nmissing-instance=rejected\nrestart-and-port-release=passed\nrestart-stale-write=unknown-result-409\nservice-token-log-and-response=redacted\nmock-runtime-boundary=clean\n' \
    > "$report_dir/summary.txt"
