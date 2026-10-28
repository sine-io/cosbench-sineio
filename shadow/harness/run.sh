#!/usr/bin/env bash
set -Eeuo pipefail

harness_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$harness_dir/../.." && pwd)
compose_file="$harness_dir/compose.yaml"
versions_file="$harness_dir/versions.env"

usage() {
    printf '%s\n' \
        'Usage: run.sh EMPTY_REPORT_DIR [-- JAVA21_COMMAND [ARG...]]' \
        'A supplied command runs in the same Java 21, MinIO, and Toxiproxy network after the default checks.' >&2
}

if (($# < 1)); then
    usage
    exit 2
fi
report_dir=$(realpath -m "$1")
shift
custom_command=()
if (($# > 0)); then
    [[ $1 == -- ]] || { usage; exit 2; }
    shift
    (($# > 0)) || { usage; exit 2; }
    custom_command=("$@")
fi

mkdir -p "$report_dir"
if [[ -n $(find "$report_dir" -mindepth 1 -maxdepth 1 -print -quit) ]]; then
    printf 'Report directory must be empty: %s\n' "$report_dir" >&2
    exit 2
fi

source "$versions_file"
minio_port=${COSBENCH_MINIO_PORT:-19000}
minio_console_port=${COSBENCH_MINIO_CONSOLE_PORT:-19001}
toxiproxy_admin_port=${COSBENCH_TOXIPROXY_ADMIN_PORT:-18474}
toxiproxy_proxy_port=${COSBENCH_TOXIPROXY_PROXY_PORT:-18666}

for port in "$minio_port" "$minio_console_port" "$toxiproxy_admin_port" "$toxiproxy_proxy_port"; do
    [[ $port =~ ^[0-9]{1,5}$ ]] || { printf 'Invalid port: %s\n' "$port" >&2; exit 2; }
    if ss -H -lnt | awk -v suffix=":$port" '$4 ~ (suffix "$" ) { found=1 } END { exit !found }'; then
        printf 'Port %s is already in use\n' "$port" >&2
        exit 2
    fi
done

sudo docker version > "$report_dir/docker-version.txt"
secret_dir=$(mktemp -d "${TMPDIR:-/tmp}/cosbench-scheme3-02.XXXXXX")
chmod 700 "$secret_dir"
compose_env="$secret_dir/compose.env"
minio_env="$secret_dir/minio.env"
project_name="cosbench-scheme3-02-$(date +%s)-$$"
project_started=0
active_docker_pid=
certificate_containers=("$project_name-keygen" "$project_name-cert-export" "$project_name-cert-inspect")
compose_arguments=(--ansi never --progress plain --project-name "$project_name"
    --env-file "$versions_file" --env-file "$compose_env" -f "$compose_file")

compose() {
    sudo docker compose "${compose_arguments[@]}" "$@"
}

run_docker() {
    sudo docker "$@" &
    active_docker_pid=$!
    docker_status=0
    wait "$active_docker_pid" || docker_status=$?
    active_docker_pid=
    return "$docker_status"
}

run_compose() {
    run_docker compose "${compose_arguments[@]}" "$@"
}

interrupt() {
    trap '' INT TERM
    if [[ -n $active_docker_pid ]]; then
        kill -TERM "$active_docker_pid" 2>/dev/null || true
    fi
    exit "$1"
}

cleanup() {
    run_status=$?
    cleanup_status=0
    trap - EXIT INT TERM
    set +e

    if [[ $project_started == 1 ]]; then
        compose logs --no-color > "$report_dir/docker-compose.log" 2>&1
        if ! compose down --volumes --remove-orphans --timeout 10 > "$report_dir/docker-cleanup.log" 2>&1; then
            cleanup_status=1
        fi

        containers_left=$(sudo docker ps --all --quiet --filter "label=com.docker.compose.project=$project_name" 2>/dev/null) || cleanup_status=1
        networks_left=$(sudo docker network ls -q --filter "label=com.docker.compose.project=$project_name" 2>/dev/null) || cleanup_status=1
        volumes_left=$(sudo docker volume ls -q --filter "label=com.docker.compose.project=$project_name" 2>/dev/null) || cleanup_status=1
        if [[ -n $containers_left || -n $networks_left || -n $volumes_left ]]; then
            cleanup_status=1
        fi
    fi

    for certificate_container in "${certificate_containers[@]}"; do
        if sudo docker container inspect "$certificate_container" >/dev/null 2>&1; then
            sudo docker logs "$certificate_container" >> "$report_dir/certificate-generation.log" 2>&1
            sudo docker container rm --force "$certificate_container" >> "$report_dir/docker-cleanup.log" 2>&1 || cleanup_status=1
        fi
    done
    certificate_containers_left=$(sudo docker ps --all --quiet --filter "label=cosbench.scheme3.run=$project_name" 2>/dev/null) || cleanup_status=1
    [[ -z $certificate_containers_left ]] || cleanup_status=1

    if [[ -n ${secret_dir:-} && -d $secret_dir ]]; then
        find "$secret_dir" -mindepth 1 -delete || cleanup_status=1
        rmdir "$secret_dir" || cleanup_status=1
    fi

    printf 'run_status=%s\ncleanup_status=%s\ncontainers_remaining=%s\nnetworks_remaining=%s\nvolumes_remaining=%s\ncertificate_containers_remaining=%s\n' \
        "$run_status" "$cleanup_status" "${containers_left:-none}" "${networks_left:-none}" "${volumes_left:-none}" "${certificate_containers_left:-none}" \
        > "$report_dir/cleanup-status.txt"
    if ((run_status == 0 && cleanup_status != 0)); then
        run_status=1
    fi
    exit "$run_status"
}

trap cleanup EXIT
trap 'interrupt 130' INT
trap 'interrupt 143' TERM

umask 077
printf '%s\n' 'MINIO_ROOT_USER=cosbench-test-user' 'MINIO_ROOT_PASSWORD=cosbench-test-password' > "$minio_env"
printf '%s' 'cosbench-test-user' > "$secret_dir/minio-root-user"
printf '%s' 'cosbench-test-password' > "$secret_dir/minio-root-password"
printf '%s' 'cosbench-test-keystore-password' > "$secret_dir/http-test-password"
chmod 600 "$minio_env"
chmod 644 "$secret_dir/minio-root-user" "$secret_dir/minio-root-password" "$secret_dir/http-test-password"

cat > "$compose_env" <<EOF
COSBENCH_REPO_ROOT="$repo_root"
COSBENCH_REPORT_DIR="$report_dir"
COSBENCH_SECRET_DIR="$secret_dir"
COSBENCH_MINIO_ENV_FILE="$minio_env"
COSBENCH_MINIO_PORT="$minio_port"
COSBENCH_MINIO_CONSOLE_PORT="$minio_console_port"
COSBENCH_TOXIPROXY_ADMIN_PORT="$toxiproxy_admin_port"
COSBENCH_TOXIPROXY_PROXY_PORT="$toxiproxy_proxy_port"
EOF

run_docker run --rm --name "${certificate_containers[0]}" --label "cosbench.scheme3.run=$project_name" --user "$(id -u):$(id -g)" \
    -v "$secret_dir:/tmp/secrets" --entrypoint /opt/java/openjdk/bin/keytool "$MAVEN_IMAGE" \
    -genkeypair -alias cosbench-test -keyalg RSA -keysize 2048 -validity 365 \
    -dname 'CN=cosbench-scheme3-test,OU=Tests,O=COSBench' \
    -ext 'SAN=dns:localhost,dns:minio,dns:driver,dns:controller,ip:127.0.0.1' \
    -storetype PKCS12 -keystore /tmp/secrets/http-test.p12 \
    -storepass cosbench-test-keystore-password -keypass cosbench-test-keystore-password \
    > "$report_dir/certificate-generation.log" 2>&1
run_docker run --rm --name "${certificate_containers[1]}" --label "cosbench.scheme3.run=$project_name" --user "$(id -u):$(id -g)" \
    -v "$secret_dir:/tmp/secrets" --entrypoint /opt/java/openjdk/bin/keytool "$MAVEN_IMAGE" \
    -exportcert -rfc -alias cosbench-test -keystore /tmp/secrets/http-test.p12 \
    -storetype PKCS12 -storepass cosbench-test-keystore-password \
    -file /tmp/secrets/http-test.crt >> "$report_dir/certificate-generation.log" 2>&1
run_docker run --rm --name "${certificate_containers[2]}" --label "cosbench.scheme3.run=$project_name" --user "$(id -u):$(id -g)" \
    -v "$secret_dir:/tmp/secrets:ro" --entrypoint /opt/java/openjdk/bin/keytool "$MAVEN_IMAGE" \
    -printcert -file /tmp/secrets/http-test.crt > "$report_dir/test-certificate.txt"

project_started=1
run_compose up --detach --wait --wait-timeout 90 minio toxiproxy > "$report_dir/docker-startup.log" 2>&1 || {
    cat "$report_dir/docker-startup.log"
    exit 1
}
compose ps > "$report_dir/container-status.txt"
run_compose run --rm --no-deps minio-smoke > "$report_dir/minio-smoke.log" 2>&1 || {
    cat "$report_dir/minio-smoke.log"
    exit 1
}
run_compose run --rm --no-deps maven /bin/bash /workspace/shadow/harness/verify-toxiproxy.sh \
    > "$report_dir/toxiproxy-controls.log" 2>&1 || {
        cat "$report_dir/toxiproxy-controls.log"
        exit 1
    }
run_compose run --rm --no-deps maven /bin/bash /workspace/shadow/harness/container-build.sh \
    > "$report_dir/container-build.log" 2>&1 || {
        cat "$report_dir/container-build.log"
        exit 1
    }

if ((${#custom_command[@]} > 0)); then
    run_compose run --rm --no-deps maven "${custom_command[@]}" > "$report_dir/custom-test.log" 2>&1 || {
        cat "$report_dir/custom-test.log"
        exit 1
    }
fi

printf 'status=passed\nproject=%s\nminio=http://127.0.0.1:%s\ntoxiproxy_admin=http://127.0.0.1:%s\ntoxiproxy_proxy_port=%s\n' \
    "$project_name" "$minio_port" "$toxiproxy_admin_port" "$toxiproxy_proxy_port" \
    > "$report_dir/run-summary.txt"
