# Production packaging

`build-production.sh` consumes the three executable artifacts from the
ordinary Java 21 Maven reactor:

* `cosbench-controller-app`;
* `cosbench-driver-app`, including the `sio`, `siov1`, `gdas` and `s3`
  ServiceLoader libraries; and
* `cosbench-operator-cli`.

It copies only runtime files into a new staging directory. The old `release/`
and `dist/` trees are never read. The staging tree contains stable application
names under `lib/`, runtime commands under `bin/`, versioned YAML
templates, supported Provider examples, small workload examples, license
files and machine-readable manifests. `SHA256SUMS` covers every file except
itself.

The archive uses sorted tar entries, Unix owner/group zero, a fixed mtime and a
gzip stream without a timestamp. Rebuilding the same artifacts with the same
`SOURCE_DATE_EPOCH` produces the same archive checksum.

Build it after `mvn -B -ntp clean verify`:

```sh
bash shadow/packaging/build-production.sh --force
```

The default output is `out/cosbench-production/`. Use `--skip-archive` when a
staging tree is needed for a Docker build. `verify-production.sh` performs the
archive scan, nested Boot JAR scan, checksum/rebuild check and a clean Java 21
empty-install health/stop probe. Pass `--build-images` to build the production
and development images.

The production Dockerfile receives only the staging directory as its build
context. It uses a Java 21 JRE, a non-root `cosbench` user and a role-aware
healthcheck. The development Dockerfile is deliberately separate and copies
the source tree, Maven reactor, test harness and MinIO Compose files.
