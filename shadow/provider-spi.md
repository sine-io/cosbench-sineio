# Java 21 Provider SPI

Production Driver discovers `sio`, `siov1`, `gdas` and `s3` at startup through
`ServiceLoader`. Mock storage/auth are test-only. Other adapters run only in
their complete legacy distribution; copying old OSGi bundles into this
distribution is unsupported.

Implement the existing `StorageAPIFactory` / `AuthAPIFactory` interfaces in
an ordinary Java 21 Maven module. Register factory classes, one per line, in
`src/main/resources/META-INF/services/com.intel.cosbench.api.storage.StorageAPIFactory`
or the corresponding `com.intel.cosbench.api.auth.AuthAPIFactory` file. Add an
explicit application dependency, admit dependency hashes/licenses through
the existing CI policy, and update the declared supported registry only when
the product scope permits it. Duplicate names and missing expected factories
fail startup. Discovery is static: changes require rebuilding the complete
package/image and restarting Driver.

Use the [Docker development entry](../BUILD-java21.md). Verify discovery,
configuration errors, init/operation/dispose, and cleanup through the public
SPI or process/HTTP seams. Run the [dependency gates](ci-policy.md) and repeat
the affected deployment and release acceptance before publishing new hashes.
See [SineIO settings and behavior](sineio-providers.md) and
[S3 settings and behavior](s3-provider.md). Provider settings remain
semicolon-separated workload XML attributes; sample `.properties` files are
templates and are not loaded or interpolated by the Provider itself.

The shared classpath uses AWS SDK v1 `1.12.767` and v2 `2.26.8`; this migration
does not upgrade Jackson 1.x, Castor, or SDK major versions. Vendor archive
restore/prefetch/range backend checks remain the previously agreed manual
validation; MinIO success does not claim those backend capabilities.
