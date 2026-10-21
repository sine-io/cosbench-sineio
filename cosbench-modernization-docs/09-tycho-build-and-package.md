# Tycho 构建、导出与发行包操作指南

本文记录当前 Tycho 1.7.0/JDK 8 方案的使用步骤。目标是把 30 个 COSBench Bundle 导出到 `dist/osgi/plugins`，与 `旧包/plugins` 中的 Eclipse 导出物核对，再用 `pack-sineio.sh` 生成最终 tar.gz。

截至 2026-10-21，Tycho 构建、插件导出和发行包生成已完成。用户报告手动解包后程序可用，并报告 Phase 1B 与 Phase 2 的手工测试通过；具体测试用例和原始记录未附在本指南中。本文只记录构建与打包步骤，不声称这些测试由本指南执行。Manta 保持独立，本 Tycho reactor 不构建或改写 Manta。

## 1. 构建输入与固定环境

| 项目 | 当前值 |
|---|---|
| Docker 镜像 | `maven@sha256:e20e4fc05fb5480c884a1f750c362ed0e91aca15cec5736e12796713710ec9b3` |
| 平台 | `linux/amd64` |
| JDK | Eclipse Temurin 8u452 |
| Maven | 3.9.9 |
| Tycho | 1.7.0，版本由根目录 `pom.xml` 固定 |
| P2 仓库 | 仓库内 `build/p2/repository`，通过 `build/p2/repository.sha256` 校验 |
| Maven 构件来源 | Maven 默认 Central 仓库（`https://repo.maven.apache.org/maven2`）；首次构建需要容器网络访问。实际下载的 JAR/POM SHA-256 写入本次结果目录 |
| 旧 Eclipse Bundle 基线 | `cosbench-modernization-docs/旧包/plugins`，30 个 `cosbench-*.jar` |

P2 镜像随源码保存，避免依赖可变的在线 Eclipse 更新站点。Tycho 1.7.0 无法接受原 FreeMarker P2 元数据中的 `J2SE-1.6/1.7/1.8` EE 名称；`build/p2` 中的兼容副本只把这些 EE 标识规范为 `JavaSE-*`。输入摘要、FreeMarker manifest 转换前后摘要和镜像逐文件校验值见 `build/p2/README.md` 与 `build/p2/repository.sha256`。不要在正式 Bundle manifest 中删除执行环境声明来绕过此问题。

## 2. 隔离构建并导出 Bundle

以下命令从仓库根目录执行。先为本次操作创建一个新的结果目录；源仓库只读挂载，构建和打包在容器临时副本中执行。示例未使用 `--network none`，因为首次 Maven 构建可能需要下载 Maven 插件及其依赖；P2 target platform 本身使用仓库内镜像。

```bash
RESULTS_DIR="$(mktemp -d /tmp/cosbench-tycho.XXXXXX)"

set +e
sudo docker run -i --rm --platform linux/amd64 \
  --volume "$PWD:/source:ro" \
  --volume "$RESULTS_DIR:/results" \
  --entrypoint /bin/bash \
  maven@sha256:e20e4fc05fb5480c884a1f750c362ed0e91aca15cec5736e12796713710ec9b3 \
  -s <<'CONTAINER_SCRIPT' 2>&1 | tee "$RESULTS_DIR/container.log"
set -euo pipefail

WORK="$(mktemp -d /tmp/cosbench-tycho.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/repo" /results/maven-cache
cp -a /source/. "$WORK/repo/"
cd "$WORK/repo"

{
  printf 'Architecture: '
  uname -m
  java -version
  mvn -version
} > /results/toolchain.txt 2>&1

(cd build/p2/repository && sha256sum -c ../repository.sha256) \
  > /results/p2-check.log 2>&1

set +e
mvn -B -ntp -f pom.xml \
  -Dmaven.repo.local=/results/maven-cache clean verify \
  > /results/maven-build.log 2>&1
BUILD_RC=$?
set -e
printf '%s\n' "$BUILD_RC" > /results/maven-build.exit
test "$BUILD_RC" -eq 0

mapfile -t BUILT_JARS < <(find dev -type f -path '*/target/cosbench-*.jar' | sort)
printf '%s\n' "${BUILT_JARS[@]}" > /results/tycho-jar-list.txt
test "${#BUILT_JARS[@]}" -eq 30

mkdir -p dist/osgi/plugins
for JAR in "${BUILT_JARS[@]}"; do
  cp -f "$JAR" dist/osgi/plugins/
done

OLD_DIR='cosbench-modernization-docs/旧包/plugins'
find "$OLD_DIR" -maxdepth 1 -type f -name 'cosbench-*.jar' \
  | sed 's|.*/||' | sort > /results/old-bundle-names.txt
printf '%s\n' "${BUILT_JARS[@]}" \
  | sed 's|.*/||' | sort > /results/new-bundle-names.txt
comm -3 /results/old-bundle-names.txt /results/new-bundle-names.txt \
  > /results/bundle-name-differences.txt
test "$(wc -l < /results/old-bundle-names.txt | tr -d ' ')" -eq 30
test "$(wc -l < /results/new-bundle-names.txt | tr -d ' ')" -eq 30
test ! -s /results/bundle-name-differences.txt

(cd "$OLD_DIR" && sha256sum cosbench-*.jar) > /results/old-bundle-sha256.txt
for JAR in "${BUILT_JARS[@]}"; do
  sha256sum "dist/osgi/plugins/$(basename "$JAR")"
done > /results/new-bundle-sha256.txt

mkdir -p /results/manifests/old /results/manifests/new \
  /results/entries/old /results/entries/new
while IFS= read -r NAME; do
  OLD_JAR="$PWD/$OLD_DIR/$NAME"
  NEW_JAR="$PWD/dist/osgi/plugins/$NAME"
  OLD_EXTRACT="$WORK/extract-old-$NAME"
  NEW_EXTRACT="$WORK/extract-new-$NAME"
  mkdir -p "$OLD_EXTRACT" "$NEW_EXTRACT"
  (cd "$OLD_EXTRACT" && jar xf "$OLD_JAR" META-INF/MANIFEST.MF)
  (cd "$NEW_EXTRACT" && jar xf "$NEW_JAR" META-INF/MANIFEST.MF)
  cp "$OLD_EXTRACT/META-INF/MANIFEST.MF" "/results/manifests/old/$NAME"
  cp "$NEW_EXTRACT/META-INF/MANIFEST.MF" "/results/manifests/new/$NAME"
  jar tf "$OLD_JAR" | LC_ALL=C sort > "/results/entries/old/$NAME.txt"
  jar tf "$NEW_JAR" | LC_ALL=C sort > "/results/entries/new/$NAME.txt"
done < /results/old-bundle-names.txt

for KIND in old new; do
  for MANIFEST in /results/manifests/"$KIND"/*; do
    printf '## %s/%s\n' "$KIND" "$(basename "$MANIFEST")"
    awk '
      function emit() { if (line != "") { print line; line = "" } }
      { sub(/\r$/, "", $0)
        if ($0 ~ /^ /) { line = line substr($0, 2); next }
        emit(); line = $0
      }
      END { emit() }
    ' "$MANIFEST" | grep -E '^(Bundle-SymbolicName|Bundle-Version|Bundle-ClassPath|Fragment-Host):'
  done
done > /results/bundle-manifest-headers.txt

find /results/maven-cache -type f \
  \( -name '*.jar' -o -name '*.pom' \) -print0 \
  | sort -z | xargs -0 sha256sum > /results/maven-artifact-sha256.txt

VERSION="$(cat VERSION)"
ARCHIVE="cosbench-${VERSION}-sineio.tar.gz"
rm -f "$ARCHIVE"
# pack.sh 在仓库中没有 executable bit；只在这个一次性副本中补上。
chmod 0755 pack.sh
set +e
bash ./pack-sineio.sh > /results/pack.log 2>&1
PACK_RC=$?
set -e
printf '%s\n' "$PACK_RC" > /results/pack.exit

# pack-sineio.sh 会吞掉 pack.sh 的失败状态，因此必须独立检查归档。
test -s "$ARCHIVE"
tar -tzf "$ARCHIVE" > /results/archive-files.txt
sha256sum "$ARCHIVE" > /results/archive-sha256.txt
cp "$ARCHIVE" /results/
CONTAINER_SCRIPT
DOCKER_RC=${PIPESTATUS[0]}
set -e
printf '%s\n' "$DOCKER_RC" | tee "$RESULTS_DIR/docker.exit"
```

容器内 `maven-build.exit` 是 Maven 的退出码，`pack.exit` 是打包脚本的退出码，`docker.exit` 是容器整体退出码。构建成功应同时满足 Maven 退出码为 0、Bundle 数量和名称检查通过、最终归档存在且 `tar -tzf` 成功。**不要只根据 `pack-sineio.sh` 打印的 `[OK]` 或其退出码判断成功**：当前脚本没有启用严格错误处理，可能在内部 `pack.sh` 失败后仍继续返回 0。

结果目录保留在宿主机 `/tmp`，包括完整构建/打包日志、工具链信息、P2 校验结果、30 个 Bundle 文件名、旧/新 JAR SHA-256、解出的完整 MANIFEST、关键 Bundle 头部摘要、每个 JAR 的条目清单、Maven JAR/POM SHA-256、归档清单和归档 SHA-256。容器由 `--rm` 自动清理，容器内源码副本由 `trap` 删除。确认不再需要结果后，只删除本次创建的 `$RESULTS_DIR`。

## 3. 查看构建结果和旧包对应关系

1. `maven-build.log` 最后应显示 reactor 成功；`maven-build.exit` 应为 `0`。
2. `tycho-jar-list.txt` 应有 30 个 Tycho JAR，导出目录为隔离副本中的 `dist/osgi/plugins`。
3. `bundle-name-differences.txt` 应为空；旧基线和新输出都应有 30 个同名 JAR。若版本名、文件名或 Bundle 数量不同，先停止打包并查明原因。
4. 对比 `bundle-manifest-headers.txt` 中每个 JAR 的 `Bundle-SymbolicName`、`Bundle-Version`、`Bundle-ClassPath` 和 `Fragment-Host`。检查 `entries/old` 与 `entries/new` 下对应清单，确认 fragment、嵌入式 JAR 和非 class 资源路径未丢失；有差异时查看 `manifests/old` 与 `manifests/new` 中的完整 MANIFEST。不要以“能生成 JAR”代替 Bundle 元数据核对。
5. `old-bundle-sha256.txt` 和 `new-bundle-sha256.txt` 用于记录实际输入和输出。它们用于追踪制品，不要求两条清单相同：Eclipse PDE 与 Tycho 生成的 ZIP/JAR 字节可以不同。

已记录的对比结果是：30/30 Bundle 文件名相同；Tycho 两次干净构建的 Bundle 内容哈希、manifest 和 `Bundle-ClassPath` 相同。Tycho 与 Eclipse 旧 JAR 的 SHA-256 并非 30/30 相同；旧、新 MANIFEST 的字节排列/构建元数据有差异，另外有 17 个 Bundle 中的 51 个 class 条目内容不同。class 条目差异的具体成因尚未归因，因此不能称为字节级等价；用户之后手动解包确认程序可用。构建路线验收结论应表述为“Tycho 能导出对应的 30 个 Bundle，并可组成经用户手动验证可用的发行包”，不应表述为“与 Eclipse 旧包二进制完全相同”。

若要重做可重复性检查，使用同一个源码快照和 P2 镜像，在另一份全新的容器临时副本中再次执行 `clean verify`，然后对照两次的 JAR 文件名、SHA-256、MANIFEST 和 `Bundle-ClassPath`。不要比较 Maven `target/` 目录时间戳来判断内容变化。Maven 依赖本地缓存校验清单为 `maven-artifact-sha256.txt`。

## 4. 生成和检查最终归档

`pack-sineio.sh` 会删除 `VERSION` 对应的发行目录，调用 `pack.sh`，创建 `cosbench-${VERSION}-sineio.tar.gz`，再删除临时发行目录。`pack.sh` 会在发行树中写入当天的 `BUILD.no`。因此必须在一次性副本中运行，不能直接在包含用户文件的工作树运行。

上面的单容器流程已完成这些隔离：仓库只读挂载，脚本只作用于容器临时副本；复制出的 JAR 进入 `dist/osgi/plugins` 后才运行打包。`pack.sh` 的 executable bit 也只在副本中设置。归档非空检查、tar 目录读取和 SHA-256 计算均在同一容器中完成；结果分别保存在 `archive-files.txt` 和 `archive-sha256.txt`。

归档 SHA-256 应随本次制品保存；由于 `BUILD.no` 日期和归档文件时间等信息可能变化，不应默认要求不同日期生成的 tar.gz 字节哈希相同。用户解包和程序运行验证由用户执行；当前状态是用户已报告手工验证可用。

## 5. 边界、风险和回退

- Tycho 1.7.0 和 JDK 8 是当前被确认的组合；Tycho 生命周期风险已接受。本指南不升级 JDK/SDK。
- 构建范围是 30 个非 Manta Bundle。Manta 保持其现有独立处理方式；若发行包还要求 Manta JAR，应确认隔离副本中既有 `dist` 输入包含正确制品。本流程不会从旧包拷贝 Manta，也不会构建或修改 Manta。
- Maven 插件及传递依赖首次可能从配置的 Maven 仓库下载。记录 Maven 构件 SHA-256，并保留本次 P2 镜像校验日志。
- Tycho/Eclipse 二进制哈希差异仍需以 class 条目差异清单做更细归因；手动运行可用不等同于所有 Bundle 字节相同。
- 失败时丢弃本次容器临时副本和结果归档，再用同一固定镜像、源码快照和 P2 校验值重新构建。不要在工作树中手工覆盖或删除 JAR 来“修复”差异。源仓库以只读挂载方式使用，因此回退不需要还原 Java 源码或打包脚本。
