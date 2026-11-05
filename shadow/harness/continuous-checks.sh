#!/usr/bin/env bash
set -Eeuo pipefail

harness_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
/bin/bash "$harness_dir/audit/test-gates.sh" /reports/ci-fixtures > /reports/ci-fixtures-summary.txt
/bin/bash "$harness_dir/controller-process.sh"
/bin/bash "$harness_dir/driver-process.sh"
/bin/bash "$harness_dir/sineio-driver-process.sh"
/bin/bash "$harness_dir/web-spike-process.sh"
/bin/bash "$harness_dir/http-contract.sh"
/bin/bash "$harness_dir/controller-driver-process.sh"
/bin/bash "$harness_dir/operator-commands.sh"
