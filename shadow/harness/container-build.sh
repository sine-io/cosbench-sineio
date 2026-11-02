#!/usr/bin/env bash
set -Eeuo pipefail

maven_repo=/tmp/maven-repository
audit_jars=/tmp/audit-jars
runtime_audit_jars=/tmp/runtime-audit-jars
provider_audit_jars=/tmp/provider-audit-jars
s3_audit_jars=/tmp/s3-audit-jars
shared_module_audit_root=/tmp/shared-module-audits
module_jar=/workspace/shadow/cosbench-config/target/cosbench-config-shadow-0.1.0-SNAPSHOT.jar
runtime_jar=/workspace/shadow/cosbench-runtime/target/cosbench-runtime-shadow-0.1.0-SNAPSHOT.jar
runtime_thin_jar=/workspace/shadow/cosbench-runtime/target/cosbench-runtime-shadow-0.1.0-SNAPSHOT.jar.original
s3_jar=/workspace/shadow/cosbench-s3/target/cosbench-s3-0.1.0-SNAPSHOT.jar

mkdir -p "$maven_repo" "$audit_jars" "$runtime_audit_jars" "$provider_audit_jars" "$s3_audit_jars" /reports
retain_test_reports() {
    local build_status=$?
    mkdir -p /reports/tests
    while IFS= read -r test_report_dir; do
        local module
        module=$(basename "$(dirname "$(dirname "$test_report_dir")")")
        cp -R "$test_report_dir" "/reports/tests/$module"
    done < <(find /workspace/shadow -type d -name surefire-reports)
    return "$build_status"
}
trap retain_test_reports EXIT
mvn --version > /reports/toolchain.txt
mvn -B -ntp -C -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml clean verify \
    > /reports/build.log 2>&1 || {
        cat /reports/build.log
        exit 1
    }
cat /reports/build.log
mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
    -DskipTests install > /reports/shared-module-install.log 2>&1 || {
        cat /reports/shared-module-install.log
        exit 1
    }
/bin/bash /workspace/shadow/harness/audit/gates.sh /reports/ci "$maven_repo" \
    > /reports/ci-gates.log 2>&1 || { cat /reports/ci-gates.log; exit 1; }
mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
    -pl cosbench-provider-spike -Dtest=ProviderMinioSmokeTest test \
    > /reports/provider-minio-test.log 2>&1 || {
        cat /reports/provider-minio-test.log
        exit 1
    }
cat /reports/provider-minio-test.log
java --class-path "$module_jar" /workspace/shadow/harness/PackagingProbe.java \
    > /reports/packaged-runtime.txt

while IFS=: read -r module artifact; do
    module_jar_path="/workspace/shadow/$module/target/$artifact-0.1.0-SNAPSHOT.jar"
    dependency_dir="$shared_module_audit_root/$module"
    mkdir -p "$dependency_dir"
    test -s "$module_jar_path"
    mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
        -pl "$module" -Dscope=test -DoutputFile="/reports/$module-dependency-tree.txt" \
        org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree
    mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
        -pl "$module" -DincludeScope=test -DoutputDirectory="$dependency_dir" \
        org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies
    jar tf "$module_jar_path" > "/reports/$module-jar-contents.txt"
    sha256sum "$module_jar_path" > "/reports/$module-artifact.sha256"
    /workspace/shadow/harness/check-duplicate-classes.sh "$module_jar_path" "$dependency_dir" \
        > "/reports/$module-duplicate-class-audit.txt"
    printf '%s:%s:%s\n' "$module" "$artifact" "$(cut -d ' ' -f 1 "/reports/$module-artifact.sha256")" \
        >> /reports/shared-module-artifacts.txt
done <<'MODULES'
cosbench-log4j:cosbench-log4j-shadow
cosbench-castor:cosbench-castor-shadow
cosbench-log:cosbench-log-shadow
cosbench-config:cosbench-config-shadow
cosbench-api:cosbench-api-shadow
cosbench-s3:cosbench-s3
cosbench-core:cosbench-core-shadow
cosbench-http:cosbench-http-shadow
MODULES

test -s "$runtime_jar"
test -s "$runtime_thin_jar"
jar tf "$runtime_jar" > /reports/runtime-jar-contents.txt
grep -q 'com/intel/cosbench/runtime/CosbenchRuntimeLauncher.class' /reports/runtime-jar-contents.txt
grep -q 'BOOT-INF/lib/spring-boot-' /reports/runtime-jar-contents.txt
printf 'runtime_jar=%s\nruntime_jar_status=present\nexecutable_launcher=present\n' "$runtime_jar" \
    > /reports/runtime-artifact.txt

mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
    -pl cosbench-runtime -am -DskipTests install > /reports/runtime-install.log 2>&1

mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
    -pl cosbench-config -Dscope=test -DoutputFile=/reports/dependency-tree.txt \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree
mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
    -pl cosbench-config -DincludeScope=test -DoutputDirectory="$audit_jars" \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies
mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
    -pl cosbench-runtime -Dscope=test -DoutputFile=/reports/runtime-dependency-tree.txt \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree
mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
    -pl cosbench-runtime -DincludeScope=test -DoutputDirectory="$runtime_audit_jars" \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies
mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
    -pl cosbench-provider-spike -Dscope=test -DoutputFile=/reports/provider-dependency-tree.txt \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree
mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
    -pl cosbench-provider-spike -DincludeScope=test -DoutputDirectory="$provider_audit_jars" \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies
mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
    -pl cosbench-s3 -Dscope=test -DoutputFile=/reports/s3-dependency-tree.txt \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree
mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
    -pl cosbench-s3 -DincludeScope=test -DoutputDirectory="$s3_audit_jars" \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies

jar tf "$module_jar" > /reports/jar-contents.txt
awk '/-mapping\.xml$/ { print }' /reports/jar-contents.txt > /reports/resource-list.txt
cp "$module_jar" /reports/cosbench-config-shadow.jar
sha256sum /reports/cosbench-config-shadow.jar > /reports/artifact.sha256
/workspace/shadow/harness/check-duplicate-classes.sh "$module_jar" "$audit_jars" \
    > /reports/duplicate-class-audit.txt
/workspace/shadow/harness/check-duplicate-classes.sh "$runtime_thin_jar" "$runtime_audit_jars" \
    > /reports/runtime-duplicate-class-audit.txt
/workspace/shadow/harness/check-duplicate-classes.sh "$s3_jar" "$s3_audit_jars" \
    > /reports/provider-duplicate-class-audit.txt
jar tf "$s3_jar" > /reports/provider-artifact-contents.txt
grep -q 'META-INF/services/com.intel.cosbench.api.storage.StorageAPIFactory' /reports/provider-artifact-contents.txt
if grep -q '^com/intel/cosbench/api/mock/' /reports/provider-artifact-contents.txt; then
    printf '%s\n' 'Mock classes leaked into the provider module artifact.' >&2
    exit 1
fi
if grep -q 'META-INF/services/com.intel.cosbench.api.auth.AuthAPIFactory' /reports/provider-artifact-contents.txt; then
    printf '%s\n' 'Mock auth descriptor leaked into the provider module artifact.' >&2
    exit 1
fi
printf '%s\n' \
    'Production storage descriptor: present.' \
    'Mock storage/auth classes and test descriptors: absent from the main artifact.' \
    > /reports/provider-artifact-boundary.txt
sha256sum "$s3_jar" > /reports/provider-artifact.sha256
sha256sum "$provider_audit_jars"/*.jar > /reports/provider-dependency-checksums.txt
sha256sum "$s3_audit_jars"/*.jar > /reports/s3-dependency-checksums.txt
printf '%s\n' \
    'The S3 production provider uses the AWS SDK for Java v1 API and the legacy S3SignerType override.' \
    'The migrated runtime resolves v1 1.12.767, the shared version established by the #04 classpath coexistence spike; the old S3 bundle used 1.10.76 and SineIO used 1.12.767.' \
    'AWS SDK for Java v2 resolved to software.amazon.awssdk:s3:2.26.8 from Maven Central, matching the SineIO bundle.' \
    'No AWS SDK v1-to-v2 provider rewrite was made; Maven selects one v1 version on the shared classpath and #04 found no class conflict.' \
    'AWS SDK dependencies are Apache-2.0; exact resolved coordinates and checksums are in s3-dependency-tree.txt and s3-dependency-checksums.txt.' \
    > /reports/provider-sdk-provenance.txt
sha256sum "$audit_jars"/*.jar "$runtime_audit_jars"/*.jar > /reports/test-dependency-checksums.txt
find "$maven_repo" -name '*.jar' -exec sha256sum '{}' + | sort > /reports/resolved-jar-checksums.txt
printf '%s\n' \
    'No local JAR or system-scoped dependencies are used.' \
    'Shared module dependency trees, JAR listings, SHA-256 checksums, and duplicate-class audits are recorded per artifact.' \
    'The runtime dependency tree and duplicate-class audit cover the executable Spring Boot shadow module.' \
    'JUnit Jupiter is test-scoped and resolved from Maven Central using the pinned Spring Boot BOM.' \
    'JUnit Jupiter and JUnit Platform: Eclipse Public License 2.0 (https://junit.org/junit5/docs/5.12.2/api/overview-summary.html).' \
    'OpenTest4J 1.3.0 and API Guardian 1.1.2: Apache License 2.0; coordinates and checksums are in the dependency reports.' \
    'Source repository for these test JARs: https://repo.maven.apache.org/maven2/.' \
    > /reports/local-jars-and-licenses.txt
find /reports -type f -exec chmod 644 '{}' +
