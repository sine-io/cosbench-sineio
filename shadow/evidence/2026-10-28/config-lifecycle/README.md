# GATE-CFG replay evidence

Recorded 2026-10-28 from the Java 21 Docker/MinIO harness. This is the executable evidence for the #03 spike; it is intentionally separate from the baseline ticket's still-open human acceptance.

## Reproduce

From the repository root:

```sh
report_dir=$(mktemp -d /tmp/cosbench-scheme3-03.XXXXXX)
shadow/harness/run.sh "$report_dir"
cat "$report_dir/config-lifecycle-summary.txt"
cat "$report_dir/cleanup-status.txt"
```

The harness invokes all Docker commands through `sudo`, creates a project-scoped MinIO/Toxiproxy environment, and removes the containers, network, volume, certificate containers, and temporary secrets on success, failure, or interruption. The normal replay also runs the Java 21 clean Maven verify and the existing #02 MinIO and HTTP-fault probes.

## Observed result

The replay passed:

```text
status=passed
schema=1
controller_migration=passed
driver_migration=passed
archive_read_before_stop=passed
archive_read_after_restart=passed
controller_driver_health=passed
term_port_release=passed
term_pid_cleanup=passed
source_and_archive_checksums=unchanged
```

The full harness reported `run_status=0`, `cleanup_status=0`, with no remaining project containers, networks, volumes, or certificate containers. The migration reports contain source/destination checksums and field-level mapping; secrets are not present. The runtime module is compiled with `maven.compiler.release=21` and packaged as a Spring Boot executable JAR.

## Scope and open acceptance inputs

The replay uses the versioned legacy Controller/Driver fixtures from `.scratch/scheme3-migration/baseline/fixtures/` and a representative archive fixture under `shadow/fixtures/archive/legacy/`. The old baseline ticket records that a real historical archive and usable SineIO/S3 endpoints still require human input. This evidence therefore proves non-destructive behavior and the shared configuration contract, but does not close those external acceptance gaps or the authenticated HTTP contract owned by #06.
