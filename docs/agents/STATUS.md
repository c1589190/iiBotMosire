# iiBotMosire 当前状态

> **更新时间**：2026-10-31 01:15（Asia/Shanghai）
> **本文是唯一实时状态入口。** 历史状态和文游文档由 Git 保留，不再作为当前设计依据。
> 当前事实与设计/维护裁定冲突时：设计/维护裁定优先；本文只记录“现在实际是什么”。

## 0. 最新更新：P1a/P1b 清理与统一 QQ 客户端重构

- **2026-10-31 D-034 B 站通用视频与 Inbox 会话 Adapter 已实现（code-level）**，
  主提交 `a3ddf32`，扫码登录提交 `6967a28`：
  - 通用只读 `bili_video_read`：任意来源 BV/av、bilibili.com/video、b23.tv 短链；
    返回视频元数据、简介、可选热评和登录态字幕；不进入消息板/Inbox；
  - `ChatType.EXTERNAL` + 统一 source：新增 `bili_dm/...`、`bili_comment/...`、
    `bili_video/...`，QQ 旧 threadId/数据保持兼容；
  - `BilibiliAdapter`：0600 Cookie 文件、nav 登录校验、WBI、私信轮询、评论回复
    通知轮询、私信/评论回复发送；入站进入统一消息板/Inbox；
  - `bili_account_login_start` / `bili_account_login_poll`：扫码登录，成功后原子写入
    0600 Cookie 文件，工具结果不返回 Cookie；
  - 新增 `conversation_read/ack/send` 通用别名，`qq_conversation_*` 继续兼容；
  - 配置 schema 支持 `adapter=bilibili`，示例见
    [`config/examples/bot.bilibili.json`](../../config/examples/bot.bilibili.json)。
  - 真实匿名 `bili_video_read` smoke PASS：公开视频元数据/热评读取成功；
    真实 B 站登录、私信轮询、评论通知、私信/评论发送均 `NOT_RUN`。
  - `tools/gate.sh docs` PASS（modules=3 files=47 links=142）；
    `tools/gate.sh full` PASS，**303 tests / 0 failures / 0 errors / 0 skipped**
    （connect 86、bot 127、app 90）。
  - 设计正本：
    [`../superpowers/specs/2026-10-31-bilibili-generic-video-and-conversation-adapter.md`](../superpowers/specs/2026-10-31-bilibili-generic-video-and-conversation-adapter.md)。

- **2026-10-30 D-033 第一阶段已实现（code-level）**，主提交 `2fbc3a4`：
  明确 `iiBotMosire`（QQ 业务事实源 + MCP 产品面）与 `iiBotMosire-DSH`
  （DSH 宿主扩展）的边界；
  `qq_conversation_read` 增加每条消息的 `messageId`，并修正 `more` 只按入站未读
  判断（OUT 不占窗口、replay 不掩盖剩余未读）；`qq_conversation_send` 新增
  `replyToSeq`，由服务端同 thread 解析平台 messageId 和 mention 发送者。
  设计正本：
  [`../superpowers/specs/2026-10-30-thin-dsh-boundary-and-semantic-mcp.md`](../superpowers/specs/2026-10-30-thin-dsh-boundary-and-semantic-mcp.md)。
  `tools/gate.sh full` PASS，**282 tests / 0 failures / 0 errors / 0 skipped**
  （connect 73、bot 124、app 85）；`tools/gate.sh docs` PASS
  （modules=3 files=46 links=132）。真实 QQ/LLBot/DSH 联调、运行中 jar 替换均
  `NOT_RUN`。
- **2026-10-31 隔壁 Bot 实测反馈已并入 D-033**：工具 description 已足以自解释
  read/send/ack；通用 wake 必须保留 `source` 和 `available`，建议保留 `kind`，
  view-ack 不需要 `itemId`；唤醒正文不得出现不存在的 `inbox_claim`。read/ack
  description 已补回 `kind=digest_complete` / `complete=true` 可直接 ack 的说明。
- **2026-10-31 D-033 DSH 侧通用化已完成（code-level）**：
  `InboxMosire-DSH` 0.7.0 的 view-ack 现在通用渲染
  `source/kind/summary[/truncated]`，保留 wake 头 `available`，不再依赖 profile
  的工具名、业务 kind、compact 开关或程序 MCP 警告；view-ack readiness 为
  `unknown`，claim 模式仍保留通用 claim 工具探测。`iiBotMosire-DSH` 的
  wake profile 收敛为零语义兼容 alias 0.3.0。iibot read/ack description 对
  `truncated=true` 明确要求先 read。
  `InboxMosire-DSH npm test` PASS **150 tests / 147 pass / 3 live-skipped /
  0 fail**；iiBotDSH wake-profile 2/2、persona 24/24 PASS。
  **真实 DSH reload/rebind、generic view-ack live wake、运行中 jar 替换均
  `NOT_RUN`；当前实际 binding 仍可能是 claim，部署前不得宣称 F-025 已解除。**
- **2026-10-30 可用代码检查点已提交 `d67b757`**：D-031/D-032 的源码、测试、
  配置、设计文档与摩擦调查报告收口提交；本 STATUS 与 B 站 Adapter 调查报告
  随后单独收口。提交前实跑 `tools/gate.sh docs` PASS
  （modules=3 files=45 links=125）与 `tools/gate.sh full` PASS，
  **277 tests / 0 failures / 0 errors / 0 skipped**
  （connect 73、bot 119、app 85）；真实 QQ/LLBot/DSH 外部联调仍为 `NOT_RUN`。
- **2026-10-30 D-032 Inbox 短正文内联与 compact read/ack 已实现（code-level）**：
  `InboxMosire` core 不改；Bot 以 `kind=digest_complete` 标记完整短文本，
  `messageBoard.inlineMaxChars` 默认 512；`InboxMosire-DSH` 增加 profile 驱动的
  `completeKinds` / `compactViewAckItems`，`iiBotMosire-DSH` 使用短 wake 文案；
  `qq_conversation_read(source)` / `qq_conversation_ack(source)` 改为 compact 契约，
  ack 只返回 `more`。iiBot full gate PASS，**277 tests / 0 failures / 0 errors /
  0 skipped**（connect 73、bot 119、app 85）；InboxMosire-DSH `npm test` PASS
  **149 tests / 146 pass / 3 live skipped / 0 fail**；iiBot-DSH profile PASS 2/2。
  真实 DSH reload、真实 QQ/LLBot、新会话 token 降幅均 `NOT_RUN`。
  设计正本：
  [`../superpowers/specs/2026-10-30-inbox-inline-and-compact-tool-results.md`](../superpowers/specs/2026-10-30-inbox-inline-and-compact-tool-results.md)。
- **2026-10-30 D-031 3A–3D 已实现（code-level）**：Raw LLBot 常规兜底、
  `mentionAll`（群聊全员）、任意主动私聊、撤回、MCP 外发图片/表情/群文件、语音提取（ASR 未接）、
  调度交宿主。`tools/gate.sh full` PASS，**272 tests / 0 failures / 0 errors /
  0 skipped**（connect 73、bot 115、app 84）；真实 QQ/LLBot 外部动作 `NOT_RUN`。
  设计正本：
  [`../superpowers/specs/2026-10-30-qq-outbound-actions-and-media-rulings.md`](../superpowers/specs/2026-10-30-qq-outbound-actions-and-media-rulings.md)。
- **2026-10-30 Bot 试运行摩擦第二批已实现（code-level）**：F-003
  `qq_forward_media_get` 顶层转发图片安全读取；F-016 `GroupFileCapability` +
  `qq_group_files` / `qq_group_file_get`（短期 fileRef，文本/图片/二进制三态）；
  F-014 DSH 改为 agent-scoped tool schema；F-015 ack 的 `remaining/nextAction`
  已由 D-032 的 `more` compact 契约取代。
  iiBot `tools/gate.sh full` PASS，**251 tests / 0 failures / 0 errors / 0 skipped**
  （connect 67、bot 110、app 74）；DSH `npm test` PASS（147 tests，3 live skipped）；
  iiBot-DSH wake profile PASS（2/2）。真实 QQ/LLBot/DSH reload 后联调 `NOT_RUN`。
- **2026-10-29 Bot 试运行摩擦第一批已实现**：F-001 卡片可读文本与 F-002 群文件元数据保真；
  `tools/gate.sh full` PASS，**246 tests / 0 failures / 0 errors / 0 skipped**
  （connect 66、bot 110、app 70）；`tools/gate.sh docs` PASS
  （modules=3 files=43 links=109）。真实 QQ/LLBot 回归 `NOT_RUN`。调查与分诊见
  [`../superpowers/reports/2026-10-29-bot-trial-friction-investigation.md`](../superpowers/reports/2026-10-29-bot-trial-friction-investigation.md)。
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
- **P8.7 直接 ack / 序号 ack 已推送 `8e8d57e`**：`qq_conversation_ack(source)` 现在有两种模式：
  已 read 时按固定 delivered 批次归档；未 read 时直接归档当前 active Inbox item。
  `NO_ACTIVE_ITEM` 是稳定 no-op。另加可选整数 `seq`（消息板 localSeq），
  `ack(source, seq=N)` 不需要 itemId、revision 或任何 hash。DSH view-ack 模板同步
  说明 read 可选、可直接 ack、可按 seq ack。
  - `tools/gate.sh full`：PASS，**240 tests / 0 failures / 0 errors / 0 skipped**
    （connect 61、bot 109、app 70）；DSH `npm test` PASS：140 tests（3 live skipped）。
- **P8.6 全唤醒注入延迟修正已推送 `c6d5194`**：`SourceWeightService.enqueueDelayMillis`
  不再按 `@/权重 actionable` 返回 2 秒 vs 默认 `quietMillis=180s`；所有入站消息统一返回
  调度器默认窗口（`messageBoard.summaryDebounceMillis`）。因此普通群消息不再
  “看起来只有 @ 才注入”，`quietMillis/cooldownMillis` 明确为保留/诊断字段。
  - `tools/gate.sh full`：PASS，**239 tests / 0 failures / 0 errors / 0 skipped**
    （connect 61、bot 108、app 70）。
  - 合并：iiBot `c6d5194` 已 fast-forward 到 `refactor/bot-first-main-agent`。
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

- P1b–P8.7 已完成并合并；
- **Bot 试运行摩擦第一批（2026-10-29）已完成（code-level）**：按
  [`../superpowers/reports/2026-10-29-bot-trial-friction-investigation.md`](../superpowers/reports/2026-10-29-bot-trial-friction-investigation.md)
  完成 F-001 卡片可读文本与 F-002 群文件元数据保真；未扩出站/任意 action；
- **Bot 试运行摩擦第二批（2026-10-30）已完成（code-level）**：F-003 转发顶层
  图片安全读取、F-016 群文件列表/读取、F-014 DSH programMcp 探测、F-015 partial
  ack 同轮续处理；真实运行/宿主 reload 仍待验证；
- 剩余：真实外部联调、F-012/F-013/F-019 等后续责任区与 AgentLib 0.2.4 公开发布。

非目标：

- 玩家、秘书、回合、行动、裁定、世界、Ticket、Publication；
- 在 iibot 内运行 LLM 或业务主 Agent；
- 让 Agent 维护 revision/snapshot/itemId/seq 等内部标记；
- 任意 OneBot action、原始 JSON、任意 SQL/shell。

## 2. 源码基线

| 项 | 当前事实 |
|---|---|
| 仓库 | `/home/cna/DevMosire-iiBot/iiBotMosire` |
| 分支 | `refactor/bot-first-main-agent`（已合并 P0–P8.7）；feature/fix 分支保留 |
| 已推送提交 | P0 `12ca813`/`de19c46`；P1a `0acd0a0`；P1b `bdef235`；P2 `e84c679`；P3 `d81442a`；P4 `7293b5b`；P5 `b501eea`；P6a `8910c7b`；P6b `5117f21`；P7a `138737c`；P7b `e6f012c`；最终状态 `2f84f02`/`65d053c`；Inbox 依赖刷新 `f151a87`（main）；2026-10-30 可用检查点 `d67b757`；D-033 主提交 `2fbc3a4`；D-034 主提交 `a3ddf32`；B 站扫码登录 `6967a28` |
| Maven reactor | 3 模块：`iibot-connect` / `iibot-bot` / `iibot-app` |
| AgentLib | `0.2.4`（用户冻结；公开 tag/Pages 仍为 0.2.3，见 blocker） |
| 工作树 | D-034 主提交 `a3ddf32` + 扫码登录 `6967a28` 已提交；本 STATUS 更新后 clean |
| 模块目标 | 已完成：`iibot-connect-agent` 已删除，模块数为 3 |

## 3. 最近功能门禁

| 项 | 实际结果 |
|---|---|
| D-034 full | `tools/gate.sh full` PASS：**303 tests / 0 failures / 0 errors / 0 skipped**（connect 86、bot 127、app 90） |
| D-034 docs | `tools/gate.sh docs` PASS（modules=3 files=47 links=142） |
| D-034 B站匿名 smoke | `bili_video_read` 公开视频元数据/热评读取 PASS；登录态字幕、私信、评论通知与真实外发 `NOT_RUN` |
| D-033 phase 1 full | `tools/gate.sh full` PASS：**282 tests / 0 failures / 0 errors / 0 skipped**（connect 73、bot 124、app 85）；docs PASS（modules=3 files=46 links=132） |
| D-033 DSH generic | InboxMosire-DSH `npm test` PASS：150 tests / 147 pass / 3 live-skipped / 0 fail；iiBotDSH wake-profile 2/2、persona 24/24；真实 reload/rebind `NOT_RUN` |
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
| P8.7 full | `tools/gate.sh full` PASS：**240 tests / 0 failures / 0 errors / 0 skipped**（connect 61、bot 109、app 70） |
| P8.7 DSH | `npm test` PASS：140 tests（3 live skipped）；direct ack 提示 |
| P8.7 merged | iiBot `8e8d57e` / DSH `0370e07` / Inbox `a040f25` |
| P8 跨仓 | Inbox `a040f25`（41 tests）、DSH `b14f7a1`/`bef43ed`（139 tests）、iiBot `bcc8646` |
| 2026-10-29 摩擦第一批 focused | `tools/gate.sh focused iibot-bot -am test` PASS：connect 66 + bot 110，0/0/0；`.work/` 未提交 |
| 2026-10-29 摩擦第一批 full | `tools/gate.sh full` PASS：**246 tests / 0 failures / 0 errors / 0 skipped**（connect 66、bot 110、app 70）；证据 `.work/bot-friction-f001-f002-full.log` |
| 2026-10-29 摩擦第一批 docs | `tools/gate.sh docs` PASS（modules=3 files=43 links=109） |
| 2026-10-30 摩擦第二批 full | `tools/gate.sh full` PASS：**251 tests / 0 failures / 0 errors / 0 skipped**（connect 67、bot 110、app 74）；证据 `.work/bot-friction-batch2-full.log` |
| 2026-10-30 摩擦第二批 docs | `tools/gate.sh docs` PASS（modules=3 files=43 links=110） |
| 2026-10-30 D-031 3A–3D full | `tools/gate.sh full` PASS：**272 tests / 0 failures / 0 errors / 0 skipped**（connect 73、bot 115、app 84）；证据 `.work/d031-full.log` |
| 2026-10-30 D-031 3A–3D docs | `tools/gate.sh docs` PASS（modules=3 files=44 links=119） |
| 2026-10-30 D-032 full | `tools/gate.sh full` PASS：**277 tests / 0 failures / 0 errors / 0 skipped**（connect 73、bot 119、app 85）；证据 `.work/d032-full.log` |
| 2026-10-30 D-032 docs | `tools/gate.sh docs` PASS（modules=3 files=45 links=125）；证据 `.work/d032-docs.log` |
| 2026-10-30 InboxMosire-DSH D-032 | `npm test` PASS：149 tests / 146 pass / 3 live skipped / 0 fail；证据 `.work/d032-inbox-dsh-test.log` |
| 2026-10-30 iiBot-DSH D-032 | profile `npm test` PASS：2/2；证据 `.work/d032-profile-test.log` |
| 2026-10-30 DSH test | `npm test` PASS：147 tests / 144 pass / 3 live skipped / 0 fail；证据 `.work/dsh-f014-test.log`（该仓 `.work` 未提交） |
| 2026-10-30 iiBot-DSH profile | `npm test` PASS：2/2；partial-ack 同轮续处理提示进入两个 view-ack body |

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
- B 站 Cookie/WBI 登录校验、扫码登录工具、私信轮询、评论回复通知轮询、
  私信/评论回复发送；
- `bili_video_read` 通用视频阅读（元数据/简介/热评/登录态字幕）；
- QQ 私聊/群聊消息板、thread、历史、用户备注、媒体、好友申请；入站卡片提取安全
  标题/描述/链接，群文件保存文件名/大小/内部 file id；MCP 不返回 file id 或 URL；
- `qq_forward_media_get`：顶层合并转发图片以 MCP ImageContent 返回；
- `qq_group_files` / `qq_group_file_get`：群文件目录、短期 fileRef、文本有界正文、
  图片 ImageContent、二进制元数据省略；不暴露 busid/URL；
- `qq_group_file_upload`：受控资产经 `upload_group_file` 上传，先写 OUT/PENDING，
  回写三态；不向模型暴露临时路径；
- `qq_conversation_send` 支持 `mentionAll` 与 image/sticker attachments；
- `qq_private_send`：主动私聊任意 QQ，消息板落库、账号路由、三态；
- `qq_message_recall`：按 source/accountId 撤回，显式记录结果，不要求 confirm；
- `qq_media_upload`：base64 入受控资产库，返回 `assetRef`；
- `qq_audio_get`：提取 AUDIO 段为 `audioRef`；`asrConfigured=false`；
- `qq_conversation_ack(source)` 返回 compact `more`；`more=true` 时同轮继续 read；
- 短文本 digest 可达 `kind=digest_complete`，wake 内联完整 bounded summary；
- `qq_conversation_read(source)` 返回 compact messages + more，不再返回内部投影；
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
6. **入站/出站真实样本未跑**：card/file/forward/group-file/audio/mentionAll/recall/
   主动私聊/图片/表情/群文件上传都只有 adapter、工具和存储单测；当前 Bot jar 未替换，
   真实 LLBot 回归为 `NOT_RUN`。
7. **F-014 宿主 reload 未跑**：DSH 的 agent-scoped 探测修复已由单测覆盖；
   运行中的 DSH 仍持有旧模块，reload 后 `programMcp` 状态转变尚未实测。
8. **D-032 运行验证未跑**：`digest_complete`、compact read/ack 与 DSH compact
   profile 只有单测；真实 DSH reload 和新会话实测为 `NOT_RUN`。
9. **B 站真实联调未跑**：Cookie 登录、WBI、私信轮询、评论通知、私信/评论外发
   只有 fake HTTP 单测；真实 B 站风控、Cookie 过期、私信内容类型和评论审核状态
   仍为 `NOT_RUN`。真实写入需要独立测试账号和用户当次授权。
10. **B 站视频字幕未实测**：无登录态只做公开元数据/热评；登录态字幕链路和
    b23 短链联网展开仍为 `NOT_RUN`。
11. **ASR 未接**：语音只到 `audioRef`，`asrConfigured=false`；转写能力 `NOT_RUN`。
12. **Raw LLBot 兜底未审计进消息板**：D-031 允许 Agent 常规使用，但其外发不会自动
    进入 iibot 消息板；必须由 Agent 如实记录 messageId/结果。
13. **source-only direct ack 竞态**：未 read 时 ack 归档当前 active item；
    wake 后、ack 前到达的新消息可能被一并归档。这是既有 direct-ack 边界，D-032
    没有引入 through 字段；需要真实运行观察，必要时再裁定。

## 7. 下一步

1. **B 站 D-034 验收**：为 B 站 Adapter 准备独立测试账号 Cookie，验证登录、
   私信轮询、评论回复通知、统一 source 入库/Inbox wake、conversation_send
   三态和 UNKNOWN 不重发；真实写入前先取得用户授权。
2. **真实运行验收**：真实 QQ/DSH/OpenCode、外发、长跑均需用户当次授权；未跑写
   `NOT_RUN` / `BLOCKED`，不写 PASS。
3. **已推进的摩擦项**：F-001/F-002/F-003/F-014/F-015/F-016 已完成 code-level；
   下一步是替换测试 jar、reload 宿主并用真实样本验收。
4. **D-031 已实现 code-level**：3A mentionAll + qq_message_recall；3B qq_private_send；
   3C qq_media_upload + image/sticker + qq_group_file_upload；3D AUDIO + qq_audio_get。
   下一步是替换测试 jar 后做真实 LLBot 外部动作验收；ASR 仍未接。
5. **Raw LLBot 兜底**：按 D-031 允许常规使用；纪律和审计边界已写设计，不新增
   iibot scheduler。
6. **D-032 验证**：替换测试 jar、reload 两个 DSH bundle 后，用新会话验证
   `complete=true` 不走 read、普通消息走 compact read/ack、`more` 同轮续读；
   旧 649 轮会话不能冒充新验收。
7. **F-013 复验**：先按 MCP 发送路径确认 OUT 已落库；只有 Raw LLBot 路径无记录，
   不再把它当消息板缺陷。
8. **上游发布跟进**：ProjectMosire 补 v0.2.4 tag/Pages；发布前干净仓库门禁保持
   `PUBLICATION_GAP` / `BLOCKED`。
9. **后续裁定**：多 consumer、descriptor 完全移出 Agent MCP、自动 ack；
   当前均未实施。
