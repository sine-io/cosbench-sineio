#!/bin/bash

docker run -dit --restart unless-stopped --net=host \
-e CONTROLLER=false \
-e DRIVER=true \
-e DRIVER_PORT=18288 \
-e COSBENCH_PLUGINS="SIO" \
sineio/cosbench-sineio:0.4.7.9-centos
