#!/usr/bin/env bash
set -Eeuo pipefail

artifact=${1:?Usage: check-duplicate-classes.sh ARTIFACT.jar DEPENDENCY_DIR}
dependency_dir=${2:?Usage: check-duplicate-classes.sh ARTIFACT.jar DEPENDENCY_DIR}
harness_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
exec java "$harness_dir/audit/DuplicateClasses.java" "$artifact" "$dependency_dir"
