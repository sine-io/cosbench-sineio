#!/usr/bin/env bash
#
#Copyright 2013 Intel Corporation, All Rights Reserved.
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

if [ $# -lt 1 ];
then
        echo Usage:  $0 {version}
        exit 1
fi

if [[ ! "$1" =~ ^[0-9A-Za-z][0-9A-Za-z.+_-]*$ ]]; then
        echo "Invalid version: $1" >&2
        exit 1
fi

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEST="$ROOT_DIR/$1"

echo "Build up main structure"
mkdir "$DEST"

shopt -s nullglob
for RELEASE_ENTRY in "$ROOT_DIR"/release/*; do
        # release/main is owned by the separate Spring Boot distribution.
        [[ "$(basename "$RELEASE_ENTRY")" == main ]] && continue
        cp -f -R "$RELEASE_ENTRY" "$DEST/"
done
shopt -u nullglob
cp -f -R "$ROOT_DIR"/dist/* "$DEST/"
cp -f -R "$ROOT_DIR"/docker "$DEST/"

cp -f "$ROOT_DIR/LICENSE" "$DEST/"
cp -f "$ROOT_DIR/NOTICE" "$DEST/"
cp -f "$ROOT_DIR/VERSION" "$DEST/"

cp -f "$ROOT_DIR/README.md" "$DEST/"
cp -f "$ROOT_DIR/BUILD.md" "$DEST/"
cp -f "$ROOT_DIR/BUILD-sineio.md" "$DEST/"

cp -f "$ROOT_DIR/CHANGELOG" "$DEST/"
cp -f "$ROOT_DIR/CHANGELOG-sineio" "$DEST/"
cp -f "$ROOT_DIR/TODO.md" "$DEST/"

cp -f "$ROOT_DIR/COSBenchUserGuide.pdf" "$DEST/"
cp -f "$ROOT_DIR/COSBenchAdaptorDevGuide.pdf" "$DEST/"
cp -f "$ROOT_DIR/3rd-party-licenses.pdf" "$DEST/"
cp -f "$ROOT_DIR/pkg.lst" "$DEST/"


echo "$(date +%Y%m%d)" > "$DEST/BUILD.no"

echo "Build up adaptor example environment"
mkdir "$DEST/ext"
cp -f -R "$ROOT_DIR"/ext/* "$DEST/ext/"
