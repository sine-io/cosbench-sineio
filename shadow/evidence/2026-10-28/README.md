# Ticket 02 Validation Record

Executable source: `9f4ec4ac366fb0e468fc8557040260892449cb6f`. Verification used a clean detached worktree with no tracked or untracked changes and a new tmpfs Maven cache. This directory retains text reports copied from `/tmp/cosbench-scheme3-02-final-clean2-20261028` plus the explicitly identified failure/policy/interruption probes. The complete replay output also holds `cosbench-config-shadow.jar` (8,078 bytes).

```sh
shadow/harness/run.sh /tmp/cosbench-scheme3-02-replay
```

| Check | Result | Report |
| --- | --- | --- |
| Empty-cache Maven `clean verify` | Pass, four Java 21 main sources / one passing test | `build.log`, `toolchain.txt` |
| Plain JAR resources and runtime | Java 21, class major 65, eight packaged mappings | `packaged-runtime.txt`, `jar-contents.txt`, `resource-list.txt`, `artifact.sha256` |
| Dependencies / licenses | No runtime or local JAR dependencies; no duplicate classes across module/test JARs | `dependency-tree.txt`, `duplicate-class-audit.txt`, `local-jars-and-licenses.txt`, `*-checksums.txt` |
| Java / release rejection | JDK 8, Maven 3.9.9, and release 17 fail at validation | `java-policy-rejection.log`, `release-policy-rejection.log` |
| Real MinIO put/get/delete | Pass; identical upload/download SHA-256; deleted object absent | `minio-smoke.log` |
| Faults | reset / empty response / timeout observed as curl 56 / 52 / 28 | `toxiproxy-controls.log`, `toxiproxy/*` |
| Test TLS material | Self-signed certificate generated separately for this run; private key destroyed | `test-certificate.txt` |
| Normal cleanup | Run 0 / cleanup 0; no project or certificate containers/network/volume | `cleanup-status.txt`, `docker-cleanup.log` |
| Failure cleanup (early smoke permission failure) | Run 1 / cleanup 0; no remaining project resources | `failure-cleanup-status.txt` |
| Interactive Ctrl-C cleanup | Run 1 / cleanup 0; no remaining project resources | `interrupt-cleanup-status.txt` |
| Direct TERM of runner during a long custom command | Run 143 / cleanup 0; no remaining project resources | `direct-term-cleanup-status.txt` |

The early failure and interactive interrupt reports are from the initial harness checks; the direct-TERM report verifies the hardened interruptible command wait. The final normal replay includes the named certificate-container cleanup. Existing host containers, data, and volumes were preserved; the task's temporary checkout is removed after verification. The legacy release archive and source tag remain available through the ticket 01 baseline references.
