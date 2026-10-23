#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
VERSION_FILE="$ROOT_DIR/VERSION"
ROOT_POM="$ROOT_DIR/pom.xml"

usage() {
  printf 'Usage: %s [VERSION|--check]\n' "$(basename "$0")" >&2
}

read_version() {
  local value
  value="$(<"$VERSION_FILE")"
  if [[ -z "$value" || "$value" == *$'\n'* || "$value" == *$'\r'* || "$value" =~ [[:space:]] ]]; then
    printf 'VERSION must contain exactly one non-empty value\n' >&2
    return 1
  fi
  printf '%s\n' "$value"
}

validate_version() {
  [[ "$1" =~ ^[0-9A-Za-z][0-9A-Za-z.+_-]*$ ]] || {
    printf 'invalid version: %s\n' "$1" >&2
    return 1
  }
}

module_paths() {
  sed -n 's@^[[:space:]]*<module>\([^<]*\)</module>[[:space:]]*$@\1@p' "$ROOT_POM"
}

manifest_paths() {
  while IFS= read -r module; do
    [[ -n "$module" ]] || continue
    printf '%s/%s\n' "$ROOT_DIR" "$module/META-INF/MANIFEST.MF"
  done < <(module_paths)
}

check_file_version() {
  local file=$1 pattern=$2 expected=$3 actual
  actual="$(sed -n "s|${pattern}|\1|p" "$file" | head -n 1)"
  [[ "$actual" == "$expected" ]] || {
    printf 'version mismatch in %s: expected %s, found %s\n' "$file" "$expected" "${actual:-<missing>}" >&2
    return 1
  }
}

check_consistency() {
  local expected=$1 module manifest
  check_file_version "$ROOT_POM" '^[[:space:]]*<version>\([^<]*\)</version>[[:space:]]*$' "$expected"

  while IFS= read -r module; do
    [[ -n "$module" ]] || continue
    check_file_version "$ROOT_DIR/$module/pom.xml" '^[[:space:]]*<version>\([^<]*\)</version>[[:space:]]*$' "$expected"
  done < <(module_paths)

  while IFS= read -r manifest; do
    check_file_version "$manifest" '^Bundle-Version: \(.*\)$' "$expected"
  done < <(manifest_paths)
}

if [[ $# -gt 1 ]]; then
  usage
  exit 2
fi

if [[ "${1:-}" == '--check' ]]; then
  expected="$(read_version)"
  validate_version "$expected"
  check_consistency "$expected"
  printf 'VERSION consistency check passed: %s\n' "$expected"
  exit 0
fi

if [[ "${1:-}" == -* ]]; then
  usage
  exit 2
fi

if [[ $# -eq 1 ]]; then
  release_id=$1
  validate_version "$release_id"
  printf '%s\n' "$release_id" > "$VERSION_FILE"
else
  release_id="$(read_version)"
fi

validate_version "$release_id"

sed -i -E "0,/^[[:space:]]*<version>[^<]*<\/version>[[:space:]]*$/s//  <version>${release_id}<\/version>/" "$ROOT_POM"
while IFS= read -r module; do
  [[ -n "$module" ]] || continue
  sed -i -E "0,/^[[:space:]]*<version>[^<]*<\/version>[[:space:]]*$/s//    <version>${release_id}<\/version>/" "$ROOT_DIR/$module/pom.xml"
done < <(module_paths)

while IFS= read -r manifest; do
  sed -i -E "s/^Bundle-Version:.*$/Bundle-Version: ${release_id}/" "$manifest"
done < <(manifest_paths)

check_consistency "$release_id"
printf 'Synchronized Tycho version: %s\n' "$release_id"

