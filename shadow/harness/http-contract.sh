#!/usr/bin/env bash
set -Eeuo pipefail

repo=/workspace
report=/reports
maven_repo=/tmp/maven-repository

test -s "$COSBENCH_HTTP_TEST_CERT_FILE"
test -s "$COSBENCH_HTTP_TEST_KEYSTORE_FILE"
test -s "$COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE"
test -s "$COSBENCH_HTTP_TEST_TRUSTSTORE_FILE"
test -n "${COSBENCH_HTTP_SERVICE_TOKEN:-}"
keytool -list -keystore "$COSBENCH_HTTP_TEST_KEYSTORE_FILE" -storepass \
    "$(cat "$COSBENCH_HTTP_TEST_KEYSTORE_PASSWORD_FILE")" > "$report/http-keystore.txt"

mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f "$repo/shadow/pom.xml" \
    -pl cosbench-http-spike -am -Dtest=IdempotencyStoreTest,RequestFingerprintTest,DriverCommandControllerTest,DriverHttpContractTest,RealDriverHttpContractTest,AuthenticatedDriverClientTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test \
    > "$report/http-targeted-tests.log" 2>&1 || {
        cat "$report/http-targeted-tests.log"
        exit 1
    }

printf '%s\n' \
    'service_token_header=Authorization: Bearer' \
    'request_identity_headers=X-COSBench-Request-Id,Idempotency-Key' \
    'write_payload_mutation=none' \
    'same_key_same_payload=original status and body replayed' \
    'same_key_different_payload=409 with no second DriverService invocation' \
    'cookie_only=401' \
    'missing_write_identity=400' \
    'transport_retry=bounded, identity/body preserved' \
    'tls=real DriverService HTTPS endpoint and explicit generated truststore' \
    'retryable_statuses=502,503,504' \
    'non_retryable=4xx and unconfigured 5xx' \
    'pool=bounded endpoint concurrency, HTTP/1.1 keep-alive' \
    'mission=real COSBDriverService submit/query with Mock auth and storage' \
    'response_loss=Toxiproxy truncates real submit response; retry reuses identity and retains one Mock mission' \
    'restart_safety=Driver instance mismatch rejected with unknown-result 409 before new process executes write' \
    'idempotency_retention=Driver process lifetime; memory grows with unique writes and response bytes; no restart recovery' \
    > "$report/http-contract-summary.txt"

mvn -B -ntp -Dmaven.repo.local="$maven_repo" -f "$repo/shadow/pom.xml" \
    -pl cosbench-http-spike -Dscope=test -DoutputFile="$report/http-dependency-tree.txt" \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree

printf '%s\n' 'status=passed' 'tls_keystore=generated-test-keystore' 'test_commands=passed' \
    > "$report/http-run-summary.txt"
