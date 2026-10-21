# Build baseline — Phase 0

> 历史静态审计记录。文中“未能构建”和 Eclipse PDE 基线描述反映 Phase 0 时的状态；后续 Tycho 构建/打包结果与当前操作步骤见 `../09-tycho-build-and-package.md`。

## 原始构建模型（已确认）

- `BUILD.md:24-25` 把工程描述为 OSGi modular，并要求 Eclipse PDE；`BUILD.md:31-52` 指示导入 Eclipse、以 PDE Export 导出到 `dist/osgi`，随后执行 `pack.cmd`/`pack.sh`。
- `BUILD.md:57-103` 的调试方式直接使用 `.project`、`release/conf/.controller/config.ini`/`.driver/config.ini` 和 Equinox launcher，而不是 Maven reactor。
- `BUILD-sineio.md:13-17` 要求 Eclipse 4.27.0 与 JDK 1.8/compiler 1.8；`BUILD-sineio.md:24-32` 还要求运行 `version_unify.sh`、删除旧插件 JAR、Eclipse export、再运行 `pack-sineio`。
- `dev/cosbench-manta/pom.xml:57-95` 是独立 Maven bundle 项目（`java.min.version=1.8`、Maven >=3.2、maven-bundle-plugin 3.0.1）；`:106-154` 的 CosBench API/config/log 为 `provided`，OSGi R4 core 也为 optional provided；`:159-181` 生成 Bundle-Activator 并嵌入 compile/runtime 依赖。

## 工具探测（首次审计 Windows 环境的历史结果）

| 命令 | exit | 结果 |
|---|---:|---|
| `mvn -version` | 1 | `'mvn' is not recognized as an internal or external command` |
| `ant -version` | 1 | `'ant' is not recognized as an internal or external command` |
| `gradle --version` | 1 | `'gradle' is not recognized as an internal or external command` |
| `.\mvnw.cmd -version` | 1 | `.\mvnw.cmd is not recognized` |
| `.\gradlew.bat --version` | 1 | `.\gradlew.bat is not recognized` |
| `git diff --check` | 0 | 通过；无新增 whitespace error |

没有安装/升级工具，也没有下载依赖。

以上是原始审计主机的历史探测，不代表当前 Docker 环境状态；本次文档修订不运行项目构建。

## build.properties 盘点（重新核对）

仓库有 33 个 build.properties（dev/ 31 个、ext/ 2 个）。此前逐项表格把多个 Bundle 错写成只有 META-INF/，不能用于构建选型，现以容器内重新读取的配置结果更正：

- 8 个 Bundle 在 bin.includes 中声明嵌入 JAR，共 169 个：ECS 15、GCS 13、librados 2、Manta 5、OpenIO 2、OSS 7、S3 18、SineIO 107。
- 多数有源码的 Bundle 除 META-INF/ 外还包含 bundle 根目录 .；这会把编译输出和根资源纳入产物，不能删去。
- 特殊资源包括：Castor 的 castor.properties；Controller/Driver web 的 WEB-INF/、resources/、index.html（Controller 另含 favicon.ico）；Log4j fragment 的 log4j.properties；SineIO 的 log4j2.xml。
- cosbench-castor 和 cosbench-log4j 是无 source../output.. 声明的资源/fragment Bundle。Manta 同时有 PDE 元数据和 Maven POM，Maven 源目录另见其 POM。
- dev/cosbench-sineio/build.properties 单独声明 107 个 JAR，dev/cosbench-gcs/build.properties 声明 13 个 JAR；不得依据“多数基础 Bundle”的配置推断整个仓库的打包规则一致。

逐项自动读取摘要：33 个配置文件；8 个含嵌入 JAR；5 个 build.properties 文件包含额外非 JAR 资源（不含 bundle 根目录 .）。PoC 必须保留 bin.includes、fragment、嵌入 classpath 和资源语义；不得把每个目录直接映射为普通 Maven JAR。

## 构建/打包脚本副作用检查

- `release/start-controller.sh:22-36` 会先调用 `stop-controller.sh`，读取 `VERSION`，把 7 个 controller bundle 传给 `cosbench-start.sh`。
- `release/start-driver.sh:34-36,69-110` 会先停 driver，删除/重建 `ip-port.list`（`:75`），复制并 `sed -i` 修改每个 `driver_i.conf` 与 Tomcat XML（`:85-102`），然后后台启动。
- `release/cosbench-start.sh:30-47` 依赖工作目录下 `conf/`, `main/*`, `log/`；`:47` 执行 `/usr/bin/nohup java ... org.eclipse.equinox.launcher.Main -configuration $OSGI_CONFIG -console $OSGI_CONSOLE_PORT`，`:78-114` 通过 telnet 逐 bundle 检查启动。
- `release/cosbench-stop.sh:29-56` 通过 OSGi telnet `exit` 或 `ps/kill` 停止；没有可靠 PID 文件，依赖命令行和 console port。
- `pack.sh:20-53` `mkdir $1`，复制 `release/*`, `dist/*`, `docker`, 文档和 `ext/*`，会在仓库下生成发行目录。
- `pack-sineio.sh:20-48` 先 `rm -rf $VERSION`，调用 `pack.sh`，再 `mv`、`tar -czf`，最后 `rm -rf` 生成目录；这是明确的破坏性副作用，本审计没有执行。
- `version_unify.sh:2-12` 读取 `VERSION`，对每个 `dev/*/META-INF/MANIFEST.MF` 使用 `sed -i` 改写 Bundle-Version；本审计没有执行。

## 产物与验证缺口

已确认的产物来源：Eclipse PDE export → `dist/osgi/plugins/*.jar`/`dist/osgi/libs/*.jar`，Equinox launcher → `dist/main/*.jar`，最后 pack 脚本复制成 release tree。未能构建：无 Maven/Ant/Gradle，且主构建依赖 Eclipse PDE。未运行启动 smoke test；需要 Linux `/usr/bin/nohup`, `telnet`, `ps`, `awk`, `sed`, `tar` 和已导出的 JAR。


## 可重复构建定义与发行基线

本项目把“可重复构建”分成两项：第一，固定同一 commit、容器镜像 digest、JDK/构建工具、target platform 与依赖校验和后，两次干净构建的文件清单、MANIFEST 和产物内容哈希一致；第二，若声称归档字节完全一致，还必须固定归档时间戳、文件排序、权限/owner、locale、timezone 和打包工具版本。只做到第一项时应明确称为“内容等价”，不要声称字节级可复现。

Phase 0 审计时的工作树 dist/osgi/plugins 只有 5 个 Equinox/Felix 框架 JAR，未包含任何 COSBench 插件 JAR。因此当时目录不是完整旧发行包。后续旧版基线位于 `../旧包/plugins`，Tycho 对比结论见 `../09-tycho-build-and-package.md`。
