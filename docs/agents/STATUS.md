# iiBotMosire 当前状态

> **更新时间**：2026-10-28 20:20（Asia/Shanghai）
> **本文是唯一实时状态入口。** 历史状态和文游文档由 Git 保留，不再作为当前设计依据。
> 当前事实与设计/维护裁定冲突时：设计/维护裁定优先；本文只记录“现在实际是什么”。

## 0. 最新更新：P1a/P1b 清理与统一 QQ 客户端重构

- **用户方向**：iibot 只做面向各类 Agent 软件的统一 QQ 消息客户端，不提供程序化文游服务。
- **执行分支**：`refactor/unified-qq-agent-client`；按责任区分批实现、测试、提交、推送，
  全部确认后再合并（M-005）。
- **已冻结选择**：
  - AgentLib 继续固定 `agentlib-mosire:0.2.4`，不降级；
  - `iibot-connect-agent` 整合进正式模块后删除；
  - `qq_conversation_read(threadId)` 只预览并记录服务端 `deliveredSeq`；
    Agent 处理完成后调用 `qq_conversation_ack(threadId)`；ack 不接受内部 seq/revision/itemId；
  - read 与 ack 之间到达的新消息保留下一次未读；发送成功可按固定规则自动 ack。
- **P1a 已完成并推送 `0acd0a0`**：删除 `iibot-taskflow` 与 `iibot-app` 旧文游/legacy 代码；
  `iibot-app` 从 151 个 main 文件收缩到 20 个，测试源文件保留 12 个。
- **P1b 已完成**：删除旧 `simulation/` 运行区和 `config/legacy/`；
  `simulation2/scripts/01-prepare.sh` 不再依赖旧文游配置，优先复用现有
  `bot.sim.local.json`，缺失时从示例生成并退出；旧文游历史由 Git 保留。
  - `tools/gate.sh docs`：PASS（modules=4 files=78 links=194）。
  - `tools/gate.sh full`：PASS，174 tests / 0 failures / 0 errors / 0 skipped。
  - simulation2 `bash -n` + `01-prepare.sh` 现有配置复用 rc=0。
- **本轮代码状态**：默认 reactor 4 模块（connect / connect-agent / bot / app）；
  文游 MCP、taskflow class 和旧 simulation/config legacy 已从默认工作树移除。

## 1. 当前目标 / 进行中范围

目标是把 iiBotMosire 收敛成：

```text
OneBot 11 / NapCat / LLBot（可多个）
  → iibot-connect
  → 通用账号/连接 AgentTool
  → iibot-bot 消息板 + 统一 QQ MCP
  → iibot-app 组合根（embedded Inbox、日志、配置）
  → 外部 Agent
```

当前进行中：

- P1b 已提交并推送；
- 下一步进入 P2 日志基础设施，然后 P3 配置系统。

非目标：

- 玩家、秘书、回合、行动、裁定、世界、Ticket、Publication；
- 在 iibot 内运行 LLM 或业务主 Agent；
- 让 Agent 维护 revision/snapshot/itemId/seq 等内部标记；
- 任意 OneBot action、原始 JSON、任意 SQL/shell。

## 2. 源码基线

| 项 | 当前事实 |
|---|---|
| 仓库 | `/home/cna/DevMosire-iiBot/iiBotMosire` |
| 分支 | `refactor/unified-qq-agent-client`，跟踪 `origin/refactor/unified-qq-agent-client` |
| 已推送提交 | `12ca813` docs、`de19c46` baseline、`0acd0a0` P1a、`207a41e` P1a evidence |
| Maven reactor | 4 模块：`iibot-connect` / `iibot-connect-agent` / `iibot-bot` / `iibot-app` |
| AgentLib | `0.2.4`（用户冻结；公开 tag/Pages 仍为 0.2.3，见 blocker） |
| 工作树 | P1b 变更待提交；无运行中 Java 进程 |
| 模块目标 | `iibot-connect-agent` 在 P6 整合后删除 |

## 3. 最近功能门禁

| 项 | 实际结果 |
|---|---|
| P1a full gate | `tools/gate.sh full` PASS：**174 tests / 0 failures / 0 errors / 0 skipped**（connect 46、connect-agent 21、bot 67、app 40） |
| P1a focused | `tools/gate.sh focused iibot-app -am test` PASS：153 tests / 0 / 0 / 0 |
| P1a jar 扫描 | `taskflow class = 0`，默认 jar 无 taskflow |
| P1b docs | `tools/gate.sh docs` PASS：modules=4 files=78 links=194 |
| P1b simulation2 | `bash -n simulation2/scripts/lib.sh simulation2/scripts/01-prepare.sh` PASS；`01-prepare.sh` 实际复用现有 `bot.sim.local.json`，rc=0 |
| P1b full | `tools/gate.sh full` PASS：174 tests / 0 failures / 0 errors / 0 skipped |

## 4. 当前有效设计/维护入口

| 类型 | 入口 |
|---|---|
| 统一 QQ Agent 客户端目标架构 | [`../superpowers/specs/2026-10-28-unified-qq-agent-client-architecture.md`](../superpowers/specs/2026-10-28-unified-qq-agent-client-architecture.md) |
| 日志分级与诊断 | [`../superpowers/specs/2026-10-28-logging-levels-and-diagnostics.md`](../superpowers/specs/2026-10-28-logging-levels-and-diagnostics.md) |
| 配置文件系统 | [`../superpowers/specs/2026-10-28-configuration-system.md`](../superpowers/specs/2026-10-28-configuration-system.md) |
| P0–P7 改造计划 | [`../superpowers/plans/2026-10-28-unified-qq-agent-client-refactor-plan.md`](../superpowers/plans/2026-10-28-unified-qq-agent-client-refactor-plan.md) |
| 用户需求原话 / D-025..D-028 | [`../superpowers/specs/2026-10-24-user-requirements-source.md`](../superpowers/specs/2026-10-24-user-requirements-source.md) |
| 设计裁定索引 | [`../superpowers/DESIGN_RULINGS.md`](../superpowers/DESIGN_RULINGS.md) |
| 维护裁定 | [`MAINTENANCE_RULINGS.md`](MAINTENANCE_RULINGS.md) |

## 5. 当前已实现能力

- OneBot 11 事件 WS + HTTP Action，重连、心跳、去重、限速、分段、发送三态；
- QQ 私聊/群聊消息板、thread、历史、用户备注、媒体、好友申请；
- Inbox summary/content 与未读桶；
- Bot MCP + embedded Inbox 生命周期；
- 回环 Bearer MCP、日志文件与 ops 事件基础；
- 发送 `SENT` / `FAILED_BEFORE_SEND` / `UNKNOWN`，`UNKNOWN` 不自动重发。

## 6. 未验证 / 风险 / blocker

1. **AgentLib 0.2.4 公开可解析性**：源码主线最新为 0.2.4，但公开 tag/Pages 最新仍为
   0.2.3，0.2.4 公开 POM 404；当前依赖本机已安装 artifact。P7 干净仓库验证在公开发布前
   只能 `BLOCKED` / `NOT_RUN`。
2. **多账号路由未实现**：当前 `BotRuntime` 仍是单 Adapter；P4 完成前不能宣称可切换
   OneBot 配置或多账号并发。
3. **读游标未实现**：当前仍是 `bot_thread_ack` + revision/snapshot 过渡实现；P5 才实现
   read/ack 语义和 Inbox 自动同步。
4. **统一 MCP 未完成**：当前仍是 `bot_* + inbox_* + tf_*` 过渡工具面；P6 删除旧标记工具
   并整合 `iibot-connect-agent`。
5. **旧文游文档历史**：旧 specs/plans/status 仍由 Git 保留；当前导航已移除，P7 做最终
   归档/清理，不得据旧文档开工。
6. **真实外部验收未跑**：真实 QQ → Inbox → DSH/OpenCode wake → Bot MCP 闭环、
   真实发送、日志轮转、长跑和崩溃恢复均 `NOT_RUN`。

## 7. 下一步

1. **开始 P2 日志基础设施**：按日志设计实现 INFO/DEBUG、关联 ID、配置化轮转、脱敏测试；
   需要改代码与测试后提交推送。
2. **P3 配置系统 v2**：多账号 schema、`--check-config`、单文件首次生成、迁移。
3. **P4–P6**：多 Adapter/账号路由 → read/ack 与 Inbox 同步 → 统一 `qq_*` MCP 并删除
   `iibot-connect-agent`。
4. **P7**：依赖 patch 升级、JUnit 6 独立迁移、文档全量重写、干净 Maven 仓库验证。
5. **真实运行验收**：所有真实 QQ/DSH/OpenCode、外发、长跑均需用户当次授权；未跑写
   `NOT_RUN` / `BLOCKED`，不写 PASS。
