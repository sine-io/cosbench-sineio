#!/bin/bash

VERSION=0.4.7.9

echo "Pushing"
docker push sineio/cosbench-sineio:${VERSION}-ubuntu

docker push sineio/cosbench-sineio:${VERSION}-alpine

docker push sineio/cosbench-sineio:${VERSION}-centos
echo "Done"