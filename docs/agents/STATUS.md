# iiBotMosire 当前状态

> **更新时间**：2026-10-28 21:10（Asia/Shanghai）
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
- **P2 已完成并推送 `e84c679`**：日志上下文（boot/trace/call）、root/ops 级别与滚动配置、
  关键路径 INFO/DEBUG、MCP 工具/入站/出站/Inbox 关联、UNKNOWN 不重试日志和
  正文/token 零命中回归。
  - `tools/gate.sh full`：PASS，192 tests / 0 failures / 0 errors / 0 skipped
    （connect 58、connect-agent 21、bot 69、app 44）。
- **P3 已完成并推送 `d81442a`**：配置 v2（多账号 schema、`messageBoard`、JSON `logging`）、
  来源文件/JSON pointer 错误定位、`--check-config`、`--print-effective-config`、
  `--log-level`、单一 `config/bot/bot.json` 首次生成和 schema/examples。
  - `tools/gate.sh full`：PASS，**208 tests / 0 failures / 0 errors / 0 skipped**
    （connect 58、connect-agent 21、bot 69、app 60）。
  - `tools/gate.sh docs`：PASS（modules=4 files=78 links=195）。
- **P4 已完成并推送 `7293b5b`**：新增 `AccountRegistry`；BotMain 启动全部 enabled account；
  BotRuntime 按账号启动 Adapter、绑定入站 accountId、按 thread.accountId 路由独立
  OutboxSender；`inbound_account_mismatch` fail-closed；多账号启动失败回滚并关闭全部
  adapter；forward/media 已按消息账号路由，direct-provider 工具显式绑定 active account
  并留 P6 完整多账号化。
  - `tools/gate.sh full`：PASS，**221 tests / 0 failures / 0 errors / 0 skipped**
    （connect 61、connect-agent 21、bot 73、app 66）。
  - `tools/gate.sh docs`：PASS（modules=4 files=78 links=195）。
- **P5 已完成**：新增 `consumer_thread_state` 持久游标、`qq_conversation_read` 预览、
  `qq_conversation_ack(threadId)` 显式 ack、`InboxDigestService.ackThrough` 内部推导
  itemId/revision/snapshot；默认 MCP 移除 `bot_thread_ack`；read 与 ack 之间新消息不被
  错误消费；ack 先同步 Inbox 再推进 readSeq。
  - `tools/gate.sh full`：PASS，**229 tests / 0 failures / 0 errors / 0 skipped**
    （connect 61、connect-agent 21、bot 80、app 67）。
  - `tools/gate.sh docs`：PASS（modules=4 files=78 links=195）。
  - 发送成功后的自动 ack 未实现；第一版统一要求显式 `qq_conversation_ack(threadId)`。
  - 真实 QQ/DSH/Inbox wake 闭环仍 `NOT_RUN`。
- **本轮代码状态**：默认 reactor 4 模块（connect / connect-agent / bot / app）；
  文游 MCP、taskflow class、旧 simulation/config legacy 已从默认工作树移除；
  P2 日志、P3 配置 v2、P4 多账号路由、P5 read/ack 游标已通过 fake/embedded loopback 测试。

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

- P1b、P2、P3、P4、P5 已完成；
- 下一步 P6：统一 `qq_*` MCP 工具面、direct-provider 多账号化、整合并删除
  `iibot-connect-agent`。

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
| 已推送提交 | `12ca813` docs、`de19c46` baseline、`0acd0a0` P1a、`207a41e` P1a evidence、`bdef235` P1b、`e84c679` P2、`d81442a` P3、`7293b5b` P4 |
| Maven reactor | 4 模块：`iibot-connect` / `iibot-connect-agent` / `iibot-bot` / `iibot-app` |
| AgentLib | `0.2.4`（用户冻结；公开 tag/Pages 仍为 0.2.3，见 blocker） |
| 工作树 | P5 变更待提交；无运行中 Java 进程 |
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
| P2 full | `tools/gate.sh full` PASS：**192 tests / 0 failures / 0 errors / 0 skipped**（connect 58、connect-agent 21、bot 69、app 44） |
| P2 docs | `tools/gate.sh docs` PASS（modules=4 files=78 links=194） |
| P3 full | `tools/gate.sh full` PASS：**208 tests / 0 failures / 0 errors / 0 skipped**（connect 58、connect-agent 21、bot 69、app 60） |
| P3 docs | `tools/gate.sh docs` PASS（modules=4 files=78 links=195） |
| P4 full | `tools/gate.sh full` PASS：**221 tests / 0 failures / 0 errors / 0 skipped**（connect 61、connect-agent 21、bot 73、app 66） |
| P4 docs | `tools/gate.sh docs` PASS（modules=4 files=78 links=195） |
| P5 full | `tools/gate.sh full` PASS：**229 tests / 0 failures / 0 errors / 0 skipped**（connect 61、connect-agent 21、bot 80、app 67） |
| P5 docs | `tools/gate.sh docs` PASS（modules=4 files=78 links=195） |

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
2. **direct-provider 工具仍未完全多账号化**：`bot_qq_recent`、`bot_qq_history`、
   `bot_friend_request_*` 仍绑定 active account；P6 完成统一 `qq_*` 工具面时处理。
3. **consumer 维度仍固定 `default`**：P5 已实现 read/ack 服务端游标，但多 Agent 并发
   consumer 隔离尚未启用；当前不宣称多 consumer 已验收。
4. **统一 MCP 未完成**：当前仍有 `bot_* + inbox_* + qq_*` 过渡工具面；P6 删除旧标记工具
   并整合 `iibot-connect-agent`。
5. **旧文游文档历史**：旧 specs/plans/status 仍由 Git 保留；当前导航已移除，P7 做最终
   归档/清理，不得据旧文档开工。
6. **真实外部验收未跑**：真实 QQ → Inbox → DSH/OpenCode wake → Bot MCP 闭环、
   真实发送、日志轮转、长跑和崩溃恢复均 `NOT_RUN`。

## 7. 下一步

1. **提交并推送 P5 checkpoint**：当前分支 `refactor/unified-qq-agent-client`。
2. **P6 统一 MCP**：`qq_*` 工具面、direct-provider 多账号化、整合并删除
   `iibot-connect-agent`。
3. **P7**：依赖 patch 升级、JUnit 6 独立迁移、文档全量重写、干净 Maven 仓库验证。
4. **真实运行验收**：所有真实 QQ/DSH/OpenCode、外发、长跑均需用户当次授权；未跑写
   `NOT_RUN` / `BLOCKED`，不写 PASS。
