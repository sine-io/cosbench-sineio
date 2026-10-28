# Ticket 02 Review

Fixed point: `8db493d485ed3765829cc3c04751ee319329f2de`. Initial review diff: `git diff 8db493d485ed3765829cc3c04751ee319329f2de...55e6328`. The Standards and Spec reviews ran independently under the code-review skill. Both re-reviewed corrections through `9f4ec4ac366fb0e468fc8557040260892449cb6f`.

## Standards

Initial findings:

- Documented rule breach — interruption cleanup: foreground Docker commands deferred the runner's TERM trap while long tests ran, contrary to execution rule 6 in `.scratch/scheme3-migration/README.md`.
- Documented rule breach — lost failure diagnostics: raw Toxiproxy API/HTTP diagnostics were stored in the disposable container `/tmp`, contrary to execution rules 4 and 6.

Final disposition: both original findings resolved. Committed code uses an interruptible background wait, explicit certificate-container cleanup, persistent `/reports/toxiproxy` outputs, and file-only permission changes. The final fault directory is mode 0755 with all 15 files mode 0644. Normal replay reported run/cleanup `0/0`; direct TERM reported `143/0`. No remaining Standards findings.

The reviewer's intermediate permissions finding was based on stale content and was withdrawn after inspecting committed HEAD and the replay's actual file permissions.

## Spec

Initial findings:

- P1 — TERM cleanup delayed during synchronous Docker commands, conflicting with “中断都…清理本轮容器/网络/临时资源”.
- P2 — Raw fault diagnostics disappeared with the temporary container, conflicting with “测试失败…保留日志”.

Final disposition: zero remaining findings. Background waits make Docker commands interruptible; raw fault replies/errors persist; standalone certificate containers have names, labels, and cleanup; the foreground Docker preflight and no-newline payload description match the implementation. The complete clean-checkout/empty-cache replay subsequently passed and is retained in `evidence/2026-10-28/`.

Totals: Standards 0 open (2 resolved); Spec 0 open (2 resolved). No open issue remains on either axis.
