#!/usr/bin/env bash
set -Eeuo pipefail

report_dir=/reports/sineio-driver-process
artifact=/workspace/shadow/cosbench-driver-app/target/cosbench-driver-app-0.1.0-SNAPSHOT.jar
test_classes=/workspace/shadow/cosbench-driver-app/target/test-classes
runtime_dir=$(mktemp -d /tmp/cosbench-sineio-process.XXXXXX)
driver_pid=
mkdir -p "$report_dir" "$runtime_dir/probe/com/intel/cosbench/app/driver" "$runtime_dir/conf"

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
    [[ ! -d $runtime_dir/log ]] || cp -R "$runtime_dir/log" "$report_dir/logs"
    rm -rf -- "$runtime_dir"
    printf 'replay_status=%s\nprocesses_remaining=none\n' "$status" > "$report_dir/cleanup.txt"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

test -s "$artifact"
for probe in SineioDriverProcessProbe SineioMissionFixture; do
    cp "$test_classes/com/intel/cosbench/app/driver/$probe.class" \
        "$runtime_dir/probe/com/intel/cosbench/app/driver/"
done
cat > "$runtime_dir/conf/driver.yml" <<'YAML'
schemaVersion: 1
role: driver
name: SineIO production process replay
listen:
  host: 127.0.0.1
  port: 19490
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
  gracePeriod: 3s
YAML

sha256sum "$artifact" > "$report_dir/artifact.sha256"
: > "$report_dir/results.txt"
for mode in sio siov1 gdas s3; do
    (
        cd "$runtime_dir"
        export COSBENCH_SINEIO_MODE="$mode"
        export COSBENCH_MISSION_BUCKET="cosbench-process-$mode"
        exec java --add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED \
            -Dloader.path="$runtime_dir/probe" \
            -Dloader.main=com.intel.cosbench.app.driver.SineioDriverProcessProbe \
            -cp "$artifact" org.springframework.boot.loader.launch.PropertiesLauncher \
            --cosbench.config="$runtime_dir/conf/driver.yml"
    ) > "$report_dir/$mode.log" 2>&1 &
    driver_pid=$!
    ready=0
    for ((attempt = 0; attempt < 250; attempt++)); do
        if grep -q "SINEIO_MISSION_READY mode=$mode " "$report_dir/$mode.log"; then
            ready=1
            break
        fi
        kill -0 "$driver_pid" 2>/dev/null || { cat "$report_dir/$mode.log"; exit 1; }
        sleep 0.1
    done
    [[ $ready == 1 ]] || { cat "$report_dir/$mode.log"; exit 1; }
    curl --silent --fail --max-time 2 http://127.0.0.1:19490/driver/health > "$report_dir/$mode-health.json"
    grep -q '"activeMissions":1' "$report_dir/$mode-health.json"
    grep -q 'detected supported storage types: gdas, s3, sio, siov1' "$report_dir/$mode.log"
    status=0
    started=$SECONDS
    kill -TERM "$driver_pid"
    for ((attempt = 0; attempt < 80; attempt++)); do
        kill -0 "$driver_pid" 2>/dev/null || break
        sleep 0.1
    done
    if kill -0 "$driver_pid" 2>/dev/null; then
        printf '%s did not stop within grace period plus process overhead\n' "$mode" >&2
        exit 1
    fi
    wait "$driver_pid" || status=$?
    driver_pid=
    [[ $status == 143 ]]
    grep -q 'Driver stopped: mission executor terminated' "$report_dir/$mode.log"
    if curl --silent --fail --max-time 1 http://127.0.0.1:19490/driver/health >/dev/null; then
        printf '%s listener survived TERM\n' "$mode" >&2
        exit 1
    fi
    printf '%s_term_exit=%s\n%s_stop_seconds=%s\n' "$mode" "$status" "$mode" "$((SECONDS - started))" \
        >> "$report_dir/results.txt"
done
printf 'status=passed\nproduction_registry=gdas,s3,sio,siov1\nmock=absent\nclasspath=shared\nactive_mission_term=sio,siov1,gdas,s3\nrestart_and_port_release=passed\n' \
    > "$report_dir/summary.txt"
