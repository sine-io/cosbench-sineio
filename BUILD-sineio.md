Overview
--------

The following procedure indicates how to compile and package cosbench for SineIO. 
First, please read [BUILD.md](BUILD.md).


Development Environment
-----------------------

To get a running completion environment, please refer to the same section in [BUILD.md#development-environment](BUILD.md#development-environment).

Note that Eclipse **4.27.0** is a known working release. You must use **JDK 1.8** and configure eclipse this way:

1. in `Window -> Preferences -> Java -> Prefered JRE` ensure a JDK 1.8 is selected.

2. in `Window -> Preferences -> Java -> Compiler` ensure `Compiler compliance level` is set to **1.8**.

Releasing a new version
-----------------------

The GitHub Actions Tycho workflow builds and checks the 30 non-Manta OSGi bundles on branch pushes and pull requests. A pushed tag named `${VERSION}-sineio` builds the same distribution, verifies its archive and SHA-256 checksum, and creates a GitHub Release with both files attached. Manta remains on its separate build path.

When preparing a release:

1. Choose the release version and run `./version_unify.sh <version>`. This synchronizes `VERSION`, the root and Tycho module POMs, and the 30 Tycho Bundle manifests. `./version_unify.sh --check` verifies that they still agree.

2. Commit and push the version change. The branch push runs verification but does not publish a release.

3. After the version commit is on GitHub, create and push the matching `${VERSION}-sineio` tag. The tag workflow builds the archive and publishes it with its `.sha256` checksum. A tag whose version differs from `VERSION` fails before a release is created.

For a local Docker build and package check, run `bash ci/build-tycho.sh /tmp/cosbench-tycho-results`. The script uses the repository's pinned `linux/amd64` Maven image, checks the embedded P2 repository, builds with Tycho 1.7.0, and leaves the archive, checksum, and logs in the results directory. Do not copy Spring Boot artifacts from `release/main` into the OSGi distribution.
