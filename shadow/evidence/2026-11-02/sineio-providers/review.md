# #11 双轴代码审查

固定起点：`f969165701a0364b8785a995b315302467005791`，本轮开始的 HEAD。审查在提交前执行，固定 diff 为 `git diff --cached f969165701a0364b8785a995b315302467005791 --`，提交列表为空。两轴分别由独立并行审查代理执行，排除工作区既有、未暂存的用户改动。规范来源为根 AGENTS.md、docs/agents/、shadow/ci-policy.md 及执行索引；规格来源为 #11、方案 spec、PROV-SIO 验收矩阵及 #04/#10 证据。

## Standards

没有剩余文档规范违规。生产迁入的 8 个 Java 文件除 `sio` HTTP client ownership/dispose 修复外只有空白规范化。SDK 版本、普通 Maven layout、生产/测试边界及 sudo Docker 验证符合现有规则。

1 项非阻塞启发式观察：**P3，possible Duplicated Code**，`sineio-driver-process.sh` 的正常 TERM 和异常清理存在相似的轮询/KILL/wait 流程，也与既有 `driver-process.sh` 相似。可在后续扩展进程测试时抽取共用等待/终止函数。

归档 runner 复核曾发现 3 项 P2，现已修复并复核：硬期限覆盖初始化/restore/轮询/流读取/扩展操作，只有控制期限的线程发布成功；partial range 使用已完整校验对象的较短前缀摘要；Docker 启动期间中断先取消客户端，再有界清理并检查最终容器状态。Logback 显式关闭。失败回放全部通过，未发现秘密输出。

## Spec

**保留 1 项 P1：真实归档/厂商 endpoint 的必需验收未完成。** #11 要求“及 #04 记录的额外操作……不可用项不算通过”；#04 将 restore 与厂商扩展的 production endpoint coverage 留给 #11。受控 HTTP 请求/响应和负向用例不能代替真实归档恢复、数据校验及厂商端点实测。当前缺少相应 endpoint、凭据文件及归档 fixture，第三项 checklist 未勾选，票保持 `needs-info`，PROV-SIO 未整体标通过。

新增严格 runner 能拒绝初始可读对象、错误数据、忽略 Range、慢 restore、轮询越界与中断，不以 skip 代替通过。复核提出的 deadline 及容器创建中断两项 P2 均已修复，负例报告确认 TERM 返回 143、cleanup_status=0、容器无残留。其余生产发现、SDK、配置、MinIO 操作、错误映射与生命周期实现没有剩余明确规格问题。

Standards：0 项硬性违规，1 项 P3 启发式观察；Spec：1 项 P1 外部验收缺口，待真实后端输入。
