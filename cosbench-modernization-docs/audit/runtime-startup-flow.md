# Controller / Driver runtime startup flow — Phase 0

## Controller 启动（已确认）

```mermaid
sequenceDiagram
  participant U as operator
  participant SC as start-controller.sh
  participant CS as cosbench-start.sh
  participant EQ as Equinox launcher
  participant T as tomcat Activator
  participant S as Spring OSGi contexts
  participant C as ControllerService
  participant W as controller web

  U->>SC: sh start-controller.sh
  SC->>SC: stop-controller.sh; read VERSION
  SC->>CS: controller + 7 bundle names + console 19089
  CS->>EQ: nohup java -Dcosbench.tomcat.config=conf/controller-tomcat-server.xml -configuration conf/.controller -console 19089
  EQ->>T: start cosbench-tomcat (config.ini start level 7)
  T->>T: TomcatLoader.start(); register StandardService
  EQ->>S: start Spring 3/Spring OSGi contexts
  S->>C: controller-osgi-context exports ControllerService
  S->>W: controller-web reference + OsgiBundleXmlWebApplicationContext
  CS-->>U: telnet console; probe every listed bundle up to 60 s
```

证据：`release/start-controller.sh:22-36` stop/read/version/bundle list；`release/cosbench-start.sh:30-47,60-114` 参数、Equinox、telnet probe；`release/conf/.controller/config.ini:1-81` bundle/start-level/framework；`release/conf/controller-tomcat-server.xml:3-9` HTTP 19088/MemoryRealm/0.0.0.0；`dev/cosbench-tomcat/src/com/intel/cosbench/tomcat/osgi/Activator.java:60-83` Tomcat lifecycle；`dev/cosbench-controller/META-INF/spring/controller-osgi-context.xml:9-13` service export；`dev/cosbench-controller-web/WEB-INF/spring/controller-web-osgi-context.xml:9-13` service reference；`dev/cosbench-controller-web/WEB-INF/web.xml:13-22,27-40,43-58,89-98` servlet/context。

## Driver 启动（已确认）

```mermaid
sequenceDiagram
  participant U as operator
  participant SD as start-driver.sh
  participant CS as cosbench-start.sh
  participant EQ as Equinox
  participant P as plugin contexts
  participant D as DriverService
  participant W as driver web

  U->>SD: sh start-driver.sh [num] [ip] [basePort] [level]
  SD->>SD: stop-driver.sh; rm ip-port.list; copy/sed driver_i.conf + Tomcat XML
  SD->>CS: driver + adapter/core/driver bundle list + console basePort+1
  CS->>EQ: nohup java ... -configuration conf/.driver -console
  EQ->>P: start adapters at level 7; core/driver/web at level 8
  P->>P: plugin-context exports Auth/StorageAPIFactory
  P->>D: driver-osgi-context collects lists; exports DriverService
  P->>W: driver web references DriverService; `.command` servlet path
  CS-->>U: telnet checks listed bundles
```

证据：`release/start-driver.sh:34-36,69-110`；`release/conf/.driver/config.ini:63-91`；`release/conf/driver-tomcat-server_template.xml:3-9`（端口由脚本改写）；`dev/cosbench-driver/META-INF/spring/driver-osgi-context.xml:11-19`；`dev/cosbench-driver-web/WEB-INF/spring/driver-web-osgi-context.xml:9-13`；`dev/cosbench-driver-web/WEB-INF/web.xml:13-22,35-50,81-90`。**已确认发行启动清单没有 `cosbench-manta` 或 `ext/adaptor` 的 `cosbench-abcauth`/`cosbench-abcstor`，所以它们不会因默认 `start-driver.sh` 自动进入服务列表；是否由其它发行流程注入待验证。**

## Controller→Driver workload 协议

1. `COSBControllerService.setContext/init`：`dev/cosbench-controller/src/com/intel/cosbench/controller/service/COSBControllerService.java:66-92` 启动 driver ping、archive loader、并发 executor。
2. `submit(XmlConfig)`：同文件 `:115-145` 建立 `WorkloadContext`，状态 `QUEUING`；`WorkloadProcessor` 执行 stage。
3. `StageRunner.runStage`：`dev/cosbench-controller/src/com/intel/cosbench/controller/service/StageRunner.java:124-161` 依次 submit/auth/launch/query/close tasklet，并 merge report。
4. `AbstractHttpTasklet.issueHttpRequest`：`dev/cosbench-controller/src/com/intel/cosbench/controller/tasklet/AbstractHttpTasklet.java:55-75` 固定 POST `driver.getUrl()+"/i/"+command+".command"`；`TriggerHttpTasklet.java:86-143` 同一 URL 规则用于 trigger。
5. Driver web 的 `*.command` 映射在 `dev/cosbench-driver-web/WEB-INF/web.xml:35-50`；具体 handler 调用 `COSBDriverService.submit/login/launch/info/close/abort`（`dev/cosbench-driver/src/com/intel/cosbench/driver/service/COSBDriverService.java:83-196,220-246`）。
6. `release/cli.sh:17-19,70-84` 对 Controller 的 `/controller/cli/submit.action`, `index.action`, `cancel.action` 发送 HTTP 请求；默认 `127.0.0.1:19088`。

## 地址、工作目录、退出和日志

| 项目 | 证据与结论 |
|---|---|
| controller HTTP | 19088，`controller-tomcat-server.xml:3`；console 19089，`start-controller.sh:34` |
| driver HTTP | 默认 18088，driver template `:3`；每个 driver 的 console 为 HTTP port+1（`start-driver.sh:101-107`）；controller.conf driver URL `:12-14` |
| bind/auth | Tomcat `0.0.0.0` + `MemoryRealm ./conf/cosbench-users.xml` (`controller/driver-tomcat-server.xml:4-9`) |
| config path | `cosbench-start.sh:30-32` 使用 `conf/.controller`/`conf/.driver` 与 `conf/*-tomcat-server.xml`; service factories 还读取 `cosbench.controller.config`/`cosbench.driver.config`，fallback `controller.conf`/`driver.conf` 和 `conf/*` |
| log | `cosbench-start.sh:24,47` 输出 `log/controller-boot.log`/`driver-boot.log`; `driver.conf:1-4` 任务日志 `log/system.log`, mission `log/mission`; controller.conf archive `archive` |
| stop | `stop-all.sh:18-22` controller→driver；`cosbench-stop.sh:29-56` telnet `exit`, fallback ps/kill；无可靠 PID 文件，`ip-port.list` 仅记录 driver 名称/地址 |
| 工作目录 | 脚本使用相对 `main`, `conf`, `log`, `archive` 路径；**推断** 必须从发行根运行，否则 Equinox/配置/日志路径可能失效；待 Linux smoke test |
| 信号/超时 | 启动轮询约 60 秒；停止仅 sleep/进程检查，未发现 SIGTERM handler 或持久 PID 文件；待故障演练 |

## 配置异常

`.driver/config.ini:79` 的 `plugins/cosbench-httpauth@7:\start` 与其它条目 `@7\:start` 不一致。**已确认原文；待验证** Equinox 解析后是否导致该 bundle 未启动，Phase 1 前应做最小发行包 console 复现。
