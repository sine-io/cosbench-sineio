#!/usr/bin/env bash
set -Eeuo pipefail

# Replay the production executable, with no test classes or service probe on its classpath.
report_dir=/reports/controller-process
artifact=/workspace/shadow/cosbench-controller-app/target/cosbench-controller-app-0.1.0-SNAPSHOT.jar
runtime_dir=$(mktemp -d /tmp/cosbench-controller-process.XXXXXX)
controller_pid=
mkdir -p "$report_dir" "$runtime_dir/conf" "$runtime_dir/launch" "$runtime_dir/workspace"

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    if [[ -n $controller_pid ]] && kill -0 "$controller_pid" 2>/dev/null; then
        kill -TERM "$controller_pid" 2>/dev/null || true
        for ((attempt = 0; attempt < 80; attempt++)); do
            kill -0 "$controller_pid" 2>/dev/null || break
            sleep 0.1
        done
        if kill -0 "$controller_pid" 2>/dev/null; then
            kill -KILL "$controller_pid" 2>/dev/null || true
            status=1
        fi
        wait "$controller_pid" 2>/dev/null || true
    fi
    cp -R "$runtime_dir/conf" "$report_dir/fixtures"
    [[ ! -d $runtime_dir/log ]] || cp -R "$runtime_dir/log" "$report_dir/business-logs"
    rm -rf -- "$runtime_dir"
    printf 'replay_status=%s\nprocesses_remaining=none\n' "$status" > "$report_dir/cleanup.txt"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

test -s "$artifact"
sha256sum "$artifact" > "$report_dir/artifact.sha256"
jar tf "$artifact" > "$report_dir/jar-contents.txt"
grep -q 'BOOT-INF/lib/cosbench-controller-shadow-' "$report_dir/jar-contents.txt"
if grep -Eq 'testing/controller|ControllerProcessProbe|build-helper|equinox|spring-osgi|tomcat-6' "$report_dir/jar-contents.txt"; then
    printf '%s\n' 'Unexpected test/legacy runtime in Controller executable' >&2
    exit 1
fi

cp -R /workspace/shadow/fixtures/archive "$runtime_dir/archive"
printf '%s\n' 'existing workspace remains in place' > "$runtime_dir/workspace/existing.txt"
(
    cd "$runtime_dir"
    find archive workspace -type f -exec sha256sum '{}' + | sort
) > "$report_dir/existing-before.sha256"
cat > "$runtime_dir/conf/controller.yml" <<'YAML'
schemaVersion: 1
role: controller
name: Controller executable replay
listen:
  host: 127.0.0.1
  port: 19488
  contextPath: /controller
paths:
  workspace: workspace
  archive: archive
logging:
  file: log/system.log
shutdown:
  gracePeriod: 5s
YAML
cat > "$runtime_dir/conf/cosbench-users.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<tomcat-users>
  <role rolename="cosbench" />
  <role rolename="cli" />
  <user username="operator" password="operator-secret" roles="cosbench" />
  <user username="cli-only" password="cli-secret" roles="cli" />
</tomcat-users>
XML

java_command=(java --add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED
    -Dcosbench.web.cosbenchUsers="$runtime_dir/conf/cosbench-users.xml" -jar "$artifact")
expect_failure() {
    local name=$1 diagnostic=$2
    shift 2
    local status=0
    (cd "$runtime_dir/launch" && env -u COSBENCH_CONFIG timeout 15s "${java_command[@]}" "$@") \
        > "$report_dir/$name.log" 2>&1 || status=$?
    [[ $status == 1 ]]
    grep -q "$diagnostic" "$report_dir/$name.log"
    printf '%s_exit=%s\n' "$name" "$status" >> "$report_dir/results.txt"
}

: > "$report_dir/results.txt"
expect_failure missing-default DEFAULT_FILE_MISSING
expect_failure missing-explicit EXPLICIT_FILE_MISSING --cosbench.config="$runtime_dir/missing.yml"
: > "$runtime_dir/conf/empty.yml"
expect_failure empty EMPTY_DOCUMENT --cosbench.config="$runtime_dir/conf/empty.yml"

start_controller() {
    local name=$1
    (
        cd "$runtime_dir/launch"
        # The explicit YAML wins over both an invalid environment path and Boot port properties.
        export COSBENCH_CONFIG="$runtime_dir/missing-environment.yml"
        exec "${java_command[@]}" --cosbench.config="$runtime_dir/conf/controller.yml" --server.port=19489
    ) > "$report_dir/$name.log" 2>&1 &
    controller_pid=$!
    for ((attempt = 0; attempt < 200; attempt++)); do
        if curl --silent --fail --max-time 1 http://127.0.0.1:19488/controller/health \
            > "$report_dir/$name-health.json"; then
            grep -q '"accepting":true' "$report_dir/$name-health.json"
            return
        fi
        kill -0 "$controller_pid" 2>/dev/null || { cat "$report_dir/$name.log"; return 1; }
        sleep 0.1
    done
    cat "$report_dir/$name.log"
    return 1
}

stop_controller() {
    local name=$1 status=0
    local stopping=$SECONDS
    kill -TERM "$controller_pid"
    for ((attempt = 0; attempt < 70; attempt++)); do
        kill -0 "$controller_pid" 2>/dev/null || break
        sleep 0.1
    done
    if kill -0 "$controller_pid" 2>/dev/null; then
        printf '%s\n' 'Controller did not stop within the configured grace period plus process overhead' >&2
        return 1
    fi
    wait "$controller_pid" || status=$?
    controller_pid=
    [[ $status == 143 ]]
    grep -q 'Controller stopped: executor terminated, heartbeat terminated' "$report_dir/$name.log"
    printf '%s_term_exit=%s\n%s_stop_seconds=%s\n' "$name" "$status" "$name" "$((SECONDS - stopping))" \
        >> "$report_dir/results.txt"
    if curl --silent --fail --max-time 1 http://127.0.0.1:19488/controller/health >/dev/null; then
        printf '%s\n' 'Controller listener survived TERM' >&2
        return 1
    fi
}

start_controller startup
web_cookie_jar="$runtime_dir/web.cookies"
unauthenticated_page_status=$(curl --silent --show-error --max-time 2 \
    --dump-header "$report_dir/web-unauthenticated.headers" --output /dev/null \
    --write-out '%{http_code}' http://127.0.0.1:19488/controller/index.html)
[[ $unauthenticated_page_status == 302 ]]
grep -qi '^Location: .*/controller/logon.html' "$report_dir/web-unauthenticated.headers"
curl --silent --show-error --fail --max-time 2 \
    http://127.0.0.1:19488/controller/logon.html > "$report_dir/web-logon.html"
grep -q 'j_username' "$report_dir/web-logon.html"
login_status=$(curl --silent --show-error --max-time 2 \
    --cookie-jar "$web_cookie_jar" --output "$report_dir/web-login-response.txt" \
    --write-out '%{http_code}' --data 'j_username=operator&j_password=operator-secret' \
    http://127.0.0.1:19488/controller/j_security_check)
[[ $login_status == 302 ]]
for page in index matrix submit config advanced-config; do
    curl --silent --show-error --fail --max-time 2 --cookie "$web_cookie_jar" \
        "http://127.0.0.1:19488/controller/$page.html" > "$report_dir/web-$page.html"
done
grep -q 'Controller Overview' "$report_dir/web-index.html"
grep -q 'action="submit-workload.do"' "$report_dir/web-submit.html"
grep -q 'action="config-workload.do"' "$report_dir/web-config.html"
grep -q 'action="advanced-config-workload.do"' "$report_dir/web-advanced-config.html"
curl --silent --show-error --fail --max-time 2 \
    http://127.0.0.1:19488/controller/resources/cosbench.css > "$report_dir/web-cosbench.css"
curl --silent --show-error --fail --max-time 2 \
    http://127.0.0.1:19488/controller/favicon.ico > "$report_dir/web-favicon.ico"
curl --silent --show-error --fail --max-time 2 \
    'http://127.0.0.1:19488/controller/cli/index.action?username=operator&password=operator-secret' \
    > "$report_dir/web-cli-index.txt"
grep -q 'Drivers:' "$report_dir/web-cli-index.txt"
curl --silent --show-error --fail --max-time 2 \
    'http://127.0.0.1:19488/controller/cli/index.action?username=cli-only&password=cli-secret' \
    > "$report_dir/web-cli-only-index.txt"
grep -q 'Drivers:' "$report_dir/web-cli-only-index.txt"
curl --silent --show-error --max-time 2 --output "$report_dir/web-default-user-cli.txt" \
    'http://127.0.0.1:19488/controller/cli/index.action?username=anonymous&password=cosbench'
grep -q 'User info \[username:password\] incorrect !' "$report_dir/web-default-user-cli.txt"
cli_only_login_status=$(curl --silent --show-error --max-time 2 \
    --cookie-jar "$runtime_dir/cli-only.cookies" --output /dev/null --write-out '%{http_code}' \
    --data 'j_username=cli-only&j_password=cli-secret' \
    http://127.0.0.1:19488/controller/j_security_check)
[[ $cli_only_login_status == 302 ]]
cli_only_page_status=$(curl --silent --show-error --max-time 2 \
    --cookie "$runtime_dir/cli-only.cookies" --output /dev/null --write-out '%{http_code}' \
    http://127.0.0.1:19488/controller/index.html)
[[ $cli_only_page_status == 302 ]]
default_login_status=$(curl --silent --show-error --max-time 2 \
    --cookie-jar "$runtime_dir/default-user.cookies" --output /dev/null --write-out '%{http_code}' \
    --data 'j_username=anonymous&j_password=cosbench' \
    http://127.0.0.1:19488/controller/j_security_check)
[[ $default_login_status == 302 ]]
default_page_status=$(curl --silent --show-error --max-time 2 \
    --cookie "$runtime_dir/default-user.cookies" --output /dev/null --write-out '%{http_code}' \
    http://127.0.0.1:19488/controller/index.html)
[[ $default_page_status == 302 ]]
expect_failure port-conflict 'Port 19488 was already in use' --cosbench.config="$runtime_dir/conf/controller.yml"
grep -q 'Controller stopped: executor terminated, heartbeat terminated' "$report_dir/port-conflict.log"
curl --silent --fail --max-time 2 http://127.0.0.1:19488/controller/health > "$report_dir/after-conflict-health.json"
stop_controller startup
start_controller restart
stop_controller restart
test -d "$runtime_dir/log"
test ! -e "$runtime_dir/launch/archive"
test ! -e "$runtime_dir/launch/workspace"
(
    cd "$runtime_dir"
    find archive workspace -type f -exec sha256sum '{}' + | sort
) > "$report_dir/existing-after.sha256"
diff -u "$report_dir/existing-before.sha256" "$report_dir/existing-after.sha256"
printf 'status=passed\nhealth=real-controller\nconfig-precedence=passed\nport-conflict=diagnosed\nrestart-and-port-release=passed\nexisting-data=unchanged\nweb=packaged-pages-resources-and-multi-user-roles\n' \
    > "$report_dir/summary.txt"
