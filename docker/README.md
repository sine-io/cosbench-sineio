# cosbench-sineio

This image provides an easy way to run [COSbench](https://github.com/sine-io/cosbench-sineio.git) with the [sio] backend support.

## How to use this image

By default, COSbench starts as a controller and a driver to allow you to start
a bench.

Environment variables available are:  
- `DRIVER`: Starts as a COSbench driver (Default to `true`)
- `CONTROLLER`: Starts as a COSbench controller (Default to `true`)
- `DRIVERS`: Comma separated list of COSbench drivers to be used by the controller
 (Default to `http://127.0.0.1:18088/driver`)
- `DRIVER_PORT`: driver base port(Default to 18088)
- `COSBENCH_PLUGINS`: Comma separated list of COSbench OSGI plugins to load. The more you add, the slower it is to start (Default to `SIO`. Available values: `SIO,OPENIO,CDMI,SWIFT,SCALITY,S3,CEPH,AMPLI`)

## Start a controller and two drivers COSbench container on the same machine(via docker-compose):
```console

// Install docker-compose
curl -SL https://github.com/docker/compose/releases/download/v2.6.1/docker-compose-linux-x86_64 -o /usr/local/bin/docker-compose
sudo ln -s /usr/local/bin/docker-compose /usr/bin/docker-compose

// Pull repo and run.
git pull https://github.com/kisscelia/fav-dockerfiles.git
cd cosbench-sineio
docker-compose up -d
```

## Start a controller and a driver COSbench container at the same time:  
```console
# docker run -dit --restart unless-stopped --net=host \
-e CONTROLLER=true \
-e DRIVER=true \
-e DRIVER_PORT=18088 \
-e DRIVERS="http://192.168.0.1:18088/driver" \
-e COSBENCH_PLUGINS="SIO,OPENIO,SWIFT,S3" \
sineio/cosbench-sineio:0.5.0.0-ubuntu
```
Then you can access the COSbench Web Interface through `http://192.168.0.1:19088/controller/index.html`


## Start driver and controller step by step

### 1. Add one driver
```console
# docker run -dit --restart unless-stopped --net=host \
-e CONTROLLER=false \
-e DRIVER=true \
-e DRIVER_PORT=18188 \
-e COSBENCH_PLUGINS="SIO,OPENIO,SWIFT,S3" \
sineio/cosbench-sineio:0.5.0.0-ubuntu
```

### 2. Add more drivers on the same machine(Just change the DRIVER_PORT) if you need

```console
# docker run -dit --restart unless-stopped --net=host \
-e CONTROLLER=false \
-e DRIVER=true \
-e DRIVER_PORT=18188 \
-e COSBENCH_PLUGINS="SIO,OPENIO,SWIFT,S3" \
sineio/cosbench-sineio:0.5.0.0-ubuntu
```

### 3. Start a controller COSbench container if you need:  
```console
# docker run -dit --restart unless-stopped --net=host \
-e CONTROLLER=true \
-e DRIVER=false \
-e DRIVERS="http://192.168.0.1:18088/driver,http://192.168.0.1:18188/driver" \
sineio/cosbench-sineio:0.5.0.0-ubuntu
```
Then you can access the COSbench Web Interface through `http://192.168.0.1:19088/controller/index.html`

## Define COSbench Workloads

- Using the SIO API:
You need to start your controller with the `SIO` support.
  * Authentication
  * Type: `None`
  * Storage
  * Type: `sio`
  * Configuration: `accesskey=<accesskey>;secretkey=<scretkey>;proxyhost=<proxyhost>;proxyport=<proxyport>;endpoint=<endpoint>;no_verify_ssl=true;path_style_access=true`

- Using the OpenIO SDS Java API
You need to start your controller with the `OPENIO` support.
  * Authentication
  * Type: `None`
  * Storage
  * Type: `openio`
  * Configuration: `ns=<NAMESPACE>;account=<ACCOUNT>;proxyd-url=<OIOPROXY_URL>;ecd-url=<ECD_URL>`

- Using the Openstack Swift API with Keystone:
You need to start your controller with the `SWIFT` support.
  * Authentication
  * Type: `keystone`
  * Configuration: `username=<USERNAME>;password=<PASSWORD>;tenant_name=<TENANT>;auth_url=http://<KEYSTONE_URL>/v2.0;service=<SWIFT_SERVICE>`
  * Storage
  * Type: `swift`
  * Configuration: `storage_url=http://<SWIFT_PROXY_URL>/auth/v1.0`

- Using the AWS S3 API:
You need to start your controller with the `S3` support.
  * Authentication
  * Type: `None`
  * Storage
  * Type: `s3`
  * Configuration: `accesskey=<accesskey>;secretkey=<scretkey>;proxyhost=<proxyhost>;proxyport=<proxyport>;endpoint=<endpoint>`
