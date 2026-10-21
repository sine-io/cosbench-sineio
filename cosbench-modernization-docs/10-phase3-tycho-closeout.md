# Phase 3 --- Tycho 多模块构建收口报告

日期：2026-10-21

状态：已完成

## 结论

Phase 3 验收通过。现有根 Tycho POM 和 30 个非 Manta Bundle 模块在固定 Docker 环境中完成一次干净 `clean verify`；30 个生成 JAR 均与 Eclipse 基线按文件名对应。Bundle 关键 MANIFEST 头、`Bundle-ClassPath` 和归档条目路径检查通过，没有发现资源或类条目路径丢失。Manta 保持在 Tycho reactor 外。

本次没有修改 POM、Java 源码、Bundle 元数据、CI、启动脚本或运行路径。Phase 1A/1B 已有发行包打包证据继续适用，且本次没有构建输入变更，因此没有再次运行会改写发行目录的 `pack-sineio.sh`。用户此前报告手动解包后的程序可用，以及 Phase 1B/2 手工测试通过；这些属于用户报告，不是本次容器执行的运行测试。

## 输入快照与工具链

构建前在 Docker 容器中记录：

| 项目 | 值 |
|---|---|
| 分支 | `refactor/cosbench-restructure` |
| HEAD | `a99954458d84e3aa46d3882bae9e969128204da1` |
| Docker 镜像 | `maven@sha256:e20e4fc05fb5480c884a1f750c362ed0e91aca15cec5736e12796713710ec9b3` |
| 平台 | `linux/amd64`（`x86_64`） |
| JDK | Eclipse Temurin `1.8.0_452` |
| Maven | `3.9.9` |
| Tycho | `1.7.0`，由根 `pom.xml` 固定 |

构建前工作区状态保存在结果目录 `git-status.txt`。快照中已有未跟踪的 `build/`、`cosbench-modernization-docs/`、根 `pom.xml` 及 `dev/cosbench-*` 模块 POM；本次保留这些文件，没有清理或覆盖它们。Manta 的独立 POM 未加入根 reactor。

构建和文档更新后的 Docker `git status --short` 条目列表一致；对比结果为空。由于 `cosbench-modernization-docs/` 在任务开始前整体已是未跟踪目录，其中的文档更新仍显示为该目录一项；其余已有未跟踪路径均保留。

依赖来源和校验：

- Target platform 使用仓库内 `build/p2/repository`，按 `build/p2/repository.sha256` 校验，66 项检查通过。
- Maven 插件和普通 Maven 构件来自 Maven Central（`https://repo.maven.apache.org/maven2`）；结果目录记录了 373 个下载 JAR/POM 的 SHA-256。
- 完整工具链输出、P2 校验日志、Maven 日志和校验清单保存在下方结果目录。

## 执行与验证结果

源工作树以只读方式挂载为 `/source`，在容器临时目录复制后构建，结果写入独立目录。最终验证使用的容器退出码为 `0`；容器内 Maven 命令及退出码：

```bash
sudo docker run --rm -i --platform linux/amd64 \
  --volume "$PWD:/source:ro" \
  --volume "$RESULTS_DIR:/results" \
  --entrypoint /bin/bash \
  maven@sha256:e20e4fc05fb5480c884a1f750c362ed0e91aca15cec5736e12796713710ec9b3 \
  -s
```

```bash
mvn -B -ntp -f pom.xml \
  -Dmaven.repo.local=/results/maven-cache clean verify
```

Maven 退出码为 `0`，日志末尾为 `BUILD SUCCESS`。验证结果：

| 检查 | 结果 |
|---|---|
| Tycho reactor | 30 个非 Manta Bundle 模块构建成功 |
| 导出 JAR | 30 个；与 `cosbench-modernization-docs/旧包/plugins` 的 30 个 Eclipse Bundle 文件名 30/30 匹配 |
| MANIFEST | 30 份可读取；`Bundle-SymbolicName`、`Bundle-Version`、`Fragment-Host` 与基线一致 |
| `Bundle-ClassPath` | 30/30 与基线一致；每个引用均能解析到归档条目 |
| 归档条目路径 | class 条目无新增/缺失；非 class 条目无丢失 |
| 非 class 新条目 | 60 项，均为 `META-INF/maven/.../pom.xml` 和 `pom.properties` Maven 元数据 |
| 意外的非 class 条目 | 0 |

这次 Phase 3 核对比较了条目路径和 MANIFEST，不代表 JAR 或 class 字节完全相同。Phase 1A 留存的 Eclipse/Tycho class 内容差异仍是已知事项：17 个 Bundle 中有 51 个 class 条目内容不同，原因尚未归因。不能据此宣称两种打包结果字节级等价。该差异没有表现为本次检查中的类路径、Bundle-ClassPath 或资源缺失。

审计辅助脚本的首次运行因 JAR 解包使用了错误的相对路径而退出 `1`，但该次 Maven `clean verify` 自身退出 `0`。随后修正解包路径并处理中文基线路径的 locale/排序后重新完整核验；中间及最终完整运行均退出 `0`。因此验收以最终结果目录为准，辅助脚本问题不属于构建失败；相关中间日志仍保留在结果目录中。

## 命令记录和结果文件

本次最终通过的 Docker 结果目录为：`/tmp/cosbench-phase3-final.zYcE6D`。`docker.exit` 与 `maven-build.exit` 均为 `0`。主要记录如下：

| 文件 | 内容 |
|---|---|
| `maven-build.log` | 完整 Maven `clean verify` 日志 |
| `toolchain.txt` | 镜像 digest、平台、JDK 和 Maven 版本 |
| `p2-check.log` | 本地 P2 仓库 SHA-256 核验结果 |
| `maven-artifact-sha256.txt` | 373 个 Maven JAR/POM 构件摘要 |
| `tycho-jar-list.txt`、`tycho-jar-sha256.txt` | Tycho 导出文件清单及 SHA-256 |
| `bundle-manifest-headers.txt` | 新旧 Bundle MANIFEST 关键头和 Bundle-ClassPath 比较 |
| `phase3-analysis-summary.txt` | 计数汇总：名称 30/30、BCP 差异 0、路径丢失 0、Maven 元数据新增 60 |
| `git-status.txt`、`git-branch.txt`、`git-head.txt` | Docker 内构建前的工作区/分支/HEAD 快照 |
| `post-doc-git-status.txt`、`post-doc-git-branch.txt`、`post-doc-git-head.txt` | 文档收口后的工作区快照；status 条目与构建前一致 |

Docker 以 `--rm` 运行，构建副本由退出 trap 清理；没有留下本次创建的容器。结果目录暂时保留以供审阅。前序临时验证目录也保留在 `/tmp/cosbench-phase3.oBUbPc` 和 `/tmp/cosbench-phase3-verified.tGrXuV`；最终报告以 `cosbench-phase3-final.zYcE6D` 的通过结果为准。

## 未解决风险与回退

- Eclipse 与 Tycho 的 51 个 class 条目字节差异尚未归因；如果后续要求字节一致或证明每个差异无行为影响，应另行授权逐项分析。
- Phase 1B/Phase 2 的具体手工用例、日志和结果未归档；当前只记录用户报告的通过结论。
- Phase 3 没有改变构建配置或运行路径，因此不存在需要回退的 POM/源码变更。若要撤销本次文档收口，只需删除本报告，并把 README、路线图、执行约定、历史提示词和审计建议恢复为本次修改前的 Phase 3 待验收状态；不要删除其他未跟踪文件。

## 后续阶段

按路线图，Phase 4（替换 OSGi 运行时）是下一项任务。它涉及 Controller/Driver 启动、服务发现、组件装配和生命周期，属于独立架构变更；Phase 3 收口不构成 Phase 4 执行授权。完成审阅后，须取得单独授权再开始。
