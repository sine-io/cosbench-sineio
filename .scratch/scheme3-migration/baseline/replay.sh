#!/usr/bin/env bash

set -euo pipefail
umask 077

BASELINE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$BASELINE_DIR/../../.." && pwd)"
VERSION=0.6.0.2
RELEASE_TAG=0.6.0.2-sineio
RUNS=${BASELINE_RUNS:-3}
OPS=${BASELINE_OPS:-10000}
CONTAINER_CPUS=${BASELINE_CONTAINER_CPUS:-1}
CONTAINER_MEMORY=${BASELINE_CONTAINER_MEMORY:-768m}
OUTPUT_DIR=${1:-}
EXPECTED_ARCHIVE_SHA256=1421f48a55673e42edd73c7b09a610b2b71ef94c70d46db3fb74dd3c34d22a06
EXPECTED_START_SCRIPT_SHA256=4f7c7a4f983cd13e774a795d494740b4cdd007880eee8e496fd60a20d9e77481

usage() {
    printf 'Usage: %s <empty-results-directory>\n' "$(basename "$0")" >&2
    printf 'Optional: BASELINE_RUNS, BASELINE_OPS\n' >&2
}

if [[ -z "$OUTPUT_DIR" ]]; then
    usage
    exit 2
fi
if [[ -n "${BASELINE_VERSION:-}" || -n "${BASELINE_TAG:-}" ]]; then
    printf 'This replay is pinned to release %s; BASELINE_VERSION and BASELINE_TAG are unsupported\n' \
        "$RELEASE_TAG" >&2
    exit 2
fi
if [[ ! "$VERSION" =~ ^[0-9A-Za-z][0-9A-Za-z.+_-]*$ ]]; then
    printf 'Invalid release version: %s\n' "$VERSION" >&2
    exit 2
fi
if [[ ! "$RUNS" =~ ^[1-9][0-9]*$ || ! "$OPS" =~ ^[1-9][0-9]*$ ]]; then
    printf 'BASELINE_RUNS and BASELINE_OPS must be positive integers\n' >&2
    exit 2
fi

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd -- "$OUTPUT_DIR" && pwd)"
if [[ -n "$(find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    printf 'Results directory must be empty: %s\n' "$OUTPUT_DIR" >&2
    exit 2
fi

for tool in curl jq sha256sum ss awk sed grep find seq nproc lscpu free sudo git cp mv mkdir mktemp date uname cat tee head tail sort tr; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        printf 'Required command not found: %s\n' "$tool" >&2
        exit 2
    fi
done
if ! sudo -n docker info >/dev/null 2>&1; then
    printf 'sudo docker must be available without an interactive password prompt\n' >&2
    exit 2
fi

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
IMAGE="cosbench-baseline:${VERSION}-${RUN_ID}"
DRIVER_CONTAINER="cosbench-baseline-driver-${RUN_ID}"
CONTROLLER_CONTAINER="cosbench-baseline-controller-${RUN_ID}"
BUILD_CONTEXT="$(mktemp -d "${TMPDIR:-/tmp}/cosbench-baseline.XXXXXX")"
DRIVER_STARTED=0
CONTROLLER_STARTED=0
COOKIE_JAR="$BUILD_CONTEXT/controller.cookies"

mkdir -p "$OUTPUT_DIR/http" "$OUTPUT_DIR/logs" "$OUTPUT_DIR/archive"

container_state() {
    local name=$1
    local output
    if ! output=$(sudo docker ps -a --format '{{.Names}}' --filter "name=^/${name}$"); then
        return 2
    fi
    if [[ "$output" == "$name" ]]; then
        return 0
    fi
    return 1
}

image_state() {
    local output
    if ! output=$(sudo docker image ls --format '{{.Repository}}:{{.Tag}}'); then
        return 2
    fi
    if grep -Fxq "$IMAGE" <<<"$output"; then
        return 0
    fi
    return 1
}

cleanup_container() {
    local role=$1
    local name=$2
    local stop_script=$3
    local state

    container_state "$name"
    state=$?
    case "$state" in
        0)
            sudo docker exec "$name" bash -lc "cd /cosbench && bash $stop_script" \
                >"$OUTPUT_DIR/logs/${role}-stop.log" 2>&1
            local stop_status=$?
            printf '%s_stop_status=%s\n' "$role" "$stop_status" >>"$OUTPUT_DIR/cleanup-status.txt"
            if [[ "$stop_status" -ne 0 ]]; then
                cleanup_status=1
            fi
            if ! sudo docker cp "$name:/cosbench/log/." "$OUTPUT_DIR/logs/$role/" \
                >/dev/null 2>&1; then
                printf '%s_log_copy=failed\n' "$role" >>"$OUTPUT_DIR/cleanup-status.txt"
                cleanup_status=1
            fi
            if [[ "$role" == controller ]] && ! sudo docker cp "$name:/cosbench/archive/." \
                "$OUTPUT_DIR/archive/" >/dev/null 2>&1; then
                printf 'controller_archive_copy=failed\n' >>"$OUTPUT_DIR/cleanup-status.txt"
                cleanup_status=1
            fi
            if ! sudo docker logs "$name" >"$OUTPUT_DIR/logs/${role}-container.log" 2>&1; then
                printf '%s_log_capture=failed\n' "$role" >>"$OUTPUT_DIR/cleanup-status.txt"
                cleanup_status=1
            fi
            if ! sudo docker rm -f "$name" >/dev/null 2>&1; then
                printf '%s_remove=failed\n' "$role" >>"$OUTPUT_DIR/cleanup-status.txt"
                cleanup_status=1
            fi
            ;;
        1)
            printf '%s_container=absent\n' "$role" >>"$OUTPUT_DIR/cleanup-status.txt"
            ;;
        *)
            printf '%s_container_query=failed\n' "$role" >>"$OUTPUT_DIR/cleanup-status.txt"
            cleanup_status=1
            ;;
    esac
}

cleanup() {
    local result=$?
    local cleanup_status=0
    set +e
    : >"$OUTPUT_DIR/cleanup-status.txt"

    if [[ "$CONTROLLER_STARTED" -eq 1 ]]; then
        cleanup_container controller "$CONTROLLER_CONTAINER" stop-controller.sh
    fi
    if [[ "$DRIVER_STARTED" -eq 1 ]]; then
        cleanup_container driver "$DRIVER_CONTAINER" stop-driver.sh
    fi
    image_state
    local image_status=$?
    case "$image_status" in
        0)
            if ! sudo docker rmi "$IMAGE" >/dev/null 2>&1; then
                printf 'image_remove=failed\n' >>"$OUTPUT_DIR/cleanup-status.txt"
                cleanup_status=1
            fi
            ;;
        1) printf 'image=absent\n' >>"$OUTPUT_DIR/cleanup-status.txt" ;;
        *) printf 'image_query=failed\n' >>"$OUTPUT_DIR/cleanup-status.txt"; cleanup_status=1 ;;
    esac
    for role_name in "controller:$CONTROLLER_CONTAINER" "driver:$DRIVER_CONTAINER"; do
        role=${role_name%%:*}
        name=${role_name#*:}
        container_state "$name"
        local container_status=$?
        case "$container_status" in
            0) printf '%s_verification=failed\n' "$role" >>"$OUTPUT_DIR/cleanup-status.txt"; cleanup_status=1 ;;
            1) : ;;
            *) printf '%s_verification_query=failed\n' "$role" >>"$OUTPUT_DIR/cleanup-status.txt"; cleanup_status=1 ;;
        esac
    done
    image_state
    image_status=$?
    case "$image_status" in
        0) printf 'image_verification=failed\n' >>"$OUTPUT_DIR/cleanup-status.txt"; cleanup_status=1 ;;
        1) : ;;
        *) printf 'image_verification_query=failed\n' >>"$OUTPUT_DIR/cleanup-status.txt"; cleanup_status=1 ;;
    esac
    if ! rm -rf -- "$BUILD_CONTEXT"; then
        printf 'build_context_remove=failed\n' >>"$OUTPUT_DIR/cleanup-status.txt"
        cleanup_status=1
    fi
    printf 'run_status=%s\ncleanup_status=%s\n' "$result" "$cleanup_status" >"$OUTPUT_DIR/cleanup.txt"
    cat "$OUTPUT_DIR/cleanup-status.txt" >>"$OUTPUT_DIR/cleanup.txt"
    if [[ "$result" -eq 0 && "$cleanup_status" -ne 0 ]]; then
        return "$cleanup_status"
    fi
    return "$result"
}
trap cleanup EXIT

mkdir -p "$OUTPUT_DIR/logs/controller" "$OUTPUT_DIR/logs/driver"
RELEASE_BASE="https://github.com/sine-io/cosbench-sineio/releases/download/${RELEASE_TAG}"
ARCHIVE="cosbench-${VERSION}-sineio.tar.gz"
curl -fLsS --retry 3 --max-time 300 -o "$OUTPUT_DIR/$ARCHIVE" "$RELEASE_BASE/$ARCHIVE"
curl -fLsS --retry 3 --max-time 60 -o "$OUTPUT_DIR/$ARCHIVE.sha256" "$RELEASE_BASE/$ARCHIVE.sha256"
(cd "$OUTPUT_DIR" && sha256sum -c "$ARCHIVE.sha256") | tee "$OUTPUT_DIR/checksum.log"
ARCHIVE_SHA256="$(sha256sum "$OUTPUT_DIR/$ARCHIVE" | awk '{print $1}')"
if [[ "$ARCHIVE_SHA256" != "$EXPECTED_ARCHIVE_SHA256" ]]; then
    printf 'Release archive checksum %s does not match pinned baseline %s\n' \
        "$ARCHIVE_SHA256" "$EXPECTED_ARCHIVE_SHA256" >&2
    exit 1
fi
START_SCRIPT_SHA256="$(sha256sum "$BASELINE_DIR/start-cosbench.sh" | awk '{print $1}')"
if [[ "$START_SCRIPT_SHA256" != "$EXPECTED_START_SCRIPT_SHA256" ]]; then
    printf 'Pinned start script checksum %s does not match expected %s\n' \
        "$START_SCRIPT_SHA256" "$EXPECTED_START_SCRIPT_SHA256" >&2
    exit 1
fi

cp -- "$BASELINE_DIR/Dockerfile" "$BUILD_CONTEXT/Dockerfile"
cp -- "$OUTPUT_DIR/$ARCHIVE" "$BUILD_CONTEXT/cosbench-sineio.tar.gz"
cp -- "$BASELINE_DIR/start-cosbench.sh" "$BUILD_CONTEXT/start-cosbench.sh"
sudo docker build --platform linux/amd64 --build-arg "VERSION=$VERSION" \
    --file "$BUILD_CONTEXT/Dockerfile" --tag "$IMAGE" "$BUILD_CONTEXT" \
    >"$OUTPUT_DIR/image-build.log" 2>&1

{
    printf 'release_tag=%s\nrelease_version=%s\n' "$RELEASE_TAG" "$VERSION"
    printf 'archive_sha256=%s\n' "$ARCHIVE_SHA256"
    printf 'expected_archive_sha256=%s\n' "$EXPECTED_ARCHIVE_SHA256"
    printf 'start_script_sha256=%s\n' "$START_SCRIPT_SHA256"
    printf 'container_cpus=%s\ncontainer_memory=%s\n' "$CONTAINER_CPUS" "$CONTAINER_MEMORY"
    printf 'baseline_image=%s\n' "$IMAGE"
    printf 'base_image='; sudo docker image inspect --format '{{.RepoDigests}}' eclipse-temurin:8-jre-jammy
    printf 'base_image_id='; sudo docker image inspect --format '{{.Id}}' eclipse-temurin:8-jre-jammy
    printf 'host_commit=%s\n' "$(git -C "$ROOT_DIR" rev-parse HEAD)"
    printf 'captured_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    uname -a
    cat /etc/os-release
    printf '\nhost_cpu_count=%s\n' "$(nproc)"
    lscpu
    free -b
    printf '\nhost_cgroup_cpu='; cat /sys/fs/cgroup/cpu.max 2>/dev/null || true; printf '\n'
    printf 'host_cgroup_memory='; cat /sys/fs/cgroup/memory.max 2>/dev/null || true; printf '\n'
    sudo docker image inspect --format 'baseline_image_bytes={{.Size}}' "$IMAGE"
} >"$OUTPUT_DIR/environment.txt" 2>&1

if ss -ltn '( sport = :18088 or sport = :19088 )' | tail -n +2 | grep -q .; then
    printf 'Ports 18088 or 19088 are already in use; choose an unused host before replaying\n' >&2
    exit 1
fi

if ! sudo docker run -dit --platform linux/amd64 --cpus "$CONTAINER_CPUS" \
    --memory "$CONTAINER_MEMORY" --pids-limit 512 \
    --label "cosbench.baseline.run=$RUN_ID" --name "$DRIVER_CONTAINER" --network host \
    -e CONTROLLER=false -e DRIVER=true -e DRIVER_PORT=18088 -e COSBENCH_PLUGINS=SIO,S3 \
    "$IMAGE" >"$OUTPUT_DIR/driver-container-id.txt"; then
    printf 'Driver container failed to start\n' >&2
    exit 1
fi
DRIVER_STARTED=1

ready=0
for attempt in $(seq 1 90); do
    if curl --max-time 2 -fsS -X POST --data '0' \
        http://127.0.0.1:18088/driver/i/ping.command -o "$OUTPUT_DIR/http/driver-ready.json"; then
        ready=1
        break
    fi
    sleep 1
done
if [[ "$ready" -ne 1 ]]; then
    printf 'Driver did not become ready within 90 seconds\n' >&2
    sudo docker logs "$DRIVER_CONTAINER" >"$OUTPUT_DIR/logs/driver-startup-failure.log" 2>&1
    exit 1
fi

sudo docker cp "$BASELINE_DIR/fixtures/trigger-noop.sh" \
    "$DRIVER_CONTAINER:/cosbench/scripts/baseline-trigger.sh"
cp -- "$BASELINE_DIR/fixtures/controller.conf" "$OUTPUT_DIR/controller-fixture.conf"
cp -- "$BASELINE_DIR/fixtures/driver.conf" "$OUTPUT_DIR/driver-fixture.conf"

if ! sudo docker run -dit --platform linux/amd64 --cpus "$CONTAINER_CPUS" \
    --memory "$CONTAINER_MEMORY" --pids-limit 512 \
    --label "cosbench.baseline.run=$RUN_ID" --name "$CONTROLLER_CONTAINER" --network host \
    -e CONTROLLER=true -e DRIVER=false -e DRIVERS=http://127.0.0.1:18088/driver \
    "$IMAGE" >"$OUTPUT_DIR/controller-container-id.txt"; then
    printf 'Controller container failed to start\n' >&2
    exit 1
fi
CONTROLLER_STARTED=1

ready=0
for attempt in $(seq 1 90); do
    if curl --max-time 2 -fsS http://127.0.0.1:19088/controller/index.html \
        -o "$OUTPUT_DIR/http/controller-ready.html"; then
        ready=1
        break
    fi
    sleep 1
done
if [[ "$ready" -ne 1 ]]; then
    printf 'Controller did not become ready within 90 seconds\n' >&2
    sudo docker logs "$CONTROLLER_CONTAINER" >"$OUTPUT_DIR/logs/controller-startup-failure.log" 2>&1
    exit 1
fi

redact_headers() {
    local file=$1
    local temporary="${file}.redacted"
    sed -E 's/^(Set-Cookie:|Cookie:)([[:space:]]*[^=]+=)[^;[:space:]]+/\1\2<redacted>/I' \
        "$file" >"$temporary"
    mv -- "$temporary" "$file"
}

assert_status() {
    local name=$1
    local expected=$2
    local actual
    actual=$(<"$OUTPUT_DIR/http/$name.status")
    if [[ "$actual" != "$expected" ]]; then
        printf '%s expected HTTP %s, got %s\n' "$name" "$expected" "$actual" >&2
        return 1
    fi
}

assert_content_type() {
    local name=$1
    local expected=$2
    if ! grep -Eiq "^Content-Type:[[:space:]]*${expected}" "$OUTPUT_DIR/http/$name.headers"; then
        printf '%s expected Content-Type matching %s\n' "$name" "$expected" >&2
        return 1
    fi
}

assert_content_disposition() {
    local name=$1
    if ! grep -Eiq '^Content-Disposition:[[:space:]]*attachment;' "$OUTPUT_DIR/http/$name.headers"; then
        printf '%s expected an attachment Content-Disposition\n' "$name" >&2
        return 1
    fi
}

assert_nonempty_body() {
    local name=$1
    if [[ ! -s "$OUTPUT_DIR/http/$name.body" ]]; then
        printf '%s returned an empty body\n' "$name" >&2
        return 1
    fi
}

assert_static_resource() {
    local name=$1
    local path=$2
    assert_status "$name" 200
    assert_nonempty_body "$name"
    case "$path" in
        *.css) assert_content_type "$name" 'text/css' ;;
        *.js) assert_content_type "$name" '.*javascript' ;;
        *.png) assert_content_type "$name" 'image/png' ;;
    esac
}

assert_page_submission() {
    local name=$1
    local id=$2
    local body="$OUTPUT_DIR/http/$name.body"
    grep -Eiq '<span class="success"><strong>Success</strong>:' "$body"
    grep -Eq "workload\.html\?id=${id}([\"&])" "$body"
    grep -Eq 'workload-state-(queuing|processing|running|finished)' "$body"
}

assert_prometheus_contract() {
    local name=$1
    local mode=${2:-full}
    local body="$OUTPUT_DIR/http/$name.body"
    assert_status "$name" 200
    assert_content_type "$name" 'text/plain;[[:space:]]*version=0\.0\.4'
    assert_nonempty_body "$name"
    if [[ "$mode" == state ]]; then
        grep -Eq '^cosbench_workload_state[[:space:]]+\{[^}]+\}[[:space:]][-+]?[0-9]+(\.[0-9]+)?([[:space:]]+[0-9]+)?$' "$body"
        return
    fi
    for metric in workload_state workload_ops_count workload_byte_count \
        workload_average_response_time workload_throughput workload_success_ratio; do
        if ! grep -Eq "^cosbench_${metric}[[:space:]]+\\{[^}]+\\}[[:space:]][-+]?[0-9]+(\\.[0-9]+)?([[:space:]]+[0-9]+)?$" "$body"; then
            printf '%s is missing a valid %s sample\n' "$name" "$metric" >&2
            return 1
        fi
    done
    local operation_sample
    operation_sample=$(grep -E '^cosbench_workload_ops_count[[:space:]]' "$body" | head -n 1)
    [[ "$operation_sample" == *'operation_name="'* ]] || return 1
    [[ "$operation_sample" == *'workload="'* ]] || return 1
    [[ "$operation_sample" == *'unit="operation"'* ]] || return 1
    [[ "$operation_sample" == *'instance="'* ]] || return 1
}

assert_success_response() {
    local name=$1
    assert_status "$name" 200
    jq -e '.code == 200 and .succ == true' "$OUTPUT_DIR/http/$name.body" \
        >"$OUTPUT_DIR/http/$name.contract"
}

cookie_code=$(curl --max-time 15 -sS -w '%{http_code}' \
    -D "$OUTPUT_DIR/http/controller-unauth-index.headers" \
    -c "$COOKIE_JAR" -o "$OUTPUT_DIR/http/controller-unauth-index.body" \
    http://127.0.0.1:19088/controller/index.html)
redact_headers "$OUTPUT_DIR/http/controller-unauth-index.headers"
printf '%s\n' "$cookie_code" >"$OUTPUT_DIR/http/controller-unauth-index.status"
login_code=$(curl --max-time 15 -sS -w '%{http_code}' \
    -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -D "$OUTPUT_DIR/http/controller-login.headers" \
    --data-urlencode 'j_username=anonymous' --data-urlencode 'j_password=cosbench' \
    -o "$OUTPUT_DIR/http/controller-login.body" \
    http://127.0.0.1:19088/controller/j_security_check)
printf 'j_username=anonymous&j_password=<redacted>\n' >"$OUTPUT_DIR/http/controller-login.request.body"
redact_headers "$OUTPUT_DIR/http/controller-login.headers"
printf '%s\n' "$login_code" >"$OUTPUT_DIR/http/controller-login.status"
assert_status controller-unauth-index 200
assert_status controller-login 302

capture_request() {
    local name=$1
    local method=$2
    local url=$3
    local body=${4:-}
    local code
    if [[ "$method" == GET ]]; then
        code=$(curl --max-time 30 -sS -w '%{http_code}' -D "$OUTPUT_DIR/http/$name.headers" \
            -o "$OUTPUT_DIR/http/$name.body" "$url")
    else
        printf '%s' "$body" >"$OUTPUT_DIR/http/$name.request.body"
        code=$(curl --max-time 30 -sS -w '%{http_code}' -X "$method" --data-binary "$body" \
            -D "$OUTPUT_DIR/http/$name.headers" -o "$OUTPUT_DIR/http/$name.body" "$url")
    fi
    redact_headers "$OUTPUT_DIR/http/$name.headers"
    printf '%s\n' "$code" >"$OUTPUT_DIR/http/$name.status"
}

capture_page() {
    local service=$1
    local path=$2
    local service_name=driver
    [[ "$service" == *:19088 ]] && service_name=controller
    local name=${path//\//_}
    name=${name//\?/_}
    name=${name//\&/_}
    name="${service_name}${name}"
    local code
    if [[ "$service" == *:19088 ]]; then
        code=$(curl --max-time 30 -sS -w '%{http_code}' -b "$COOKIE_JAR" \
            -D "$OUTPUT_DIR/http/$name.headers" \
            -o "$OUTPUT_DIR/http/$name.body" "$service$path")
    else
        code=$(curl --max-time 30 -sS -w '%{http_code}' \
            -D "$OUTPUT_DIR/http/$name.headers" \
            -o "$OUTPUT_DIR/http/$name.body" "$service$path")
    fi
    redact_headers "$OUTPUT_DIR/http/$name.headers"
    printf '%s\n' "$code" >"$OUTPUT_DIR/http/$name.status"
}

capture_upload() {
    local name=$1
    local url=$2
    local file=$3
    local code
    cp -- "$file" "$OUTPUT_DIR/http/$name.request.config.xml"
    code=$(curl --max-time 30 -sS -w '%{http_code}' -b "$COOKIE_JAR" -F "config=@$file" \
        -D "$OUTPUT_DIR/http/$name.headers" -o "$OUTPUT_DIR/http/$name.body" "$url")
    redact_headers "$OUTPUT_DIR/http/$name.headers"
    printf '%s\n' "$code" >"$OUTPUT_DIR/http/$name.status"
}

capture_form() {
    local name=$1
    local url=$2
    local body=$3
    local code
    printf '%s\n' "$body" >"$OUTPUT_DIR/http/$name.request.body"
    code=$(curl --max-time 30 -sS -w '%{http_code}' -b "$COOKIE_JAR" -X POST \
        --data "$body" -D "$OUTPUT_DIR/http/$name.headers" \
        -o "$OUTPUT_DIR/http/$name.body" "$url")
    redact_headers "$OUTPUT_DIR/http/$name.headers"
    printf '%s\n' "$code" >"$OUTPUT_DIR/http/$name.status"
}

capture_cancel() {
    local name=$1
    local url=$2
    local id=$3
    local code
    printf 'id=%s\n' "$id" >"$OUTPUT_DIR/http/$name.request.body"
    code=$(curl --max-time 30 -sS -w '%{http_code}' -X POST --data-urlencode "id=$id" \
        -D "$OUTPUT_DIR/http/$name.headers" -o "$OUTPUT_DIR/http/$name.body" "$url")
    redact_headers "$OUTPUT_DIR/http/$name.headers"
    printf '%s\n' "$code" >"$OUTPUT_DIR/http/$name.status"
}

wait_controller_workload() {
    local name=$1
    local id=$2
    local state=
    for attempt in $(seq 1 120); do
        capture_request "$name-workload" GET \
            "http://127.0.0.1:19088/controller/cli/workload.action?username=anonymous&password=cosbench&id=$id"
        assert_status "$name-workload" 200
        state=$(awk -F '\t' 'NF >= 3 {print $NF}' "$OUTPUT_DIR/http/$name-workload.body")
        case "$state" in
            FINISHED|CANCELLED)
                printf '%s\t%s\n' "$id" "$state" >"$OUTPUT_DIR/http/$name-workload.state"
                if [[ "${3:-FINISHED}" == "$state" ]]; then
                    return 0
                fi
                printf '%s expected terminal state %s, got %s\n' "$name" "${3:-FINISHED}" "$state" >&2
                return 1
                ;;
            FAILED|TERMINATED)
                printf '%s\t%s\n' "$id" "$state" >"$OUTPUT_DIR/http/$name-workload.state"
                printf '%s reached failure terminal state %s\n' "$name" "$state" >&2
                return 1
                ;;
        esac
        sleep 1
    done
    printf '%s\t%s\n' "$id" "TIMEOUT:$state" >"$OUTPUT_DIR/http/$name-workload.state"
    return 1
}

capture_controller_artifacts() {
    local name=$1
    local id=$2
    local workload_body="$OUTPUT_DIR/http/$name-workload-page.body"
    local stage_id=

    capture_page http://127.0.0.1:19088 "/controller/workload.html?id=$id"
    mv "$OUTPUT_DIR/http/controller_controller_workload.html_id=$id.body" "$workload_body"
    mv "$OUTPUT_DIR/http/controller_controller_workload.html_id=$id.headers" \
        "$OUTPUT_DIR/http/$name-workload-page.headers"
    mv "$OUTPUT_DIR/http/controller_controller_workload.html_id=$id.status" \
        "$OUTPUT_DIR/http/$name-workload-page.status"
    assert_status "$name-workload-page" 200
    grep -Eq "workload\.html\?id=${id}" "$workload_body"
    grep -Eq 'workload-state-finished' "$workload_body"

    stage_id=$(grep -oE "stage\.html\?wid=${id}(&amp;|&)sid=[A-Za-z0-9_.-]+" "$workload_body" \
        | head -n 1 | sed -E 's/.*sid=//' || true)
    printf 'workload_id=%s\nstage_id=%s\n' "$id" "${stage_id:-}" >"$OUTPUT_DIR/http/$name-artifact-ids.txt"
    if [[ -z "$stage_id" ]]; then
        printf '%s did not expose a stage link\n' "$name" >&2
        return 1
    fi

    capture_page http://127.0.0.1:19088 "/controller/stage.html?wid=$id&sid=$stage_id"
    capture_page http://127.0.0.1:19088 "/controller/timeline.html?wid=$id&sid=$stage_id"
    capture_page http://127.0.0.1:19088 "/controller/timeline.csv?wid=$id&sid=$stage_id"
    capture_page http://127.0.0.1:19088 "/controller/download-log.do?id=$id"
    capture_page http://127.0.0.1:19088 "/controller/download-config.do?id=$id"
    assert_status "controller_controller_stage.html_wid=${id}_sid=${stage_id}" 200
    assert_status "controller_controller_timeline.html_wid=${id}_sid=${stage_id}" 200
    assert_status "controller_controller_timeline.csv_wid=${id}_sid=${stage_id}" 200
    assert_status "controller_controller_download-log.do_id=${id}" 200
    assert_status "controller_controller_download-config.do_id=${id}" 200
}

for path in \
    /controller/index.html /controller/logon.html /controller/logonError.html \
    /controller/matrix.html /controller/submit.html /controller/config.html \
    /controller/advanced-config.html /controller/workload.html?id=w1 \
    /controller/stage.html?wid=w1\&sid=s1-main /controller/timeline.html?wid=w1\&sid=s1-main \
    /controller/timeline.csv?wid=w1\&sid=s1-main /controller/prometheus \
    /controller/resources/cosbench.css /controller/resources/build/dist/echarts.js \
    /controller/resources/build/dist/chart/bar.js /controller/resources/build/dist/chart/line.js \
    /controller/resources/up_arrow.png /controller/resources/down_arrow.png \
    /controller/resources/bg-header.png /controller/resources/bg-footer.png \
    /driver/resources/cosbench.css /driver/resources/bg-header.png /driver/resources/bg-footer.png \
    /driver/index.html /driver/submit.html /driver/mission.html \
    /driver/workers.html; do
    if [[ "$path" == /controller/* ]]; then
        capture_page http://127.0.0.1:19088 "$path"
        service_name=controller
    else
        capture_page http://127.0.0.1:18088 "$path"
        service_name=driver
    fi
    page_name=${path//\//_}
    page_name=${page_name//\?/_}
    page_name=${page_name//\&/_}
    assert_status "${service_name}${page_name}" 200
    case "$path" in
        *.css|*.js|*.png) assert_static_resource "${service_name}${page_name}" "$path" ;;
    esac
done

capture_request driver-ping POST http://127.0.0.1:18088/driver/i/ping.command 0
capture_request driver-invalid-ping POST http://127.0.0.1:18088/driver/i/ping.command ''
capture_request driver-missing-mission POST http://127.0.0.1:18088/driver/i/query.command missing
capture_request driver-submit-invalid POST http://127.0.0.1:18088/driver/i/submit.command \
    '<mission name="invalid"/>'
capture_request driver-trigger-noop POST http://127.0.0.1:18088/driver/i/trigger.command \
    enableTrigger,baseline-trigger.sh,1
assert_status driver-ping 200
assert_status driver-invalid-ping 400
assert_status driver-missing-mission 404
assert_status driver-submit-invalid 400
assert_status driver-trigger-noop 200

jq -e --slurpfile expected "$BASELINE_DIR/fixtures/expected/driver-ping.json" \
    '.code == $expected[0].code and .succ == $expected[0].succ and .name == $expected[0].name and .address == $expected[0].address and (.timeStamp | type == "string" and test("^[0-9]+$"))' \
    "$OUTPUT_DIR/http/driver-ping.body" >"$OUTPUT_DIR/http/driver-ping.contract"
jq -e --slurpfile expected "$BASELINE_DIR/fixtures/expected/driver-mission-not-found.json" \
    '. == $expected[0]' "$OUTPUT_DIR/http/driver-missing-mission.body" \
    >"$OUTPUT_DIR/http/driver-missing-mission.contract"
jq -e --slurpfile expected "$BASELINE_DIR/fixtures/expected/driver-submit-invalid.json" \
    '. == $expected[0]' "$OUTPUT_DIR/http/driver-submit-invalid.body" \
    >"$OUTPUT_DIR/http/driver-submit-invalid.contract"

create_mission() {
    local name=$1
    local operations=$2
    local delay=$3
    local runtime=$4
    local body
    body="<mission name=\"$name\" workers=\"1\" totalWorkers=\"1\" totalOps=\"$operations\" runtime=\"$runtime\" interval=\"1\"><auth type=\"mock\" config=\"delay=$delay;errors=0\"/><storage type=\"mock\" config=\"size=64;delay=$delay;errors=0\"/><operation type=\"read\" ratio=\"100\" config=\"containers=c(1);objects=c(1)\"/></mission>"
    capture_request "$name-submit" POST http://127.0.0.1:18088/driver/i/submit.command "$body"
    assert_success_response "$name-submit"
    jq -er '.id | strings | select(length > 0)' "$OUTPUT_DIR/http/$name-submit.body"
}

create_mission_from_file() {
    local name=$1
    local file=$2
    capture_request "$name-submit" POST http://127.0.0.1:18088/driver/i/submit.command "$(<"$file")"
    assert_success_response "$name-submit"
    jq -er '.id | strings | select(length > 0)' "$OUTPUT_DIR/http/$name-submit.body"
}

complete_mission() {
    local name=$1
    local id=$2
    capture_request "$name-login" POST http://127.0.0.1:18088/driver/i/login.command "$id"
    assert_success_response "$name-login"
    capture_request "$name-launch" POST http://127.0.0.1:18088/driver/i/launch.command "$id"
    assert_success_response "$name-launch"
    local completed=0
    local running=true
    for attempt in $(seq 1 120); do
        capture_request "$name-query" POST http://127.0.0.1:18088/driver/i/query.command "$id"
        assert_success_response "$name-query"
        jq -e '.running | type == "boolean"' "$OUTPUT_DIR/http/$name-query.body" \
            >"$OUTPUT_DIR/http/$name-query.contract"
        running=$(jq -r '.running' "$OUTPUT_DIR/http/$name-query.body")
        if [[ "$running" == false ]]; then
            completed=1
            break
        fi
        sleep 1
    done
    if [[ "$completed" -ne 1 ]]; then
        printf 'running=%s attempts=120\n' "$running" >"$OUTPUT_DIR/http/$name-timeout.txt"
        return 1
    fi
}

normal_mission=$(create_mission_from_file driver-lifecycle "$BASELINE_DIR/fixtures/mock-noop-mission.xml")
complete_mission driver-lifecycle "$normal_mission"
capture_request driver-lifecycle-close POST http://127.0.0.1:18088/driver/i/close.command "$normal_mission"
assert_success_response driver-lifecycle-close

abort_mission=$(create_mission driver-abort 0 50 120)
capture_request driver-abort-login POST http://127.0.0.1:18088/driver/i/login.command "$abort_mission"
assert_success_response driver-abort-login
capture_request driver-abort-launch POST http://127.0.0.1:18088/driver/i/launch.command "$abort_mission"
assert_success_response driver-abort-launch
capture_request driver-abort-abort POST http://127.0.0.1:18088/driver/i/abort.command "$abort_mission"
assert_success_response driver-abort-abort
capture_request driver-abort-query POST http://127.0.0.1:18088/driver/i/query.command "$abort_mission"
assert_success_response driver-abort-query
capture_request driver-abort-close POST http://127.0.0.1:18088/driver/i/close.command "$abort_mission"
assert_status driver-abort-close 409

for run in warmup $(seq 1 "$RUNS"); do
    started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    started_ns=$(date +%s%N)
    mission=$(create_mission "noop-$run" "$OPS" 0 0)
    capture_request "noop-$run-login" POST http://127.0.0.1:18088/driver/i/login.command "$mission"
    assert_success_response "noop-$run-login"
    capture_request "noop-$run-launch" POST http://127.0.0.1:18088/driver/i/launch.command "$mission"
    assert_success_response "noop-$run-launch"
    completed=0
    running=true
    for attempt in $(seq 1 120); do
        capture_request "noop-$run-query" POST http://127.0.0.1:18088/driver/i/query.command "$mission"
        assert_success_response "noop-$run-query"
        jq -e '.running | type == "boolean"' "$OUTPUT_DIR/http/noop-$run-query.body" \
            >"$OUTPUT_DIR/http/noop-$run-query.contract"
        running=$(jq -r '.running' "$OUTPUT_DIR/http/noop-$run-query.body")
        if [[ "$running" == false ]]; then
            completed=1
            break
        fi
        sleep 1
    done
    if [[ "$completed" -ne 1 ]]; then
        printf 'running=%s attempts=120\n' "$running" >"$OUTPUT_DIR/http/noop-$run-timeout.txt"
        exit 1
    fi
    ended_ns=$(date +%s%N)
    ended_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    elapsed_ms=$(( (ended_ns - started_ns) / 1000000 ))
    stats=$(sudo docker stats --no-stream --format '{{.CPUPerc}},{{.MemUsage}},{{.PIDs}}' "$DRIVER_CONTAINER")
    process=$(sudo docker exec "$DRIVER_CONTAINER" bash -lc 'pid=$(pgrep -x java | head -n 1); if [[ -z "$pid" ]]; then exit 1; fi; rss=$(awk "/^VmRSS:/ {print \$2}" /proc/$pid/status); hwm=$(awk "/^VmHWM:/ {print \$2}" /proc/$pid/status); threads=$(awk "/^Threads:/ {print \$2}" /proc/$pid/status); fds=$(find /proc/$pid/fd -maxdepth 1 -type l | wc -l); printf "%s,%s,%s,%s\n" "$rss" "$hwm" "$threads" "$fds"')
    capture_request "noop-$run-close" POST http://127.0.0.1:18088/driver/i/close.command "$mission"
    assert_success_response "noop-$run-close"
    jq -e '.wrReport[0].sampleCount > 0 and .wrReport[0].byteCount >= 0 and .wrReport[0].ratio == 1' \
        "$OUTPUT_DIR/http/noop-$run-close.body" >"$OUTPUT_DIR/http/noop-$run-close.contract"
    # The legacy close response exposes the histogram under report[].latency;
    # derive percentile buckets because wrReport[].latency is absent.
    jq -e -r --arg run "$run" --arg requested "$OPS" --arg elapsed "$elapsed_ms" \
        --arg stats "$stats" --arg process "$process" --arg started "$started_at" --arg ended "$ended_at" \
        'def percentile_bucket($percent):
            (.report[0].latency.histoData // []) as $hist
            | (($hist | add) // 0) as $total
            | if $total == 0 then "null"
              else (($total * $percent / 100) | ceil) as $rank
              | (reduce range(0; ($hist | length)) as $index
                  ({seen: 0, bucket: null};
                    .seen += $hist[$index]
                    | if .bucket == null and .seen >= $rank
                      then .bucket = [$index * 10, (($index + 1) * 10)]
                      else .
                      end)
                | if .bucket == null then "null"
                  else ((.bucket[0] | tostring) + "~" + (.bucket[1] | tostring))
                  end)
              end;
          [ $run, $requested, $elapsed, (.wrReport[0].sampleCount|tostring), (.wrReport[0].byteCount|tostring), (.wrReport[0].avgResTime|tostring), (.wrReport[0].throughput|tostring), percentile_bucket(50), percentile_bucket(95), percentile_bucket(99), ((.wrReport[0].ratio * 100 | tostring) + "%"), ($stats|split(",")[0]), ($stats|split(",")[1]), ($process|split(",")[0]), ($process|split(",")[1]), ($process|split(",")[2]), ($process|split(",")[3]), $started, $ended ] | @csv' \
        "$OUTPUT_DIR/http/noop-$run-close.body" >"$OUTPUT_DIR/http/noop-$run.metrics.csv"
    if [[ "$run" != warmup ]]; then
        if [[ ! -s "$OUTPUT_DIR/performance.csv" ]]; then
            printf 'run,requested_ops,elapsed_ms,reported_ops,reported_bytes,avg_response_ms,published_ops_per_second,p50_bucket,p95_bucket,p99_bucket,success_ratio,docker_cpu_percent,docker_memory_usage,java_rss_kib,java_hwm_kib,java_threads,java_fd_count,started_at_utc,ended_at_utc\n' >"$OUTPUT_DIR/performance.csv"
        fi
        cat "$OUTPUT_DIR/http/noop-$run.metrics.csv" >>"$OUTPUT_DIR/performance.csv"
    fi
done

capture_request controller-cli-index GET \
    'http://127.0.0.1:19088/controller/cli/index.action?username=anonymous&password=cosbench'
assert_status controller-cli-index 200
capture_upload controller-cli-submit \
    'http://127.0.0.1:19088/controller/cli/submit.action?username=anonymous&password=cosbench' \
    "$BASELINE_DIR/fixtures/mock-noop.xml"
assert_status controller-cli-submit 200
CONTROLLER_NOOP_ID=$(sed -n 's/^Accepted with ID: //p' "$OUTPUT_DIR/http/controller-cli-submit.body")
[[ -n "$CONTROLLER_NOOP_ID" ]]
wait_controller_workload controller-noop "$CONTROLLER_NOOP_ID" FINISHED

capture_upload controller-cli-config \
    'http://127.0.0.1:19088/controller/cli/config.action?username=anonymous&password=cosbench' \
    "$BASELINE_DIR/fixtures/mock-noop.xml"
assert_status controller-cli-config 200
CONTROLLER_CONFIG_ID=$(sed -n 's/^Accepted with ID: //p' "$OUTPUT_DIR/http/controller-cli-config.body")
[[ -n "$CONTROLLER_CONFIG_ID" ]]
wait_controller_workload controller-config "$CONTROLLER_CONFIG_ID" FINISHED
capture_upload controller-page-submit \
    http://127.0.0.1:19088/controller/submit-workload.do \
    "$BASELINE_DIR/fixtures/mock-noop.xml"
assert_status controller-page-submit 200
CONTROLLER_PAGE_ID=$(grep -oE 'workload\.html\?id=[A-Za-z0-9_.-]+' \
    "$OUTPUT_DIR/http/controller-page-submit.body" | head -n 1 | sed 's/.*id=//')
[[ -n "$CONTROLLER_PAGE_ID" ]]
assert_page_submission controller-page-submit "$CONTROLLER_PAGE_ID"
wait_controller_workload controller-page "$CONTROLLER_PAGE_ID" FINISHED

capture_form controller-page-config \
    http://127.0.0.1:19088/controller/config-workload.do \
    'workload.name=baseline-config&workload.desc=baseline&auth.type=mock&auth.config=delay%3D0%3Berrors%3D0&storage.type=mock&storage.config=size%3D64%3Bdelay%3D0%3Berrors%3D0&init.checked=on&init.workers=1&init.containers=r&init.containers.min=1&init.containers.max=1&prepare.checked=on&prepare.workers=1&prepare.containers=r&prepare.containers.min=1&prepare.containers.max=1&prepare.objects=r&prepare.objects.min=1&prepare.objects.max=1&prepare.sizes=u&prepare.sizes.min=64&prepare.sizes.max=64&prepare.sizes.unit=KB&normal.checked=on&normal.workers=1&normal.rampup=0&normal.runtime=1&read.ratio=100&read.containers=c&read.containers.min=1&read.containers.max=1&read.objects=c&read.objects.min=1&read.objects.max=1&write.ratio=0&write.containers=c&write.containers.min=1&write.containers.max=1&write.objects=c&write.objects.min=1&write.objects.max=1&write.sizes=c&write.sizes.min=64&write.sizes.max=64&write.sizes.unit=KB&filewrite.ratio=0&filewrite.containers=c&filewrite.containers.min=1&filewrite.containers.max=1&filewrite.fileselection=all&filewrite.files=%2Ftmp%2Ftestfiles%2F&delete.ratio=0&delete.containers=c&delete.containers.min=1&delete.containers.max=1&delete.objects=c&delete.objects.min=1&delete.objects.max=1&cleanup.checked=on&cleanup.workers=1&cleanup.containers=r&cleanup.containers.min=1&cleanup.containers.max=1&cleanup.objects=r&cleanup.objects.min=1&cleanup.objects.max=1&dispose.checked=on&dispose.workers=1&dispose.containers=r&dispose.containers.min=1&dispose.containers.max=1'
assert_status controller-page-config 200
assert_content_disposition controller-page-config
grep -Eq '<workload([[:space:]>])' "$OUTPUT_DIR/http/controller-page-config.body"

capture_form controller-page-advanced-submit \
    http://127.0.0.1:19088/controller/advanced-config-workload.do \
    'workload.matrix.name=baseline-matrix&auth.type=mock&auth.config=delay%3D0&storage.type=mock&storage.config=size%3D64%3Bdelay%3D0%3Berrors%3D0&runtime=1&rampup=0&delay=0&num_of_drivers=1&workload.matrix.checked=on&workload.name=baseline-advanced&workload-number=0&object-sizes=64&object-size-unit=KB&num-of-objects=1&num-of-containers=1&workers=1&read.write.delete.matrix.checked=on&read-ratio0=100&write-ratio0=0&delete-ratio0=0&submit-workload=Submit%20Workload%2Fs'
assert_status controller-page-advanced-submit 200
grep -q '<title>Workload Submission</title>' "$OUTPUT_DIR/http/controller-page-advanced-submit.body"
CONTROLLER_ADVANCED_ID=$(grep -B1 -F '<td>baseline-advanced</td>' \
    "$OUTPUT_DIR/http/controller-page-advanced-submit.body" | head -n 1 \
    | sed -E 's/.*<td>([A-Za-z0-9_.-]+)<\/td>.*/\1/')
[[ -n "$CONTROLLER_ADVANCED_ID" ]]
grep -Eq "<td>${CONTROLLER_ADVANCED_ID}</td>" "$OUTPUT_DIR/http/controller-page-advanced-submit.body"
grep -Eq 'workload-state-(queuing|processing|running|finished)' \
    "$OUTPUT_DIR/http/controller-page-advanced-submit.body"
wait_controller_workload controller-advanced "$CONTROLLER_ADVANCED_ID" FINISHED
capture_request controller-prometheus GET http://127.0.0.1:19088/controller/prometheus
assert_prometheus_contract controller-prometheus state
capture_upload controller-cli-long-submit \
    'http://127.0.0.1:19088/controller/cli/submit.action?username=anonymous&password=cosbench' \
    "$BASELINE_DIR/fixtures/long-noop.xml"
assert_status controller-cli-long-submit 200
LONG_ID=$(sed -n 's/^Accepted with ID: //p' "$OUTPUT_DIR/http/controller-cli-long-submit.body")
[[ -n "$LONG_ID" ]]
capture_request controller-cli-workload GET \
    "http://127.0.0.1:19088/controller/cli/workload.action?username=anonymous&password=cosbench&id=$LONG_ID"
assert_status controller-cli-workload 200
capture_cancel controller-cli-cancel \
    'http://127.0.0.1:19088/controller/cli/cancel.action?username=anonymous&password=cosbench' "$LONG_ID"
assert_status controller-cli-cancel 200
wait_controller_workload controller-long "$LONG_ID" CANCELLED
capture_controller_artifacts controller-noop "$CONTROLLER_NOOP_ID"
capture_request controller-prometheus-final GET http://127.0.0.1:19088/controller/prometheus
assert_prometheus_contract controller-prometheus-final

sudo docker cp "$DRIVER_CONTAINER:/cosbench/conf/driver_1.conf" \
    "$OUTPUT_DIR/driver-generated.conf"
sudo docker cp "$CONTROLLER_CONTAINER:/cosbench/conf/controller.conf" \
    "$OUTPUT_DIR/controller-generated.conf"

for command in index submit config workload cancel; do
    case "$command" in
        index) url='http://127.0.0.1:19088/controller/cli/index.action?username=anonymous&password=cosbench'; method=GET; body= ;;
        submit) url='http://127.0.0.1:19088/controller/cli/submit.action?username=anonymous&password=cosbench'; method=POST; body= ;;
        config) url='http://127.0.0.1:19088/controller/cli/config.action?username=anonymous&password=cosbench'; method=POST; body= ;;
        workload) url="http://127.0.0.1:19088/controller/cli/workload.action?username=anonymous&password=cosbench&id=$LONG_ID"; method=GET; body= ;;
        cancel) url='http://127.0.0.1:19088/controller/cli/cancel.action?username=anonymous&password=cosbench'; method=POST; body="id=$LONG_ID" ;;
    esac
    printf '%s\t%s\t%s\n' "$command" "$method" "${url%%\?*}" >>"$OUTPUT_DIR/cli-index.tsv"
done

printf 'workload-submit-id=%s\n' "$CONTROLLER_NOOP_ID" >"$OUTPUT_DIR/controller-workload-ids.txt"
printf 'config-workload-id=%s\n' "$CONTROLLER_CONFIG_ID" >>"$OUTPUT_DIR/controller-workload-ids.txt"
printf 'page-workload-id=%s\n' "$CONTROLLER_PAGE_ID" >>"$OUTPUT_DIR/controller-workload-ids.txt"
printf 'advanced-workload-id=%s\n' "$CONTROLLER_ADVANCED_ID" >>"$OUTPUT_DIR/controller-workload-ids.txt"
printf 'long-workload-id=%s\n' "$LONG_ID" >>"$OUTPUT_DIR/controller-workload-ids.txt"

sudo docker exec "$CONTROLLER_CONTAINER" bash -lc \
    'find archive -maxdepth 3 -type f -printf "%p\t%s\n" | sort' >"$OUTPUT_DIR/archive-index.tsv"
sudo docker exec "$DRIVER_CONTAINER" bash -lc \
    'pid=$(pgrep -x java | head -n 1); printf "java_pid=%s\n" "$pid"; java -version 2>&1; find log/mission -maxdepth 1 -type f -printf "%f\t%s\n" | sort' \
    >"$OUTPUT_DIR/driver-runtime.txt" 2>&1
sudo docker exec "$CONTROLLER_CONTAINER" bash -lc \
    'pid=$(pgrep -x java | head -n 1); printf "java_pid=%s\n" "$pid"; java -version 2>&1; find archive -maxdepth 2 -type d -printf "%p\n" | sort' \
    >"$OUTPUT_DIR/controller-runtime.txt" 2>&1

printf 'Driver and Controller smoke requests and %s performance run(s) completed.\n' "$RUNS"
