#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
    cat <<'EOF'
Usage: bash dev/cosbench-dev.sh [action] [service] [options]

Actions:
  start       Build and start the selected service(s) (default)
  stop        Stop the selected service(s)
  restart     Stop, build, and start the selected service(s)

Services:
  all         Controller and Driver (default)
  controller  Controller only
  driver      Driver only

Options:
  --no-build       Reuse the last compiled bundle output
  --reset-runtime  Recreate out/cosbench-dev before starting
  -h, --help       Show this help

Examples:
  bash dev/cosbench-dev.sh --reset-runtime
  bash dev/cosbench-dev.sh restart controller
  bash dev/cosbench-dev.sh start driver --no-build
  bash dev/cosbench-dev.sh stop
EOF
}

die() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
RUNTIME="$ROOT/out/cosbench-dev"
PID_DIR="$RUNTIME/pids"

ACTION="start"
SERVICE="all"
NO_BUILD=0
RESET_RUNTIME=0

is_action() {
    case "$1" in
        start|stop|restart) return 0 ;;
        *) return 1 ;;
    esac
}

is_service() {
    case "$1" in
        all|controller|driver) return 0 ;;
        *) return 1 ;;
    esac
}

while (($# > 0)); do
    case "$1" in
        start|stop|restart)
            [[ "$ACTION" == "start" ]] || die "action was specified more than once"
            ACTION="$1"
            ;;
        all|controller|driver)
            [[ "$SERVICE" == "all" ]] || die "service was specified more than once"
            SERVICE="$1"
            ;;
        --action)
            (($# >= 2)) || die '--action requires start, stop, or restart'
            is_action "$2" || die "unknown action: $2"
            ACTION="$2"
            shift
            ;;
        --action=*)
            ACTION="${1#*=}"
            is_action "$ACTION" || die "unknown action: $ACTION"
            ;;
        --service)
            (($# >= 2)) || die '--service requires all, controller, or driver'
            is_service "$2" || die "unknown service: $2"
            SERVICE="$2"
            shift
            ;;
        --service=*)
            SERVICE="${1#*=}"
            is_service "$SERVICE" || die "unknown service: $SERVICE"
            ;;
        --no-build)
            NO_BUILD=1
            ;;
        --reset-runtime)
            RESET_RUNTIME=1
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown argument: $1"
            ;;
    esac
    shift
done

JAVA_BIN=""
JAVA_HOME_DIR=""
MAVEN_BIN=""

find_java8() {
    local candidate home version
    local -a candidates=()

    if [[ -n "${JAVA_HOME:-}" ]]; then
        candidates+=("$JAVA_HOME/bin/java")
    fi
    if command -v java >/dev/null 2>&1; then
        candidates+=("$(command -v java)")
    fi

    for candidate in "${candidates[@]}"; do
        [[ -x "$candidate" ]] || continue
        version="$("$candidate" -version 2>&1 || true)"
        if grep -Eq 'version "1\.8\.' <<<"$version"; then
            home="$(cd -- "$(dirname -- "$candidate")/.." && pwd)"
            [[ -x "$home/bin/javac" ]] || die "JDK 8 compiler not found under $home"
            JAVA_BIN="$candidate"
            JAVA_HOME_DIR="$home"
            return 0
        fi
    done

    die 'JDK 8 is required. Set JAVA_HOME to a JDK 8 installation.'
}

find_maven() {
    if [[ -n "${MAVEN_HOME:-}" && -x "$MAVEN_HOME/bin/mvn" ]]; then
        MAVEN_BIN="$MAVEN_HOME/bin/mvn"
    elif command -v mvn >/dev/null 2>&1; then
        MAVEN_BIN="$(command -v mvn)"
    else
        die 'Maven is required. Install Maven 3.9.x or set MAVEN_HOME.'
    fi
}

pid_file() {
    printf '%s/%s.pid' "$PID_DIR" "$1"
}

is_cosbench_process() {
    local process_id="$1"
    [[ -r "/proc/$process_id/cmdline" ]] || return 1
    tr '\0' ' ' <"/proc/$process_id/cmdline" | grep -q 'org.eclipse.equinox.launcher.Main'
}

stop_service_process() {
    local name="$1" pid_file_path process_id attempt
    pid_file_path="$(pid_file "$name")"
    [[ -f "$pid_file_path" ]] || return 0

    process_id="$(tr -d '[:space:]' <"$pid_file_path")"
    rm -f -- "$pid_file_path"
    [[ "$process_id" =~ ^[0-9]+$ ]] || return 0
    kill -0 "$process_id" 2>/dev/null || return 0
    is_cosbench_process "$process_id" || die "PID $process_id is not a COSBench Equinox process"

    kill "$process_id" 2>/dev/null || true
    for ((attempt = 0; attempt < 30; attempt++)); do
        kill -0 "$process_id" 2>/dev/null || return 0
        sleep 1
    done

    kill -9 "$process_id" 2>/dev/null || true
}

stop_selected_services() {
    if [[ "$SERVICE" == controller || "$SERVICE" == all ]]; then
        stop_service_process controller
    fi
    if [[ "$SERVICE" == driver || "$SERVICE" == all ]]; then
        stop_service_process driver
    fi
}

build_bundles() {
    find_java8
    find_maven
    export JAVA_HOME="$JAVA_HOME_DIR"
    printf 'Building OSGi bundles with %s ...\n' "$JAVA_BIN"
    "$MAVEN_BIN" -B -ntp -f "$ROOT/pom.xml" package
}

sync_runtime() {
    local source directory plugin_directory bundle_count

    if ((RESET_RUNTIME)); then
        [[ "$RUNTIME" == "$ROOT/out/cosbench-dev" ]] || die "refusing to remove unexpected runtime: $RUNTIME"
        rm -rf -- "$RUNTIME"
    fi

    mkdir -p -- "$RUNTIME" "$PID_DIR"
    for directory in main osgi; do
        source="$ROOT/dist/$directory"
        [[ -d "$source" ]] || die "missing runtime directory: $source"
        rm -rf -- "$RUNTIME/$directory"
        cp -a -- "$source" "$RUNTIME/$directory"
    done

    rm -rf -- "$RUNTIME/conf"
    cp -a -- "$ROOT/release/conf" "$RUNTIME/conf"
    mkdir -p -- "$RUNTIME/log" "$RUNTIME/archive"

    plugin_directory="$RUNTIME/osgi/plugins"
    find "$plugin_directory" -maxdepth 1 -type f -name 'cosbench-*.jar' -delete
    mapfile -t bundles < <(find "$ROOT/dev" -type f -path '*/target/cosbench-*.jar' -print | sort)
    bundle_count="${#bundles[@]}"
    [[ "$bundle_count" -eq 30 ]] || die "expected 30 built COSBench bundles, found $bundle_count; run the build first"
    for bundle in "${bundles[@]}"; do
        cp -f -- "$bundle" "$plugin_directory/"
    done
}

wait_for_http() {
    local name="$1" port="$2" process_id="$3" attempt url
    url="http://127.0.0.1:$port/$name/index.html"
    for ((attempt = 0; attempt < 60; attempt++)); do
        kill -0 "$process_id" 2>/dev/null || die "$name exited while waiting for $url; check $RUNTIME/log"
        if command -v curl >/dev/null 2>&1; then
            if curl --fail --silent --show-error --max-time 2 -o /dev/null "$url" 2>/dev/null; then
                return 0
            fi
        elif (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
            exec 3>&-
            return 0
        fi
        sleep 1
    done
    die "$name did not become ready at $url within 60 seconds; check $RUNTIME/log"
}

start_service_process() {
    local name="$1" tomcat_config="$2" configuration="$3" console_port="$4" web_port="$5"
    local stdout stderr process_id
    find_java8
    stdout="$RUNTIME/log/$name.stdout.log"
    stderr="$RUNTIME/log/$name.stderr.log"

    (
        cd -- "$RUNTIME"
        nohup "$JAVA_BIN" \
            "-Dcosbench.tomcat.config=$tomcat_config" \
            -server \
            -cp 'main/*' \
            org.eclipse.equinox.launcher.Main \
            -configuration "$configuration" \
            -console "$console_port" \
            >"$stdout" 2>"$stderr" < /dev/null &
        printf '%s\n' "$!" >"$(pid_file "$name")"
    )
    process_id="$(tr -d '[:space:]' <"$(pid_file "$name")")"
    wait_for_http "$name" "$web_port" "$process_id"
    printf '%s is running at http://127.0.0.1:%s/%s/index.html\n' "$name" "$web_port" "$name"
}

if [[ "$ACTION" == stop || "$ACTION" == restart ]]; then
    stop_selected_services
    if [[ "$ACTION" == stop ]]; then
        printf 'COSBench development services stopped.\n'
        exit 0
    fi
fi

if ((RESET_RUNTIME)) && [[ "$ACTION" == start ]]; then
    stop_selected_services
fi

if ((NO_BUILD == 0)); then
    build_bundles
fi

sync_runtime

if [[ "$SERVICE" == driver || "$SERVICE" == all ]]; then
    start_service_process driver conf/driver-tomcat-server.xml conf/.driver 18089 18088
fi
if [[ "$SERVICE" == controller || "$SERVICE" == all ]]; then
    start_service_process controller conf/controller-tomcat-server.xml conf/.controller 19089 19088
fi

printf 'Runtime directory: %s\n' "$RUNTIME"
printf 'Use "bash dev/cosbench-dev.sh stop" to stop the selected service(s).\n'
