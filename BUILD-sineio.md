# SineIO build and GitHub Release

This document covers the SineIO Tycho distribution. The older Eclipse workflow in [BUILD.md](BUILD.md) remains useful for general COSBench development; Eclipse **4.27.0** with **JDK 1.8** is a known working setup. Manta remains on its separate build path and is not part of the Tycho SineIO release.

## What the workflows do

The `Tycho verification` workflow ([`.github/workflows/tycho-verify.yml`](.github/workflows/tycho-verify.yml)) runs on branch pushes and pull requests. It builds and checks the 30 non-Manta OSGi bundles, but does not publish a release.

The `SineIO release` workflow ([`.github/workflows/sineio-release.yml`](.github/workflows/sineio-release.yml)) runs when a tag matching `<version>-sineio` is pushed. It builds the distribution once, shares the archive between jobs, publishes the Ubuntu, Alpine, and Rocky Linux 8 images to GHCR, and then creates a GitHub Release with the archive and checksum attached. The individual bundle JARs are packaged inside the distribution archive rather than uploaded as separate release assets:

- `cosbench-<version>-sineio.tar.gz`
- `cosbench-<version>-sineio.tar.gz.sha256`

The build uses the repository's pinned Maven Docker image on `linux/amd64`; Docker images are published for `linux/amd64`. The image job uses `GITHUB_TOKEN` with `packages: write`, and the release job uses `contents: write`. No Docker Hub credentials or manual release creation are needed. Make the GHCR package public once in the repository's Packages settings if anonymous pulls are required.

## Prepare and validate a release

Choose a new version that has not already been released. Synchronize the version in `VERSION`, the root Maven POM, the 30 Tycho module POMs, and their bundle manifests:

```sh
RELEASE_VERSION=0.6.0.3
./version_unify.sh "$RELEASE_VERSION"
./version_unify.sh --check
git diff --check
```

Review the generated metadata changes and any release notes, then run the same build and package checks locally:

```sh
bash ci/build-tycho.sh "/tmp/cosbench-tycho-${RELEASE_VERSION}"
```

This command requires Docker access through `sudo` and network access to pull the pinned Maven image if it is not already local. It does not require Maven or Java installed on the host. The script checks the checked-in P2 repository checksum, runs `mvn clean verify` with Tycho 1.7.0, confirms all 30 expected bundle JARs, packages the runnable distribution, checks its contents, and verifies the generated SHA-256. It runs the build in a temporary container and removes that container when finished.

The results directory contains the build logs and these release files:

```text
/tmp/cosbench-tycho-<version>/artifact/cosbench-<version>-sineio.tar.gz
/tmp/cosbench-tycho-<version>/artifact/cosbench-<version>-sineio.tar.gz.sha256
```

It also contains `maven-build.log`, `pack.log`, `p2-check.log`, `archive-files.txt`, and the bundle lists for diagnosing a failed check. The script works on a copy of the source tree and does not modify the working tree's generated distribution files. Spring Boot artifacts under `release/main` are intentionally excluded from this OSGi archive.

## Push the version and release tag

Review `git status --short` and commit the synchronized version metadata and any intended source or release-note changes to the release branch. Keep unrelated working-tree changes out of the release commit. Push that commit and wait for the branch's `Tycho verification` workflow to pass. The version commit must be present on GitHub before tagging it.

From that exact commit, create and push a tag whose name is exactly the version in `VERSION` followed by `-sineio`:

```sh
VERSION="$(<VERSION)"
TAG="${VERSION}-sineio"
git tag -a "$TAG" -m "COSBench SineIO $VERSION"
git push origin "$TAG"
```

Pushing the tag starts the `SineIO release` workflow. It checks the tag against `VERSION`, builds the tagged source, verifies the package and checksum, publishes three GHCR images, then runs `publish-release.sh` to create the GitHub Release and upload the two assets. A mismatched tag or inconsistent version metadata fails before publication. A branch push alone never creates a release.

## Verify the published assets

Open the [GitHub Releases page](https://github.com/sine-io/cosbench-sineio/releases), select the new `<version>-sineio` release, and confirm that both expected files are attached. Download both files into the same directory, set `VERSION` to the release version, and run:

```sh
VERSION=0.6.0.3 # replace with the released version
sha256sum -c "cosbench-${VERSION}-sineio.tar.gz.sha256"
```

The check must report `OK`. Pull the published image tags to confirm GHCR access:

```sh
sudo docker pull "ghcr.io/sine-io/cosbench-sineio:${VERSION}-ubuntu"
sudo docker pull "ghcr.io/sine-io/cosbench-sineio:${VERSION}-alpine"
sudo docker pull "ghcr.io/sine-io/cosbench-sineio:${VERSION}-centos"
```

If the workflow fails, inspect the `SineIO release` run's build, image-publish, or release-publish job. Check that the tag points to the pushed version commit, the tag equals `VERSION-sineio`, and `./version_unify.sh --check` passes before retrying.

## Local build without publishing

To reproduce the build at any time, choose an output directory and run:

```sh
bash ci/build-tycho.sh /tmp/cosbench-tycho-results
```

The archive and checksum are written under `/tmp/cosbench-tycho-results/artifact/`; diagnostics are left alongside them. This command builds and verifies artifacts only. Publishing is performed by the tag-triggered GitHub Actions workflow.
