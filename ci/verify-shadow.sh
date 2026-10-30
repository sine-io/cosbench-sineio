#!/usr/bin/env bash
set -Eeuo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
report_dir=${1:?Usage: verify-shadow.sh EMPTY_REPORT_DIR}
exec /bin/bash "$repo_root/shadow/harness/run.sh" "$report_dir" -- \
    /bin/bash /workspace/shadow/harness/continuous-checks.sh
