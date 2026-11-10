# Tycho 1.7.0 P2 input

This repository is the checked-in, offline P2 input for the Tycho build in the
repository root. It was produced from the original COSBench dependency P2
repository used by the Phase 1A PoC, with one compatibility transformation:
`org.freemarker.freemarker_2.3.20.stable.jar` has the legacy OSGi execution
environment tokens `J2SE-1.6`, `J2SE-1.7`, and `J2SE-1.8` rewritten to their
`JavaSE-*` equivalents. Tycho 1.7.0 rejects the legacy token before compiling;
the replacement is an EE-name normalization and does not change COSBench source
or bundle manifests.

The root `pom.xml` consumes this repository through the local `cosbench-p2`
repository declaration. `repository.sha256` records every file in the mirror.
The source P2 content hash and the compatibility-mirror hash are recorded here
so the mirror can be audited without a network repository:

* original `content.jar` metadata-archive SHA-256: `70944328b4497c208fb2fee56f5dbd1c84e261df13b2792ce348b9dd9c0d9f98`
* original `artifacts.jar` metadata-archive SHA-256: `bb8d49ca2669b224ceed5ff947781a85b28b89717b4c5f8f9cfa90264a795b5d`
* compatibility `content.jar` metadata-archive SHA-256: `900bba7db81dcf79cda63b4c95dc90f99ba252f9fdcdac3eb176b271b06d6a2e`
* compatibility `artifacts.jar` metadata-archive SHA-256: `1a20611b4da0db3717358f3c58602aa936161429309b61e07e647fff1fd54bc7`
* FreeMarker manifest before normalization: `10fc697ec84499aeba45b1a54afa8b730d2278693b3d9f1629950801e9344ed5`
* FreeMarker manifest after normalization: `5c05afdbbe45f27e8cc55a358d8f9ef463e69eee4d993e2ffaac7065f6505ab8`

The mirror is intentionally versioned with the build so an offline JDK 8 build
does not depend on a mutable remote repository.
