# Production configuration

The two YAML files use schema version 1. Copy them to a protected
configuration directory and adjust the listen address, paths, Driver list and
timeouts for the installation. The Controller and Driver are separate
processes.

Service authentication is independent from page login. Set
`COSBENCH_SERVICE_TOKEN` through a protected environment or replace the token
reference with a protected file reference. Do not put the token in YAML,
workloads, images, logs or source control.

Driver TLS requires a PKCS12 key store at the configured `keyStore` path. The
Controller trusts Driver certificates through its `trustStore`. Mount both
stores read-only, or use another protected absolute path. The password is read
from `SERVER_SSL_KEY_STORE_PASSWORD` or
`COSBENCH_TLS_STORE_PASSWORD_FILE`.

Set `drivers` in `controller.yml` to the HTTPS URLs of the Driver instances.
The default empty list is useful for validating a Controller-only install and
does not schedule workloads.

The supported production storage factories are `sio`, `siov1`, `gdas` and
`s3`. They are discovered at Driver startup from the bundled ServiceLoader
descriptors. Adding or removing a factory requires a new package and process
restart. Provider settings belong in workload XML; examples in
`conf/providers/` contain placeholders only.
