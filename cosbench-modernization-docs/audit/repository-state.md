# Repository state — Phase 0 audit and current review

审计范围：仅源码、配置、构建/启动脚本的只读盘点；未实施目标架构、未删除或修改 OSGi/Equinox/Spring OSGi、未升级 JDK/SDK、未移动源码。证据标记：**已确认**（文件中直接可见）、**推断**（由已确认链路推导）、**待验证**（需要运行时/依赖解析/人工环境验证）。

## 当前评审工作树（2026-10-20，Docker 容器内检查）

| 项目 | 结果 |
|---|---|
| 仓库根目录 | /workspace（bind mount: /home/ubuntu/cosbench-sineio） |
| branch | refactor/cosbench-restructure |
| HEAD | a99954458d84e3aa46d3882bae9e969128204da1 |
| git status | ?? cosbench-modernization-docs/ |
| 工作环境 | 文档检查/修改使用 python:3.12-alpine@sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4；本轮未运行 Java/Maven 项目构建 |

当前整套文档目录是用户提供的未跟踪内容，必须保留。此处未运行 Controller/Driver 或 workload。

## 首次审计环境快照（历史记录，原始日期未记录）

| 项目 | 结果 | 证据 |
|---|---|---|
| repository root | `G:/sineio-projects/cosbench-sineio` | `git rev-parse --show-toplevel` |
| branch | `codex/next` | `git branch --show-current` |
| HEAD | `a99954458d84e3aa46d3882bae9e969128204da1` | `git rev-parse HEAD` |
| status at audit start | 首次 Windows 审计状态记录了一个已删除样例文件和未跟踪 docs/ | 原始环境的历史快照；不代表当前状态，删除未恢复 |
| Java runtime | `java version "21.0.4" 2024-07-16 LTS`; HotSpot 64-bit | `java -version` |
| javac | `javac 21.0.4` | `javac -version` |
| JAVA_HOME | `C:\Program Files\Java\jdk-21` | PowerShell environment |
| Maven/Ant/Gradle | 未安装或不在 PATH | `mvn -version`/`ant -version`/`gradle --version` 均 exit 1；`.\mvnw.cmd`/`.\gradlew.bat` 不存在 |
| read-only check | 通过 | 原始审计环境的 git diff --check, exit 0 |

## 盘点数量

- `dev/` 下 30 个 Eclipse bundle 目录；`ext/adaptor/` 下 2 个扩展 bundle；共 32 个 `MANIFEST.MF`。
- `dev/` 下 31 个、`ext/` 下 2 个 `build.properties`，共 33 个；`cosbench-manta` 是同时存在 Maven `pom.xml` 和 Eclipse `build.properties` 的混合模块。
- Eclipse 工程文件：32 个 `.project`（每个 bundle 一个）、33 个 `.classpath`（含根 `.classpath`）；只有 `release/conf/.controller/config.ini` 与 `.driver/config.ini` 两个 `config.ini`；只有 `dev/cosbench-manta/pom.xml`；未发现 `build.gradle` 或 `*.launch`。
- Java 源文件 473 个（`dev` 与 `ext`）；没有仓库根 POM/Gradle 构建，只有 `dev/cosbench-manta/pom.xml`。
- `dist/main/org.eclipse.equinox.launcher_1.6.400.v20210924-0641.jar` 与 `dist/osgi/org.eclipse.osgi_3.18.100.v20220817-1601.jar` 是发行启动器/框架；发行库和插件在 `dist/osgi/libs`、`dist/osgi/plugins`。

## 已执行与未执行

已执行：文件清单、`rg` 源码/配置搜索、MANIFEST/build.properties/config.ini/脚本逐项读取、`git diff --check`。未执行：Eclipse PDE 导出、Maven 构建、Equinox 启动、网络适配器 smoke test；工具缺失且构建脚本会写入/删除发行目录，故未强行构建。

## 待人工审阅的边界

1. 本报告记录当前工作树状态，不代表 `release/conf/config-samples/sio-config-smoke-test.xml` 的删除由本次审计造成。
2. Java 21 只表示审计主机运行时版本；项目 MANIFEST/构建文档明确的是 Java 8 目标，未证明 Java 21 兼容（见 [dependency-and-jdk-compatibility.md](dependency-and-jdk-compatibility.md)）。
3. 未执行会改变源码版本、生成目录或归档的脚本；命令及副作用见 [build-baseline.md](build-baseline.md)。
