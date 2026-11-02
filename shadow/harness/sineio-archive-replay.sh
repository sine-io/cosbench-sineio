#!/usr/bin/env bash
set -Eeuo pipefail

harness_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$harness_dir/../.." && pwd)
source "$harness_dir/versions.env"
fixture_dir=$(realpath "${1:?Usage: sineio-archive-replay.sh FIXTURE_DIR EMPTY_REPORT_DIR}")
report_dir=$(realpath -m "${2:?Usage: sineio-archive-replay.sh FIXTURE_DIR EMPTY_REPORT_DIR}")
test -s "$fixture_dir/archive.properties"
test -s "$repo_root/shadow/cosbench-driver-app/target/cosbench-driver-app-0.1.0-SNAPSHOT.jar"
mkdir -p "$report_dir"
[[ -z $(find "$report_dir" -mindepth 1 -maxdepth 1 -print -quit) ]] || { printf 'Report directory must be empty\n' >&2; exit 2; }
container_name="cosbench-sineio-archive-$$-$(date +%s)"
docker_pid=
cleanup() {
    local status=$? cleanup_status=0
    trap - EXIT INT TERM
    if [[ -n $docker_pid ]]; then
        # Stop an in-progress pull/create as well as an already running replay.
        kill -TERM "$docker_pid" 2>/dev/null || true
        for ((attempt = 0; attempt < 30; attempt++)); do
            if sudo docker container inspect "$container_name" >/dev/null 2>&1; then
                sudo docker rm --force "$container_name" >/dev/null 2>&1 || true
            fi
            kill -0 "$docker_pid" 2>/dev/null || break
            sleep 0.1
        done
        if kill -0 "$docker_pid" 2>/dev/null; then kill -KILL "$docker_pid" 2>/dev/null || true; fi
        wait "$docker_pid" 2>/dev/null || true
    fi
    # --rm can be removing the container concurrently; judge the final state.
    for ((attempt = 0; attempt < 30; attempt++)); do
        sudo docker container inspect "$container_name" >/dev/null 2>&1 || break
        sudo docker rm --force "$container_name" >/dev/null 2>&1 || true
        sleep 0.1
    done
    local containers_remaining=none
    if sudo docker container inspect "$container_name" >/dev/null 2>&1; then
        cleanup_status=1
        containers_remaining=present
    fi
    printf 'run_status=%s\ncleanup_status=%s\ncontainers_remaining=%s\n' "$status" "$cleanup_status" "$containers_remaining" \
        > "$report_dir/cleanup-status.txt"
    if ((status == 0 && cleanup_status != 0)); then status=1; fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
sudo docker run --rm --name "$container_name" --network host --entrypoint /bin/bash \
    -v "$repo_root:/workspace:ro" -v "$fixture_dir:/fixtures:ro" -v "$report_dir:/reports" \
    "$MAVEN_IMAGE" -c '
        set -Eeuo pipefail
        replay_dir=$(mktemp -d /tmp/sineio-archive.XXXXXX)
        trap '\''rm -rf -- "$replay_dir"'\'' EXIT
        artifact=/workspace/shadow/cosbench-driver-app/target/cosbench-driver-app-0.1.0-SNAPSHOT.jar
        sha256sum "$artifact" > /reports/driver-artifact.sha256
        java -version > /reports/java-version.txt 2>&1
        cd "$replay_dir"
        jar xf "$artifact" BOOT-INF/lib
        printf "%s\n" "<configuration><root level=\"OFF\"/></configuration>" > "$replay_dir/logback.xml"
        java -Dlogback.configurationFile="$replay_dir/logback.xml" -cp "$replay_dir/BOOT-INF/lib/*" \
            /workspace/shadow/harness/SineioArchiveReplay.java \
            /fixtures/archive.properties /reports
    ' > "$report_dir/container.log" 2>&1 &
docker_pid=$!
status=0
wait "$docker_pid" || status=$?
docker_pid=
exit "$status"
