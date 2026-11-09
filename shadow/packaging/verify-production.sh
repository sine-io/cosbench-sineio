#!/usr/bin/env bash
set -Eeuo pipefail

LC_ALL=C
export LC_ALL
umask 022

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/../.." && pwd)
archive=
package_dir=
report_dir=
build_images=0
process_probe=1
rebuild=1
dependency_audit=1
source_date_epoch=

usage() {
    cat <<'EOF'
Usage: verify-production.sh [options]

Verify a Java 21 production archive built by build-production.sh.

Options:
  --archive PATH          Archive to verify (defaults to the only archive in out/cosbench-production)
  --package-dir DIR       Existing extracted package to verify alongside --archive
  --report-dir DIR        Write a compact verification report to DIR
  --build-images          Build and scan the production and separate development images
  --skip-dependency-audit Do not run final nested-closure dependency gates
  --skip-process-probe    Do not run the Java 21 empty-install health/stop probe
  --skip-rebuild          Do not rebuild and compare the deterministic archive
  -h, --help              Show this help
EOF
}

die() {
    printf 'production verification: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "required command is missing: $1"
}

while (($# > 0)); do
    case "$1" in
        --archive)
            (($# >= 2)) || die '--archive requires a path'
            archive=$2
            shift
            ;;
        --archive=*) archive=${1#*=} ;;
        --package-dir)
            (($# >= 2)) || die '--package-dir requires a path'
            package_dir=$2
            shift
            ;;
        --package-dir=*) package_dir=${1#*=} ;;
        --report-dir)
            (($# >= 2)) || die '--report-dir requires a path'
            report_dir=$2
            shift
            ;;
        --report-dir=*) report_dir=${1#*=} ;;
        --build-images) build_images=1 ;;
        --skip-dependency-audit) dependency_audit=0 ;;
        --skip-process-probe) process_probe=0 ;;
        --skip-rebuild) rebuild=0 ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
    shift
done

for command_name in awk cmp cp curl find grep gzip mktemp realpath rm sed sha256sum sort stat tar; do
    require_command "$command_name"
done
if ! command -v unzip >/dev/null 2>&1 && ! command -v jar >/dev/null 2>&1; then
    die 'required command is missing: unzip or the JDK jar tool'
fi

if [[ -z $archive ]]; then
    mapfile -t archives < <(find "$repo_root/out/cosbench-production" -maxdepth 1 -type f -name '*.tar.gz' -print 2>/dev/null | sort)
    ((${#archives[@]} == 1)) || die 'pass --archive when the output directory does not contain exactly one archive'
    archive=${archives[0]}
fi
archive=$(realpath -e -- "$archive")
[[ -f $archive ]] || die "archive is not a regular file: $archive"

if [[ -n $package_dir ]]; then
    package_dir=$(realpath -e -- "$package_dir")
    [[ -d $package_dir ]] || die "package directory is not a directory: $package_dir"
fi

maven_image=${MAVEN_IMAGE:-maven:3.10.0-eclipse-temurin-21@sha256:d2230484f42a845db4a2ae5c4b4b2ba474636bc9c17233917f95ddf2110ab06d}
if [[ -f "$repo_root/shadow/harness/versions.env" ]]; then
    source "$repo_root/shadow/harness/versions.env"
    maven_image=${MAVEN_IMAGE:-$maven_image}
fi

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/cosbench-production-verify.XXXXXX")
declare -a probe_containers=()
declare -a image_tags=()
declare -a image_containers=()
cleanup() {
    local status=$?
    trap - EXIT INT TERM
    set +e
    for container in "${probe_containers[@]}"; do
        sudo docker rm --force "$container" >/dev/null 2>&1 || true
    done
    for container in "${image_containers[@]}"; do
        sudo docker rm --force "$container" >/dev/null 2>&1 || true
    done
    for image in "${image_tags[@]}"; do
        sudo docker image rm --force "$image" >/dev/null 2>&1 || true
    done
    if [[ -n $report_dir ]]; then
        mkdir -p -- "$report_dir"
        if [[ -d $work_dir ]]; then
            cp -a "$work_dir/." "$report_dir/work" 2>/dev/null || true
        fi
        printf 'status=%s\narchive=%s\npackage=%s\nimages=%s\nprocess_probe=%s\nrebuild=%s\ndependency_audit=%s\n' \
            "$([[ $status == 0 ]] && printf passed || printf failed)" "$archive" "${package_dir:-unknown}" \
            "$build_images" "$process_probe" "$rebuild" "$dependency_audit" > "$report_dir/verification.txt"
    fi
    rm -rf -- "$work_dir"
    exit "$status"
}
trap cleanup EXIT INT TERM

if [[ -n $package_dir ]]; then
    extracted_package=$package_dir
else
    mkdir -p "$work_dir/archive"
    tar -xzf "$archive" -C "$work_dir/archive"
    top_level=$(tar -tzf "$archive" | awk -F/ 'NF > 1 && !seen { print $1; seen=1 }')
    [[ -n $top_level ]] || die 'archive has no top-level package directory'
    extracted_package="$work_dir/archive/$top_level"
    [[ -d $extracted_package ]] || die "archive top-level directory is missing: $top_level"
fi
package_dir=${package_dir:-$extracted_package}

archive_entries="$work_dir/archive-entries.txt"
tar -tzf "$archive" > "$archive_entries"

forbidden_patterns=(
    '(^|/)(release|legacy|p2|equinox|osgi)(/|$)'
    '(^|/)spike([-/.]|$)'
    '(^|/)(mock|mockito)([-/.]|$)'
    '(^|/)(spring-dm|spring-osgi)([-/.]|$)'
    '(^|/)tycho([-/.]|$)'
    '(^|/)[^/]*-sources\.jar$'
    '(^|/)(src|source)/(main|test|java|resources)/'
    '(^|/)(tomcat-6|catalina-6|servlet-api-2)([-/.]|$)'
)

scan_names() {
    local names_file=$1 label=$2 pattern
    for pattern in "${forbidden_patterns[@]}"; do
        if grep -Ei "$pattern" "$names_file" >/dev/null; then
            grep -Ein "$pattern" "$names_file" >&2 || true
            die "forbidden $label content matched: $pattern"
        fi
    done
}

scan_names "$archive_entries" archive

jar_entries() {
    if command -v unzip >/dev/null 2>&1; then
        unzip -Z1 "$1"
    else
        jar tf "$1"
    fi
}

jar_print_entry() {
    local jar_file=$1 entry=$2
    if command -v unzip >/dev/null 2>&1; then
        unzip -p "$jar_file" "$entry"
        return
    fi
    local extract_dir
    extract_dir=$(mktemp -d "${TMPDIR:-/tmp}/cosbench-jar.XXXXXX")
    if (cd "$extract_dir" && jar xf "$jar_file" "$entry") && [[ -f $extract_dir/$entry ]]; then
        cat "$extract_dir/$entry"
    fi
    rm -rf -- "$extract_dir"
}

jar_scan_root="$work_dir/jars"
mkdir -p "$jar_scan_root"
jar_counter=0
scan_jar() {
    local jar_file=$1 label=$2 depth=$3 entries_file nested nested_file
    entries_file="$work_dir/jar-entries-$jar_counter.txt"
    jar_counter=$((jar_counter + 1))
    jar_entries "$jar_file" > "$entries_file"
    scan_names "$entries_file" "JAR ($label)"
    ((depth < 2)) || return 0
    while IFS= read -r nested; do
        [[ -n $nested ]] || continue
        nested_file="$jar_scan_root/$jar_counter.jar"
        jar_counter=$((jar_counter + 1))
        jar_print_entry "$jar_file" "$nested" > "$nested_file"
        [[ -s $nested_file ]] || die "cannot extract nested JAR $nested from $label"
        scan_jar "$nested_file" "$label!$nested" "$((depth + 1))"
    done < <(grep -E '(^|/)(BOOT-INF/lib|lib)/[^/]+\.jar$' "$entries_file" || true)
}

package_names="$work_dir/package-names.txt"
find "$extracted_package" -type f -printf '%P\n' | sort > "$package_names"
scan_names "$package_names" package

required_files=(
    VERSION BUILD SHA256SUMS
    lib/cosbench-controller.jar
    lib/cosbench-driver.jar
    lib/cosbench-operator-cli.jar
    bin/cosbench-controller
    bin/cosbench-driver
    bin/cosbench-stop
    bin/cosbench-config-migrate
    bin/cosbench-launch
    bin/cosbench-container
    bin/cosbench-health
    conf/controller.yml
    conf/driver.yml
    conf/providers/gdas.properties
    conf/providers/sio.properties
    conf/providers/siov1.properties
    conf/providers/s3.properties
    workloads/s3-smoke.xml
    workloads/sio-smoke.xml
    docs/production-install.md
    docs/packaging.md
    docs/release-rollback.md
    docs/release-notes.md
    docs/provider-spi.md
    licenses/COSBench-LICENSE.txt
    licenses/COSBench-NOTICE.txt
    licenses/3rd-party-licenses.pdf
    licenses/SineIO-LICENSE.txt
    licenses/S3-LICENSE.txt
    licenses/S3-NOTICE.txt
    licenses/dependency-lock.tsv
    manifest/runtime.properties
    manifest/applications.tsv
    manifest/dependencies.tsv
    manifest/providers.tsv
    manifest/installation-files.txt
)
for relative_file in "${required_files[@]}"; do
    [[ -f $extracted_package/$relative_file ]] || die "required package file is missing: $relative_file"
done
for executable in bin/cosbench-controller bin/cosbench-driver bin/cosbench-stop bin/cosbench-config-migrate \
    bin/cosbench-launch bin/cosbench-container bin/cosbench-health; do
    [[ -x $extracted_package/$executable ]] || die "package command is not executable: $executable"
done

version=$(<"$extracted_package/VERSION")
version=${version//$'\n'/}
[[ $version =~ ^[0-9A-Za-z][0-9A-Za-z.+_-]*$ ]] || die "invalid package version: $version"
grep -Fxq $'application\tpath\tmain-class\tbytes\tsha256' "$extracted_package/manifest/applications.tsv" ||
    die 'application manifest header is missing'
grep -Fxq $'application\tnested-path\tcoordinate\tsource\tlicense\tbytes\tsha256' \
    "$extracted_package/manifest/dependencies.tsv" || die 'dependency manifest header is missing'
for expected_factory in \
    com.intel.cosbench.api.sio.SIOStorageFactory \
    com.intel.cosbench.api.sio.SIOStorageFactoryV1 \
    com.intel.cosbench.api.gdas.GdasStorageFactory \
    com.intel.cosbench.api.S3Stor.S3StorageFactory; do
    grep -F $'storage\tMETA-INF/services/com.intel.cosbench.api.storage.StorageAPIFactory\t'"$expected_factory" \
        "$extracted_package/manifest/providers.tsv" >/dev/null ||
        die "provider manifest is missing: $expected_factory"
done
grep -Fq 'java.runtime=21' "$extracted_package/manifest/runtime.properties" || die 'runtime manifest is not Java 21'
grep -Fq 'cosbench-production-java21' "$extracted_package/BUILD" || die 'production build marker is missing'

(cd "$extracted_package" && sha256sum -c SHA256SUMS) > "$work_dir/checksums.txt"
if [[ -f "$archive.sha256" ]]; then
    (cd "$(dirname -- "$archive")" && sha256sum -c "$(basename -- "$archive").sha256") > "$work_dir/archive-checksum.txt"
fi

for application in controller driver operator-cli; do
    scan_jar "$extracted_package/lib/cosbench-$application.jar" "lib/cosbench-$application.jar" 0
done

if ((dependency_audit == 1)); then
    command -v sudo >/dev/null 2>&1 || die 'sudo is required for final dependency gates'
    command -v docker >/dev/null 2>&1 || die 'docker is required for final dependency gates'
    for application in controller driver operator-cli; do
        audit_dir="$work_dir/dependency-audit-$application"
        mkdir -p "$audit_dir"
        audit_log="$work_dir/$application-dependency-audit.log"
        if ! sudo docker run --rm --user "$(id -u):$(id -g)" \
            -e "APP=$application" \
            -v "$extracted_package:/package:ro" \
            -v "$audit_dir:/audit" \
            -v "$repo_root/shadow/harness/audit:/audit-source:ro" \
            --entrypoint /bin/bash "$maven_image" -lc '
                set -Eeuo pipefail
                rm -rf /audit/classes /audit/libs /audit/tools /audit/BOOT-INF
                mkdir -p /audit/classes /audit/libs /audit/tools
                cp "/package/lib/cosbench-${APP}.jar" /audit/app.jar
                cd /audit
                jar xf app.jar BOOT-INF/classes
                cp -a BOOT-INF/classes/. classes/
                while IFS= read -r nested; do
                    jar xf app.jar "$nested"
                    cp "$nested" "libs/${nested##*/}"
                done < <(jar tf app.jar | grep '^BOOT-INF/lib/[^/]*\.jar$')
                javac --release 21 -d tools /audit-source/ArtifactAudit.java /audit-source/DuplicateClasses.java
                java -cp tools ArtifactAudit production app.jar
                jar --create --file classes.jar -C classes .
                java -cp tools DuplicateClasses classes.jar libs
                classpath=$(printf "%s:" libs/*.jar)
                jdeps --multi-release 21 --class-path "$classpath" --missing-deps classes.jar > "jdeps-${APP}.txt" 2>&1
                if grep -Fq "not found" "jdeps-${APP}.txt"; then
                    cat "jdeps-${APP}.txt"
                    exit 1
                fi
                printf "PASS final nested closure %s\\n" "$APP"
            ' > "$audit_log" 2>&1; then
            cat "$audit_log" >&2
            die "final dependency gates failed for $application"
        fi
    done
fi

if ((rebuild == 1)); then
    source_date_epoch=$(awk -F= '$1 == "source.date.epoch" { print $2; exit }' \
        "$extracted_package/manifest/runtime.properties")
    [[ $source_date_epoch =~ ^[0-9]+$ ]] || die 'runtime manifest has no valid source.date.epoch'
    rebuild_output="$work_dir/rebuild"
    bash "$script_dir/build-production.sh" --version "$version" --source-date-epoch "$source_date_epoch" \
        --output-dir "$rebuild_output" --force > "$work_dir/rebuild.log"
    rebuilt_archive="$rebuild_output/cosbench-${version}-java21.tar.gz"
    [[ -f $rebuilt_archive ]] || die 'deterministic rebuild did not produce an archive'
    cmp -s "$archive" "$rebuilt_archive" || die 'archive is not byte-for-byte reproducible from the same artifacts'
fi

if ((process_probe == 1)); then
    command -v sudo >/dev/null 2>&1 || die 'sudo is required for the Java 21 process probe'
    command -v docker >/dev/null 2>&1 || die 'docker is required for the Java 21 process probe'
    command -v ss >/dev/null 2>&1 || die 'ss is required for the Java 21 process probe'
    command -v timeout >/dev/null 2>&1 || die 'timeout is required for the Java 21 process probe'
    probe_install="$work_dir/empty-install"
    cp -a "$extracted_package" "$probe_install"
    probe_secrets="$work_dir/probe-secrets"
    mkdir -p "$probe_secrets"
    probe_password=cosbench-package-probe
    printf '%s' "$probe_password" > "$probe_secrets/password"
    chmod 644 "$probe_secrets/password"
    sudo docker run --rm --user "$(id -u):$(id -g)" -v "$probe_secrets:/tmp/secrets" \
        --entrypoint /opt/java/openjdk/bin/keytool "$maven_image" \
        -genkeypair -alias cosbench-probe -keyalg RSA -keysize 2048 -validity 365 \
        -dname 'CN=cosbench-package-probe,OU=Tests,O=COSBench' \
        -storetype PKCS12 -keystore /tmp/secrets/cosbench-keystore.p12 \
        -storepass "$probe_password" -keypass "$probe_password" > "$work_dir/keytool.log" 2>&1
    cp "$probe_secrets/cosbench-keystore.p12" "$probe_secrets/cosbench-truststore.p12"
    chmod 644 "$probe_secrets"/*.p12

    probe_role() {
        local role=$1 port=$2 url=$3 name="cosbench-package-probe-${1}-$$" health=0
        local -a docker_env=(
            -e COSBENCH_ROLE="$role"
            -e COSBENCH_SERVICE_TOKEN=cosbench-package-probe-token
            -e COSBENCH_TLS_STORE_PASSWORD_FILE=/run/secrets/password
            -e JAVA_OPTS='--add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED'
        )
        if [[ $role == driver ]]; then
            docker_env+=(
                -e SERVER_SSL_KEY_STORE_PASSWORD="$probe_password"
                -e SERVER_SSL_KEY_PASSWORD="$probe_password"
            )
        fi
        probe_containers+=("$name")
        sudo docker run --detach --name "$name" --network host --user "$(id -u):$(id -g)" \
            -v "$probe_install:/opt/cosbench" -v "$probe_secrets:/run/secrets:ro" \
            -w /opt/cosbench "${docker_env[@]}" \
            --entrypoint /bin/bash "$maven_image" -lc \
            "exec /opt/cosbench/bin/cosbench-$role --config /opt/cosbench/conf/$role.yml --pid-file /opt/cosbench/run/$role.pid" \
            > "$work_dir/$role-container-id.txt"
        for _ in $(seq 1 60); do
            if curl --fail --silent --insecure --max-time 2 "$url" 2>/dev/null | grep '"accepting":true' >/dev/null; then
                health=1
                break
            fi
            if ! sudo docker inspect "$name" >/dev/null 2>&1; then
                break
            fi
            sleep 1
        done
        sudo docker logs "$name" > "$work_dir/$role.log" 2>&1 || true
        ((health == 1)) || die "$role empty-install health probe failed; see $work_dir/$role.log"
        stop_status=0
        sudo docker exec "$name" /opt/cosbench/bin/cosbench-stop "$role" --pid-dir /opt/cosbench/run --timeout 15 \
            > "$work_dir/$role-stop.log" 2>&1 || stop_status=$?
        timeout 30 sudo docker wait "$name" > "$work_dir/$role-exit.txt" || true
        container_status=$(sudo docker inspect --format '{{.State.Status}}' "$name" 2>/dev/null || printf 'missing')
        [[ $container_status == exited ]] || die "$role stop probe left container in state $container_status"
        container_pid=$(sudo docker inspect --format '{{.State.Pid}}' "$name" 2>/dev/null || printf 'unknown')
        [[ $container_pid == 0 ]] || die "$role stop probe left container process $container_pid"
        if curl --fail --silent --insecure --max-time 2 "$url" >/dev/null 2>&1; then
            die "$role stop probe left port $port accepting requests"
        fi
        if ss -ltnH "sport = :$port" | grep -q .; then
            ss -ltnH "sport = :$port" >&2 || true
            die "$role stop probe left port $port listening"
        fi
        sudo docker rm --force "$name" >/dev/null 2>&1 || true
        probe_containers=()
        case "$stop_status" in
            0|137|143) ;;
            *) die "$role stop probe failed with status $stop_status; see $work_dir/$role-stop.log" ;;
        esac
        [[ ! -e "$probe_install/run/$role.pid" ]] || die "$role stop probe left a PID file behind"
    }

    probe_role driver 18088 'https://127.0.0.1:18088/driver/health'
    probe_role controller 19088 'http://127.0.0.1:19088/controller/health'
fi

if ((build_images == 1)); then
    command -v sudo >/dev/null 2>&1 || die 'sudo is required for image verification'
    command -v docker >/dev/null 2>&1 || die 'docker is required for image verification'
    image_suffix=$(printf '%s' "$version-$$" | sed 's/[^A-Za-z0-9_.-]/-/g')
    production_image="cosbench-production-check:$image_suffix"
    development_image="cosbench-development-check:$image_suffix"
    image_tags+=("$production_image" "$development_image")
    sudo docker build --file "$script_dir/Dockerfile.production" \
        --build-arg "COSBENCH_VERSION=$version" --tag "$production_image" "$extracted_package" \
        > "$work_dir/production-image.log"
    sudo docker build --file "$script_dir/Dockerfile.development" \
        --build-arg "MAVEN_IMAGE=$maven_image" \
        --tag "$development_image" "$repo_root" > "$work_dir/development-image.log"
    production_image_root="$work_dir/production-image-root"
    production_container=$(sudo docker create "$production_image")
    image_containers+=("$production_container")
    sudo docker cp "$production_container:/opt/cosbench" "$production_image_root"
    sudo chown -R "$(id -u):$(id -g)" "$production_image_root"
    sudo docker rm --force "$production_container" >/dev/null
    image_containers=()
    image_names="$work_dir/production-image-names.txt"
    find "$production_image_root" -type f -printf '%P\n' | sort > "$image_names"
    scan_names "$image_names" production-image
    while IFS= read -r -d '' image_jar; do
        scan_jar "$image_jar" "production image ${image_jar#"$production_image_root/"}" 0
    done < <(find "$production_image_root" -type f -name '*.jar' -print0 | sort -z)
    for executable in bin/cosbench-controller bin/cosbench-driver bin/cosbench-stop; do
        [[ -x "$production_image_root/$executable" ]] || die "production image is missing executable $executable"
    done
    sudo docker run --rm --entrypoint java "$production_image" -version 2> "$work_dir/production-java-version.txt"
    grep -E 'version "21([.]|"|$)' "$work_dir/production-java-version.txt" >/dev/null || die 'production image does not run Java 21'
    sudo docker run --rm --entrypoint java "$development_image" -version 2> "$work_dir/development-java-version.txt"
    grep -E 'version "21([.]|"|$)' "$work_dir/development-java-version.txt" >/dev/null || die 'development image does not run Java 21'
    development_image_root="$work_dir/development-image-root"
    development_container=$(sudo docker create "$development_image")
    image_containers+=("$development_container")
    sudo docker cp "$development_container:/workspace" "$development_image_root"
    sudo chown -R "$(id -u):$(id -g)" "$development_image_root"
    sudo docker rm --force "$development_container" >/dev/null
    image_containers=()
    for development_file in shadow/harness/compose.yaml shadow/harness/run.sh shadow/harness/continuous-checks.sh; do
        [[ -f "$development_image_root/$development_file" ]] || die "development image is missing $development_file"
    done
    grep -Eq '^  minio:' "$development_image_root/shadow/harness/compose.yaml" ||
        die 'development image compose file does not define the MinIO service'
    sudo docker run --rm --entrypoint /bin/bash "$development_image" -lc \
        'command -v mvn && command -v git && command -v curl && command -v ps' \
        > "$work_dir/development-tools.txt" 2>&1 || die 'development image is missing required test tools'
    production_dev_patterns=(
        '(^|/)(shadow|harness|src|test|tests|target|pom\.xml|mvnw|compose|\.git|node_modules)(/|$|\.)'
    )
    for pattern in "${production_dev_patterns[@]}"; do
        if grep -Ei "$pattern" "$image_names" >/dev/null; then
            grep -Ein "$pattern" "$image_names" >&2 || true
            die "production image contains development content: $pattern"
        fi
    done
    sudo docker run --rm --entrypoint /bin/bash "$production_image" -lc \
        '! command -v mvn && ! test -e /opt/cosbench/shadow && ! test -e /opt/cosbench/pom.xml' \
        > "$work_dir/production-development-exclusion.txt" 2>&1 ||
        die 'production image contains development tools or source paths'
fi

printf 'status=passed\narchive=%s\npackage=%s\nversion=%s\nrebuild=%s\nprocess_probe=%s\ndependency_audit=%s\nimages=%s\n' \
    "$archive" "$package_dir" "$version" "$rebuild" "$process_probe" "$dependency_audit" "$build_images"
