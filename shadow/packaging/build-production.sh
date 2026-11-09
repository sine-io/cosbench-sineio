#!/usr/bin/env bash
set -Eeuo pipefail

# Build the Java 21 production distribution from ordinary Maven artifacts. The
# script intentionally has no fallback to release/, dist/, or an Eclipse
# workspace: those trees belong to the legacy distribution.

LC_ALL=C
export LC_ALL
umask 022

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/../.." && pwd)
artifacts_root="$repo_root/shadow"
version_file="$repo_root/VERSION"
output_dir="$repo_root/out/cosbench-production"
version=
package_name=
archive_name=
source_date_epoch=${SOURCE_DATE_EPOCH:-0}
force=0
skip_archive=0

usage() {
    cat <<'EOF'
Usage: build-production.sh [options]

Build a reproducible Java 21 production package from the shadow Maven reactor.

Options:
  --version VERSION       Package version (defaults to VERSION)
  --artifacts-dir DIR     Shadow reactor directory (defaults to ./shadow)
  --output-dir DIR        Output directory (defaults to ./out/cosbench-production)
  --package-name NAME     Top-level archive directory (defaults to cosbench-VERSION-java21)
  --archive PATH          Archive path (defaults to OUTPUT/cosbench-VERSION-java21.tar.gz)
  --source-date-epoch N   Tar/gzip timestamp (defaults to 0)
  --force                 Replace this command's generated package directory
  --skip-archive          Keep the staging directory and do not create an archive
  -h, --help              Show this help
EOF
}

die() {
    printf 'production package: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "required command is missing: $1"
}

while (($# > 0)); do
    case "$1" in
        --version)
            (($# >= 2)) || die '--version requires a value'
            version=$2
            shift
            ;;
        --version=*) version=${1#*=} ;;
        --artifacts-dir)
            (($# >= 2)) || die '--artifacts-dir requires a path'
            artifacts_root=$2
            shift
            ;;
        --artifacts-dir=*) artifacts_root=${1#*=} ;;
        --output-dir)
            (($# >= 2)) || die '--output-dir requires a path'
            output_dir=$2
            shift
            ;;
        --output-dir=*) output_dir=${1#*=} ;;
        --package-name)
            (($# >= 2)) || die '--package-name requires a value'
            package_name=$2
            shift
            ;;
        --package-name=*) package_name=${1#*=} ;;
        --archive)
            (($# >= 2)) || die '--archive requires a path'
            archive_name=$2
            shift
            ;;
        --archive=*) archive_name=${1#*=} ;;
        --source-date-epoch)
            (($# >= 2)) || die '--source-date-epoch requires a value'
            source_date_epoch=$2
            shift
            ;;
        --source-date-epoch=*) source_date_epoch=${1#*=} ;;
        --force) force=1 ;;
        --skip-archive) skip_archive=1 ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
    shift
done

if [[ -z $version ]]; then
    [[ -f $version_file ]] || die "version file is missing: $version_file"
    version=$(<"$version_file")
fi
version=${version//$'\n'/}
[[ $version =~ ^[0-9A-Za-z][0-9A-Za-z.+_-]*$ ]] || die "invalid version: $version"
[[ $source_date_epoch =~ ^[0-9]+$ ]] || die '--source-date-epoch must be a non-negative integer'

if [[ -z $package_name ]]; then
    package_name="cosbench-${version}-java21"
fi
[[ $package_name =~ ^[A-Za-z0-9][A-Za-z0-9.+_-]*$ ]] || die "invalid package name: $package_name"

if [[ -z $archive_name ]]; then
    archive_name="$output_dir/${package_name}.tar.gz"
fi
if [[ $archive_name != /* ]]; then
    archive_name="$repo_root/$archive_name"
fi
output_dir=$(realpath -m -- "$output_dir")
artifacts_root=$(realpath -m -- "$artifacts_root")
archive_name=$(realpath -m -- "$archive_name")
package_dir="$output_dir/$package_name"

for command_name in awk basename cat cp find grep gzip mktemp sha256sum sort stat tar touch tr wc; do
    require_command "$command_name"
done

if ! command -v unzip >/dev/null 2>&1 && ! command -v jar >/dev/null 2>&1; then
    die 'required command is missing: unzip or the JDK jar tool'
fi

[[ -d $artifacts_root ]] || die "artifacts directory is missing: $artifacts_root"

find_single_artifact() {
    local module=$1 pattern=$2
    local -a matches=()
    while IFS= read -r -d '' candidate; do
        matches+=("$candidate")
    done < <(find "$artifacts_root/$module/target" -maxdepth 1 -type f -name "$pattern" ! -name '*.original' -print0 2>/dev/null | sort -z)
    ((${#matches[@]} == 1)) || die "expected exactly one $pattern under $artifacts_root/$module/target (found ${#matches[@]})"
    printf '%s\n' "${matches[0]}"
}

controller_artifact=$(find_single_artifact cosbench-controller-app 'cosbench-controller-app-*.jar')
driver_artifact=$(find_single_artifact cosbench-driver-app 'cosbench-driver-app-*.jar')
operator_artifact=$(find_single_artifact cosbench-operator-cli 'cosbench-operator-cli-*.jar')

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

for artifact in "$controller_artifact" "$driver_artifact" "$operator_artifact"; do
    [[ -s $artifact ]] || die "artifact is empty: $artifact"
    jar_entries "$artifact" | grep '^BOOT-INF/lib/' >/dev/null || die "artifact is not a Spring Boot executable JAR: $artifact"
    jar_entries "$artifact" | grep '^org/springframework/boot/loader/' >/dev/null || die "Boot launcher is missing from: $artifact"
done

for forbidden in \
    '(^|/)(release|legacy|p2|equinox|osgi)(/|$)' \
    '(^|/)spike([-/.]|$)' \
    '(^|/)(mock|mockito)([-/.]|$)' \
    '(^|/)(spring-dm|spring-osgi)([-/.]|$)' \
    '(^|/)tycho([-/.]|$)' \
    '(^|/)[^/]*-sources\.jar$' \
    '(^|/)(src|source)/(main|test|java|resources)/' \
    '(^|/)(tomcat-6|catalina-6|servlet-api-2)([-/.]|$)'; do
    if jar_entries "$controller_artifact" | grep -Ei "$forbidden" >/dev/null ||
        jar_entries "$driver_artifact" | grep -Ei "$forbidden" >/dev/null ||
        jar_entries "$operator_artifact" | grep -Ei "$forbidden" >/dev/null; then
        die "forbidden production content matched: $forbidden"
    fi
done

storage_descriptor='META-INF/services/com.intel.cosbench.api.storage.StorageAPIFactory'
auth_descriptor='META-INF/services/com.intel.cosbench.api.auth.AuthAPIFactory'
dependency_lock="$repo_root/shadow/dependency-lock.tsv"
[[ -s $dependency_lock ]] || die "dependency lock is missing: $dependency_lock"
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/cosbench-package.XXXXXX")
cleanup() {
    local status=$?
    trap - EXIT INT TERM
    rm -rf -- "$tmp_dir"
    exit "$status"
}
trap cleanup EXIT

storage_lines=()
auth_lines=()
sineio_jar=
s3_jar=
while IFS= read -r nested; do
    case "$nested" in
        BOOT-INF/lib/cosbench-sineio-*.jar|BOOT-INF/lib/cosbench-s3-*.jar)
            nested_file="$tmp_dir/$(basename -- "$nested")"
            jar_print_entry "$driver_artifact" "$nested" > "$nested_file"
            [[ -s $nested_file ]] || die "cannot extract provider library: $nested"
            case "$nested" in
                BOOT-INF/lib/cosbench-sineio-*.jar) sineio_jar=$nested_file ;;
                BOOT-INF/lib/cosbench-s3-*.jar) s3_jar=$nested_file ;;
            esac
            if descriptor=$(jar_print_entry "$nested_file" "$storage_descriptor" 2>/dev/null); then
                while IFS= read -r line; do
                    [[ -z ${line//[[:space:]]/} || $line == \#* ]] || storage_lines+=("$line")
                done <<< "$descriptor"
            fi
            if descriptor=$(jar_print_entry "$nested_file" "$auth_descriptor" 2>/dev/null); then
                while IFS= read -r line; do
                    [[ -z ${line//[[:space:]]/} || $line == \#* ]] || auth_lines+=("$line")
                done <<< "$descriptor"
            fi
            ;;
    esac
done < <(jar_entries "$driver_artifact" | grep '^BOOT-INF/lib/cosbench-\(sineio\|s3\)-.*\.jar$' | sort)

((${#storage_lines[@]} > 0)) || die 'production storage SPI descriptor is missing from the Driver artifact'

for expected_factory in \
    com.intel.cosbench.api.sio.SIOStorageFactory \
    com.intel.cosbench.api.sio.SIOStorageFactoryV1 \
    com.intel.cosbench.api.gdas.GdasStorageFactory \
    com.intel.cosbench.api.S3Stor.S3StorageFactory; do
    printf '%s\n' "${storage_lines[@]}" | grep -Fxq -- "$expected_factory" ||
        die "required production storage provider is missing from the Driver artifact: $expected_factory"
done

if ((force == 0)) && [[ -e $package_dir ]]; then
    die "generated package already exists (use --force to replace): $package_dir"
fi
mkdir -p "$output_dir"
if ((force == 1)); then
    rm -rf -- "$package_dir"
fi
rm -f -- "$archive_name" "$archive_name.sha256"

mkdir -p "$package_dir"/{bin,conf/providers,docs,lib,licenses,manifest,workloads,run}
cp -- "$controller_artifact" "$package_dir/lib/cosbench-controller.jar"
cp -- "$driver_artifact" "$package_dir/lib/cosbench-driver.jar"
cp -- "$operator_artifact" "$package_dir/lib/cosbench-operator-cli.jar"

for command_name in cosbench-controller cosbench-driver cosbench-stop cosbench-config-migrate cosbench-launch; do
    cp -- "$repo_root/bin/$command_name" "$package_dir/bin/$command_name"
    chmod 0755 "$package_dir/bin/$command_name"
done
cp -- "$script_dir/templates/cosbench-container" "$package_dir/bin/cosbench-container"
cp -- "$script_dir/templates/cosbench-health" "$package_dir/bin/cosbench-health"
chmod 0755 "$package_dir/bin/cosbench-container" "$package_dir/bin/cosbench-health"

cp -- "$script_dir/templates/conf/controller.yml" "$package_dir/conf/controller.yml"
cp -- "$script_dir/templates/conf/driver.yml" "$package_dir/conf/driver.yml"
cp -- "$script_dir/templates/conf/README.md" "$package_dir/conf/README.md"
cp -- "$script_dir/templates/conf/providers/"*.properties "$package_dir/conf/providers/"
cp -- "$script_dir/templates/workloads/"*.xml "$package_dir/workloads/"
cp -- "$script_dir/templates/docs/production-install.md" "$package_dir/docs/production-install.md"
cp -- "$script_dir/README.md" "$package_dir/docs/packaging.md"

# Ship the operator handover with the binary. Rewrite source-tree links to the
# corresponding documents in the distribution's flat docs directory.
copy_release_document() {
    local source=$1 destination=$2
    sed \
        -e 's|(../shadow/packaging/templates/docs/production-install.md)|(production-install.md)|g' \
        -e 's|(shadow/packaging/templates/docs/production-install.md)|(production-install.md)|g' \
        -e 's|(../BUILD-java21.md)|(build-java21.md)|g' \
        -e 's|(BUILD-java21.md)|(build-java21.md)|g' \
        -e 's|(docs/release-rollback.md)|(release-rollback.md)|g' \
        -e 's|(shadow/provider-spi.md)|(provider-spi.md)|g' \
        -e 's|\[old build\](BUILD-sineio.md)|old build in the source repository|g' \
        "$repo_root/$source" > "$package_dir/docs/$destination"
}
copy_release_document docs/release-rollback.md release-rollback.md
copy_release_document RELEASE-NOTES-java21.md release-notes.md
copy_release_document BUILD-java21.md build-java21.md
for document in provider-spi sineio-providers s3-provider ci-policy config-contract operator-commands; do
    copy_release_document "shadow/$document.md" "$document.md"
done

cp -- "$repo_root/LICENSE" "$package_dir/licenses/COSBench-LICENSE.txt"
cp -- "$repo_root/NOTICE" "$package_dir/licenses/COSBench-NOTICE.txt"
cp -- "$repo_root/3rd-party-licenses.pdf" "$package_dir/licenses/3rd-party-licenses.pdf"
[[ -n $sineio_jar ]] || die 'SineIO provider library was not found in the Driver artifact'
[[ -n $s3_jar ]] || die 'S3 provider library was not found in the Driver artifact'
jar_print_entry "$sineio_jar" META-INF/LICENSE > "$package_dir/licenses/SineIO-LICENSE.txt" ||
    die 'SineIO provider license is missing from the packaged provider JAR'
jar_print_entry "$s3_jar" META-INF/LICENSE > "$package_dir/licenses/S3-LICENSE.txt" ||
    die 'S3 provider license is missing from the packaged provider JAR'
jar_print_entry "$s3_jar" META-INF/NOTICE > "$package_dir/licenses/S3-NOTICE.txt" ||
    die 'S3 provider notice is missing from the packaged provider JAR'
for license_file in SineIO-LICENSE.txt S3-LICENSE.txt S3-NOTICE.txt; do
    [[ -s $package_dir/licenses/$license_file ]] || die "packaged license file is empty: $license_file"
done
cp -- "$dependency_lock" "$package_dir/licenses/dependency-lock.tsv"

printf '%s\n' "$version" > "$package_dir/VERSION"
printf '%s\n' 'cosbench-production-java21' > "$package_dir/BUILD"

source_revision=unknown
if [[ -d $repo_root/.git ]] && command -v git >/dev/null 2>&1; then
    source_revision=$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || printf 'unknown')
fi
cat > "$package_dir/manifest/runtime.properties" <<EOF
format=cosbench-production-java21
version=$version
java.runtime=21
maven.compiler.release=21
spring-boot.version=3.5.16
source.revision=$source_revision
source.date.epoch=$source_date_epoch
EOF

printf 'application\tpath\tmain-class\tbytes\tsha256\n' > "$package_dir/manifest/applications.tsv"
for app in controller driver operator-cli; do
    jar="$package_dir/lib/cosbench-$app.jar"
    case "$app" in
        controller) main_class=com.intel.cosbench.app.controller.ControllerApplication ;;
        driver) main_class=com.intel.cosbench.app.driver.DriverApplication ;;
        operator-cli) main_class=com.intel.cosbench.operator.OperatorCli ;;
    esac
    printf '%s\tlib/cosbench-%s.jar\t%s\t%s\t%s\n' "$app" "$app" "$main_class" \
        "$(stat -c '%s' "$jar")" "$(sha256sum "$jar" | awk '{print $1}')" >> "$package_dir/manifest/applications.tsv"
done

coordinate_source() {
    local coordinate=$1 group artifact packaging classifier version filename
    local -a parts=()
    IFS=: read -r -a parts <<< "$coordinate"
    case "${#parts[@]}" in
        4)
            group=${parts[0]}
            artifact=${parts[1]}
            packaging=${parts[2]}
            classifier=
            version=${parts[3]}
            ;;
        5)
            group=${parts[0]}
            artifact=${parts[1]}
            packaging=${parts[2]}
            classifier=${parts[3]}
            version=${parts[4]}
            ;;
        *)
            printf 'unknown://%s\n' "$coordinate"
            return 0
            ;;
    esac
    filename="$artifact-$version"
    [[ -n $classifier ]] && filename+="-$classifier"
    filename+=".$packaging"
    printf 'https://repo.maven.apache.org/maven2/%s/%s/%s/%s\n' \
        "${group//./\/}" "$artifact" "$version" "$filename"
}

dependency_metadata() {
    local nested_file=$1 sha=$2 lock_line coordinate license group artifact version source props
    lock_line=$(awk -F '\t' -v wanted_sha="$sha" '$2 == wanted_sha { print; exit }' "$dependency_lock")
    if [[ -n $lock_line ]]; then
        IFS=$'\t' read -r coordinate _ license <<< "$lock_line"
        source=$(coordinate_source "$coordinate")
        printf '%s\t%s\t%s\n' "$coordinate" "$source" "$license"
        return 0
    fi

    props=
    while IFS= read -r entry; do
        [[ -n $entry ]] || continue
        props=$(jar_print_entry "$nested_file" "$entry")
        group=$(printf '%s\n' "$props" | awk -F= '$1 == "groupId" { print substr($0, index($0, "=") + 1); exit }')
        artifact=$(printf '%s\n' "$props" | awk -F= '$1 == "artifactId" { print substr($0, index($0, "=") + 1); exit }')
        version=$(printf '%s\n' "$props" | awk -F= '$1 == "version" { print substr($0, index($0, "=") + 1); exit }')
        if [[ -n $group && -n $artifact && -n $version ]]; then
            case "$artifact" in
                cosbench-sineio*) license=SineIO-LICENSE.txt ;;
                cosbench-s3*) license=S3-LICENSE.txt ;;
                cosbench-*) license=COSBench-LICENSE.txt ;;
                *) license=unknown ;;
            esac
            printf '%s:%s:jar:%s\t%s\t%s\n' "$group" "$artifact" "$version" \
                "reactor://$group/$artifact/$version" "$license"
            return 0
        fi
    done < <(jar_entries "$nested_file" | grep '^META-INF/maven/[^/]*/[^/]*/pom.properties$' | sort)
    return 1
}

printf 'application\tnested-path\tcoordinate\tsource\tlicense\tbytes\tsha256\n' > "$package_dir/manifest/dependencies.tsv"
dependency_counter=0
for app in controller driver operator-cli; do
    jar="$package_dir/lib/cosbench-$app.jar"
    while IFS= read -r nested; do
        [[ $nested == BOOT-INF/lib/*.jar ]] || continue
        nested_file="$tmp_dir/dependency-$dependency_counter.jar"
        dependency_counter=$((dependency_counter + 1))
        jar_print_entry "$jar" "$nested" > "$nested_file"
        [[ -s $nested_file ]] || die "cannot extract dependency for manifest: $app $nested"
        nested_bytes=$(stat -c '%s' "$nested_file")
        nested_sha=$(sha256sum "$nested_file" | awk '{print $1}')
        metadata=$(dependency_metadata "$nested_file" "$nested_sha") ||
            die "dependency has no lock entry or Maven provenance: $app $nested ($nested_sha)"
        IFS=$'\t' read -r coordinate source license <<< "$metadata"
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$app" "$nested" "$coordinate" "$source" \
            "$license" "$nested_bytes" "$nested_sha" >> "$package_dir/manifest/dependencies.tsv"
    done < <(jar_entries "$jar" | grep '^BOOT-INF/lib/.*\.jar$' | sort)
done

printf 'provider\tdescriptor\tfactory\n' > "$package_dir/manifest/providers.tsv"
while IFS= read -r factory; do
    printf 'storage\t%s\t%s\n' "$storage_descriptor" "$factory" >> "$package_dir/manifest/providers.tsv"
done < <(printf '%s\n' "${storage_lines[@]}" | sed '/^[[:space:]]*#/d;/^[[:space:]]*$/d' | sort -u)
while IFS= read -r factory; do
    printf 'auth\t%s\t%s\n' "$auth_descriptor" "$factory" >> "$package_dir/manifest/providers.tsv"
done < <(printf '%s\n' "${auth_lines[@]}" | sed '/^[[:space:]]*#/d;/^[[:space:]]*$/d' | sort -u)
sort -u "$package_dir/manifest/providers.tsv" -o "$package_dir/manifest/providers.tsv"

cat > "$package_dir/manifest/installation-files.txt" <<'EOF'
Required files for an empty installation:
  VERSION
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

Runtime-created directories:
  run/       PID files (or set COSBENCH_PID_DIR)
  workspace/ Controller working data
  archive/   completed workload archives
  mission/   Driver mission data
  log/       application logs

The service token and TLS stores are injected from protected runtime mounts.
They are intentionally absent from this archive.
EOF

# The checksum manifest excludes itself so it can be checked after extraction.
(cd "$package_dir" && find . -type f ! -path './SHA256SUMS' -printf '%P\n' | LC_ALL=C sort | while IFS= read -r file; do sha256sum -- "$file"; done) > "$package_dir/SHA256SUMS"

# Normalize staging mtimes before archiving. Tar also applies these values to
# entries, which keeps a rebuild from the same input byte-for-byte identical.
find "$package_dir" -exec touch -h -d "@$source_date_epoch" '{}' +

if ((skip_archive == 0)); then
    mkdir -p "$(dirname -- "$archive_name")"
    tar -C "$output_dir" --sort=name --mtime="@$source_date_epoch" --owner=0 --group=0 --numeric-owner \
        -cf - "$package_name" | gzip -n > "$archive_name"
    (cd "$(dirname -- "$archive_name")" && sha256sum "$(basename -- "$archive_name")") > "$archive_name.sha256"
fi

printf 'package_dir=%s\n' "$package_dir"
if ((skip_archive == 0)); then
    printf 'archive=%s\nchecksum=%s\n' "$archive_name" "$archive_name.sha256"
fi
