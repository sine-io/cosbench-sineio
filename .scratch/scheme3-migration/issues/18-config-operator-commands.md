# 18: 完成配置转换、正式命令与多 Driver 运维

**What to build:** 让现有安装通过正式命令转换配置、启动多个 Driver、停止或重启两个应用，并继续读取历史数据。

**Blocked by:** 14: 交付长时间 workload 的可靠跨进程调用

**Status:** ready-for-human

**Contract:** 开始前读取 [spec](../spec.md)、[执行规则](../README.md#execution-rules)及[验收责任表](../../../docs/migration/scheme3/acceptance-matrix.md#ticket-ownership)中的 OPS-01 和其对应章节。

**Inputs:** #03 已冻结 YAML/最小命令、#09/#10 的可执行应用、#14 的实际跨进程配置，以及 #01 的全部配置/目录/退出码 fixture；不等待页面迁移。

- [x] 完成 Controller/Driver 全部受支持旧字段到版本化 YAML 的映射与默认值对照，五类配置边界/未知字段/非法范围都有诊断；保持 secret 引用及路径/优先级语义，原文件 checksum 不变。
- [x] 交付 bin/cosbench-controller、bin/cosbench-driver、bin/cosbench-stop、bin/cosbench-config-migrate 的完整帮助/参数/退出码；记录哪些旧命令需要短期包装器。
- [x] 正常、异常、重复 start/stop/config-migrate 均有确定结果；检查已有 PID/陈旧 PID/端口占用/启动失败，避免重复创建进程、误停其他实例或覆盖原数据。
- [x] 多 Driver 的端口递增与独立配置/mission/log 目录按基线规则工作，Controller 可连接各实例；指定实例停止不影响其他实例。
- [x] 停止先拒绝新任务，再在 grace period 内关闭 worker、HTTP、Provider 和日志；busy/idle stop 均验证端口与 PID 清理，应用退出状态由既有 process suites 覆盖。
- [x] 空目录运行/配置生成与既有配置迁移、启动、停止、重启通过验证；代表性旧 archive 可按 #03 重启后读取，转换和启停不覆盖原数据。真实生产历史目录仍须人工验收。
- [x] 开发脚本入口改用新命令，保留 start/stop/restart/controller/driver/all 体验参数；帮助与操作样例通过实际命令校验，测试接入 CI。

**Validation evidence:** [Operator command acceptance](../../../shadow/evidence/2026-11-05/operator-commands/README.md)

## Comments

- 2026-11-05: Implemented full configuration migration diagnostics, non-overwriting config generation/binding, the four formal commands, PID ownership checks, multi-Driver startup and the Java 21 Docker development workflow. The isolated operator replay passed migration, two TLS Drivers, Controller connectivity, busy/idle stop, instance isolation, stale/duplicate/port/symlink cases and start/stop/restart; cleanup reported `replay_status=0` with no remaining processes.
- 2026-11-05: Full `ci/verify-shadow.sh` Maven clean verify and all 21 module gates passed. The overall replay stopped at the pre-existing Controller/Driver metrics equality assertion: Prometheus `average_process_time=50.09` while the archived CSV recorded `50.05`. Docker cleanup passed with no project containers, networks, volumes or certificate containers remaining. See the evidence report for the command and exact result.
- 2026-11-05: Follow-up code review fixed `paths.logs` inheritance when `logging.file` is absent, made Driver generation/binding enforce startup TLS/token prerequisites, and made INI conversion emit service credential references plus HTTPS Driver endpoints without reading secret contents. The complete config lifecycle and operator Docker replays then passed using converted Controller and Driver files, with `run_status=0` and `cleanup_status=0`; see the follow-up report.
- 2026-11-05: Controller binding now also rejects any Driver whose resolved service token differs from the Controller token. The mismatch regression test passes and diagnostics do not include either token value.
- Human acceptance remains required for a real production historical archive/workspace/mission/log fixture; the available #03 archive is representative and is not claimed as production evidence.
