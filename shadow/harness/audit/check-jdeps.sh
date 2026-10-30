#!/usr/bin/env bash
set -Eeuo pipefail

artifact=${1:?Usage: check-jdeps.sh ARTIFACT CLASSPATH REPORT}
classpath=${2-}
report=${3:?Usage: check-jdeps.sh ARTIFACT CLASSPATH REPORT}
if [[ -z $classpath ]]; then
    empty_classpath=$(mktemp -d)
    trap 'rmdir "$empty_classpath"' EXIT
    classpath=$empty_classpath
fi
jdeps --multi-release 21 --class-path "$classpath" --missing-deps "$artifact" > "$report" 2>&1 || {
    cat "$report" >&2
    exit 1
}
if grep -Fq 'not found' "$report"; then
    cat "$report" >&2
    printf 'Unresolved production dependency in %s\n' "$artifact" >&2
    exit 1
fi
printf 'PASS jdeps %s\n' "$artifact"
