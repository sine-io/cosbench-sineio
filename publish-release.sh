#!/usr/bin/env bash

set -euo pipefail

usage() {
  printf 'Usage: %s <tag> <archive> <checksum>\n' "$(basename "$0")" >&2
}

if [[ $# -ne 3 ]]; then
  usage
  exit 2
fi

TAG=$1
ARCHIVE=$2
CHECKSUM=$3

if [[ ! "$TAG" =~ ^[0-9A-Za-z][0-9A-Za-z.+_-]*-sineio$ ]]; then
  printf 'invalid SineIO release tag: %s\n' "$TAG" >&2
  exit 1
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
VERSION="$(<"$SCRIPT_DIR/VERSION")"
if [[ ! "$VERSION" =~ ^[0-9A-Za-z][0-9A-Za-z.+_-]*$ ]]; then
  printf 'VERSION must be a single safe version value\n' >&2
  exit 1
fi
EXPECTED_TAG="${VERSION}-sineio"
if [[ "$TAG" != "$EXPECTED_TAG" ]]; then
  printf 'tag %s does not match VERSION %s (expected %s)\n' "$TAG" "$VERSION" "$EXPECTED_TAG" >&2
  exit 1
fi

if [[ ! -f "$ARCHIVE" || ! -s "$ARCHIVE" ]]; then
  printf 'archive does not exist or is empty: %s\n' "$ARCHIVE" >&2
  exit 1
fi
if [[ ! -f "$CHECKSUM" || ! -s "$CHECKSUM" ]]; then
  printf 'checksum file does not exist or is empty: %s\n' "$CHECKSUM" >&2
  exit 1
fi
if [[ "$(basename -- "$ARCHIVE")" != "cosbench-${VERSION}-sineio.tar.gz" ]]; then
  printf 'archive name does not match VERSION %s: %s\n' "$VERSION" "$ARCHIVE" >&2
  exit 1
fi

if ! bash "$SCRIPT_DIR/version_unify.sh" --check; then
  printf 'Tycho version metadata is inconsistent; refusing to publish\n' >&2
  exit 1
fi

if [[ "$(wc -l < "$CHECKSUM" | tr -d ' ')" -ne 1 ]]; then
  printf 'checksum file must contain exactly one SHA-256 entry: %s\n' "$CHECKSUM" >&2
  exit 1
fi
read -r EXPECTED_SHA256 CHECKSUM_FILENAME _ < "$CHECKSUM"
if [[ ! "$EXPECTED_SHA256" =~ ^[[:xdigit:]]{64}$ ]]; then
  printf 'checksum file does not contain a SHA-256 digest: %s\n' "$CHECKSUM" >&2
  exit 1
fi
if [[ "$CHECKSUM_FILENAME" != "$(basename -- "$ARCHIVE")" ]]; then
  printf 'checksum entry does not name the archive %s\n' "$(basename -- "$ARCHIVE")" >&2
  exit 1
fi
ACTUAL_SHA256="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
  printf 'checksum mismatch for %s\n' "$ARCHIVE" >&2
  printf 'expected: %s\nactual:   %s\n' "$EXPECTED_SHA256" "$ACTUAL_SHA256" >&2
  exit 1
fi

if [[ -z "${GH_TOKEN:-}" ]]; then
  printf 'GH_TOKEN must be set before publishing a release\n' >&2
  exit 1
fi
command -v gh >/dev/null 2>&1 || {
  printf 'gh CLI is required to publish a release\n' >&2
  exit 1
}

gh release create "$TAG" \
  --verify-tag \
  --title "COSBench SineIO $VERSION" \
  --generate-notes \
  "$ARCHIVE" "$CHECKSUM"
