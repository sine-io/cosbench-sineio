#!/usr/bin/env bash
set -Eeuo pipefail

harness_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$harness_dir/../.." && pwd)
source "$harness_dir/versions.env"
report_dir=$(realpath -m "${1:?Usage: test-sineio-archive-replay.sh EMPTY_REPORT_DIR}")
mkdir -p "$report_dir"
[[ -z $(find "$report_dir" -mindepth 1 -maxdepth 1 -print -quit) ]] || exit 2
fixture_dir=$(mktemp -d /tmp/sineio-archive-failures.XXXXXX)
server_name="cosbench-archive-fixture-$$"
replay_pid=
cleanup() {
    local status=$? cleanup_status=0
    trap - EXIT INT TERM
    if [[ -n $replay_pid ]]; then
        kill -TERM "$replay_pid" 2>/dev/null || true
        wait "$replay_pid" 2>/dev/null || true
    fi
    sudo docker logs "$server_name" > "$report_dir/fixture-server.log" 2>&1 || true
    if sudo docker container inspect "$server_name" >/dev/null 2>&1; then
        sudo docker rm --force "$server_name" >/dev/null || cleanup_status=1
    fi
    rm -rf -- "$fixture_dir"
    printf 'run_status=%s\ncleanup_status=%s\n' "$status" "$cleanup_status" > "$report_dir/cleanup-status.txt"
    if ((status == 0 && cleanup_status != 0)); then status=1; fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ss -H -lnt | awk '$4 ~ /:19592$/ { found=1 } END { exit !found }'; then exit 2; fi
sudo docker run --rm --detach --name "$server_name" --network host --entrypoint java \
    -v "$repo_root:/workspace:ro" "$MAVEN_IMAGE" \
    /workspace/shadow/harness/fixtures/ArchiveEndpointFixture.java 19592 > "$report_dir/fixture-container.txt"
ready=0
for ((attempt = 0; attempt < 150; attempt++)); do
    if curl --silent --fail --max-time 1 http://127.0.0.1:19592/health | grep -q '^archive-failure-fixture$'; then
        ready=1
        break
    fi
    sleep 0.1
done
[[ $ready == 1 ]]
printf '%s' 'test' > "$fixture_dir/access-key"
printf '%s' 'test' > "$fixture_dir/secret-key"
chmod 600 "$fixture_dir/"*-key
payload_sha=$(printf '%s' 'archive replay failure fixture' | sha256sum | cut -d ' ' -f 1)
write_fixture() {
    local scenario=$1 deadline=$2
    printf 'restoreDeadlineSeconds=%s\npollIntervalSeconds=10\n' "$deadline" > "$fixture_dir/archive.properties"
    for mode in sio siov1 gdas; do
        cat >> "$fixture_dir/archive.properties" <<EOF
$mode.storageConfig=endpoint=http://127.0.0.1:19592/$scenario;path_style_access=true;timeout=10000
$mode.accessKeyFile=access-key
$mode.secretKeyFile=secret-key
$mode.bucket=bucket
$mode.object=object
$mode.sha256=$payload_sha
$mode.objectBytes=30
EOF
    done
}
for scenario in slow poll range readable; do
    write_fixture "$scenario" 1
    if [[ $scenario == range || $scenario == readable ]]; then write_fixture "$scenario" 10; fi
    status=0
    bash "$harness_dir/sineio-archive-replay.sh" "$fixture_dir" "$report_dir/$scenario" || status=$?
    [[ $status == 1 ]]
    grep -q '^status=failed$' "$report_dir/$scenario/summary.txt"
    grep -q '^cleanup_status=0$' "$report_dir/$scenario/cleanup-status.txt"
    if [[ $scenario == range ]]; then
        grep -q 'FixtureException: Response exceeds expected object length' "$report_dir/$scenario/container.log"
    elif [[ $scenario == readable ]]; then
        grep -q 'FixtureException: Fixture must be archived and unreadable before restore' "$report_dir/$scenario/container.log"
    else
        grep -q 'Archive acceptance FAILED: TimeoutException' "$report_dir/$scenario/container.log"
    fi
done

write_fixture interrupt 60
bash "$harness_dir/sineio-archive-replay.sh" "$fixture_dir" "$report_dir/interrupt" &
replay_pid=$!
created=0
for ((attempt = 0; attempt < 150; attempt++)); do
    if [[ -n $(sudo docker ps --all --quiet --filter "name=^cosbench-sineio-archive-$replay_pid-") ]]; then
        created=1
        break
    fi
    kill -0 "$replay_pid" 2>/dev/null || exit 1
    sleep 0.1
done
[[ $created == 1 ]]
interrupted_pid=$replay_pid
kill -TERM "$replay_pid"
status=0
wait "$replay_pid" || status=$?
replay_pid=
[[ $status == 143 ]]
[[ -z $(sudo docker ps --all --quiet --filter "name=^cosbench-sineio-archive-$interrupted_pid-") ]]
grep -q '^cleanup_status=0$' "$report_dir/interrupt/cleanup-status.txt"
printf 'status=passed\nslow_restore_deadline=passed\npoll_deadline=passed\nignored_range=rejected\nreadable_object=rejected\ninterrupt_cleanup=passed\n' > "$report_dir/summary.txt"
