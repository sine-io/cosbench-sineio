#!/bin/bash

VERSION=0.4.7.9

FILENAME=cosbench-${VERSION}-sineio.tar.gz
cp ../${FILENAME} .

if [ -f $FILENAME ]; then
  echo "Building..."
  docker build -f Dockerfile-ubuntu -t sineio/cosbench-sineio:${VERSION}-ubuntu --build-arg VERSION=$VERSION .
  echo "next..."
  docker build -f Dockerfile-alpine -t sineio/cosbench-sineio:${VERSION}-alpine --build-arg VERSION=$VERSION .
  echo "next..."
  docker build -f Dockerfile-centos -t sineio/cosbench-sineio:${VERSION}-centos --build-arg VERSION=$VERSION .
  echo "Done"

  rm -f ./${FILENAME}
else
  echo "${FILENAME}" does not exist, please check it.
fi