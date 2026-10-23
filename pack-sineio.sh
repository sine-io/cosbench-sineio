#!/usr/bin/env bash
#Copyright 2020-Present SineIO Corporation, All Rights Reserved.
#
#Copyright 2019 OpenIO Corporation, All Rights Reserved.
#
#Licensed under the Apache License, Version 2.0 (the "License");
#you may not use this file except in compliance with the License.
#You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
#Unless required by applicable law or agreed to in writing, software
#distributed under the License is distributed on an "AS IS" BASIS,
#WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#See the License for the specific language governing permissions and
#limitations under the License.
#

set -euo pipefail

PREFIX="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
VERSION="$(<"$PREFIX/VERSION")"
DIST="$PREFIX/$VERSION"
DIST_SINEIO="cosbench-$VERSION-sineio"
ARCHIVE="$PREFIX/$DIST_SINEIO.tar.gz"

if [[ ! "$VERSION" =~ ^[0-9A-Za-z][0-9A-Za-z.+_-]*$ ]]; then
  printf 'VERSION must be a single safe version value\n' >&2
  exit 1
fi

cleanup() {
  local status=$?
  rm -rf -- "$DIST" "$PREFIX/$DIST_SINEIO"
  if [[ "$status" -ne 0 ]]; then
    rm -f -- "$ARCHIVE"
  fi
  return "$status"
}
trap cleanup EXIT

if [[ -e "$DIST" ]]; then
  printf 'Deleting old %s ... ' "$DIST"
  rm -rf -- "$DIST"
  printf '[OK]\n'
fi

rm -f -- "$ARCHIVE"
printf 'calling %s/pack.sh for version %s ...\n' "$PREFIX" "$VERSION"
if ! OUT="$(cd -- "$PREFIX" && bash ./pack.sh "$VERSION" 2>&1)"; then
  printf '%s\n' "$OUT" >&2
  printf 'pack.sh failed; archive was not created\n' >&2
  exit 1
fi
printf '%s\n' "$OUT"

printf 'Moving %s to %s ... ' "$DIST" "$PREFIX/$DIST_SINEIO"
mv -- "$DIST" "$PREFIX/$DIST_SINEIO"
printf '[OK]\n'

printf 'Creating archive %s ... ' "$ARCHIVE"
tar -C "$PREFIX" -czf "$ARCHIVE" "$DIST_SINEIO"
printf '[OK]\n'

printf 'DONE -> Cosbench SineIO %s generated in %s!\n' "$VERSION" "$ARCHIVE"
