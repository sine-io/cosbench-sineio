#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
VERSION_FILE="$SCRIPT_DIR/../../VERSION"
if [[ ! -f "$VERSION_FILE" ]]; then
  VERSION_FILE="$SCRIPT_DIR/../../../VERSION"
fi
VERSION=${COSBENCH_VERSION:-$(<"$VERSION_FILE")}
DRIVER_PORT=${DRIVER_PORT:-18088}
COSBENCH_PLUGINS=${COSBENCH_PLUGINS:-SIO}

exec sudo docker run -dit --restart unless-stopped --network host --name "cosbench-driver-${DRIVER_PORT}" \
  -e CONTROLLER=false \
  -e DRIVER=true \
  -e "DRIVER_PORT=$DRIVER_PORT" \
  -e "COSBENCH_PLUGINS=$COSBENCH_PLUGINS" \
  "ghcr.io/sine-io/cosbench-sineio:${VERSION}-ubuntu"
