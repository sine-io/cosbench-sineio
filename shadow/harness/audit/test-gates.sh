#!/usr/bin/env bash
set -Eeuo pipefail

audit_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
report_dir=${1:?Usage: test-gates.sh REPORT_DIR}
mkdir -p "$report_dir"
work_dir=$(mktemp -d)
trap 'rm -rf -- "$work_dir"' EXIT
mkdir -p "$work_dir/resources" "$work_dir/empty"
printf '%s\n' '<mapping/>' > "$work_dir/resources/required-mapping.xml"
jar --create --file "$work_dir/good.jar" -C "$work_dir/resources" .
jar --create --file "$work_dir/missing-resource.jar" -C "$work_dir/empty" .

accept() {
    local name=$1
    shift
    "$@" > "$report_dir/$name.log" 2>&1 || {
        cat "$report_dir/$name.log"
        printf 'Expected acceptance: %s\n' "$name" >&2
        exit 1
    }
    printf 'PASS accept %s\n' "$name"
}

reject() {
    local name=$1 diagnostic=$2
    shift 2
    if "$@" > "$report_dir/$name.log" 2>&1; then
        printf 'Expected rejection: %s\n' "$name" >&2
        exit 1
    fi
    grep -Fq "$diagnostic" "$report_dir/$name.log" || {
        cat "$report_dir/$name.log"
        printf 'Missing rejection diagnostic: %s\n' "$diagnostic" >&2
        exit 1
    }
    printf 'PASS reject %s\n' "$name"
}

accept normal-resource java "$audit_dir/ArtifactAudit.java" production \
    "$work_dir/good.jar" "$work_dir/resources"
reject missing-resource 'Missing packaged resource' java "$audit_dir/ArtifactAudit.java" production \
    "$work_dir/missing-resource.jar" "$work_dir/resources"

mkdir -p "$work_dir/source/org/osgi/framework" "$work_dir/source/com/intel/cosbench/api/mock"
printf '%s\n' 'package org.osgi.framework; public interface Bundle {}' \
    > "$work_dir/source/org/osgi/framework/Bundle.java"
printf '%s\n' 'public class Consumer { public org.osgi.framework.Bundle bundle; }' \
    > "$work_dir/source/Consumer.java"
printf '%s\n' 'package com.intel.cosbench.api.mock; public class MockStorage {}' \
    > "$work_dir/source/com/intel/cosbench/api/mock/MockStorage.java"
javac --release 21 -d "$work_dir/classes" "$work_dir/source/org/osgi/framework/Bundle.java" \
    "$work_dir/source/Consumer.java" "$work_dir/source/com/intel/cosbench/api/mock/MockStorage.java"
jar --create --file "$work_dir/osgi-reference.jar" -C "$work_dir/classes" Consumer.class
reject osgi-reference 'Forbidden artifact reference' java "$audit_dir/ArtifactAudit.java" production \
    "$work_dir/osgi-reference.jar"
mkdir -p "$work_dir/spring-dm/META-INF/spring"
printf '%s\n' '<beans xmlns:osgi="http://www.springframework.org/schema/osgi"><osgi:reference id="legacy" interface="example.Service"/></beans>' \
    > "$work_dir/spring-dm/META-INF/spring/osgi-context.xml"
jar --create --file "$work_dir/spring-dm.jar" -C "$work_dir/spring-dm" .
reject spring-dm-descriptor 'Forbidden artifact reference' java "$audit_dir/ArtifactAudit.java" production \
    "$work_dir/spring-dm.jar"
jar --create --file "$work_dir/mock.jar" -C "$work_dir/classes" com/intel/cosbench/api/mock
accept test-only-mock java "$audit_dir/ArtifactAudit.java" mock-test "$work_dir/mock.jar"
reject production-mock 'Mock leaked into production' java "$audit_dir/ArtifactAudit.java" production \
    "$work_dir/mock.jar"
reject unapproved-test-mock 'Mock leaked into production' java "$audit_dir/ArtifactAudit.java" test \
    "$work_dir/mock.jar"
mkdir -p "$work_dir/nested/BOOT-INF/lib"
cp "$work_dir/mock.jar" "$work_dir/nested/BOOT-INF/lib/mock.jar"
jar --create --file "$work_dir/application.jar" -C "$work_dir/nested" .
reject nested-production-mock 'Mock leaked into production' java "$audit_dir/ArtifactAudit.java" production \
    "$work_dir/application.jar"
cp "$work_dir/good.jar" "$work_dir/nested/BOOT-INF/lib/mock.jar"
jar --create --file "$work_dir/unlisted-application.jar" -C "$work_dir/nested" .
reject unlisted-packaged-dependency 'Unlisted packaged dependency' java "$audit_dir/ArtifactAudit.java" production \
    "$work_dir/unlisted-application.jar" "$work_dir/empty" "$work_dir/empty"

checksum=$(sha256sum "$work_dir/good.jar" | cut -d ' ' -f 1)
printf 'example:fixture:jar:1\t%s\tApache-2.0\n' "$checksum" > "$work_dir/dependency-lock.tsv"
printf 'example:fixture:jar:1:compile:%s\n' "$work_dir/good.jar" > "$work_dir/dependencies.txt"
: > "$work_dir/reactor.tsv"
accept admitted-dependency java "$audit_dir/BuildAudit.java" dependencies \
    "$work_dir/dependencies.txt" "$work_dir/dependency-lock.tsv" "$work_dir/reactor.tsv" "$work_dir/deps"
printf 'example:fixture:jar:2:compile:%s\n' "$work_dir/good.jar" > "$work_dir/unknown.txt"
reject unreviewed-license 'Dependency is not admitted' java "$audit_dir/BuildAudit.java" dependencies \
    "$work_dir/unknown.txt" "$work_dir/dependency-lock.tsv" "$work_dir/reactor.tsv" "$work_dir/deps"
printf 'example:fixture:jar:1\t%s\tUNKNOWN\n' "$checksum" > "$work_dir/bad-license.tsv"
reject unknown-license 'Unapproved license' java "$audit_dir/BuildAudit.java" dependencies \
    "$work_dir/dependencies.txt" "$work_dir/bad-license.tsv" "$work_dir/reactor.tsv" "$work_dir/deps"
printf 'example:fixture:jar:1\t%064d\tApache-2.0\n' 0 > "$work_dir/bad-checksum.tsv"
reject changed-checksum 'Checksum mismatch' java "$audit_dir/BuildAudit.java" dependencies \
    "$work_dir/dependencies.txt" "$work_dir/bad-checksum.tsv" "$work_dir/reactor.tsv" "$work_dir/deps"
printf 'org.junit.jupiter:junit-jupiter:jar:5.12.2:compile:%s\n' "$work_dir/good.jar" > "$work_dir/test-leak.txt"
reject production-test-dependency 'Test dependency in production scope' java "$audit_dir/BuildAudit.java" dependencies \
    "$work_dir/test-leak.txt" "$work_dir/dependency-lock.tsv" "$work_dir/reactor.tsv" "$work_dir/deps"

printf 'org.osgi:org.osgi.core:jar:6.0.0:compile:%s\n' "$work_dir/good.jar" > "$work_dir/osgi.txt"
reject osgi-dependency 'Forbidden dependency' java "$audit_dir/BuildAudit.java" dependencies \
    "$work_dir/osgi.txt" "$work_dir/dependency-lock.tsv" "$work_dir/reactor.tsv" "$work_dir/deps"
mkdir -p "$work_dir/duplicate-deps"
accept no-duplicate-classes java "$audit_dir/DuplicateClasses.java" "$work_dir/osgi-reference.jar" "$work_dir/duplicate-deps"
cp "$work_dir/osgi-reference.jar" "$work_dir/duplicate-deps/duplicate.jar"
reject duplicate-classes 'Duplicate classes' java "$audit_dir/DuplicateClasses.java" \
    "$work_dir/osgi-reference.jar" "$work_dir/duplicate-deps"
mkdir -p "$work_dir/multi-release/META-INF/versions/21"
cp "$work_dir/classes/Consumer.class" "$work_dir/multi-release/META-INF/versions/21/Consumer.class"
printf 'Manifest-Version: 1.0\nMulti-Release: true\n\n' > "$work_dir/multi-release.mf"
jar --create --file "$work_dir/multi-release.jar" --manifest "$work_dir/multi-release.mf" -C "$work_dir/multi-release" .
mkdir -p "$work_dir/multi-release-deps"
cp "$work_dir/multi-release.jar" "$work_dir/multi-release-deps/duplicate.jar"
reject multi-release-duplicate 'Duplicate classes' java "$audit_dir/DuplicateClasses.java" \
    "$work_dir/osgi-reference.jar" "$work_dir/multi-release-deps"
accept resolved-jdeps /bin/bash "$audit_dir/check-jdeps.sh" \
    "$work_dir/osgi-reference.jar" "$work_dir/classes" "$work_dir/resolved-jdeps.txt"
reject missing-jdeps 'Unresolved production dependency' /bin/bash "$audit_dir/check-jdeps.sh" \
    "$work_dir/osgi-reference.jar" "$work_dir/empty" "$work_dir/missing-jdeps.txt"

cat > "$work_dir/effective-pom.xml" <<EOF
<project><groupId>example</groupId><artifactId>fixture</artifactId><version>1</version>
  <properties><maven.compiler.release>21</maven.compiler.release></properties>
  <build><directory>$work_dir</directory><finalName>good</finalName>
    <outputDirectory>$work_dir/resources</outputDirectory><testOutputDirectory>$work_dir/empty</testOutputDirectory>
    <plugins>
      <plugin><artifactId>spring-boot-maven-plugin</artifactId><version>3.5.16</version></plugin>
      <plugin><artifactId>maven-enforcer-plugin</artifactId><version>3.6.3</version>
        <executions><execution><phase>validate</phase><goals><goal>enforce</goal></goals>
          <configuration><rules><requireJavaVersion/><requireMavenVersion/><requireProperty/><dependencyConvergence/></rules></configuration>
        </execution></executions>
      </plugin>
      <plugin><artifactId>maven-clean-plugin</artifactId><version>3.5.0</version></plugin>
      <plugin><artifactId>maven-resources-plugin</artifactId><version>3.5.0</version></plugin>
      <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version>
        <configuration><release>21</release></configuration>
      </plugin>
      <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.6.1</version></plugin>
      <plugin><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
      <plugin><artifactId>maven-dependency-plugin</artifactId><version>3.11.0</version></plugin>
    </plugins>
  </build>
</project>
EOF
accept inherited-verification java "$audit_dir/BuildAudit.java" modules "$work_dir/effective-pom.xml"
sed 's@</rules>@</rules><skip>true</skip>@' "$work_dir/effective-pom.xml" > "$work_dir/skipped-execution.xml"
reject skipped-execution 'Disabled verification execution' java "$audit_dir/BuildAudit.java" modules "$work_dir/skipped-execution.xml"
sed 's@</rules>@</rules><fail>false</fail>@' "$work_dir/effective-pom.xml" > "$work_dir/nonblocking-execution.xml"
reject nonblocking-execution 'Nonblocking Enforcer execution' java "$audit_dir/BuildAudit.java" modules "$work_dir/nonblocking-execution.xml"

write_policy_project() {
    local artifact=$1 packaging=$2 missing_plugin=${3:-}
    cat >> "$work_dir/policy-projects.xml" <<EOF
  <project><groupId>com.intel.cosbench</groupId><artifactId>$artifact</artifactId><version>1</version>
    <packaging>$packaging</packaging><properties><maven.compiler.release>21</maven.compiler.release></properties>
EOF
    if [[ $artifact != cosbench-build ]]; then
        cat >> "$work_dir/policy-projects.xml" <<'EOF'
    <build><directory>target</directory><finalName>fixture</finalName>
      <outputDirectory>target/classes</outputDirectory><testOutputDirectory>target/test-classes</testOutputDirectory>
      <plugins>
EOF
        while IFS=: read -r plugin version; do
            [[ $plugin == "$missing_plugin" ]] && continue
            cat >> "$work_dir/policy-projects.xml" <<EOF
        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>$plugin</artifactId><version>$version</version>
EOF
            if [[ $plugin == maven-compiler-plugin ]]; then
                printf '%s\n' '          <configuration><release>21</release></configuration>' \
                    >> "$work_dir/policy-projects.xml"
            elif [[ $plugin == maven-enforcer-plugin ]]; then
                cat >> "$work_dir/policy-projects.xml" <<'EOF'
          <executions><execution><phase>validate</phase><goals><goal>enforce</goal></goals>
            <configuration><rules><requireJavaVersion/><requireMavenVersion/><requireProperty/><dependencyConvergence/></rules><fail>true</fail></configuration>
          </execution></executions>
EOF
            fi
            printf '%s\n' '        </plugin>' >> "$work_dir/policy-projects.xml"
        done <<'EOF'
spring-boot-maven-plugin:3.5.16
maven-enforcer-plugin:3.6.3
maven-clean-plugin:3.5.0
maven-resources-plugin:3.5.0
maven-compiler-plugin:3.16.0
maven-surefire-plugin:3.6.1
maven-jar-plugin:3.4.2
maven-dependency-plugin:3.11.0
EOF
        cat >> "$work_dir/policy-projects.xml" <<'EOF'
      </plugins>
    </build>
EOF
    fi
    if [[ $artifact == cosbench-build ]]; then
        cat >> "$work_dir/policy-projects.xml" <<'EOF'
    <modules><module>shadow</module></modules>
EOF
    fi
    printf '%s\n' '  </project>' >> "$work_dir/policy-projects.xml"
}

write_policy_fixture() {
    local missing_plugin=${1:-}
    printf '%s\n' '<projects>' > "$work_dir/policy-projects.xml"
    write_policy_project cosbench-scheme3-shadow pom "$missing_plugin"
    write_policy_project audit-module jar
    write_policy_project cosbench-build pom
    printf '%s\n' '</projects>' >> "$work_dir/policy-projects.xml"
}

write_policy_fixture
accept root-aggregator-policy-exemption java "$audit_dir/BuildAudit.java" modules "$work_dir/policy-projects.xml"
write_policy_fixture maven-clean-plugin
reject missing-parent-plugin 'Missing required pinned plugin in cosbench-scheme3-shadow: maven-clean-plugin' \
    java "$audit_dir/BuildAudit.java" modules "$work_dir/policy-projects.xml"

mkdir -p "$work_dir/license-repo/example/fixture/1"
cat > "$work_dir/license-repo/example/fixture/1/fixture-1.pom" <<'EOF'
<project><licenses>
  <license><name>Eclipse Public License 2.0</name><url>https://www.eclipse.org/legal/epl-v20.html</url></license>
  <license><name>LGPL-2.1-only</name><url>https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html</url></license>
</licenses></project>
EOF
check_license_alternative() {
    java "$audit_dir/BuildAudit.java" lock-candidate "$work_dir/license-repo" "$work_dir/dependencies.txt" \
        | awk -F '\t' '$3 != "EPL-2.0" { failed=1 } END { exit (NR != 1 || failed) }'
}
accept accurate-license-alternative check_license_alternative

shadow_dir=$(cd -- "$audit_dir/../.." && pwd)
parent_pom=$(realpath --relative-to="$work_dir" "$shadow_dir/pom.xml")
cat > "$work_dir/pom.xml" <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.intel.cosbench.shadow</groupId><artifactId>cosbench-scheme3-shadow</artifactId>
    <version>0.1.0-SNAPSHOT</version><relativePath>$parent_pom</relativePath>
  </parent>
  <artifactId>audit-convergence-fixture</artifactId>
  <dependencies>
    <dependency><groupId>commons-beanutils</groupId><artifactId>commons-beanutils</artifactId><version>1.9.4</version></dependency>
  </dependencies>
</project>
EOF
maven=(mvn -B -ntp -C -Dmaven.repo.local=/tmp/maven-repository -f "$work_dir/pom.xml")
accept convergent-dependencies "${maven[@]}" validate
sed -i 's@</dependencies>@<dependency><groupId>commons-collections</groupId><artifactId>commons-collections</artifactId><version>3.2.1</version></dependency></dependencies>@' "$work_dir/pom.xml"
reject divergent-dependencies 'Dependency convergence error' "${maven[@]}" validate
reject lower-java-release 'Every shadow module must use' "${maven[@]}" -Dmaven.compiler.release=17 validate
