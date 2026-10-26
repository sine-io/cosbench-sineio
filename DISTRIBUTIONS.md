## SineIO distributions

Download supported SineIO distributions from the [cosbench-sineio GitHub Releases page](https://github.com/sine-io/cosbench-sineio/releases). A release tagged `<version>-sineio` contains:

- `cosbench-<version>-sineio.tar.gz` — the runnable COSBench distribution.
- `cosbench-<version>-sineio.tar.gz.sha256` — the SHA-256 checksum for that archive.

Verify a downloaded archive from the directory containing both files. Replace `0.6.0.2` with the version you downloaded:

```sh
VERSION=0.6.0.2
sha256sum -c "cosbench-${VERSION}-sineio.tar.gz.sha256"
```

Pushing a matching `<version>-sineio` tag starts the GitHub Actions workflow, which builds the archive and publishes both the GitHub Release and three Docker images in GHCR. See [ci/docker/README.md](ci/docker/README.md) for image tags and usage. For the full release procedure and local reproduction steps, see [BUILD-sineio.md](BUILD-sineio.md).
