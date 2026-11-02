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
logging:
  level: INFO
  file: log/system.log
shutdown:
  gracePeriod: 5s
YAML

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

start_driver() {
    local name=$1
    (
        cd "$runtime_dir/launch"
        export COSBENCH_CONFIG="$runtime_dir/missing-environment.yml"
        exec "${java_command[@]}" --cosbench.config="$runtime_dir/conf/driver.yml" --server.port=19489
    ) > "$report_dir/$name.log" 2>&1 &
    driver_pid=$!
    for ((attempt = 0; attempt < 200; attempt++)); do
        if curl --silent --fail --max-time 1 http://127.0.0.1:19488/driver/health \
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
    grep -q 'detected supported storage types: gdas, sio, siov1' "$report_dir/$name.log"
    printf '%s_term_exit=%s\n%s_stop_seconds=%s\n' "$name" "$status" "$name" "$((SECONDS - stopping))" \
        >> "$report_dir/results.txt"
    if curl --silent --fail --max-time 1 http://127.0.0.1:19488/driver/health >/dev/null; then
        printf '%s\n' 'Driver listener survived TERM' >&2
        return 1
    fi
}

: > "$report_dir/results.txt"
start_driver startup
stop_driver startup
start_driver restart
stop_driver restart
test -d "$runtime_dir/missions"
test -d "$runtime_dir/log"
test ! -e "$runtime_dir/launch/missions"
test ! -e "$runtime_dir/launch/log"
printf 'status=passed\nhealth=real-driver\nconfig-precedence=passed\nrestart-and-port-release=passed\nmock-runtime-boundary=clean\n' \
    > "$report_dir/summary.txt"
