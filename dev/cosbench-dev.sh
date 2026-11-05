#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
    cat <<'EOF'
Usage: bash dev/cosbench-dev.sh [action] [service] [options]

Actions:
  start       Build and start selected service(s) (default)
  stop        Stop selected service(s)
  restart     Stop, build, and start selected service(s)

Services:
  all         Controller and all configured Drivers (default)
  controller  Controller only
  driver      Drivers only

Options:
  --drivers N      Number of Driver instances to configure on first start (default: 1)
  --no-build       Reuse the latest Java 21 executable JARs
  --reset-runtime  Recreate out/cosbench-dev before starting
  -h, --help       Show this help

Examples:
  bash dev/cosbench-dev.sh --drivers 2
  bash dev/cosbench-dev.sh restart controller
  bash dev/cosbench-dev.sh start driver --drivers 2 --no-build
  bash dev/cosbench-dev.sh stop
EOF
}

die() {
    printf 'Error: %s\n' "$*" >&2
    exit 2
}

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
RUNTIME=${COSBENCH_DEV_RUNTIME:-$ROOT/out/cosbench-dev}
PID_DIR="$RUNTIME/pids"
ACTION=start
SERVICE=all
DRIVERS=1
DRIVERS_SET=0
NO_BUILD=0
RESET_RUNTIME=0
ACTION_SET=0
SERVICE_SET=0

while (($# > 0)); do
    case "$1" in
        start|stop|restart)
            ((ACTION_SET == 0)) || die 'action was specified more than once'
            ACTION=$1
            ACTION_SET=1
            ;;
        all|controller|driver)
            ((SERVICE_SET == 0)) || die 'service was specified more than once'
            SERVICE=$1
            SERVICE_SET=1
            ;;
        --drivers)
            (($# >= 2)) || die '--drivers requires a count'
            DRIVERS=$2
            DRIVERS_SET=1
            shift
            ;;
        --drivers=*) DRIVERS=${1#*=}; DRIVERS_SET=1 ;;
        --no-build) NO_BUILD=1 ;;
        --reset-runtime) RESET_RUNTIME=1 ;;
        -h|--help)
            usage
            exit 0
            ;;
        *) die "unknown argument: $1" ;;
    esac
    shift
done

[[ $DRIVERS =~ ^[1-9][0-9]*$ && $DRIVERS -le 32 ]] || die '--drivers must be between 1 and 32'

JAVA_BIN=${JAVA:-java}
find_java21() {
    command -v "$JAVA_BIN" >/dev/null 2>&1 || die "Java 21 is required; executable not found: $JAVA_BIN"
    local version
    version=$("$JAVA_BIN" -version 2>&1 || true)
    grep -Eq 'version "21([.]|"|\+)' <<<"$version" || die 'Java 21 is required to run COSBench'
    JAVA_BIN=$(command -v "$JAVA_BIN")
    JAVA_HOME_DIR=$(cd -- "$(dirname -- "$JAVA_BIN")/.." && pwd)
    KEYTOOL="$JAVA_HOME_DIR/bin/keytool"
    [[ -x $KEYTOOL ]] || die "JDK keytool not found under $JAVA_HOME_DIR"
}

build_shadow() {
    local uid_gid maven_repository image
    source "$ROOT/shadow/harness/versions.env"
    image=$MAVEN_IMAGE
    maven_repository="$RUNTIME/maven-repository"
    mkdir -p -- "$maven_repository"
    uid_gid="$(id -u):$(id -g)"
    printf 'Building Java 21 applications in %s ...\n' "$image"
    sudo docker run --rm --user "$uid_gid" \
        -e HOME=/tmp/cosbench-maven-home \
        -v "$ROOT:/workspace" \
        -v "$maven_repository:/maven-repository" \
        -w /workspace "$image" \
        mvn -B -ntp -Dmaven.repo.local=/maven-repository -f shadow/pom.xml -DskipTests package
}

operator_jar="$ROOT/shadow/cosbench-operator-cli/target/cosbench-operator-cli-0.1.0-SNAPSHOT.jar"
controller_jar="$ROOT/shadow/cosbench-controller-app/target/cosbench-controller-app-0.1.0-SNAPSHOT.jar"
driver_jar="$ROOT/shadow/cosbench-driver-app/target/cosbench-driver-app-0.1.0-SNAPSHOT.jar"

stop_selected() {
    COSBENCH_PID_DIR="$PID_DIR" "$ROOT/bin/cosbench-stop" "$SERVICE"
}

if [[ $ACTION == stop || $ACTION == restart ]]; then
    if [[ -d $PID_DIR ]]; then
        stop_selected
    fi
    if [[ $ACTION == stop ]]; then
        printf 'COSBench development services stopped.\n'
        exit 0
    fi
fi

if ((RESET_RUNTIME)); then
    if [[ -d $PID_DIR ]]; then
        stop_selected
    fi
    [[ $RUNTIME == "$ROOT/out/cosbench-dev" ]] || die "refusing to remove unexpected runtime: $RUNTIME"
    rm -rf -- "$RUNTIME"
fi
mkdir -p -- "$RUNTIME/conf" "$RUNTIME/log" "$RUNTIME/archive" "$RUNTIME/workspace" \
    "$RUNTIME/scripts" "$PID_DIR"

if ((DRIVERS_SET == 0)); then
    shopt -s nullglob
    configured_drivers=("$RUNTIME/conf"/driver-[0-9]*.yml)
    if ((${#configured_drivers[@]} > 0)); then
        DRIVERS=${#configured_drivers[@]}
    fi
fi

if ((NO_BUILD == 0)); then
    build_shadow
fi
[[ -f $operator_jar && -f $controller_jar && -f $driver_jar ]] || die 'build the Java 21 shadow applications first'
find_java21

generate_certificates() {
    local password=$1
    local server_store="$RUNTIME/conf/dev-server.p12"
    local trust_store="$RUNTIME/conf/dev-trust.p12"
    local certificate="$RUNTIME/conf/dev-server.crt"
    if [[ ! -f $server_store ]]; then
        "$KEYTOOL" -genkeypair -alias cosbench-dev -keyalg RSA -keysize 2048 -validity 365 \
            -dname 'CN=localhost,OU=Development,O=COSBench' \
            -ext 'SAN=dns:localhost,ip:127.0.0.1' \
            -storetype PKCS12 -keystore "$server_store" -storepass "$password" -keypass "$password" \
            >"$RUNTIME/log/keytool.log" 2>&1
    fi
    if [[ ! -f $trust_store ]]; then
        "$KEYTOOL" -exportcert -rfc -alias cosbench-dev -keystore "$server_store" \
            -storetype PKCS12 -storepass "$password" -file "$certificate" \
            >>"$RUNTIME/log/keytool.log" 2>&1
        "$KEYTOOL" -importcert -noprompt -alias cosbench-dev -file "$certificate" \
            -keystore "$trust_store" -storetype PKCS12 -storepass "$password" \
            >>"$RUNTIME/log/keytool.log" 2>&1
    fi
}

initialize_configs() {
    local password=${COSBENCH_TLS_STORE_PASSWORD:-cosbench-dev-only}
    generate_certificates "$password"
    if [[ ! -f $RUNTIME/conf/driver-1.yml && ! -f $RUNTIME/conf/driver.yml ]]; then
        "$JAVA_BIN" -jar "$operator_jar" expand-drivers \
            --config "$ROOT/shadow/fixtures/config/dev-driver.yml" \
            --output-dir "$RUNTIME/conf" --count "$DRIVERS" \
            >"$RUNTIME/log/driver-config-generation.log" 2>&1
    elif [[ ! -f $RUNTIME/conf/driver-1.yml && -f $RUNTIME/conf/driver.yml ]]; then
        cp -- "$RUNTIME/conf/driver.yml" "$RUNTIME/conf/driver-1.yml"
    fi
    if [[ ! -f $RUNTIME/conf/controller.yml ]]; then
        "$JAVA_BIN" -jar "$operator_jar" bind-controller \
            --config "$ROOT/shadow/fixtures/config/dev-controller.yml" \
            --drivers-dir "$RUNTIME/conf" --count "$DRIVERS" \
            --output "$RUNTIME/conf/controller.yml" \
            >"$RUNTIME/log/controller-config-generation.log" 2>&1
    fi
}

export COSBENCH_SERVICE_TOKEN=${COSBENCH_SERVICE_TOKEN:-cosbench-dev-service-token}
export COSBENCH_TLS_STORE_PASSWORD=${COSBENCH_TLS_STORE_PASSWORD:-cosbench-dev-only}
initialize_configs

wait_for_http() {
    local service_name=$1 url=$2 pid=$3 certificate=${4:-}
    local -a curl_args=(--fail --silent --show-error --max-time 2)
    [[ -z $certificate ]] || curl_args+=(--cacert "$certificate")
    for ((attempt = 0; attempt < 60; attempt++)); do
        kill -0 "$pid" 2>/dev/null || { printf '%s exited before becoming healthy; check %s/log\n' "$service_name" "$RUNTIME" >&2; return 1; }
        if curl "${curl_args[@]}" -o /dev/null "$url" 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    printf '%s did not become healthy at %s within 60 seconds\n' "$service_name" "$url" >&2
    return 1
}

start_driver() {
    local index=$1 config="$RUNTIME/conf/driver-$index.yml" pid_file="$PID_DIR/driver-$index.pid"
    [[ -f $config ]] || die "missing Driver instance config: $config"
    local port=$((18088 + index - 1))
    SERVER_SSL_KEY_STORE_PASSWORD="$COSBENCH_TLS_STORE_PASSWORD" COSBENCH_DRIVER_JAR="$driver_jar" "$ROOT/bin/cosbench-driver" \
        --config "$config" --pid-file "$pid_file" \
        >"$RUNTIME/log/driver-$index.stdout.log" 2>&1 &
    local pid=$!
    wait_for_http "Driver $index" "https://127.0.0.1:$port/driver/health" "$pid" "$RUNTIME/conf/dev-server.crt" || {
        COSBENCH_PID_DIR="$PID_DIR" "$ROOT/bin/cosbench-stop" driver --instance "$index" || true
        return 1
    }
    printf 'Driver %s is healthy at https://127.0.0.1:%s/driver\n' "$index" "$port"
}

start_controller() {
    local pid_file="$PID_DIR/controller.pid"
    COSBENCH_CONTROLLER_JAR="$controller_jar" "$ROOT/bin/cosbench-controller" \
        --config "$RUNTIME/conf/controller.yml" --pid-file "$pid_file" \
        >"$RUNTIME/log/controller.stdout.log" 2>&1 &
    local pid=$!
    wait_for_http Controller http://127.0.0.1:19088/controller/health "$pid" || {
        COSBENCH_PID_DIR="$PID_DIR" "$ROOT/bin/cosbench-stop" controller || true
        return 1
    }
    printf 'Controller is healthy at http://127.0.0.1:19088/controller\n'
}

if [[ $SERVICE == all || $SERVICE == driver ]]; then
    for ((index = 1; index <= DRIVERS; index++)); do
        start_driver "$index"
    done
fi
if [[ $SERVICE == all || $SERVICE == controller ]]; then
    start_controller
fi

printf 'Runtime directory: %s\n' "$RUNTIME"
printf 'Use "bash dev/cosbench-dev.sh stop %s" to stop the selected service(s).\n' "$SERVICE"
