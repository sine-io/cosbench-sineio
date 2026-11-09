# Java 21 release acceptance and rollback

Use the [Java 21 build](../BUILD-java21.md) and the packaged
[installation guide](../shadow/packaging/templates/docs/production-install.md).
Validate one fixed archive and build its production image from that archive's
extracted files. Application JAR hashes must match #19 and the #20 Driver
profile; each hash covers its entire dependency closure. A changed candidate
fails the release check until affected integration/performance evidence has
been repeated. A development-image smoke cannot satisfy REL-01.

Run from the repository root, with the previous complete archive already
retained locally:

```sh
python3 shadow/packaging/validate-release.py \
  --archive out/java21-release/cosbench-0.6.0.2-java21.tar.gz \
  --legacy-archive /protected/rollback/cosbench-0.6.0.2-sineio.tar.gz \
  --report out/release-acceptance-NEW
```

The report directory must not exist. Python 3.12+, `sudo docker`,
`sha256sum`, and network access to the pinned images are required. Every Docker
command uses `sudo`. The runner records commands/exit codes, archive and JAR
checksums, image IDs/sizes, health, workload operation/byte counts, stops,
old-data reads and rollback. Application containers mount only release files,
configuration, test secrets and isolated data; the Maven image is also used
as a Java 21 host for the archive and as an external HTTP probe. It does not
put sources or test classes on an application's classpath.

The old archive is pinned to SHA-256
`1421f48a55673e42edd73c7b09a610b2b71ef94c70d46db3fb74dd3c34d22a06`
and source tag `0.6.0.2-sineio`
(`cd18daf2f6f97f97409f4709d00bbd7389676d6`). The runner uses that Java 8
distribution to generate an isolated completed Mock workload, archives,
mission logs and original INI. It converts copies using the production
operator CLI, checks history/downloads before and after restart, then starts
the complete previous distribution with the retained original files. This
fixture establishes binary-format compatibility; it is not a claim about an
unprovided customer dataset. Mission logs are readable files; the legacy and
new Driver RAM mission registry is not restored by restarting.

For an operator rollback:

1. Before upgrading, quiesce workloads and record the old archive checksum,
   Java runtime, exact command lines, original INI and data paths. Take a
   consistent snapshot of `conf`, `workspace`, `archive`, mission/log data,
   `start` and `stop`. Keep the complete package and snapshot until the
   migration window ends. Run the new version against copies or a separate
   snapshot so its writes cannot alter the rollback input.
2. Convert copies of INI to YAML. Preserve original relative directory
   structure. Do not copy individual new JARs over an old installation.
3. Stop the new Controller first with
   `bin/cosbench-stop controller --pid-dir /path/to/new/run --timeout 40`,
   then stop its Drivers with
   `bin/cosbench-stop driver --pid-dir /path/to/new/run --timeout 40`.
   Confirm process exit and listener release. For containers, stop each
   Controller/Driver container with `sudo docker stop --time 40 NAME`,
   inspect its exit code and confirm it is no longer running.
4. Extract the previous complete archive into its own directory, restore
   the original configuration and data snapshot, use its original Java
   runtime, and start with the recorded old commands. `start-controller.sh`
   consumes the retained Controller INI. The legacy `start-driver.sh`
   regenerates Driver INI: preserve that original first and use the recorded
   `cosbench-start.sh driver BUNDLES CONSOLE_PORT` invocation when exact
   original Driver configuration must be reused. The automated report records
   this invocation and uses the original `driver_1.conf`.
5. Open Controller history with `index.html?loadArch=true`, check workload
   details and config/log downloads, and verify original archive/workspace/
   mission/config checksums. Retain new-version data separately for diagnosis.
   Rollback does not merge concurrent or newly created results between the
   two versions. Stop the previous processes with its `stop-all.sh` when the
   rehearsal is complete; record exits and remaining resources.

The automated runner removes only its uniquely named containers, network,
image tags and temporary generated secrets, including on failure or signals.
It preserves reports, original/new data copies, MinIO data, rollback archive,
shared base images and existing resources. It performs no global prune and
does not remove data volumes. `cleanup-status.json` records the result.

The previous stop wrapper checks only three seconds after requesting exit;
its original nonzero status is retained if the JVM needs longer. The runner
then independently requires every legacy JVM to exit within 40 seconds. It
does not force-kill a JVM and count that as graceful shutdown. Docker-specific
listen/Driver DNS adjustments are recorded beside the unmodified conversion
output; original INI is never rewritten by the migration.
The previous runtime regenerates its `org.eclipse.osgi` caches and appends
system logs on startup; these are reported separately from preserved original
INI, archive, workspace and mission-file checksums.
