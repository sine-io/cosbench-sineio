#!/usr/bin/env bash
set -Eeuo pipefail

harness_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
shadow_dir=$(cd -- "$harness_dir/.." && pwd)
report_dir=${1:-/reports/ci}
maven_repo=${2:-/tmp/maven-repository}
mkdir -p "$report_dir"
: > "$report_dir/gates-summary.txt"
work_dir=$(mktemp -d)
trap 'rm -rf -- "$work_dir"' EXIT
javac --release 21 -Xlint:all -Werror -d "$work_dir/tools" "$harness_dir/audit/"*.java
audit=(java -cp "$work_dir/tools" BuildAudit)
maven=(mvn -B -ntp -C "-Dmaven.repo.local=$maven_repo" -f "$shadow_dir/pom.xml")

"${maven[@]}" validate \
    org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom "-Doutput=$report_dir/effective-pom.xml" \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree -Dverbose -DoutputFile=target/ci-tree.txt \
    > "$report_dir/maven-audit.log" 2>&1 || { cat "$report_dir/maven-audit.log"; exit 1; }
"${maven[@]}" \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:list \
    -DoutputAbsoluteArtifactFilename=true -DoutputFile=target/ci-dependencies.txt \
    >> "$report_dir/maven-audit.log" 2>&1 || { cat "$report_dir/maven-audit.log"; exit 1; }
"${audit[@]}" modules "$report_dir/effective-pom.xml" > "$report_dir/reactor.tsv"

while IFS=$'\t' read -r coordinate artifact target classes test_classes module; do
    module_report="$report_dir/$module"
    mkdir -p "$module_report"
    cp "$target/ci-tree.txt" "$module_report/dependency-tree.txt"
    cp "$target/ci-dependencies.txt" "$module_report/dependencies.txt"
    "${audit[@]}" dependencies "$target/ci-dependencies.txt" "$shadow_dir/dependency-lock.tsv" \
        "$report_dir/reactor.tsv" "$work_dir/$module" > "$module_report/licenses-checksums.tsv"
    java -cp "$work_dir/tools" ArtifactAudit production "$artifact" "$classes" "$work_dir/$module" \
        > "$module_report/artifact-scan.txt"
    # Audit the ordinary JAR against the actual shared runtime classpath. No global class exclusions.
    thin_artifact=$artifact
    if [[ -f $artifact.original ]]; then
        thin_artifact="$work_dir/$module-thin.jar"
        cp "$artifact.original" "$thin_artifact"
    fi
    java -cp "$work_dir/tools" DuplicateClasses "$thin_artifact" "$work_dir/$module" \
        > "$module_report/duplicate-classes.txt"
    java -cp "$work_dir/tools" DuplicateClasses "$thin_artifact" "$work_dir/$module/test" \
        > "$module_report/test-duplicate-classes.txt"
    /bin/bash "$harness_dir/audit/check-jdeps.sh" "$thin_artifact" "$(<"$work_dir/$module/classpath.txt")" \
        "$module_report/jdeps.txt" > "$module_report/jdeps-status.txt"
    : > "$module_report/dependency-artifact-scan.txt"
    for dependency in "$work_dir/$module/"*.jar; do
        [[ -f $dependency ]] || continue
        java -cp "$work_dir/tools" ArtifactAudit dependency "$dependency" \
            >> "$module_report/dependency-artifact-scan.txt"
    done
    if [[ -d $test_classes ]]; then
        jar --create --file "$work_dir/$module-tests.jar" -C "$test_classes" .
        scope=test
        case "$module" in
            cosbench-provider-spike|cosbench-http-spike|cosbench-driver-app) scope=mock-test ;;
        esac
        java -cp "$work_dir/tools" ArtifactAudit "$scope" "$work_dir/$module-tests.jar" \
            > "$module_report/test-artifact-scan.txt"
        cp "$thin_artifact" "$work_dir/$module/test/$module-main.jar"
        java -cp "$work_dir/tools" DuplicateClasses "$work_dir/$module-tests.jar" "$work_dir/$module/test" \
            > "$module_report/test-duplicate-classes.txt"
    fi
    jar tf "$artifact" > "$module_report/jar-contents.txt"
    sha256sum "$artifact" > "$module_report/artifact.sha256"
    printf 'PASS %s\n' "$coordinate" >> "$report_dir/gates-summary.txt"
done < "$report_dir/reactor.tsv"
printf 'status=passed\nmodules=%s\n' "$(wc -l < "$report_dir/reactor.tsv")" >> "$report_dir/gates-summary.txt"
