#!/usr/bin/env bash
set -Eeuo pipefail

report=${COSBENCH_PERFORMANCE_REPORT_DIR:?Set COSBENCH_PERFORMANCE_REPORT_DIR}
runtime=$(mktemp -d /tmp/cosbench-performance-profile.XXXXXX)
password=cosbench-performance-test-password
cleanup() {
    local status=$?
    trap - EXIT INT TERM
    rm -rf -- "$runtime"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "$report"
for artifact in \
    application-artifacts.txt certificate-generation.log container-cpu-limit.txt container-memory-limit.txt \
    container-pids-limit.txt driver-process.log java-version.txt lifecycle.txt maven-test.log maven-version.txt \
    performance.csv performance-summary.txt resource-samples.csv run-summary.txt warmup-first-operation.txt \
    run-1-first-operation.txt run-2-first-operation.txt run-3-first-operation.txt; do
    rm -f "$report/$artifact"
done
for trial in warmup run-1 run-2 run-3; do
    rm -f "$report/$trial-mission.xml" "$report/$trial-submit.json" \
        "$report/$trial-query-finished.json" "$report/$trial-close.json"
done
chmod 700 "$runtime"
printf '%s' "$password" > "$runtime/keystore-password"
keytool -genkeypair -alias cosbench-profile -keyalg RSA -keysize 2048 -validity 2 \
    -dname 'CN=localhost,OU=Tests,O=COSBench' \
    -ext 'SAN=dns:localhost,ip:127.0.0.1' -storetype PKCS12 \
    -keystore "$runtime/keystore.p12" -storepass "$password" -keypass "$password" \
    > "$report/certificate-generation.log" 2>&1
keytool -exportcert -rfc -alias cosbench-profile -keystore "$runtime/keystore.p12" \
    -storetype PKCS12 -storepass "$password" -file "$runtime/certificate.crt" \
    >> "$report/certificate-generation.log" 2>&1
keytool -importcert -noprompt -alias cosbench-profile -file "$runtime/certificate.crt" \
    -keystore "$runtime/truststore.p12" -storetype PKCS12 -storepass "$password" \
    >> "$report/certificate-generation.log" 2>&1

export COSBENCH_HTTP_TEST_KEYSTORE_FILE="$runtime/keystore.p12"
export COSBENCH_HTTP_TEST_TRUSTSTORE_FILE="$runtime/truststore.p12"
export COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE="$runtime/keystore-password"
export COSBENCH_HTTP_SERVICE_TOKEN=cosbench-test-service-token
export COSBENCH_PERFORMANCE_REPORT_DIR="$report"

cat /sys/fs/cgroup/cpu.max > "$report/container-cpu-limit.txt"
cat /sys/fs/cgroup/memory.max > "$report/container-memory-limit.txt"
cat /sys/fs/cgroup/pids.max > "$report/container-pids-limit.txt"
java -version > "$report/java-version.txt" 2>&1
mvn -version > "$report/maven-version.txt" 2>&1

mvn -B -ntp -f pom.xml -pl shadow/cosbench-driver-app -am \
    -Dtest=DriverPerformanceProfileTest \
    -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false package \
    > "$report/maven-test.log" 2>&1 || {
        tail -n 100 "$report/maven-test.log" >&2
        exit 1
    }

candidate_jar=shadow/cosbench-driver-app/target/cosbench-driver-app-0.1.0-SNAPSHOT.jar
printf 'driver_jar_sha256=%s\ndriver_jar_bytes=%s\n' \
    "$(sha256sum "$candidate_jar" | cut -d ' ' -f 1)" \
    "$(stat -c '%s' "$candidate_jar")" >> "$report/application-artifacts.txt"

awk -F, '
    NR > 1 && $2 == "true" {
        count++
        throughput = $10 + 0
        elapsed = $9 + 0
        throughput_sum += throughput
        throughput_squares += throughput * throughput
        elapsed_sum += elapsed
        elapsed_squares += elapsed * elapsed
        if (count == 1 || throughput < throughput_min) throughput_min = throughput
        if (count == 1 || throughput > throughput_max) throughput_max = throughput
        if (count == 1 || elapsed < elapsed_min) elapsed_min = elapsed
        if (count == 1 || elapsed > elapsed_max) elapsed_max = elapsed
    }
    END {
        if (count != 3) exit 1
        throughput_mean = throughput_sum / count
        throughput_sd = sqrt(throughput_squares / count - throughput_mean * throughput_mean)
        elapsed_mean = elapsed_sum / count
        elapsed_sd = sqrt(elapsed_squares / count - elapsed_mean * elapsed_mean)
        printf "measured_runs=%d\nthroughput_mean_ops_per_second=%.2f\nthroughput_min_ops_per_second=%.2f\nthroughput_max_ops_per_second=%.2f\nthroughput_population_cv_percent=%.2f\nthroughput_range_over_mean_percent=%.2f\nelapsed_mean_ms=%.2f\nelapsed_min_ms=%.2f\nelapsed_max_ms=%.2f\nelapsed_population_cv_percent=%.2f\n", count, throughput_mean, throughput_min, throughput_max, 100 * throughput_sd / throughput_mean, 100 * (throughput_max - throughput_min) / throughput_mean, elapsed_mean, elapsed_min, elapsed_max, 100 * elapsed_sd / elapsed_mean
    }
' "$report/performance.csv" > "$report/performance-summary.txt"

printf 'status=profile-recorded\nwarmup_runs=1\nmeasured_runs=3\ngc_reset_before=each_measured_run\nlegacy_comparison=not_performed_by_scope\n' \
    > "$report/run-summary.txt"
