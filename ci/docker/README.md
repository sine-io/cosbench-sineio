# COSBench SineIO Docker images

Images are published to GitHub Container Registry (GHCR) by the same tag-triggered GitHub Actions workflow that creates the SineIO GitHub Release. No Docker Hub account or repository secret is used. A release for version `0.6.0.2` publishes:

```text
ghcr.io/sine-io/cosbench-sineio:0.6.0.2-ubuntu
ghcr.io/sine-io/cosbench-sineio:0.6.0.2-alpine
ghcr.io/sine-io/cosbench-sineio:0.6.0.2-centos
```

Replace `0.6.0.2` with the desired release version. Images are built for `linux/amd64`. The suffixes select Ubuntu Jammy, Alpine, and Rocky Linux 8; `centos` remains in the tag for compatibility. The standalone GitHub Release also includes the runnable `.tar.gz` distribution and its SHA-256 checksum.

## Automated publishing

When a maintainer pushes a tag named `<VERSION>-sineio`, GitHub Actions builds and validates the Tycho archive once, shares it between jobs, builds and pushes all three GHCR images, and then creates the GitHub Release with the archive and checksum. A normal branch push or pull request only runs verification and never publishes images or a release.

The image job uses the repository's `GITHUB_TOKEN` with `packages: write`; no Docker Hub credentials are required. The Dockerfiles include the source repository label so GHCR associates the package with this repository. After the first publish, make the GHCR package public in the repository's Packages settings if users should be able to pull images without authenticating.

## Pull an image

```sh
VERSION=0.6.0.2
sudo docker pull "ghcr.io/sine-io/cosbench-sineio:${VERSION}-ubuntu"
```

For a private package, log in to `ghcr.io` with a GitHub token that has package read access before pulling.

## Build or push images locally

Download the archive from the matching GitHub Release and verify it before building. From the repository root:

```sh
VERSION="$(<VERSION)"
TAG="${VERSION}-sineio"
ARCHIVE="cosbench-${VERSION}-sineio.tar.gz"

curl -fL -o "$ARCHIVE" \
  "https://github.com/sine-io/cosbench-sineio/releases/download/${TAG}/${ARCHIVE}"
curl -fL -o "${ARCHIVE}.sha256" \
  "https://github.com/sine-io/cosbench-sineio/releases/download/${TAG}/${ARCHIVE}.sha256"
sha256sum -c "${ARCHIVE}.sha256"
bash ci/docker/build-image.sh "$VERSION" "$ARCHIVE"
```

The local build script uses a temporary build context and creates the same three GHCR-named tags. To push them manually, authenticate `sudo docker` to GHCR with a GitHub token that has package write access, then run:

```sh
sudo docker login ghcr.io --username <github-user>
bash ci/docker/push-image.sh "$VERSION"
```

The same commands and paths work from the root of an extracted SineIO distribution because the package preserves the `ci/docker/` directory. Do not commit the downloaded release archive into the repository.

## Run one driver and controller

The example scripts read `VERSION` from either the source checkout or extracted distribution. Set `COSBENCH_VERSION` to override it. Start a driver, then start the controller with that driver's URL:

```sh
DRIVER_PORT=18088 bash ci/docker/examples/run-driver-ubuntu.sh
DRIVERS=http://127.0.0.1:18088/driver bash ci/docker/examples/run-controller-ubuntu.sh
```

The scripts use host networking and `sudo docker run`. The controller UI is available at `http://<controller-host>:19088/controller/index.html`. Use the matching `run-driver-<variant>.sh` and `run-controller-<variant>.sh` scripts for Alpine or Rocky Linux 8 images.

To stop and remove the example containers:

```sh
sudo docker rm -f cosbench-controller cosbench-driver-18088
```

## Run the multi-container Compose example

The Compose files start one controller and three drivers. They bind logs and workload archives under `/data/cosbench`, so create those host directories first. Set `COSBENCH_VERSION` to the version of the images:

```sh
VERSION="$(<VERSION)"
sudo mkdir -p /data/cosbench/archive \
  /data/cosbench/controller-log \
  /data/cosbench/driver1-log \
  /data/cosbench/driver2-log \
  /data/cosbench/driver3-log
sudo env COSBENCH_VERSION="$VERSION" docker compose \
  -f ci/docker/examples/docker-compose-ubuntu.yml up -d
```

Replace `docker-compose-ubuntu.yml` with the `alpine` or `centos` file for another image variant. Stop the deployment with:

```sh
sudo env COSBENCH_VERSION="$VERSION" docker compose \
  -f ci/docker/examples/docker-compose-ubuntu.yml down
```

The examples use host networking; the driver ports are `18088`, `18188`, and `18288`, and the controller listens on `19088`.

## Define workloads

- **SIO API:** start the controller with the `SIO` plugin. Use Authentication type `None`, Storage type `sio`, and a configuration such as `accesskey=<accesskey>;secretkey=<secretkey>;proxyhost=<proxyhost>;proxyport=<proxyport>;endpoint=<endpoint>;no_verify_ssl=true;path_style_access=true`.
- **OpenIO SDS Java API:** start the controller with the `OPENIO` plugin. Use Authentication type `None`, Storage type `openio`, and `ns=<NAMESPACE>;account=<ACCOUNT>;proxyd-url=<OIOPROXY_URL>;ecd-url=<ECD_URL>`.
- **OpenStack Swift with Keystone:** start the controller with the `SWIFT` plugin. Use Authentication type `keystone`, configure `username=<USERNAME>;password=<PASSWORD>;tenant_name=<TENANT>;auth_url=http://<KEYSTONE_URL>/v2.0;service=<SWIFT_SERVICE>`, and use Storage type `swift` with `storage_url=http://<SWIFT_PROXY_URL>/auth/v1.0`.
- **AWS S3 API:** start the controller with the `S3` plugin. Use Authentication type `None`, Storage type `s3`, and `accesskey=<accesskey>;secretkey=<secretkey>;proxyhost=<proxyhost>;proxyport=<proxyport>;endpoint=<endpoint>`.
