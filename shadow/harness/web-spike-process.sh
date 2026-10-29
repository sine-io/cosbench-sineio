#!/usr/bin/env bash
set -Eeuo pipefail

report_dir=/reports
maven_repo=/tmp/maven-repository
http_dir=$report_dir/web-http
fixture_dir=$report_dir/web-fixtures
artifact=/workspace/shadow/cosbench-web-spike/target/cosbench-web-spike-0.1.0-SNAPSHOT.jar
controller_fixture=/workspace/shadow/cosbench-web-spike/src/test/resources/web-spike-controller.yml
workload_fixture=/workspace/.scratch/scheme3-migration/baseline/fixtures/mock-noop.xml
mkdir -p "$http_dir" "$fixture_dir"

mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
    -pl cosbench-web-spike -am -DskipTests install \
    > "$report_dir/web-install.log" 2>&1 || {
        cat "$report_dir/web-install.log"
        exit 1
    }
mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml -pl cosbench-web-spike \
    -Dscope=test -DoutputFile=$report_dir/web-dependency-tree.txt \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree \
    > $report_dir/web-dependency-tree-command.log 2>&1 || {
        cat $report_dir/web-dependency-tree-command.log
        exit 1
    }

test -s "$artifact"
sha256sum "$artifact" > $report_dir/web-artifact.sha256
stat -c 'artifact=%n\nsize_bytes=%s' "$artifact" > $report_dir/web-artifact.txt
jar tf "$artifact" > $report_dir/web-artifact-contents.txt
grep -q 'BOOT-INF/lib/spring-boot-' $report_dir/web-artifact-contents.txt
grep -q 'BOOT-INF/classes/templates/logon.ftl' $report_dir/web-artifact-contents.txt
grep -q 'BOOT-INF/classes/static/resources/cosbench.css' $report_dir/web-artifact-contents.txt
cp "$controller_fixture" $fixture_dir/controller.yml
cp "$workload_fixture" $fixture_dir/mock-noop.xml
printf '%s\n' \
    'Castor 1.3.3 requires --add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED on Java 21 for workload archive XML serialization.' \
    'The export option is limited to the Web spike until the planned Castor migration.' \
    > $report_dir/jvm-compatibility.txt

runtime_dir=$(mktemp -d /tmp/cosbench-web-process.XXXXXX)
mkdir -p "$runtime_dir/conf"
cp "$controller_fixture" "$runtime_dir/conf/controller.yml"
app_pid=

cleanup() {
    run_status=$?
    process_cleanup_status=0
    trap - EXIT
    set +e
    if [[ -n "$app_pid" ]] && kill -0 "$app_pid" 2>/dev/null; then
        kill -TERM "$app_pid" 2>/dev/null
        for _ in $(seq 1 100); do
            kill -0 "$app_pid" 2>/dev/null || break
            sleep 0.1
        done
        if kill -0 "$app_pid" 2>/dev/null; then
            kill -KILL "$app_pid" 2>/dev/null
            process_cleanup_status=1
        fi
        wait "$app_pid" 2>/dev/null
        app_pid=
    fi
    rm -rf -- "$runtime_dir" || process_cleanup_status=1
    if [[ -d "$runtime_dir" ]]; then runtime_removed=no; else runtime_removed=yes; fi
    printf 'script_status=%s\nprocess_cleanup_status=%s\ntemporary_runtime_removed=%s\n' \
        "$run_status" "$process_cleanup_status" "$runtime_removed" \
        > $report_dir/web-process-cleanup-status.txt
    if ((run_status == 0 && process_cleanup_status != 0)); then exit 1; fi
    exit "$run_status"
}
trap cleanup EXIT

base_url=http://127.0.0.1:19088/controller
port_status() {
    curl --noproxy '*' --silent --output /dev/null --max-time 1 \
        --write-out '%{http_code}' "$base_url/logon.html" || true
}
if [[ $(port_status) != 000 ]]; then
    printf '%s\n' 'Port 19088 is already in use in the Maven container.' >&2
    exit 1
fi
cd "$runtime_dir"
java --add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED \
    -jar "$artifact" --cosbench.config="$runtime_dir/conf/controller.yml" \
    --cosbench.web.username=anonymous --cosbench.web.password=cosbench \
    > $report_dir/web-process.log 2>&1 &
app_pid=$!
cookie_jar=$runtime_dir/session.cookies
for _ in $(seq 1 60); do
    if curl --silent --output /dev/null "$base_url/logon.html"; then break; fi
    sleep 0.5
done

capture() {
    name=$1
    shift
    raw_headers=$runtime_dir/$name.raw-headers
    status=$(curl --silent --show-error --dump-header "$raw_headers" \
        --output "$http_dir/$name.body" --write-out '%{http_code}' "$@")
    printf '%s\n' "$status" > "$http_dir/$name.status"
    sed -E 's/^(Set-Cookie:[[:space:]]*).*/\1<redacted>/I' "$raw_headers" \
        > "$http_dir/$name.headers"
}
assert_status() {
    name=$1
    expected=$2
    actual=$(<"$http_dir/$name.status")
    if [[ $actual != "$expected" ]]; then
        printf '%s expected HTTP %s but got %s\n' "$name" "$expected" "$actual" >&2
        sed -n '1,80p' "$http_dir/$name.body" >&2 || true
        exit 1
    fi
}
assert_contains() {
    name=$1
    value=$2
    grep -aFq "$value" "$http_dir/$name.body" || {
        printf '%s response did not contain %s\n' "$name" "$value" >&2
        exit 1
    }
}

capture protected-index "$base_url/index.html"
assert_status protected-index 302
grep -Eiq '^Location:.*logon[.]html' "$http_dir/protected-index.headers"
capture login-page "$base_url/logon.html"
assert_status login-page 200
assert_contains login-page j_username
capture css "$base_url/resources/cosbench.css"
assert_status css 200
grep -Eiq '^Content-Type:[[:space:]]*text/css' "$http_dir/css.headers"
capture javascript "$base_url/resources/build/dist/echarts.js"
assert_status javascript 200
grep -Eiq '^Content-Type:[[:space:]].*javascript' "$http_dir/javascript.headers"
capture image "$base_url/resources/bg-header.png"
assert_status image 200
grep -Eiq '^Content-Type:[[:space:]]*image/png' "$http_dir/image.headers"
compare_static() {
    name=$1
    baseline=$2
    cmp "$http_dir/$name.body" "$baseline"
    printf '%s source_sha256=%s served_sha256=%s match=yes\n' \
        "$name" "$(sha256sum "$baseline" | awk '{print $1}')" \
        "$(sha256sum "$http_dir/$name.body" | awk '{print $1}')" \
        >> "$report_dir/web-static-baseline-comparison.txt"
}
compare_static css /workspace/dev/cosbench-controller-web/resources/cosbench.css
compare_static javascript /workspace/dev/cosbench-controller-web/resources/build/dist/echarts.js
compare_static image /workspace/dev/cosbench-controller-web/resources/bg-header.png

capture login -c "$cookie_jar" -b "$cookie_jar" \
    --data-urlencode j_username=anonymous --data-urlencode j_password=cosbench \
    "$base_url/j_security_check"
assert_status login 302
grep -Eiq '^Location:.*[/]controller/index.html' "$http_dir/login.headers"
capture authenticated-index -b "$cookie_jar" "$base_url/index.html"
assert_status authenticated-index 200
assert_contains authenticated-index 'Controller Overview'

capture cli-submit -F "config=@$workload_fixture;type=application/xml" \
    "$base_url/cli/submit.action?username=anonymous&password=cosbench"
assert_status cli-submit 200
grep -Eq '^Accepted with ID: w[0-9]+$' "$http_dir/cli-submit.body"
workload_id=$(sed -n 's/^Accepted with ID: //p' "$http_dir/cli-submit.body")
for _ in $(seq 1 40); do
    capture download-config -b "$cookie_jar" "$base_url/download-config.do?id=$workload_id"
    [[ $(<"$http_dir/download-config.status") == 200 ]] && break
    sleep 0.25
done
assert_status download-config 200
grep -Eiq '^Content-Disposition:.*workload-config[.]xml' "$http_dir/download-config.headers"
grep -Fq baseline-noop "$http_dir/download-config.body"
capture download-log -b "$cookie_jar" "$base_url/download-log.do?id=$workload_id"
assert_status download-log 200
grep -Eiq '^Content-Disposition:.*workload-log[.]txt' "$http_dir/download-log.headers"
test -s "$http_dir/download-log.body"

kill -TERM "$app_pid"
set +e
wait "$app_pid"
process_exit_status=$?
set -e
app_pid=
printf 'java_process_exit_status=%s\n' "$process_exit_status" > $report_dir/web-process-stop.txt
for _ in $(seq 1 40); do
    if [[ $(port_status) == 000 ]]; then break; fi
    sleep 0.25
done
if [[ $(port_status) != 000 ]]; then
    printf '%s\n' 'Port 19088 remained open after TERM.' >&2
    exit 1
fi
printf '%s\n' 'port_19088_released=true' >> $report_dir/web-process-stop.txt
