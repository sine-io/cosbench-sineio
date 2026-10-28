#!/usr/bin/env bash
set -Eeuo pipefail

admin_url=${COSBENCH_TOXIPROXY_ADMIN_URL:-http://127.0.0.1:${COSBENCH_TOXIPROXY_ADMIN_PORT:-18474}}
command_name=${1:-}

valid_name() {
    [[ ${1:-} =~ ^[a-zA-Z0-9_-]+$ ]]
}

request() {
    curl --fail --silent --show-error --connect-timeout 2 --max-time 10 "$@"
}

case "$command_name" in
    list)
        request "$admin_url/proxies"
        ;;
    create)
        name=${2:?Usage: toxiproxy.sh create NAME UPSTREAM_HOST:PORT [LISTEN_PORT]}
        upstream=${3:?Usage: toxiproxy.sh create NAME UPSTREAM_HOST:PORT [LISTEN_PORT]}
        listen_port=${4:-18666}
        valid_name "$name" || { printf '%s\n' 'Invalid proxy name' >&2; exit 2; }
        [[ $upstream =~ ^[a-zA-Z0-9._-]+:[0-9]{1,5}$ ]] || { printf '%s\n' 'Invalid upstream host:port' >&2; exit 2; }
        [[ $listen_port =~ ^[0-9]{1,5}$ ]] || { printf '%s\n' 'Invalid listen port' >&2; exit 2; }
        request -X POST -H 'Content-Type: application/json' \
            --data "{\"name\":\"$name\",\"listen\":\"0.0.0.0:$listen_port\",\"upstream\":\"$upstream\"}" \
            "$admin_url/proxies"
        ;;
    add)
        name=${2:?Usage: toxiproxy.sh add NAME disconnect|response-lost|timeout [TIMEOUT_MS]}
        fault=${3:?Usage: toxiproxy.sh add NAME disconnect|response-lost|timeout [TIMEOUT_MS]}
        valid_name "$name" || { printf '%s\n' 'Invalid proxy name' >&2; exit 2; }
        case "$fault" in
            disconnect)
                toxic_name=cosbench_disconnect
                toxic_type=reset_peer
                attributes='{}'
                ;;
            response-lost)
                toxic_name=cosbench_response_lost
                toxic_type=limit_data
                attributes='{"bytes":0}'
                ;;
            timeout)
                toxic_name=cosbench_timeout
                timeout_ms=${4:-2000}
                [[ $timeout_ms =~ ^[0-9]{1,6}$ ]] || { printf '%s\n' 'Invalid timeout duration' >&2; exit 2; }
                toxic_type=timeout
                attributes="{\"timeout\":$timeout_ms}"
                ;;
            *)
                printf '%s\n' 'Fault must be disconnect, response-lost, or timeout' >&2
                exit 2
                ;;
        esac
        request -X POST -H 'Content-Type: application/json' \
            --data "{\"name\":\"$toxic_name\",\"type\":\"$toxic_type\",\"stream\":\"downstream\",\"toxicity\":1.0,\"attributes\":$attributes}" \
            "$admin_url/proxies/$name/toxics"
        ;;
    remove)
        name=${2:?Usage: toxiproxy.sh remove NAME}
        valid_name "$name" || { printf '%s\n' 'Invalid proxy name' >&2; exit 2; }
        request -X DELETE "$admin_url/proxies/$name"
        ;;
    *)
        printf '%s\n' \
            'Usage: toxiproxy.sh list | create NAME UPSTREAM_HOST:PORT [LISTEN_PORT] | add NAME disconnect|response-lost|timeout [TIMEOUT_MS] | remove NAME' \
            >&2
        exit 2
        ;;
esac
