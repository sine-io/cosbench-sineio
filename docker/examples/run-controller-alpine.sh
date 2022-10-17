#!/bin/bash

docker run -dit --restart unless-stopped --net=host --name cosbench-controller \
-e CONTROLLER=true \
-e DRIVER=false \
-e DRIVERS="http://127.0.0.1:18088/driver,http://127.0.0.1:18188/driver" \
sineio/cosbench-sineio:0.4.7.9-alpine
