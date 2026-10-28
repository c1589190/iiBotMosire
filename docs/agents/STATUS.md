# iiBotMosire 当前状态

> **更新时间**：2026-10-29 00:35（Asia/Shanghai）
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
- **P5 已完成并推送 `b501eea`**：新增 `consumer_thread_state` 持久游标、
  `qq_conversation_read` 预览、`qq_conversation_ack(threadId)` 显式 ack、
  `InboxDigestService.ackThrough` 内部推导 itemId/revision/snapshot；默认 MCP 移除
  `bot_thread_ack`；read 与 ack 之间新消息不被错误消费；ack 先同步 Inbox 再推进 readSeq。
  - `tools/gate.sh full`：PASS，**229 tests / 0 failures / 0 errors / 0 skipped**
    （connect 61、connect-agent 21、bot 80、app 67）。
  - 发送成功后的自动 ack 未实现；第一版统一要求显式 `qq_conversation_ack(threadId)`。
- **P6a 已完成并推送 `8910c7b`**：默认 Agent MCP 统一为 `qq_*`，加入
  `qq_account_list/status/login_info`；`qq_conversation_send` 的 idempotencyKey 变可选；
  默认 Inbox 工具仅保留 `inbox_rest` 和暂留的 `qq_inbox_binding_descriptor`；删除
  `iibot-connect-agent`，reactor 收缩为 connect / bot / app 三模块。
  - `tools/gate.sh full`：PASS，**213 tests / 0 failures / 0 errors / 0 skipped**
    （connect 61、bot 83、app 69）。
- **P6b 已完成并推送 `5117f21`**：recent/history/friend request/user note 工具增加可选
  `accountId`，单账号默认、多账号 `ACCOUNT_REQUIRED`；好友申请 store 迁移到按账号分区的
  `friend_request_v2`；每账号绑定独立 FriendRequestService；备注按账号隔离。
  - `tools/gate.sh full`：PASS，**225 tests / 0 failures / 0 errors / 0 skipped**
    （connect 61、bot 94、app 70）。
- **P7a 依赖刷新已完成并推送 `138737c`**：Jackson 2.22.4、SLF4J 2.0.20、JUnit 6.1.3、
  Java-WebSocket 1.6.0、Maven compiler 3.16.0/surefire 3.6.1/jar 3.5.1/enforcer 3.6.3；
  `iibot-connect` 使用父 POM 统一 SLF4J 版本。
  - `./mvnw -B -ntp clean verify` PASS，**225 tests / 0 failures / 0 errors / 0 skipped**。
  - `iibot-app` shaded jar 成功，Jackson 解析为 2.22.4。
- **P8.1 地址核心已实现并推送 `48e3700`**：新增 `MessageSourceAddress`，规范形式为
  `qq_group/{account}/{group}`、`qq_private/{account}/{peer}`、
  `qq_system/{account}/{event}`、`app_system/{event}`；支持严格 parse/canonical、
  UTF-8 百分号编码、旧 threadId 解码与 `wechat_*`/`discord_*` 同语法扩展。
  MCP thread/message 输出增加 `source`；`InboxSource` 增加 source 投影。
  - `tools/gate.sh full`：PASS，**231 tests / 0 failures / 0 errors / 0 skipped**
    （connect 61、bot 100、app 70）。
  - `tools/gate.sh docs`：PASS（modules=3 files=41 links=101）。
- **P8.2 Inbox/DSH source 契约已完成**：Inbox commit `a040f25` 增加可选 item.source
  （SQLite 幂等迁移、HTTP/MCP/client 透传）；DSH commit `b14f7a1` 在 wake 摘要携带
  source，view-ack 模板改为 `qq_conversation_read(source)` →
  `qq_conversation_ack(source)`，programMcp 按 wakeMode 探测；`bef43ed` 让
  `inbox_adapter_status` 回源 board，避免旧 revision 缓存。
  - Inbox `clean verify` PASS：41 tests；DSH `npm test` PASS：139 tests（3 live skipped）。
- **P8.3 读窗口与迁移已完成并推送 `b3bc680`**：`qq_conversation_read/ack/send/history`
  接受 `source`（兼容旧 threadId）；read 默认只返回 IN，OUT 通过
  `includeOutbound` 单独返回且不进入 ack 边界；SQLite consumer 游标从旧
  `inbox_digest_state.last_seq` 回填。
- **P8.6 全唤醒注入延迟修正（待提交）**：`SourceWeightService.enqueueDelayMillis`
  不再按 `@/权重 actionable` 返回 2 秒 vs 默认 `quietMillis=180s`；所有入站消息统一返回
  调度器默认窗口（`messageBoard.summaryDebounceMillis`）。因此普通群消息不再
  “看起来只有 @ 才注入”，`quietMillis/cooldownMillis` 明确为保留/诊断字段。
  - `tools/gate.sh full`：PASS，**239 tests / 0 failures / 0 errors / 0 skipped**
    （connect 61、bot 108、app 70）。
- **P8.5 P8 corner case 修复已推送 `f076958`**：
  - consumer migration v2 从 active item 的 `inbox_digest_audit.from_seq` 取
    `fromSeq-1`，覆盖 P8 旧回填/手工种子造成的错误行；无 active item 才用 digest `last_seq`。
  - Inbox item 的 `toSeq` 改为最后一条入站消息，OUT 不再把 ack 边界推后；新增 ack 后仍有 OUT 的回归用例。
  - DSH bind/重启在 `lastWake` 为空且 `available>0` 时一次 bootstrap wake
    （`73da106`，README `4b209f3`）。
  - `tools/gate.sh full`：PASS，**238 tests / 0 failures / 0 errors / 0 skipped**
    （connect 61、bot 107、app 70）；DSH `npm test` PASS：140 tests（3 live skipped）。
  - 合并：iiBot `f076958` 已 fast-forward 到 `refactor/bot-first-main-agent`；
    DSH `73da106`/`4b209f3` 在 `main`；Inbox 仍为 `a040f25`。
- **P8.4 群名/缓存/全唤醒收口已推送 `bcc8646`**：消息板 thread 展示名可由
  `QqPlatformCapability` 的 joinedGroups/friends 异步同步（remark 优先，失败回退
  targetId）；DSH `inbox_adapter_status` 改为回源 board；来源地址进入 MCP 输出和
  读/写主力工具；全唤醒保持，权重/阈值/quiet/cooldown 仅排序/诊断。
  - `tools/gate.sh full`：PASS，**235 tests / 0 failures / 0 errors / 0 skipped**
    （connect 61、bot 104、app 70）。
  - `tools/gate.sh docs`：PASS（modules=3 files=41 links=103）；selftest PASS。
  - 合并：iiBot `77c1f2b` 已 fast-forward 到 `refactor/bot-first-main-agent`；
    Inbox `a040f25` 已 fast-forward 到 `main`；DSH `bef43ed` 已 fast-forward 到 `main`。
- **P7b 文档收口完成并合并**：旧文游 specs/plans/status/reports 已归档到
  `docs/archive/legacy-game/`；当前设计索引/README/AGENTS/config/handoff 已更新；
  docs checker 排除归档目录；`tools/gate.sh selftest` PASS。
  - `tools/gate.sh docs`：PASS（modules=3 files=39 links=96）。
  - `tools/gate.sh full`：PASS，**225 tests / 0 failures / 0 errors / 0 skipped**。
  - 干净空 Maven 仓库验证因公开 AgentLib 0.2.4 不存在且依赖下载超时，结果只能
    `PUBLICATION_GAP` / `NOT_RUN`；不得用本机 `~/.m2` 冒充公开可复现。
  - 合并：iiBot feature `e6f012c` 已 fast-forward 到 `refactor/bot-first-main-agent`；
    Inbox dependency commit `f151a87` 已 fast-forward 到 `main`。
  - 最终本机门禁：`tools/gate.sh docs` PASS、`tools/gate.sh selftest` PASS、
    `tools/gate.sh full` PASS（225 tests）；Inbox `clean verify` PASS（37 tests）。
  - 真实 QQ/DSH/Inbox wake 闭环仍 `NOT_RUN`。
- **本轮代码状态**：默认 reactor 3 模块（connect / bot / app）；`iibot-connect-agent`
  已删除；文游 MCP、taskflow class、旧 simulation/config legacy 已从默认工作树移除；
  P2 日志、P3 配置 v2、P4 多账号路由、P5 read/ack、P6a/P6b 统一与逐账号化 `qq_*` 工具、
  P7a 依赖刷新和 P7b 文档归档均已通过本机 fake/embedded loopback 测试。

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

- P1b–P8.5 已完成并合并；P8.6 全唤醒注入延迟修正在 `fix/p8-injection-delay`；
- 剩余：P8.6 提交/合并、真实外部联调与 AgentLib 0.2.4 公开发布。

非目标：

- 玩家、秘书、回合、行动、裁定、世界、Ticket、Publication；
- 在 iibot 内运行 LLM 或业务主 Agent；
- 让 Agent 维护 revision/snapshot/itemId/seq 等内部标记；
- 任意 OneBot action、原始 JSON、任意 SQL/shell。

## 2. 源码基线

| 项 | 当前事实 |
|---|---|
| 仓库 | `/home/cna/DevMosire-iiBot/iiBotMosire` |
| 分支 | `fix/p8-injection-delay`（P8.6 待提交）；`refactor/bot-first-main-agent` 为 P0–P8.5 基线 |
| 已推送提交 | P0 `12ca813`/`de19c46`；P1a `0acd0a0`；P1b `bdef235`；P2 `e84c679`；P3 `d81442a`；P4 `7293b5b`；P5 `b501eea`；P6a `8910c7b`；P6b `5117f21`；P7a `138737c`；P7b `e6f012c`；最终状态 `2f84f02`/`65d053c`；Inbox 依赖刷新 `f151a87`（main） |
| Maven reactor | 3 模块：`iibot-connect` / `iibot-bot` / `iibot-app` |
| AgentLib | `0.2.4`（用户冻结；公开 tag/Pages 仍为 0.2.3，见 blocker） |
| 工作树 | P8.6 变更待提交；无运行中 Java 进程 |
| 模块目标 | 已完成：`iibot-connect-agent` 已删除，模块数为 3 |

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
| P6a full | `tools/gate.sh full` PASS：**213 tests / 0 failures / 0 errors / 0 skipped**（connect 61、bot 83、app 69） |
| P6a docs | `tools/gate.sh docs` PASS（modules=3 files=77 links=192） |
| P6b full | `tools/gate.sh full` PASS：**225 tests / 0 failures / 0 errors / 0 skipped**（connect 61、bot 94、app 70） |
| P6b docs | `tools/gate.sh docs` PASS（modules=3 files=77 links=192） |
| P7a full | `./mvnw -B -ntp clean verify` PASS：**225 tests / 0 failures / 0 errors / 0 skipped**；Jackson/SLF4J/JUnit 新版本已解析 |
| P7b docs | `tools/gate.sh docs` PASS（modules=3 files=39 links=96） |
| P7b selftest | `tools/gate.sh selftest` PASS（check-docs 11/11、skills-lint 6/6） |
| P7b full | `tools/gate.sh full` PASS：**225 tests / 0 failures / 0 errors / 0 skipped** |
| P7b clean repo | `PUBLICATION_GAP` / `NOT_RUN`：公开 AgentLib 0.2.4 缺失，空仓下载超时 |
| P8.1 full | `tools/gate.sh full` PASS：**231 tests / 0 failures / 0 errors / 0 skipped**（connect 61、bot 100、app 70） |
| P8.1 docs | `tools/gate.sh docs` PASS（modules=3 files=41 links=101） |
| P8.2 Inbox | `clean verify` PASS：41 tests；source 字段 HTTP/MCP/SQLite 全链路 |
| P8.2 DSH | `npm test` PASS：139 tests（3 live skipped）；wake source/qq tools |
| P8.3 full | `tools/gate.sh full` PASS：**233 tests / 0 failures / 0 errors / 0 skipped**（connect 61、bot 102、app 70） |
| P8.4 full | `tools/gate.sh full` PASS：**235 tests / 0 failures / 0 errors / 0 skipped**（connect 61、bot 104、app 70） |
| P8.4 DSH | `npm test` PASS：139 tests（3 live skipped）；status 回源 board |
| P8.5 full | `tools/gate.sh full` PASS：**238 tests / 0 failures / 0 errors / 0 skipped**（connect 61、bot 107、app 70） |
| P8.5 DSH | `npm test` PASS：140 tests（3 live skipped）；bootstrap backlog |
| P8.5 merged | iiBot `f076958` / DSH `73da106`+`4b209f3` / Inbox `a040f25` |
| P8.6 full | `tools/gate.sh full` PASS：**239 tests / 0 failures / 0 errors / 0 skipped**（connect 61、bot 108、app 70） |
| P8 跨仓 | Inbox `a040f25`（41 tests）、DSH `b14f7a1`/`bef43ed`（139 tests）、iiBot `bcc8646` |

## 4. 当前有效设计/维护入口

| 类型 | 入口 |
|---|---|
| 统一 QQ Agent 客户端目标架构 | [`../superpowers/specs/2026-10-28-unified-qq-agent-client-architecture.md`](../superpowers/specs/2026-10-28-unified-qq-agent-client-architecture.md) |
| 日志分级与诊断 | [`../superpowers/specs/2026-10-28-logging-levels-and-diagnostics.md`](../superpowers/specs/2026-10-28-logging-levels-and-diagnostics.md) |
| 配置文件系统 | [`../superpowers/specs/2026-10-28-configuration-system.md`](../superpowers/specs/2026-10-28-configuration-system.md) |
| P0–P7 改造计划 | [`../superpowers/plans/2026-10-28-unified-qq-agent-client-refactor-plan.md`](../superpowers/plans/2026-10-28-unified-qq-agent-client-refactor-plan.md) |
| 用户需求原话 / D-025..D-029 | [`../superpowers/specs/2026-10-24-user-requirements-source.md`](../superpowers/specs/2026-10-24-user-requirements-source.md) |
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
2. **consumer 维度仍固定 `default`**：P5 已实现 read/ack 服务端游标，但多 Agent 并发
   consumer 隔离尚未启用；当前不宣称多 consumer 已验收。
3. **多账号省略 accountId 会失败**：P6b 设计要求多账号必须显式指定 accountId；
   Agent 必须先调 `qq_account_list`；单账号可省略。
4. **descriptor 仍暂留 Agent MCP / 自动 ack 未实现**：`qq_inbox_binding_descriptor`
   尚未改成纯宿主 handoff；回复成功后仍要求显式 `qq_conversation_ack(source)`。
5. **真实外部验收未跑**：真实 QQ → Inbox → DSH/OpenCode wake → Bot MCP 闭环、
   真实发送、日志轮转、长跑和崩溃恢复均 `NOT_RUN`。

## 7. 下一步

1. **提交并合并 P8.6**：`fix/p8-injection-delay`。
2. **真实运行验收**：真实 QQ/DSH/OpenCode、外发、长跑均需用户当次授权；未跑写
   `NOT_RUN` / `BLOCKED`，不写 PASS。
3. **上游发布跟进**：ProjectMosire 补 v0.2.4 tag/Pages；发布前干净仓库门禁保持
   `PUBLICATION_GAP` / `BLOCKED`。
4. **后续裁定**：多 consumer、descriptor 完全移出 Agent MCP、自动 ack；
   当前均未实施。
