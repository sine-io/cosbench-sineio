#!/usr/bin/env bash

set -euo pipefail

DOCKER_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$DOCKER_DIR/.." && pwd)"
if [[ ! -f "$ROOT_DIR/VERSION" ]]; then
  ROOT_DIR="$(cd -- "$DOCKER_DIR/../.." && pwd)"
fi
VERSION=${1:-$(<"$ROOT_DIR/VERSION")}
ARCHIVE=${2:-"$ROOT_DIR/cosbench-${VERSION}-sineio.tar.gz"}

if [[ ! "$VERSION" =~ ^[0-9A-Za-z][0-9A-Za-z.+_-]*$ ]]; then
  printf 'Invalid version: %s\nUsage: %s [version] [archive]\n' "$VERSION" "$(basename "$0")" >&2
  exit 2
fi
if [[ ! -s "$ARCHIVE" ]]; then
  printf 'Release archive is missing or empty: %s\n' "$ARCHIVE" >&2
  printf 'Download it from the matching GitHub Release before building images.\n' >&2
  exit 1
fi

BUILD_CONTEXT="$(mktemp -d "${TMPDIR:-/tmp}/cosbench-image.XXXXXX")"
trap 'rm -rf -- "$BUILD_CONTEXT"' EXIT
cp -- "$ARCHIVE" "$BUILD_CONTEXT/cosbench-sineio.tar.gz"
cp -- "$DOCKER_DIR/start-cosbench.sh" "$BUILD_CONTEXT/"

for VARIANT in ubuntu alpine centos; do
  IMAGE="ghcr.io/sine-io/cosbench-sineio:${VERSION}-${VARIANT}"
  printf 'Building %s\n' "$IMAGE"
  sudo docker build \
    --build-arg "VERSION=$VERSION" \
    --file "$DOCKER_DIR/Dockerfile-$VARIANT" \
    --tag "$IMAGE" \
    "$BUILD_CONTEXT"
done

printf 'Built Docker images for COSBench SineIO %s.\n' "$VERSION"
