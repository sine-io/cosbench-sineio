#!/usr/bin/env bash
set -Eeuo pipefail

role=${1:?Expected controller or driver}
export COSBENCH_TLS_STORE_PASSWORD_FILE=/run/secrets/http_test_keystore_password
if [[ $role == driver ]]; then
    export SERVER_SSL_KEY_STORE_PASSWORD="$(</run/secrets/http_test_keystore_password)"
fi
export JAVA_OPTS='--add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED'
exec "/opt/cosbench/bin/cosbench-$role" --config "/opt/cosbench/conf/$role.yml" \
    --pid-file "/run/cosbench/$role.pid"
