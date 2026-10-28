#!/usr/bin/env bash
set -Eeuo pipefail

artifact=${1:?Usage: check-duplicate-classes.sh ARTIFACT.jar DEPENDENCY_DIR}
dependency_dir=${2:?Usage: check-duplicate-classes.sh ARTIFACT.jar DEPENDENCY_DIR}
work_dir=$(mktemp -d)
entries="$work_dir/entries"
duplicates="$work_dir/duplicates"
trap 'find "$work_dir" -mindepth 1 -delete; rmdir "$work_dir"' EXIT

shopt -s nullglob
for jar in "$artifact" "$dependency_dir"/*.jar; do
    [[ -f "$jar" ]] || continue
    while IFS= read -r entry; do
        case "$entry" in
            *.class)
                case "$entry" in
                    module-info.class|META-INF/versions/*|META-INF/*) continue ;;
                esac
                printf '%s\t%s\n' "$entry" "$jar" >> "$entries"
                ;;
        esac
    done < <(jar tf "$jar")
done

sort -o "$entries" "$entries"
cut -f1 "$entries" | uniq -d > "$duplicates"
if [[ -s "$duplicates" ]]; then
    while IFS= read -r class_name; do
        printf '%s\n' "$class_name"
        awk -F '\t' -v class_name="$class_name" '$1 == class_name { printf "  %s\n", $2 }' "$entries"
    done < "$duplicates"
    exit 1
fi

printf '%s\n' 'No duplicate classes found across the module JAR and test dependency JARs.'
