#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
VERSION_FILE="$SCRIPT_DIR/../../VERSION"
if [[ ! -f "$VERSION_FILE" ]]; then
  VERSION_FILE="$SCRIPT_DIR/../../../VERSION"
fi
VERSION=${COSBENCH_VERSION:-$(<"$VERSION_FILE")}
DRIVERS=${DRIVERS:-http://127.0.0.1:18088/driver}

exec sudo docker run -dit --restart unless-stopped --network host --name cosbench-controller \
  -e CONTROLLER=true \
  -e DRIVER=false \
  -e "DRIVERS=$DRIVERS" \
  "ghcr.io/sine-io/cosbench-sineio:${VERSION}-centos"
