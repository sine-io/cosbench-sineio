#!/bin/bash

: ${DRIVERS:=http://127.0.0.1:18088/driver}
: ${CONTROLLER:=true}
: ${DRIVER:=true}
: ${COSBENCH_PLUGINS:=SIO}
: ${DRIVER_PORT:=18088}


cd /cosbench
### Driver configuration
if [ "$DRIVER" = true ]; then
  # change nc to telnet tool. If use nc, pls uncommneted this.
  # Fix invalid option '-i 0' and add timeout option
  #sed -i -e 's@^TOOL_PARAMS=.*@TOOL_PARAMS="-w 1"@g' \
  #  cosbench-start.sh

  # Activate backend support for fast startup
  unset COSBENCH_OSGI
  for plugin in ${COSBENCH_PLUGINS//,/ }
  do
    case $plugin in
      'CDMI')    COSBENCH_OSGI="$COSBENCH_OSGI"' cosbench-cdmi-util_${VERSION} cosbench-cdmi-swift_${VERSION} cosbench-cdmi-base_${VERSION}' ;;
      'SWIFT')   COSBENCH_OSGI="$COSBENCH_OSGI"' cosbench-swift_${VERSION} cosbench-keystone_${VERSION}' ;;
      'OPENIO')  COSBENCH_OSGI="$COSBENCH_OSGI"' cosbench-openio_${VERSION}' ;;
      'SCALITY') COSBENCH_OSGI="$COSBENCH_OSGI"' cosbench-scality_${VERSION}' ;;
      'S3')      COSBENCH_OSGI="$COSBENCH_OSGI"' cosbench-s3_${VERSION}' ;;
      'CEPH')    COSBENCH_OSGI="$COSBENCH_OSGI"' cosbench-librados_${VERSION}' ;;
      'AMPLI')   COSBENCH_OSGI="$COSBENCH_OSGI"' cosbench-ampli_${VERSION}' ;;
      'SIO' | 'SIOV2' | 'GDAS')   COSBENCH_OSGI="$COSBENCH_OSGI"' cosbench-sineio_${VERSION}' ;;
      'GDAS')   COSBENCH_OSGI="$COSBENCH_OSGI"' cosbench-gdas_${VERSION}' ;;
    esac
  done

  # Set minimal plugins to optimize startup time
  sed -i -e 's@^OSGI_BUNDLES=.*@OSGI_BUNDLES="cosbench-log_${VERSION} cosbench-tomcat_${VERSION} cosbench-config_${VERSION} cosbench-http_${VERSION} cosbench-core_${VERSION} cosbench-core-web_${VERSION} cosbench-api_${VERSION} cosbench-mock_${VERSION} cosbench-httpauth_${VERSION} cosbench-driver_${VERSION} cosbench-driver-web_${VERSION} %COSBENCH_OSGI%"@g' start-driver.sh

  # Set custom backends
  sed -i -e "s@%COSBENCH_OSGI%@$COSBENCH_OSGI@g" start-driver.sh

fi


### Controller configuration
if [ "$CONTROLLER" = true -a -z "$DRIVERS" ]; then
  echo "Warning: No drivers specified but configured as controller."
  CONTROLLER=false
elif [ "$CONTROLLER" = true ]; then
  nbdrivers=$(echo ${DRIVERS//,/ }|wc -w)
  # Header
  cat <<EOF >conf/controller.conf
[controller]
drivers = $nbdrivers
log_level = INFO
log_file = log/system.log
archive_dir = archive
name = SineIO-Controller
url = http://127.0.0.1:19088/controller/index.html
EOF

  # Driver configuration
  index=1
  for driver in ${DRIVERS//,/ }
  do
    cat <<EOF >>conf/controller.conf
[driver$index]
name = driver$index
url = $driver
EOF
    ((index++))
  done

fi

### Start services
if [ "$CONTROLLER" = true -a "$DRIVER" = true ]; then
  echo "Starting both controller and driver"
  sh start-all.sh
elif [ "$CONTROLLER" = true ]; then
  echo "Sarting controller"
  sh start-controller.sh
elif [ "$DRIVER" = true ]; then
  echo "Starting driver"
  sh start-driver.sh 1 127.0.0.1 $DRIVER_PORT
else
  echo 'Houston, we have had a problem here.'
fi

/bin/bash
