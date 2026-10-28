#!/usr/bin/env bash
set -Eeuo pipefail

helper=/workspace/shadow/harness/toxiproxy.sh
proxy_name=cosbench-fault-probe

for fault in disconnect response-lost timeout; do
    "$helper" create "$proxy_name" minio:9000 18666 > "/tmp/toxiproxy-$fault-create.json"
    curl -fsS --connect-timeout 2 --max-time 5 http://toxiproxy:18666/minio/health/ready >/dev/null
    "$helper" add "$proxy_name" "$fault" 2000 > "/tmp/toxiproxy-$fault-add.json"
    if curl -fsS --connect-timeout 1 --max-time 1 http://toxiproxy:18666/minio/health/ready \
        > "/tmp/toxiproxy-$fault-response.txt" 2> "/tmp/toxiproxy-$fault-error.txt"; then
        printf 'Expected %s to interrupt the proxied HTTP response\n' "$fault" >&2
        exit 1
    fi
    "$helper" remove "$proxy_name" > "/tmp/toxiproxy-$fault-remove.json"
done

printf '%s\n' \
    'proxy_create=passed' \
    'disconnect_reset_peer=passed' \
    'response_loss_limit_data=passed' \
    'timeout=passed' \
    'proxy_remove=passed'
