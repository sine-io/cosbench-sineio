# 05: 验证代表性页面和 multipart 在 Boot/Jakarta 可用

**What to build:** 用一个真实 Controller 页面和 multipart CLI 路径验证 Boot 3/Spring 6/Jakarta、FreeMarker、登录和下载装配，确定后续 Web 批次的做法。

**Blocked by:** 03: 验证统一 YAML、配置转换与最小启停链路

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 GATE-WEB 和其对应章节。

**Inputs:** #01 的页面/CLI fixture、#02 的固定工具链、#03 的 YAML/生命周期合同；验证只编译代表性 ControllerService 路径需要的业务与 Web adapter。

- [x] 最小 Boot app 通过真实 ControllerService seam 提供代表性页面和 multipart CLI handler，普通 Spring wiring 复用兼容 bean；不以假 service 或静态 HTML 代替此证据。
- [x] 验证 Jakarta filter/session、登录成功/失败、FreeMarker model/view、CSS/JS/图片和真实 multipart 提交，页面及 CLI 输出与对应基线比较。
- [x] 验证上传边界、下载内容/文件名/权限及错误响应，记录旧 Spring API、Servlet namespace 和资源路径需要适配的范围。
- [x] 按 #03 配置启动与停止，验证页面、multipart 和下载在 Boot embedded runtime 中共存，使用固定 Boot 3.x BOM。
- [x] 保留命令、fixture、依赖树和产物/错误日志，给出 Web runtime/模板加载/handler 装配的实测方案；其余页面和完整 Controller→Driver 链路由正式迁移票扩展。

## Result

Docker `clean verify` passed with three Web integration tests. The packaged Boot 3.5.16 application served the login and authenticated index, accepted the #01 `mock-noop.xml` multipart fixture through the real `COSBControllerService`, and returned archived config/log downloads. The upload limit accepted 10 MiB and rejected 10 MiB + 1 byte with HTTP 413. CSS, JavaScript, and PNG response bytes matched the legacy resource files.

The fixture has no configured Driver, so the real workload service archives it as `TERMINATED` after `no free driver available`; end-to-end Controller-to-Driver behavior remains with later tickets. On Java 21, the legacy Castor 1.3.3 archive serializer requires `--add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED`; the spike scopes this compatibility flag to its test and launch evidence.

Surefire runs from the module `target` directory, and the packaged process runs from its temporary runtime directory. This isolates the legacy repository's relative `start`/`stop` marker files from the checkout.

Evidence and replay command: [web Jakarta evidence](../../../shadow/evidence/2026-10-29/web-jakarta/README.md).
