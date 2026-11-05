#!/usr/bin/env bash
set -Eeuo pipefail

# Invoked by run.sh after the verified artifacts and prerequisite replays exist.
report_root=$(realpath "${1:?Expected report directory}")
compose_env=$(realpath "${2:?Expected harness Compose env file}")
project=${3:?Expected harness Compose project}
harness_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$harness_dir/versions.env"
source "$compose_env"
report="$report_root/deployment-integration"
if [[ -e $report ]]; then
    printf 'Deployment report directory already exists: %s\n' "$report" >&2
    exit 2
fi
runtime=$(mktemp -d "$COSBENCH_SECRET_DIR/deployment.XXXXXX")
attempt_id=${runtime##*/}
mkdir -p "$runtime/conf" "$report"
printf 'COSBENCH_DEPLOYMENT_DIR="%s"\nCOSBENCH_REPORT_DIR="%s"\n' "$runtime" "$report_root" > "$runtime/compose.env"
compose_args=(--ansi never --progress plain --project-name "$project"
    --env-file "$harness_dir/versions.env" --env-file "$compose_env" --env-file "$runtime/compose.env"
    -f "$harness_dir/compose.yaml" -f "$harness_dir/deployment/compose.yaml")
active_pid=
compose() { sudo docker compose "${compose_args[@]}" "$@"; }
run_compose() {
    sudo docker compose "${compose_args[@]}" "$@" &
    active_pid=$!
    local status=0
    wait "$active_pid" || status=$?
    active_pid=
    return "$status"
}
cleanup() {
    local status=$? cleanup_status=0
    trap - EXIT INT TERM
    set +e
    if [[ -n $active_pid ]]; then
        kill -TERM "$active_pid" 2>/dev/null || true
        wait "$active_pid" 2>/dev/null || true
    fi
    compose logs --no-color controller driver driver2 > "$report/applications.log" 2>&1
    local probe_containers
    probe_containers=$(sudo docker ps --all --quiet --filter "label=com.docker.compose.project=$project" \
        --filter "label=cosbench.integration.attempt=$attempt_id") || cleanup_status=1
    for container in $probe_containers; do
        sudo docker logs "$container" >> "$report/probe.log" 2>&1
        sudo docker rm --force "$container" >> "$report/probe-cleanup.log" 2>&1 || cleanup_status=1
    done
    compose stop --timeout 20 controller driver driver2 > "$report/stop.log" 2>&1 || cleanup_status=1
    # Retain the mounted data for diagnosis; run.sh redacts it before removing secrets.
    for role in controller driver driver2; do
        [[ ! -d $runtime/$role ]] || sudo cp -R "$runtime/$role" "$report/$role-data"
    done
    compose ps --all --format json > "$report/final-containers.json"
    compose rm --force controller driver driver2 > "$report/remove.log" 2>&1 || cleanup_status=1
    printf 'run_status=%s\ncleanup_status=%s\n' "$status" "$cleanup_status" > "$report/cleanup-status.txt"
    if ((status == 0 && cleanup_status != 0)); then status=1; fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for role in controller driver driver2; do
    mkdir -p "$runtime/$role/log" "$runtime/$role/archive" "$runtime/$role/workspace" "$runtime/$role/mission"
    printf '%s\n' "preserved-$role-data" > "$runtime/$role/workspace/preexisting.txt"
done
for role in driver driver2; do
    cat > "$runtime/conf/$role.yml" <<YAML
schemaVersion: 1
role: driver
name: $role
listen:
  host: 0.0.0.0
  port: 18088
  contextPath: /driver
paths:
  workspace: /var/lib/cosbench/workspace
  archive: /var/lib/cosbench/archive
  mission: /var/lib/cosbench/mission
  logs: /var/lib/cosbench/log/system.log
auth:
  token:
    file: /run/secrets/http_service_token
  tls:
    enabled: true
    keyStore: file:/run/secrets/http_test_keystore
logging:
  level: INFO
shutdown:
  gracePeriod: 10s
YAML
done
configure_controller() {
    local count=$1
    cat > "$runtime/conf/controller.yml" <<'YAML'
schemaVersion: 1
role: controller
name: integration-controller
listen:
  host: 0.0.0.0
  port: 19088
  contextPath: /controller
paths:
  workspace: /var/lib/cosbench/workspace
  archive: /var/lib/cosbench/archive
  logs: /var/lib/cosbench/log/system.log
auth:
  token:
    file: /run/secrets/http_service_token
  tls:
    enabled: true
    trustStore: file:/run/secrets/http_test_truststore
http:
  timeouts:
    connect: 2s
    read: 5s
    command: 10s
    workload: 5m
logging:
  level: INFO
shutdown:
  gracePeriod: 10s
YAML
    if ((count == 0)); then printf 'drivers: []\n' >> "$runtime/conf/controller.yml"; return; fi
    printf 'drivers:\n  - name: driver\n    url: https://driver:18088/driver\n' >> "$runtime/conf/controller.yml"
    if ((count == 2)); then
        printf '  - name: driver2\n    url: https://driver2:18088/driver\n' >> "$runtime/conf/controller.yml"
    fi
}
check_health() {
    local scenario=$1 role=$2 url
    mkdir -p "$report/$scenario"
    if [[ $role == controller ]]; then url=http://localhost:19088/controller/health
    else url=https://localhost:18088/driver/health; fi
    compose exec -T "$role" curl -fsS --max-time 5 --cacert /run/secrets/http_test_certificate "$url" \
        > "$report/$scenario/$role-health.json"
    grep -q '"accepting":true' "$report/$scenario/$role-health.json"
    local container
    container=$(compose ps --quiet "$role")
    sudo docker inspect --format '{{json .State}} {{json .Mounts}}' "$container" \
        > "$report/$scenario/$role-container.txt"
    sudo docker top "$container" -eo pid,ppid,args > "$report/$scenario/$role-processes.txt"
    local app_role=${role/driver2/driver}
    grep -Eq "java .*cosbench-$app_role.jar" "$report/$scenario/$role-processes.txt"
    grep -q '"Status":"healthy"' "$report/$scenario/$role-container.txt"
    python3 - "$report/$scenario/$role-container.txt" "$app_role" <<'PY'
import json
import pathlib
import sys
state, offset = json.JSONDecoder().raw_decode(pathlib.Path(sys.argv[1]).read_text())
mounts = json.loads(pathlib.Path(sys.argv[1]).read_text()[offset:])
mounted = {mount['Destination']: mount for mount in mounts}
assert state['Running'] and state['Pid'] > 0
assert not mounted[f'/opt/cosbench/conf/{sys.argv[2]}.yml']['RW']
assert not mounted['/run/secrets/http_service_token']['RW']
assert not mounted['/run/secrets/http_test_keystore']['RW']
assert mounted['/var/lib/cosbench']['RW']
PY
    cp "$runtime/conf/$role.yml" "$report/$scenario/$role-config.yml"
    sha256sum "$report/$scenario/$role-config.yml" > "$report/$scenario/$role-config.sha256"
}
stop_app() {
    local scenario=$1 role=$2 container status
    container=$(compose ps --quiet "$role")
    run_compose stop --timeout 20 "$role"
    status=$(sudo docker inspect --format '{{.State.Running}} {{.State.ExitCode}}' "$container")
    [[ $status == 'false 143' || $status == 'false 0' ]]
    printf '%s %s\n' "$role" "$status" >> "$report/$scenario/stopped.txt"
    [[ $(sudo docker inspect --format '{{.State.Pid}}' "$container") == 0 ]]
}

sha256sum "$COSBENCH_REPO_ROOT"/shadow/cosbench-{controller-app,driver-app,operator-cli}/target/*.jar \
    > "$report/artifacts.sha256"
cp "$harness_dir/versions.env" "$report/versions.env"
git -C "$COSBENCH_REPO_ROOT" rev-parse HEAD > "$report/source-commit.txt"
git -C "$COSBENCH_REPO_ROOT" diff --binary HEAD -- shadow bin ci .github/workflows/shadow-verify.yml \
    | sha256sum > "$report/source-diff.sha256"
baseline="$COSBENCH_REPO_ROOT/.scratch/scheme3-migration/baseline"
mkdir -p "$report/baseline"
cp "$baseline/README.md" "$baseline/scenarios.md" "$baseline/observations.md" "$report/baseline/"
find "$baseline" -type f -print0 | sort -z | xargs -0 sha256sum > "$report/baseline/inputs.sha256"
git -C "$COSBENCH_REPO_ROOT" log -1 --format=%H -- .scratch/scheme3-migration/baseline \
    > "$report/baseline/source-commit.txt"
printf 'status=consumed\nreplay=.scratch/scheme3-migration/baseline/replay.sh\nlegacy_release=0.6.0.2-sineio\nlegacy_source=cd18daf2f6f97f97409f4709d00bbd7389676d6\nrelease_sha256=1421f48a55673e42edd73c7b09a610b2b71ef94c70d46db3fb74dd3c34d22a06\ncurrent_replays=config-lifecycle.sh,http-contract.sh,controller-driver-process.sh\nacceptance=consumption-only;baseline-open-items-remain-with-original-owners\n' \
    > "$report/baseline/reference.txt"

configure_controller 0
run_compose up --detach --wait --wait-timeout 100 controller
check_health controller-only controller
stop_app controller-only controller

run_compose up --detach --wait --wait-timeout 100 driver
check_health driver-only driver
# A listener must require TLS; possession of the health URL grants no /i access.
compose exec -T driver /bin/bash -c '
    code=$(curl -sS --cacert /run/secrets/http_test_certificate -o /dev/null -w "%{http_code}" \
        --data-binary 1782057600000 https://localhost:18088/driver/i/ping.command)
    [[ $code == 401 ]]
    if curl -fsS --max-time 2 http://localhost:18088/driver/health >/dev/null 2>&1; then exit 1; fi
' > "$report/driver-only/tls-auth.txt" 2>&1

configure_controller 1
run_compose up --detach --wait --wait-timeout 100 controller
check_health both controller
check_health both driver
run_compose run --rm --no-deps --label "cosbench.integration.attempt=$attempt_id" \
    maven /bin/bash /workspace/shadow/harness/deployment/workloads.sh both 1
stop_app both controller

configure_controller 2
run_compose up --detach --wait --wait-timeout 100 controller driver2
for role in controller driver driver2; do check_health multi-driver "$role"; done
for role in controller driver driver2; do
    sudo docker inspect --format '{{.State.Pid}}' "$(compose ps --quiet "$role")"
done > "$report/multi-driver/host-pids.txt"
[[ $(sort -u "$report/multi-driver/host-pids.txt" | wc -l) == 3 ]]
run_compose run --rm --no-deps --label "cosbench.integration.attempt=$attempt_id" \
    maven /bin/bash /workspace/shadow/harness/deployment/workloads.sh multi-driver 2
# Stopping one Driver must leave the Controller and second Driver healthy.
stop_app multi-driver driver
check_health isolated-stop controller
check_health isolated-stop driver2
stop_app isolated-stop controller
stop_app isolated-stop driver2

# Restart against the same mounts, read historical results through the page API,
# and verify the data written before startup has survived.
run_compose up --detach --wait --wait-timeout 100 controller driver driver2
for role in controller driver driver2; do
    check_health restart "$role"
    grep -q "preserved-$role-data" "$runtime/$role/workspace/preexisting.txt"
    test -s "$runtime/$role/log/system.log"
done
compose exec -T controller /bin/bash -c '
    set -Eeuo pipefail
    curl -fsS -c /tmp/integration-cookies --data "j_username=anonymous&j_password=cosbench" \
        http://localhost:19088/controller/j_security_check >/dev/null
    curl -fsS -b /tmp/integration-cookies "http://localhost:19088/controller/index.html?loadArch=true"
    rm /tmp/integration-cookies
' > "$report/restart/history.html"
grep -q 'integration-both-sio' "$report/restart/history.html"
grep -q 'integration-multi-driver-s3' "$report/restart/history.html"
for role in driver driver2; do
    find "$runtime/$role/mission" -type f -name '*.log' -print -quit | grep -q .
    sudo grep -q 'detected supported storage types: gdas, s3, sio, siov1' "$runtime/$role/log/system.log"
done
for role in controller driver driver2; do stop_app restart "$role"; done
cp -R "$runtime/conf" "$report/configuration"
sha256sum "$runtime/conf/"*.yml > "$report/configuration.sha256"
printf 'status=passed\ndeployments=controller-only,driver-only,both,multi-driver\nhealth=real-http-and-compose\nprocesses=separate-containers\nmounts=config-read-only,data-persistent,secrets-read-only\nproviders=sio,siov1,gdas,s3\nclasspath=production-shared-without-mock\nrestart=archive,workspace,mission,logs-preserved\nisolated-stop=passed\n' \
    > "$report/summary.txt"
