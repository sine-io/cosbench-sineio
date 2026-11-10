# Archived OSGi Build

This directory preserves the old Tycho/PDE source tree, checked-in P2 inputs,
providers outside the Java 21 target, packaging and release scripts, and old
runtime metadata. It is not an active Maven module, CI workflow, Docker build
input, or Java 21 distribution input.

The runnable rollback distribution remains the verified
`0.6.0.2-sineio` archive with SHA-256
`1421f48a55673e42edd73c7b09a610b2b71ef94c70d46db3fb74dd3c34d22a06`. The
matching source tag points to commit
`cd18daff2f6f97f97409f4709d00bbd7389676d6`.

To reproduce the old source build, create a detached worktree at that tag and
run its original script from that checkout:

```sh
git worktree add --detach /tmp/cosbench-legacy-0.6.0.2 0.6.0.2-sineio
bash /tmp/cosbench-legacy-0.6.0.2/ci/build-tycho.sh /tmp/cosbench-legacy-rebuild
```

Keep the rollback archive, source checkout, original INI files, and historical
data together through the migration window. The root Maven reactor does not
reference this directory.
