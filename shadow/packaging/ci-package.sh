#!/usr/bin/env bash
set -Eeuo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/../.." && pwd)
output_dir=${1:-${RUNNER_TEMP:-/tmp}/cosbench-production-package}
report_dir=${2:-$output_dir/report}
output_dir=$(realpath -m -- "$output_dir")
report_dir=$(realpath -m -- "$report_dir")

mkdir -p "$output_dir"
if [[ -n $(find "$output_dir" -mindepth 1 -maxdepth 1 ! -path "$report_dir" -print -quit) ]]; then
    printf 'CI package output directory must be empty: %s\n' "$output_dir" >&2
    exit 2
fi
mkdir -p "$report_dir"
if [[ -n $(find "$report_dir" -mindepth 1 -print -quit) ]]; then
    printf 'CI package report directory must be empty: %s\n' "$report_dir" >&2
    exit 2
fi

bash "$script_dir/build-production.sh" --output-dir "$output_dir" --force
archive=$(find "$output_dir" -maxdepth 1 -type f -name '*.tar.gz' -print)
[[ -n $archive ]] || { printf 'CI package archive was not created\n' >&2; exit 1; }
bash "$script_dir/verify-production.sh" --archive "$archive" --report-dir "$report_dir" --build-images
printf 'CI package verification passed: %s\n' "$repo_root"
