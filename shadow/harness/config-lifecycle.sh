#!/usr/bin/env bash
set -Eeuo pipefail

repo=/workspace
report=/reports
work=$(mktemp -d /tmp/cosbench-config-lifecycle.XXXXXX)
controller_root="$work/controller"
driver_root="$work/driver"
pid_dir="$work/pids"
mkdir -p "$controller_root/conf" "$controller_root/archive/legacy" "$driver_root/conf" "$driver_root/archive" "$pid_dir"
export COSBENCH_SERVICE_TOKEN=${COSBENCH_HTTP_SERVICE_TOKEN:-config-lifecycle-test-token}

cleanup() {
    set +e
    COSBENCH_PID_DIR="$pid_dir" "$repo/bin/cosbench-stop" all >"$report/stop-cleanup.log" 2>&1
    for pid_file in "$pid_dir"/*.pid; do
        [[ -f "$pid_file" ]] || continue
        pid=$(tr -d '[:space:]' < "$pid_file")
        [[ "$pid" =~ ^[0-9]+$ ]] && kill -TERM "$pid" 2>/dev/null || true
    done
    find "$work" -mindepth 1 -delete 2>/dev/null || true
    rmdir "$work" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

runtime_jar="$repo/shadow/cosbench-runtime/target/cosbench-runtime-shadow-0.1.0-SNAPSHOT.jar"
[[ -f "$runtime_jar" ]] || { echo "runtime jar is missing: $runtime_jar" >&2; exit 1; }

cp "$repo/.scratch/scheme3-migration/baseline/fixtures/controller.conf" "$controller_root/legacy-controller.conf"
cp "$repo/.scratch/scheme3-migration/baseline/fixtures/driver.conf" "$driver_root/legacy-driver.conf"
cp "$repo/shadow/fixtures/archive/legacy/workload-001.xml" "$controller_root/archive/legacy/workload-001.xml"
cp "$repo/shadow/fixtures/archive/legacy/workload-001.log" "$controller_root/archive/legacy/workload-001.log"
source_checksum=$(sha256sum "$controller_root/legacy-controller.conf" | awk '{print $1}')
archive_checksum=$(sha256sum "$controller_root/archive/legacy/workload-001.xml" | awk '{print $1}')

"$repo/bin/cosbench-config-migrate" \
    "$controller_root/legacy-controller.conf" "$controller_root/conf/controller.yml" "$report/controller-migration.json" \
    >"$report/controller-migration.log" 2>&1
"$repo/bin/cosbench-config-migrate" \
    "$driver_root/legacy-driver.conf" "$driver_root/conf/driver.yml" "$report/driver-migration.json" \
    >"$report/driver-migration.log" 2>&1

[[ "$(sha256sum "$controller_root/legacy-controller.conf" | awk '{print $1}')" == "$source_checksum" ]]
[[ "$(sha256sum "$controller_root/archive/legacy/workload-001.xml" | awk '{print $1}')" == "$archive_checksum" ]]
grep -q '"success": true' "$report/controller-migration.json"
grep -q '"success": true' "$report/driver-migration.json"

start_service() {
    local role=$1
    local root=$2
    local port=$3
    local context=$4
    local log="$report/$role.log"
    COSBENCH_RUNTIME_JAR="$runtime_jar" COSBENCH_CONFIG="$root/conf/$role.yml" \
        COSBENCH_PID_FILE="$pid_dir/$role.pid" \
        "$repo/bin/cosbench-$role" >"$log" 2>&1 &
    local launch_pid=$!
    local deadline=$((SECONDS + 60))
    while ((SECONDS < deadline)); do
        if curl -fsS "http://127.0.0.1:$port$context/health" >"$report/$role-health.json" 2>/dev/null; then
            printf 'role=%s pid=%s\n' "$role" "$(cat "$pid_dir/$role.pid")" >> "$report/processes.txt"
            return 0
        fi
        if ! kill -0 "$launch_pid" 2>/dev/null; then
            cat "$log" >&2
            return 1
        fi
        sleep 0.2
    done
    cat "$log" >&2
    return 1
}

start_service controller "$controller_root" 19088 /controller
start_service driver "$driver_root" 18088 /driver

curl -fsS "http://127.0.0.1:19088/controller/archive" > "$report/archive-before.json"
grep -q 'workload-001.xml' "$report/archive-before.json"
curl -fsS -X POST 'http://127.0.0.1:19088/controller/tasks?durationMs=500' > "$report/task-accepted.json"
grep -q '"accepted":true' "$report/task-accepted.json"

COSBENCH_PID_DIR="$pid_dir" "$repo/bin/cosbench-stop" controller
if curl -fsS 'http://127.0.0.1:19088/controller/health' >"$report/controller-after-stop.json" 2>/dev/null; then
    echo 'controller port remained reachable after stop' >&2
    exit 1
fi
[[ ! -f "$pid_dir/controller.pid" ]]

COSBENCH_RUNTIME_JAR="$runtime_jar" COSBENCH_CONFIG="$controller_root/conf/controller.yml" \
    COSBENCH_PID_FILE="$pid_dir/controller.pid" \
    "$repo/bin/cosbench-controller" >"$report/controller-restart.log" 2>&1 &
controller_restart_pid=$!
deadline=$((SECONDS + 60))
while ((SECONDS < deadline)); do
    if curl -fsS 'http://127.0.0.1:19088/controller/health' >"$report/controller-restart-health.json" 2>/dev/null; then
        break
    fi
    kill -0 "$controller_restart_pid" 2>/dev/null || { cat "$report/controller-restart.log" >&2; exit 1; }
    sleep 0.2
done
grep -q 'workload-001.xml' <(curl -fsS 'http://127.0.0.1:19088/controller/archive')
[[ "$(sha256sum "$controller_root/archive/legacy/workload-001.xml" | awk '{print $1}')" == "$archive_checksum" ]]

COSBENCH_PID_DIR="$pid_dir" "$repo/bin/cosbench-stop" all
if curl -fsS 'http://127.0.0.1:18088/driver/health' >/dev/null 2>&1; then
    echo 'driver port remained reachable after stop' >&2
    exit 1
fi
[[ ! -f "$pid_dir/controller.pid" && ! -f "$pid_dir/driver.pid" ]]

printf '%s\n' \
    'status=passed' \
    'schema=1' \
    'controller_migration=passed' \
    'driver_migration=passed' \
    'archive_read_before_stop=passed' \
    'archive_read_after_restart=passed' \
    'controller_driver_health=passed' \
    'term_port_release=passed' \
    'term_pid_cleanup=passed' \
    'source_and_archive_checksums=unchanged' \
    > "$report/config-lifecycle-summary.txt"
