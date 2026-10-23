#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS_DIR=${1:-}
DOCKER_IMAGE='maven@sha256:e20e4fc05fb5480c884a1f750c362ed0e91aca15cec5736e12796713710ec9b3'

if [[ -z "$RESULTS_DIR" ]]; then
  printf 'Usage: %s <results-directory>\n' "$(basename "$0")" >&2
  exit 2
fi

RESULTS_DIR="$(mkdir -p "$RESULTS_DIR" && cd -- "$RESULTS_DIR" && pwd)"

sudo docker run -i --rm --platform linux/amd64 \
  --volume "$ROOT_DIR:/source:ro" \
  --volume "$RESULTS_DIR:/results" \
  --entrypoint /bin/bash \
  "$DOCKER_IMAGE" -s <<'CONTAINER_SCRIPT'
set -euo pipefail

WORK="$(mktemp -d /tmp/cosbench-tycho.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/repo"
cp -a /source/. "$WORK/repo/"
cd "$WORK/repo"

printf 'Architecture: ' > /results/toolchain.txt
uname -m >> /results/toolchain.txt
java -version >> /results/toolchain.txt 2>&1
mvn -version >> /results/toolchain.txt 2>&1

bash ./version_unify.sh --check
(cd build/p2/repository && sha256sum -c ../repository.sha256) > /results/p2-check.log

mvn -B -ntp -f pom.xml clean verify > /results/maven-build.log 2>&1

mapfile -t BUILT_JARS < <(find dev -type f -path '*/target/cosbench-*.jar' | sort)
printf '%s\n' "${BUILT_JARS[@]}" > /results/tycho-jar-list.txt
EXPECTED_BUNDLE_COUNT="$(sed -n 's@^[[:space:]]*<module>\([^<]*\)</module>[[:space:]]*$@\1@p' pom.xml | wc -l | tr -d ' ')"
test "$EXPECTED_BUNDLE_COUNT" -eq 30
test "${#BUILT_JARS[@]}" -eq "$EXPECTED_BUNDLE_COUNT"

VERSION="$(<VERSION)"
mapfile -t MODULES < <(sed -n 's@^[[:space:]]*<module>\([^<]*\)</module>[[:space:]]*$@\1@p' pom.xml)
EXPECTED_JARS=()
for MODULE in "${MODULES[@]}"; do
  ARTIFACT_ID="$(sed -n 's@^[[:space:]]*<artifactId>\([^<]*\)</artifactId>[[:space:]]*$@\1@p' "$MODULE/pom.xml" | tail -n 1)"
  test -n "$ARTIFACT_ID"
  EXPECTED_JAR="$MODULE/target/${ARTIFACT_ID}_${VERSION}.jar"
  test -s "$EXPECTED_JAR"
  EXPECTED_JARS+=("$EXPECTED_JAR")
done
printf '%s\n' "${EXPECTED_JARS[@]}" | sort > /results/expected-tycho-jar-list.txt
printf '%s\n' "${BUILT_JARS[@]}" | sort > /results/actual-tycho-jar-list.txt
diff -u /results/expected-tycho-jar-list.txt /results/actual-tycho-jar-list.txt

for JAR in "${BUILT_JARS[@]}"; do
  case "$JAR" in
    dev/cosbench-manta/*)
      printf 'Manta must remain outside the Tycho build: %s\n' "$JAR" >&2
      exit 1
      ;;
  esac
done

rm -f dist/osgi/plugins/cosbench-*.jar
for JAR in "${BUILT_JARS[@]}"; do
  cp -f "$JAR" dist/osgi/plugins/
done

ARCHIVE="cosbench-${VERSION}-sineio.tar.gz"
rm -f "$ARCHIVE"
bash ./pack-sineio.sh > /results/pack.log 2>&1
test -s "$ARCHIVE"

tar -tzf "$ARCHIVE" > /results/archive-files.txt
ROOT="cosbench-${VERSION}-sineio"
grep -Fx "$ROOT/VERSION" /results/archive-files.txt
grep -Fx "$ROOT/start-controller.sh" /results/archive-files.txt
grep -Fx "$ROOT/start-driver.sh" /results/archive-files.txt
grep -Fx "$ROOT/main/org.eclipse.equinox.launcher_1.6.400.v20210924-0641.jar" /results/archive-files.txt

mapfile -t ARCHIVE_BUNDLES < <(grep -E "^${ROOT}/osgi/plugins/cosbench-[^/]+\\.jar$" /results/archive-files.txt | sort)
test "${#ARCHIVE_BUNDLES[@]}" -eq "$EXPECTED_BUNDLE_COUNT"
if grep -Eq "^${ROOT}/(release/main/|.*cosbench-(controller|driver)-app\\.jar$)" /results/archive-files.txt; then
  printf 'Spring Boot release artifacts leaked into the Tycho archive\n' >&2
  exit 1
fi

mkdir -p /results/artifact
cp -f "$ARCHIVE" "/results/artifact/$ARCHIVE"
(cd /results/artifact && sha256sum "$ARCHIVE" > "$ARCHIVE.sha256")
(cd /results/artifact && sha256sum -c "$ARCHIVE.sha256")
CONTAINER_SCRIPT

VERSION="$(<"$ROOT_DIR/VERSION")"
ARCHIVE="cosbench-${VERSION}-sineio.tar.gz"
printf '%s\n' "$RESULTS_DIR/artifact/$ARCHIVE" > "$RESULTS_DIR/archive-path.txt"
printf '%s\n' "$RESULTS_DIR/artifact/$ARCHIVE.sha256" > "$RESULTS_DIR/checksum-path.txt"
