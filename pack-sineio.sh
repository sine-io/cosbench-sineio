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

PREFIX="$(dirname $0)"
VERSION=$(cat VERSION)
DIST="$PREFIX/$VERSION"
DIST_SINEIO="cosbench-$VERSION-sineio"


if test -d $DIST; then
  echo -n "Deleting old $DIST ... "
  rm -rf $DIST
  echo "[OK]"
fi

echo -n "calling $PREFIX/pack.sh for version $VERSION ... "
OUT="$($PREFIX/pack.sh $VERSION 2>&1)"
echo "[OK]:"
echo "$OUT"

echo -n "Moving $DIST to $PREFIX/$DIST_SINEIO ... "
mv $DIST $PREFIX/$DIST_SINEIO
echo "[OK]"

echo -n "Creating archive $PREFIX/$DIST_SINEIO.tar.gz ... "
tar -C $PREFIX -czf $DIST_SINEIO.tar.gz $DIST_SINEIO
echo "[OK]"

echo -n "Removing $PREFIX/$DIST_SINEIO ... "
rm -rf $PREFIX/$DIST_SINEIO
echo "[OK]"

echo "DONE -> Cosbench SineIO $VERSION generated in $PREFIX/$DIST_SINEIO.tar.gz!"
exit 0
