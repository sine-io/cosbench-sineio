#!/usr/bin/env bash
set -Eeuo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/../.." && pwd)
test_root=$(mktemp -d "${TMPDIR:-/tmp}/cosbench-packaging-test.XXXXXX")
trap 'rm -rf -- "$test_root"' EXIT

first_output="$test_root/first"
second_output="$test_root/second"

bash "$script_dir/build-production.sh" --output-dir "$first_output" --source-date-epoch 0 --force
bash "$script_dir/build-production.sh" --output-dir "$second_output" --source-date-epoch 0 --force

archive=$(find "$first_output" -maxdepth 1 -type f -name '*.tar.gz' -print)
rebuilt_archive=$(find "$second_output" -maxdepth 1 -type f -name '*.tar.gz' -print)
[[ -n $archive && -n $rebuilt_archive ]] || {
    printf 'packaging test: expected one archive in each build output\n' >&2
    exit 1
}
cmp -s "$archive" "$rebuilt_archive" || {
    printf 'packaging test: repeated builds differ\n' >&2
    exit 1
}

bash "$script_dir/verify-production.sh" --archive "$archive" --skip-process-probe --skip-rebuild \
    --report-dir "$test_root/report" > "$test_root/verification.log"
grep -Fxq 'status=passed' "$test_root/report/verification.txt"
printf 'packaging test passed: %s\n' "$repo_root"
