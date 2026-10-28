#!/usr/bin/env bash
set -Eeuo pipefail

mc=/opt/bitnami/minio-client/bin/mc
access_key=$(< /run/secrets/minio_root_user)
secret_key=$(< /run/secrets/minio_root_password)
work_dir=$(mktemp -d)
trap 'find "$work_dir" -mindepth 1 -delete; rmdir "$work_dir"' EXIT

"$mc" alias set local "$COSBENCH_MINIO_ENDPOINT" "$access_key" "$secret_key"
"$mc" ready local
"$mc" mb --ignore-existing "local/$COSBENCH_MINIO_BUCKET"

payload="$work_dir/payload.txt"
download="$work_dir/download.txt"
printf '%s' 'cosbench scheme3 minio smoke v1' > "$payload"
"$mc" cp "$payload" "local/$COSBENCH_MINIO_BUCKET/payload.txt"
"$mc" cp "local/$COSBENCH_MINIO_BUCKET/payload.txt" "$download"
cmp "$payload" "$download"
sha256sum "$payload" "$download"
"$mc" rm "local/$COSBENCH_MINIO_BUCKET/payload.txt"

if "$mc" stat "local/$COSBENCH_MINIO_BUCKET/payload.txt" >/dev/null 2>&1; then
    printf '%s\n' 'MinIO delete probe left the object visible' >&2
    exit 1
fi

curl -fsS "$COSBENCH_TOXIPROXY_ADMIN_URL/proxies" >/dev/null
printf 'minio_endpoint=%s\nbucket=%s\nput=get=delete=passed\ntoxiproxy_admin=available\n' \
    "$COSBENCH_MINIO_ENDPOINT" "$COSBENCH_MINIO_BUCKET"
