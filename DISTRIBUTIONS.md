## SineIO distributions

Download supported SineIO distributions from the [cosbench-sineio GitHub Releases page](https://github.com/sine-io/cosbench-sineio/releases). A release tagged `<version>-sineio` contains:

- `cosbench-<version>-sineio.tar.gz` — the runnable COSBench distribution.
- `cosbench-<version>-sineio.tar.gz.sha256` — the SHA-256 checksum for that archive.

Verify a downloaded archive from the directory containing both files. Replace `0.6.0.2` with the version you downloaded:

```sh
VERSION=0.6.0.2
sha256sum -c "cosbench-${VERSION}-sineio.tar.gz.sha256"
```

The `<version>-sineio` workflow and Docker instructions are archived under [`legacy/osgi/`](legacy/osgi/); they do not publish Java 21 candidate artifacts. For the historical release format and rollback rebuild, see [BUILD-sineio.md](BUILD-sineio.md). Build the current Java 21 candidate using [BUILD-java21.md](BUILD-java21.md).
