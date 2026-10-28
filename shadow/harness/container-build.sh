#!/usr/bin/env bash
set -Eeuo pipefail

maven_repo=/tmp/maven-repository
audit_jars=/tmp/audit-jars
module_jar=/workspace/shadow/cosbench-config/target/cosbench-config-shadow-0.1.0-SNAPSHOT.jar

mkdir -p "$maven_repo" "$audit_jars" /reports
mvn --version > /reports/toolchain.txt
mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml clean verify \
    > /reports/build.log 2>&1 || {
        cat /reports/build.log
        exit 1
    }
cat /reports/build.log
java --class-path "$module_jar" /workspace/shadow/harness/PackagingProbe.java \
    > /reports/packaged-runtime.txt

mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
    -pl cosbench-config -Dscope=test -DoutputFile=/reports/dependency-tree.txt \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree
mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f /workspace/shadow/pom.xml \
    -pl cosbench-config -DincludeScope=test -DoutputDirectory="$audit_jars" \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies

jar tf "$module_jar" > /reports/jar-contents.txt
awk '/-mapping\.xml$/ { print }' /reports/jar-contents.txt > /reports/resource-list.txt
cp "$module_jar" /reports/cosbench-config-shadow.jar
sha256sum /reports/cosbench-config-shadow.jar > /reports/artifact.sha256
/workspace/shadow/harness/check-duplicate-classes.sh "$module_jar" "$audit_jars" \
    > /reports/duplicate-class-audit.txt
sha256sum "$audit_jars"/*.jar > /reports/test-dependency-checksums.txt
find "$maven_repo" -name '*.jar' -exec sha256sum '{}' + | sort > /reports/resolved-jar-checksums.txt
printf '%s\n' \
    'No local JAR or system-scoped dependencies are used.' \
    'The representative XML-resource slice has no runtime dependencies.' \
    'JUnit Jupiter is test-scoped and resolved from Maven Central using the pinned Spring Boot BOM.' \
    'JUnit Jupiter and JUnit Platform: Eclipse Public License 2.0 (https://junit.org/junit5/docs/5.12.2/api/overview-summary.html).' \
    'OpenTest4J 1.3.0 and API Guardian 1.1.2: Apache License 2.0; coordinates and checksums are in the dependency reports.' \
    'Source repository for these test JARs: https://repo.maven.apache.org/maven2/.' \
    > /reports/local-jars-and-licenses.txt
find /reports -type f -exec chmod 644 '{}' +
