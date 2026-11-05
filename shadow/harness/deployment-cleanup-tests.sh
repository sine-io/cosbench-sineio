#!/usr/bin/env bash
set -Eeuo pipefail

report_root=$(realpath "${1:?Expected report directory}")
compose_env=$(realpath "${2:?Expected harness Compose env file}")
project=${3:?Expected harness Compose project}
harness_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
report="$report_root/deployment-cleanup-tests"
mkdir -p "$report"
compose_args=(--ansi never --progress plain --project-name "$project"
    --env-file "$harness_dir/versions.env" --env-file "$compose_env" -f "$harness_dir/compose.yaml")
compose() { sudo docker compose "${compose_args[@]}" "$@"; }
replay_pid=
cleanup() {
    local status=$?
    trap - EXIT INT TERM
    if [[ -n $replay_pid ]]; then
        kill -TERM "$replay_pid" 2>/dev/null || true
        wait "$replay_pid" 2>/dev/null || true
    fi
    # On interruption the enclosing harness is stopping; do not create new
    # resources while its cleanup is removing the project.
    if ((status == 0)); then
        compose up --detach --wait --wait-timeout 90 minio > "$report/restore-minio.log" 2>&1 || status=1
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

assert_removed() {
    local output=$1 containers
    : > "$output"
    for service in controller driver driver2 maven; do
        containers=$(sudo docker ps --all --quiet --filter "label=com.docker.compose.project=$project" \
            --filter "label=com.docker.compose.service=$service")
        [[ -z $containers ]]
        printf '%s_containers=none\n' "$service" >> "$output"
    done
}

compose stop --timeout 10 minio > "$report/unavailable-minio.log" 2>&1
status=0
bash "$harness_dir/deployment-integration.sh" "$report/unavailable" "$compose_env" "$project" \
    > "$report/unavailable.log" 2>&1 || status=$?
[[ $status == 1 ]]
failed="$report/unavailable/deployment-integration"
grep -q '^run_status=1$' "$failed/cleanup-status.txt"
grep -q '^cleanup_status=0$' "$failed/cleanup-status.txt"
grep -Eq 'FAILED|TERMINATED' "$failed/both/sio-state.txt"
test -s "$failed/applications.log"
[[ ! -e $failed/summary.txt ]]
assert_removed "$report/unavailable-containers.txt"
compose up --detach --wait --wait-timeout 90 minio > "$report/restart-minio.log" 2>&1

bash "$harness_dir/deployment-integration.sh" "$report/interrupted" "$compose_env" "$project" \
    > "$report/interrupted.log" 2>&1 &
replay_pid=$!
ready=0
for ((attempt = 0; attempt < 900; attempt++)); do
    state="$report/interrupted/deployment-integration/both/sio-state.txt"
    if [[ -s $state ]] && grep -q PROCESSING "$state"; then ready=1; break; fi
    kill -0 "$replay_pid" 2>/dev/null || { wait "$replay_pid"; exit 1; }
    sleep 0.1
done
[[ $ready == 1 ]]
kill -TERM "$replay_pid"
status=0
wait "$replay_pid" || status=$?
replay_pid=
[[ $status == 143 ]]
interrupted="$report/interrupted/deployment-integration"
grep -q '^run_status=143$' "$interrupted/cleanup-status.txt"
grep -q '^cleanup_status=0$' "$interrupted/cleanup-status.txt"
test -s "$interrupted/applications.log"
[[ ! -e $interrupted/summary.txt ]]
assert_removed "$report/interrupted-containers.txt"
printf 'status=passed\nunavailable_endpoint=rejected\nfailed_attempt=logs-and-state-retained\ninterrupted_workload=exit-143\ncontainers=removed\nretry_preconditions=minio-restored,fresh-application-mounts\n' \
    > "$report/summary.txt"
