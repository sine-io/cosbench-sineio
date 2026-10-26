#!/usr/bin/env bash

set -euo pipefail

DOCKER_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$DOCKER_DIR/.." && pwd)"
if [[ ! -f "$ROOT_DIR/VERSION" ]]; then
  ROOT_DIR="$(cd -- "$DOCKER_DIR/../.." && pwd)"
fi
VERSION=${1:-$(<"$ROOT_DIR/VERSION")}

if [[ ! "$VERSION" =~ ^[0-9A-Za-z][0-9A-Za-z.+_-]*$ ]]; then
  printf 'Invalid version: %s\nUsage: %s [version]\n' "$VERSION" "$(basename "$0")" >&2
  exit 2
fi

for VARIANT in ubuntu alpine centos; do
  IMAGE="ghcr.io/sine-io/cosbench-sineio:${VERSION}-${VARIANT}"
  if ! sudo docker image inspect "$IMAGE" >/dev/null 2>&1; then
    printf 'Image is not available locally: %s\nBuild the images first with ci/docker/build-image.sh.\n' "$IMAGE" >&2
    exit 1
  fi
done

printf 'Pushing COSBench SineIO %s images.\n' "$VERSION"
for VARIANT in ubuntu alpine centos; do
  sudo docker push "ghcr.io/sine-io/cosbench-sineio:${VERSION}-${VARIANT}"
done
