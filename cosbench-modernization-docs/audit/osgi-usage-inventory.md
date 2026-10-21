# OSGi / Spring OSGi usage inventory — Phase 0

## 结论摘要

**已确认：** Java 代码直接使用 OSGi API 的生产位置只有 4 处；服务发现和大多数注册由 Spring OSGi XML 完成。**推断：** 迁移时真正的高耦合面是 Spring OSGi extender/web context、Equinox start-level 和服务列表，而不是 Java 中的 `ServiceTracker`。**待验证：** 运行时服务排序、多个同名 factory 冲突处理、`osgi:list` 的动态更新行为尚未在 Equinox 中复现。

## Java OSGi API 使用点

| 状态 | 文件/符号/行 | 当前作用 | 可替代方向（Phase 0 不实施） | 风险/验证 |
|---|---|---|---|---|
| 已确认 | `dev/cosbench-log/src/com/intel/cosbench/log/osgi/Activator.java:20,32-41`, `LogActivator.start/stop` | `BundleActivator.start` 读取 `LogManager`，可选 `cosbench.log.console`；stop dispose | 普通 bootstrap/lifecycle | 依赖 `org.osgi.framework`（manifest:7,10）；启动顺序验证 |
| 已确认 | `dev/cosbench-tomcat/src/com/intel/cosbench/tomcat/osgi/Activator.java:20,31,60-83`, `TomcatRegister.java:26,40,55-63` | 启动 `TomcatLoader`，向 `BundleContext.registerService` 发布 `StandardService/Service/MBeanRegistration/Lifecycle`，stop 注销并停止 | 显式 embedded Tomcat lifecycle | Activator + service registration 是 web 启动关键路径；需端口/类加载 smoke test |
| 已确认 | `dev/cosbench-manta/src/main/java/com/joyent/manta/cosbench/Activator.java:22-47` | Manta bundle 仅记录 start/stop，不注册服务 | 由构建/运行时初始化调用 | Maven 生成 Bundle-Activator (`pom.xml:159-181`) 与 Eclipse `build.properties` 并存 |
| 已确认 | `dev/cosbench-tomcat/src/com/intel/cosbench/tomcat/TomcatRegister.java:57` | `ServiceRegistration<?> registration` 保存 unregister 句柄 | 容器对象由应用直接持有 | stop 流程依赖 registration 非空，需异常路径验证 |
| 已确认 | 全树 `rg` 结果（排除 `release/workspace`/`release/javadoc` 生成物） | 未找到生产 `ServiceTracker`, `FrameworkUtil`, `BundleWiring`, `installBundle`, `getServiceReference`, `ungetService` | 服务容器替代方案需覆盖动态发现 | 不能据此断言运行时没有第三方内部使用；需 bundle resolver 日志 |

## Spring OSGi 服务图

- 每个 adapter 的 `META-INF/spring/plugin-context.xml:9-15` 使用 `spring-osgi.xsd` 和 `<osgi:service ... interface="com.intel.cosbench.api.storage.StorageAPIFactory">`；auth plugins (`httpauth`, `keystone`, `swauth`, `abc-auth`) 同样导出 `AuthAPIFactory`。
- SineIO 在 `dev/cosbench-sineio/META-INF/spring/plugin-context.xml:9-27` 导出三个 bean：`storageFactorySIO`、`storageFactorySIOV1`、`storageFactoryGdas`。
- `dev/cosbench-driver/META-INF/spring/driver-osgi-context.xml:9-19` 以 `<osgi:list>` 收集 `AuthAPIFactory`/`StorageAPIFactory`，再以 `<osgi:service>` 导出 `DriverService`。
- `dev/cosbench-driver/META-INF/spring/driver-context.xml:9-24` 把两份 factory list 注入 `COSBAuthAPIService`/`COSBStorageAPIService`，并构造 `COSBDriverServiceFactory`。
- `dev/cosbench-controller/META-INF/spring/controller-osgi-context.xml:9-13` 导出 `ControllerService`；`dev/cosbench-controller-web/WEB-INF/spring/controller-web-osgi-context.xml:9-13` 引用它。Driver web 对应 `dev/cosbench-driver-web/WEB-INF/spring/driver-web-osgi-context.xml:9-13`。
- 两个 web.xml 均用 `org.springframework.osgi.web.context.support.OsgiBundleXmlWebApplicationContext`：controller `dev/cosbench-controller-web/WEB-INF/web.xml:13-22,89-98`，driver `dev/cosbench-driver-web/WEB-INF/web.xml:13-22,81-90`。

## 配置/打包中的 OSGi 使用

- `release/conf/.controller/config.ini:1-73` 与 `.driver/config.ini:1-91` 都启动 Spring 3.0.5、Spring OSGi 2.0.0.M1、Felix Gogo、Equinox console 及 CosBench bundles；`:74-81`/`:92-99` 设置 `osgi.clean=true`, `osgi.noShutdown=true`, start level 8, `osgi.framework=...org.eclipse.osgi_3.18.100...`, `eclipse.ignoreApp=true`。
- `.driver/config.ini:79` 的原文是 `plugins/cosbench-httpauth@7:\start`，与相邻项的 `@7\:start` 写法不一致；**已确认字符串，待验证** Equinox 对该转义是否接受。
- `dev/cosbench-log/META-INF/MANIFEST.MF:7` 和 `dev/cosbench-tomcat/META-INF/MANIFEST.MF:6` 声明 Activator；`dev/cosbench-castor/META-INF/MANIFEST.MF:8`、`cosbench-log4j/META-INF/MANIFEST.MF:8` 声明 Fragment-Host。
- `release/cosbench-start.sh:47` 的入口 class 是 `org.eclipse.equinox.launcher.Main`；`dist/main`/`dist/osgi` 是运行时实际输入。

## 证据分类与后续验证

- **已确认：** 服务导出/引用 XML、bundle headers、两个 Activator、Equinox launcher 参数。
- **推断：** 删除 Spring OSGi 或改为普通 classpath 会改变 factory 可见性和 web context 创建时序。
- **待验证：** `osgi:list` 的动态服务更新、start-level 失败时的错误传播、所有 embedded Bundle-ClassPath 依赖在 resolver 中的真实版本选择；需要可复现发行包和 Equinox console 日志。
