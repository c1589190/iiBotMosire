# iiBotMosire 当前状态

> **更新时间**：2026-10-28 19:35（Asia/Shanghai）
> **本文是唯一实时状态入口。** `docs/superpowers/status/*` 都只是某时点的历史快照。
> 当前事实与设计/维护裁定冲突时：设计/维护裁定优先；本文只记录“现在实际是什么”。

## 0. 最新更新：统一 QQ Agent 客户端重构规划（2026-10-28）

- **用户方向**：iibot 不再提供程序化文游服务，只做面向各类 Agent 软件的统一 QQ 消息客户端；
  文游模块退出默认产品，消息板服务端维护内部游标/幂等/Inbox 同步，Agent 不维护额外标记。
- **执行分支**：`refactor/unified-qq-agent-client`；按责任区分批实现、测试、提交、推送，
  全部确认后再合并（M-005）。
- **已冻结选择**：
  - AgentLib 继续固定 `agentlib-mosire:0.2.4`，不降级；
  - `iibot-connect-agent` 整合进正式模块后删除；
  - 读取只预览，Agent 处理完成后调用 `qq_conversation_ack(threadId)`；ack 不接受内部 seq/revision。
- **规划文档**：
  - [统一 QQ Agent 客户端与消息板目标架构](../superpowers/specs/2026-10-28-unified-qq-agent-client-architecture.md)
  - [日志分级与诊断](../superpowers/specs/2026-10-28-logging-levels-and-diagnostics.md)
  - [配置文件系统](../superpowers/specs/2026-10-28-configuration-system.md)
  - [P0–P7 改造计划](../superpowers/plans/2026-10-28-unified-qq-agent-client-refactor-plan.md)
- **依赖事实**：iibot 与 inbox 当前均用并继续固定 `agentlib-mosire:0.2.4`；源码主线最新
  为 0.2.4，但公开 tag/Pages 最新仍为 0.2.3、0.2.4 公开 POM 为 404。该落差作为
  `PUBLICATION_GAP` 记录；干净仓库复现前不得宣称可公开构建。
- **本次动作**：新增/更新规划文档，作为 P0 独立 checkpoint 提交；未改生产代码、
  未删除模块、未跑 Maven、未启动 QQ/Inbox/MCP、未执行外发。
- **状态**：`P0_IN_PROGRESS`；版本/读取语义/模块整合策略已确认，正在建立分支 checkpoint
  与提交规划文档；尚未进入 P1 删除。

## 0.1 历史更新：D-024 Inbox 改为 QQ 式未读列表（2026-10-28 00:05）

- **用户原话（逐字）**：
  > 「接下来要实现的就是我说的新inbox了」
- **设计裁定**：D-024 已加入需求原稿 §5.13 与设计裁定索引。Inbox 是每个来源的
  Agent 客户端未读列表，不是正文库；完整历史仍由来源查看工具读取。
- **数据模型**：
  - `InboxDigest` 新增 `source`（threadId/chatType/targetId/displayName）、
    `trigger`（messageId/localSeq/sender/replyTo/summary/wakeReason）、
    `unreadCount`、`hasMoreUnread`；
  - `InboxDigestItem` 可回读 content digest，供同一 item 更新时复用；
  - `InboxDigest.messages` 只保留最后 n 条未读预览，n 复用现有
    `summary.maxMessagesPerDigest` / `weighting.maxMessagesPerDigest` 有效上限；
    `fromSeq/toSeq` 表示完整未读范围，不再等于预览边界。
- **生命周期**：
  - 同一 thread 的 active item 不再冻结；新消息原地更新同一 item，revision 递增，
    `unreadCount` 增长、预览滚动到最后 n 条；
  - `MessageBoardStore.countInboundAfter` 精确统计未读入站数（SQLite 用 COUNT）；
  - `bot_thread_ack` 的 `snapshotToSeq` 部分消费后保留同一 item；完整 ack 归档；
    之后新消息从 cursor 之后创建新 item；
  - 来源查看工具未加任何 item 范围限制；完整/分页/递归历史照常可用。
- **验证**：`tools/gate.sh full` **PASS，730 tests / 0 failures / 0 errors /
  0 skipped**（connect 46、connect-agent 21、bot 67、taskflow 250、app 346）；
  `tools/gate.sh docs` PASS（files=517 links=208）。新增回归覆盖
  source/trigger/unreadCount、last-n 预览、同 item 追加、partial ack、legacy content、
  SQLite 未读计数和 snapshot cursor 竞态。
- **运行态**：源码与 target jar 已更新（target sha256
  `e513237f793d0e22792dad441acde2d085c68e938ac87e1e0da5e9115c729ab2`）；
  当前常驻进程 PID `46457` 仍运行旧 jar（sha256
  `a1b42e7ec985e14638e2d69eb671c65afed34277f85809793fb225d874336e0a`），
  未替换/未重启，真实 QQ/OpenCode wake-ack 闭环仍为 `NOT_RUN`。不得覆盖运行中 jar。

## 0.2 历史更新：D-023 删除程序侧文本 Guard（2026-10-27 23:10）

- **用户原话（逐字）**：
  > 「谁让你在程序上自己替换了？？？？？？？？？？？？？？？？？？？？？？？？？？这玩意他妈是Skill约束的，不是程序约束的，整个删了！！！！！」
- **设计裁定**：D-023 已加入设计裁定索引与需求原稿 §5.12。正文文风、后台词、
  Markdown、路径和可见性属于 **Skill / AGENTS.md**；程序不得清洗、删行、静默替换
  或生成 fallback。
- **源码删除**：
  - 删除 `iibot-taskflow` 的 `PlayerFacingTextGuard` 与其测试；
  - 删除 `iibot-bot` 的 `BotReplyTextNormalizer`；
  - 删除 app 侧全部接线：BotRuntime、OutboxSender、PlayerOnboardingService、
    ExternalPostService、PublicationOrchestrator、LlmActionBatchExecutor、
    SecretarySession、GmPostComposeTool；
  - `BotReplyService` 直接使用原文落库/发送，只保留非空、长度、目标、权限、
    幂等和发送三态等结构校验。
- **测试语义更新**：OutboxSender、PublicationOrchestrator、BotReplyService 的测试改为
  断言原文被保留，不再断言行级清洗或 fallback。
- **验证**：`tools/gate.sh full` **PASS，723 tests / 0 failures / 0 errors /
  0 skipped**（connect 46、connect-agent 21、bot 61、taskflow 250、app 345）；
  `tools/gate.sh docs` PASS（files=517 links=206）。
- **运行态**：源码已删除，但正在运行的 Bot 仍是旧 jar；不得覆盖运行中 jar。
  新行为需重新构建 jar、停止旧进程并启动新 jar 后才生效。

## 0.3 历史更新：Bot 运维日志补全与 Inbox 可见性纠正（2026-10-27 21:43）

- **用户需求原话（逐字）**：
  > 「注意到iibot程序本体的log运维记录并不全面，能不能完善一下？」
- **设计正本**：
  [`docs/superpowers/specs/2026-10-27-bot-operational-logging.md`](../superpowers/specs/2026-10-27-bot-operational-logging.md)。
- **用户纠正（逐字）**：
  > 「首先，inbox确实是和唤醒绑在一起的，其次，我什么时候说这些低权重信息不进inbox了？」
  当前口径已写入
  [`docs/superpowers/specs/2026-10-27-onebot11-rich-messaging-history-media-and-requests.md`](../superpowers/specs/2026-10-27-onebot11-rich-messaging-history-media-and-requests.md)
  §12.6：Inbox 与唤醒绑定是已知语义；权重只决定 priority/wakeReason/防抖延迟，
  **不得过滤低权重消息**。
- **代码纠正**：`InboxDigestService` 不再按 `actionable` 过滤 digest 与 context；
  普通群聊会生成 `wakeReason=BELOW_THRESHOLD`、`priority=0` 的 item，@ 触发时
  同一 digest 会包含此前普通消息。`MessageWeightDecision.actionable` 仍保留用于
  防抖/冷启动策略，不再作为 Inbox 可见性开关。
- **日志落盘**：`log4j2.xml` 新增 `iibot.log` 与独立 `iibot-ops.log` 两个滚动文件；
  默认目录 `logs/`，可用 `-Diibot.log.dir=/path` 或 `IIBOT_LOG_DIR=/path` 覆盖；
  按天 + 20 MB 滚动、gzip、最多 30 个归档；文件权限实测为 `0600`。
- **运维事件覆盖率**：新增 `iibot.ops` 门面并接入进程启停、配置模板、BotRuntime 生命周期、
  Adapter 连接/重连/发送、入站 accepted/duplicate、Inbox embedded/客户端调用、
  digest 发布/冻结/ack/审计失败、MCP HTTP 鉴权与请求、逐 MCP 工具调用（工具名/结果/
  错误码/耗时/参数 key）、回复、好友申请、用户备注、来源权重、出站三态。
- **脱敏边界**：日志不记录聊天/备注/回复正文、token、Authorization、完整 OneBot JSON；
  MCP 参数只记录 key 名，不记录 value。`OpsLog` 对日志实现异常静默隔离，不改变业务结果。
- **自动化测试**：日志测试 4 条 + Inbox 可见性回归 2 条；`tools/gate.sh full`
  **PASS，731 tests / 0 failures / 0 errors / 0 skipped**（connect 46、connect-agent 21、
  bot 61、taskflow 256、app 347），日志 `/tmp/iibot-inbox-allmsg-full.log`；
  `tools/gate.sh docs` **PASS**（files=517 links=204）。
- **落盘 smoke**：最终 target jar 以不存在配置启动，实际生成 `600` 权限的
  `iibot.log`/`iibot-ops.log`，含 `process_start` 与 `bot_process_failed`；property 与
  `IIBOT_LOG_DIR` 两种目录覆盖均实测。
- **本地部署已完成**：旧 bot 进程已停止，`8765` 与 Bot 其他回环端口均已关闭；
  `bot/iibot-app-0.1.0-SNAPSHOT.jar` 已替换为新 jar，sha256
  `a1b42e7ec985e14638e2d69eb671c65afed34277f85809793fb225d874336e0a`；
  旧 jar 备份为
  `bot/iibot-app-0.1.0-SNAPSHOT.jar.backup-20261027-215934-pre-ops-log-and-inbox-visibility`
  （sha256 `9ac7686c...`）。
- **GitHub Release 已更新**：`v0.1.0-bot-first.1` 的
  `iibot-app-0.1.0-bot-first.1.jar` 与 `.sha256` 已 clobber 上传；
  远端下载后 `sha256sum -c` 校验 OK。
- **当前构建产物**：`iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar` sha256
  `a1b42e7ec985e14638e2d69eb671c65afed34277f85809793fb225d874336e0a`。
- **未完成/`NOT_RUN`**：当前没有 Bot 常驻进程，未按用户指令重新启动；真实
  QQ → Inbox → DSH wake → Bot MCP 闭环、真实运行期日志轮转与长跑容量仍为 `NOT_RUN`。

## 0.4 历史更新（2026-10-27 20:43）

- **工作区已迁移**：本仓现在位于 `/home/cna/DevMosire-iiBot/iiBotMosire`；
  `InboxMosire` 与 `InboxMosire-DSH` 是该根下的同级独立仓库。跨仓关系见
  `/home/cna/DevMosire-iiBot/AGENTS.md`。
- **DSH 插件断链已修复**：`~/.dsh/profiles/web` 的
  `@mosire/dsh-inbox-mosire` 已从旧 `link:/home/cna/InboxMosire-DSH`
  改到 `/home/cna/DevMosire-iiBot/InboxMosire-DSH`。当前 DSH 进程是在修复前
  启动的，因此 adapter 工具尚未在本进程加载；需重载/重启 DSH 后再绑定。
- **Bot 迁移验证已完成并停止**：验证时 Bot MCP 37 tools 监听、OneBot WS 上游
  连接建立、embedded Inbox 启动、DSH `custom-iibot` health 通过；随后按用户要求
  停止，当前无常驻 Bot，`8765` 已关闭。验证用 descriptor 已随进程停止而失效。
- **运行产物交接**：编译 jar 已复制到
  `/home/cna/DevMosire-iiBot/bot/iibot-app-0.1.0-SNAPSHOT.jar`
  （sha256 `9ac7686c010140ac417a0b06ce210979d5437a0fd54d6b3f0c8d6a759130418f`）。
  Bot 源码仍以本仓为准；`bot/` 是另一个 DSH 工作区的运行目录，不是第四个项目。
- **MCP 边界（用户当次纠正）**：Bot MCP 专门给 Bot 使用，本协调/开发工作区
  已停用 `custom-iibot`；不得再次启用、激活或调用其 `bot_*` 业务工具。
- **迁移后验证**：`InboxMosire-DSH` `npm test` 为 124 tests / 121 pass /
  3 skipped / 0 fail；本仓 `tools/gate.sh docs` PASS。迁移后未重跑全量 Maven
  gate，旧 725 tests 结果不能冒充迁移后结果。
- **未完成/`NOT_RUN`**：真实 QQ → Inbox → DSH wake → Bot MCP 的完整闭环尚未
  验收。下一步在新 Bot 工作区启动 `bot/` 中的 jar、重新生成 descriptor，
  重载 DSH 后以 `wakeMode=view-ack` 绑定，并用 `inbox_adapter_status` 核对
  `programMcp=available` 与 SSE/occupancy。

## 0.5 历史更新（2026-10-27 19:22）

- **Bot 已成为默认启动模式**：`java -jar iibot-app-0.1.0-SNAPSHOT.jar`
  不带 `--mode` 直接走 bot-first；`--mode bot` 仅保留兼容，legacy 必须显式
  `--mode legacy`。
- **多配置源已落地**：
  - 根目录新增 `config/`，默认 bot 配置目录为 `config/bot/`；
  - `--config` 可重复，值可以是文件或目录；
  - 目录读取直接子级 `*.json` 并按文件名升序合并；
  - 对象递归合并，数组/标量后文件覆盖；多源必须同一目录，相对路径以该目录为基准；
  - 新增 `config/README.md`、`config/bot/README.md`、`config/legacy/README.md`
    和 `config/examples/bot/` 四段示例；
  - 配置仍只保存 secret 的环境变量名。
- **首次启动自动生成配置模板已实现**：
  - 默认 `config/bot/` 完全没有任何 `*.json` 时，`java -jar` 生成
    `00-base.json` / `10-napcat.json` / `20-control-inbox.json` /
    `30-policy.json`，打印编辑提示，以 exit 2 退出；
  - 生成阶段不启动 QQ/Inbox/MCP；
  - 已有任意 JSON 时不覆盖；
  - 显式传 `--config` 时不触发模板生成；
  - 用户编辑配置并设置环境变量后，再次执行同一 `java -jar` 即可进入正常启动；
  - 已用最终 jar 在空临时目录实测 first-run `rc=2`、四个文件权限 0600；
    第二次执行因未设置 `IIBOT_CONTROL_TOKEN` 按预期 `rc=1`。
- **新增上游需求提示词**：
  [`docs/upstream/inboxmosire-embedded-parameterization-request.md`](../upstream/inboxmosire-embedded-parameterization-request.md)
  已写好并已被 InboxMosire 实现（`6013cde` / `f93e7e2`，37 tests，
  artifact 已 `clean install`）。
- **Inbox embedded 参数化已接入本仓**：
  - `BotConfig.Inbox` 新增 `apiHost/apiPort/mcpHost/mcpPort/mcpPath/
    heartbeatMillis/claimSweepIntervalMillis`；
  - 默认仍为 `127.0.0.1:0` API、`127.0.0.1:0` MCP、`/mcp`、15 秒 heartbeat、
    5 秒 claim sweeper；
  - 新增 `BotInboxRuntimeOptions`，`BotRuntimeOptions` 改为嵌套持有它；
  - `BotInboxRuntime.start(options)` 全量传给 `EmbeddedInboxServer`，
    `BotRuntime` 不再直接拼散参数；
  - host 只允许 loopback，固定端口冲突 fail-closed；
  - 所有 bot 配置样例、`simulation2/scripts/01-prepare.sh`、simulation2 示例与
    当前生成的 `simulation2/config/bot.sim.local.json` 已同步新字段；
  - `BotRuntimeE2ETest` 已用 `/custom/mcp`、2500ms heartbeat、250ms sweeper
    跑通真实 embedded 启动与 MCP 工具链。
- **最新门禁**：
  - `tools/gate.sh docs` **PASS**（modules=5 files=516 links=203）；
  - `tools/gate.sh full` **PASS，725 tests / 0 failures / 0 errors**
    （connect 46、connect-agent 21、bot 55、taskflow 256、app 347）；
    日志 `.work/bootstrap-full.log`；
  - 新 jar sha256 `9ac7686c010140ac417a0b06ce210979d5437a0fd54d6b3f0c8d6a759130418f`。
- **运行态核对**：`simulation2/scripts/04-status.sh` 当前返回 `BOT_PID=stale`、
  `MCP_PORT=closed`；没有常驻 bot 进程，不能沿用旧 PID 192530。
- **CLI smoke**：
  - `--help` 显示默认 bot、无 `--mode`；
  - 无配置时 fail-closed：读取 `/home/cna/iiBotMosire/config/bot`，目录无
    `*.json` 时明确报错；
  - `--config config/examples/bot` 能完成多文件合并，随后因未设置
    `IIBOT_CONTROL_TOKEN` 按预期 fail-closed，未发起 QQ 外发。
- **下一步**：在干净 runtime 启动新 jar，先做一次自定义 `mcpPath` /
  heartbeat / 固定或随机端口的启动 smoke；通过后再按用户授权部署
  `simulation2/dist` 并重新绑定 DSH。**未跑前不宣称真实 Inbox/DSH 闭环通过。**

## 0.6 历史更新（2026-10-27 03:02）

- **Inbox/DSH 对接已切换到 view-ack**：InboxMosire `e43f099` 提供
  `ITEM_STATUS_CONFLICT` / `ITEM_REVISION_CONFLICT` 及 `details`；InboxMosire-DSH
  `f770160` 支持 `wakeMode=view-ack`、程序 MCP 手动挂载和 programMcp 诊断。
- **iiBot bot digest 修复已完成源码**：
  - `bot_thread_ack` 成功后保存 `cursorOnly(threadId,lastSeq)`，不再把 thread 游标清回 0；
  - ack / flush 使用 Inbox item content 的 `toSeq` 校正本地滞后游标；
  - claim/非 available 状态 fail-closed，不再为同一 thread 新建第二个 available digest；
  - `bot_thread_ack` 返回稳定错误码和脱敏 details；
  - threadId 与 itemId 不匹配时拒绝归档，避免清错 thread 游标。
- **OneBot 11 富消息/历史/媒体/好友申请已落地源码**：
  - rich `OutboundSegment`（Text/Mention/Reply）、`bot_reply` 的 reply/at；
  - 入站 `MessageSegment` 与 SQLite segments、`bot_thread_history`、
    `bot_message_thread`、`bot_forward_read`；
  - `bot_media_get` + `ToolAssetResolver`（MCP ImageContent）；
  - `bot_qq_recent` / `bot_qq_history`（客户端侧，不等于 Inbox）；
  - `inbox_view.content.context`（最近 6 条 IN/OUT）；
  - `bot_inbox_history` 审计；
  - 入站好友申请 `bot_friend_request_list/decide`；主动加好友不实现。
- **view-ack 竞态修复已部署**：
  - active item 发布后冻结，新消息在 ack 后形成下一版；
  - `bot_thread_ack` 增加 `snapshotToSeq`，按 `inbox_view.content.toSeq` 归档；
  - ack 后立即 `flushThread` 发布冻结期间消息；
  - 启动 `flushAll` 修复旧 state/item 的 seq 空洞；
  - 旧 claim 由 lease 过期清理，当前 `claimed=0`，不新增 claim 绕过。
- **来源权重路由已实现并部署**：
  - `SourceWeightPolicy` / `MessageWeightDecision` / `source_weight_policy`、
    `chat_message_weight` 已落地；
  - 统一分：`sourceWeight + max(signalWeight) + sum(keywordWeight)`；
  - `priority` 直接映射到 Inbox 排序；群聊默认 `sourceWeight=0`、阈值100，
    私聊默认 `sourceWeight=300`；
  - 首次来源生成 `FIRST_CONTACT` item；已有 7 个 thread 已迁移为已引导；
  - `bot_source_weight_get/list/set` 可调来源权重/阈值/信号/关键词；
  - 新 Inbox lib 的 `inbox_rest` 已随 `InboxClientMcpTools` 自动出现。
- **最新门禁**：
  - `tools/gate.sh docs` **PASS**（modules=5 files=512 links=201）；
  - `tools/gate.sh full` **PASS，705 tests / 0 failures / 0 errors**
    （connect 46、connect-agent 21、bot 55、taskflow 256、app 327）；
    日志 `.work/weight-full2.log`。
- **正文一致性/旧图片修复（历史；D-023 已删除程序守卫）**：
  - 当时 `BotReplyService` 在落库前调用 `BotReplyTextNormalizer`，app 注入
    `PlayerFacingTextGuard`；该行为现已被 D-023 删除；
  - 当前程序不再清洗、删行、静默替换或 fallback，正文约束只在 Skill / `AGENTS.md`；
  - OUT `bot_reply` 的 reply/mention/text 段写入 `chat_message_segment`；
  - `MediaCapability.imageSegmentsForMessage` + `get_msg` fallback，旧图片可按
    messageId 读取；实测旧消息 `1472304806` 返回
    `source=onebot_get_msg`、`image/jpeg`、`size=21561`；
  - 一次性修复历史 OUT 正文 10 条（8 条 guard fallback、2 条删行），修复后
    dry-run 为 0 changes。
- **运行态**：新 jar 已部署，PID `192530`，Bot MCP 37 tools；
  `available=2`、`claimed=0`；`source_weight_policy` 已迁移 7 个现有 thread。
  实测群 sourceWeight 0→500 后 active item priority 0→500，恢复 0 后回到 0。
  尚未重新 bind DSH，未做真实回复/好友审批外发。
- **未完成**：语音/文件媒体读取仍是非目标（图片已实现）；2 秒 debounce 与分段耗时指标
  尚未调整；真实 DSH view-ack 全链路需要重新 bind 后验证。
- **默认入口（历史）**：当时仍为 legacy；现已被 2026-10-27 18:56 的默认 bot 更新覆盖。
- 下一步：读取当前 descriptor，以 `wakeMode=view-ack` rebind DSH → 用
  `inbox_view.content.toSeq` 作为 `snapshotToSeq` 做一轮真实 smoke；
  真实回复/好友审批按 M-004 等授权。

## 0.7 历史更新（2026-10-25 23:31）

- **模拟区文档系统已归档并清理项目级残留副本**：
  `simulation/docs/` 成为模拟区文档入口，`simulation/docs/world/` 收纳公开/秘密世界设定，
  `simulation/.agents/skills/` 收纳可复用推演/秘书/工程操作 Skill；
  原项目级 `.agents/skills/iibot-live-simulation-gm/` 旧副本已删除，
  项目级 active Skill 当前为 0，模拟区 Skill 由
  [`../../simulation/docs/agents/SKILLS.md`](../../simulation/docs/agents/SKILLS.md) 登记。
- **秘书单响应工具调用上限已从 8 改为 128**：
  `AgentRunner.DEFAULT_MAX_TOOL_CALLS_PER_RESPONSE=128`；
  `SecretarySession` 与 `GmAideAgent` 默认均使用 128。
  新增 10 个工具调用单响应回归测试，验证 10 个全部执行。
  `tools/gate.sh focused iibot-taskflow test` PASS（taskflow 256）；
  `tools/gate.sh full` PASS（全仓 **627 tests**）。
  target/dist jar sha256 `55212c2d...`，旧 dist jar 备份
  `simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar.backup-20261025-224319-pre-tool-call-128`。
  证据：[`../superpowers/status/2026-10-25-secretary-tool-call-limit-128.md`](../superpowers/status/2026-10-25-secretary-tool-call-limit-128.md)。
- **模拟区 Inbox Mode A 已部署并通过启动 smoke**：
  `simulation/dist` jar 已替换为 `55212c2d...`；本次替换前 jar 备份
  `simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar.backup-20261025-224319-pre-tool-call-128`，
  更早 Mode A 部署前备份
  `simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar.backup-20261025-221343-pre-inbox`；
  模拟 local config 已加入 Inbox 块（备份
  `simulation/config/iibot.sim.local.json.backup-20261025-221343-pre-inbox`）。
  启动 smoke 实测 `tools/list=88`、无 `tf_ticket_board`；`inbox_status` 已从权威工单
  产生 `available=1`；descriptor 生成成功且 token 文件 0600。smoke 进程已退出，
  当前不保持常驻；用户用 `simulation/scripts/02-start.sh` 启动测试。
- 启动脚本 `02-start.sh` 现在会自动调用新增的 `08-inbox-descriptor.sh`，
  写出 `simulation/runtime/inbox-binding-descriptor.json`；给隔壁会话的引导词为
  `simulation/dsh/INBOX_NEIGHBOR_HANDOFF_PROMPT.md`。
- **InboxMosire Mode A 通知工作流已实现代码与自动化测试**：
  `iibot-app` 内嵌 `EmbeddedInboxServer`，签发 program MCP token 与 DSH wake token
  到 `0600` 文件，持久化不含 raw token 的 `BindingDescriptor`；
  控制 MCP 增加 11 个 `inbox_*` + `tf_inbox_binding_descriptor`，启用时隐藏
  `tf_ticket_board` 长轮询入口但保留 `tf_ticket_list/get`；
  宿主通知桥按 revision 去重推送 ticket 与 publication-review 引用。
- `tools/gate.sh focused iibot-app test` **PASS**：app 312 tests / 0 failures /
  0 errors / 0 skipped；结论为 `FOCUSED TEST PASSED (NOT a full gate)`。
- 此前 Inbox Mode A full gate：全仓 **626 tests / 0 failures / 0 errors / 0 skipped**
  （connect 38、connect-agent 21、taskflow 255、app 312）；当时 shaded jar sha256
  `a1b1711a998806d76cf2a39862e84f0b3dfde302bd1be97498d49b3c1f24adfb`。
- 当前 docs gate **PASS**：modules=4 files=491 links=178，active skills=1。
- 隔离 smoke（Fake LLM/Fake QQ adapter，非真实外发）：新工单 + 待审推文产生
  `revision=2, available=2`；当前项目 MCP `tools/list=83` 且无 `tf_ticket_board`（隔离 Fake 装配；真实模拟装配 `NOT_RUN`）；
  经当前项目 MCP 手工完成 push→list→claim→complete（push revision 3，
  complete 后 available 回到 2）；DSH adapter v2 bind 后 occupancy=true，
  unbind 后 occupied=false；两个 token 文件 0600、descriptor 无 raw token；
  进程停止后 control/embedded 端口关闭，显式 inbox dataDir 与 `inbox.db` 保留。
  证据：[`../superpowers/status/2026-10-25-inbox-mode-a-notification-workflow.md`](../superpowers/status/2026-10-25-inbox-mode-a-notification-workflow.md)。
- **当前运行态（2026-10-25 22:20）**：收尾核对未发现常驻模拟 app 进程。
  需要测试时运行 `simulation/scripts/02-start.sh`；该脚本会自动生成当前端口的
  Inbox descriptor 并打印隔壁会话 handoff 提示。停止用
  `simulation/scripts/03-stop.sh`。启动 smoke 未执行真实 QQ 外发。
- **此前（01:45 快照）**：D-017 平台级 QQ 能力与受控自主入团已实现代码与自动化测试：
  connectlib `QqPlatformCapability`、NapCat 目录/历史/消息定位/临时私聊发送；
  MCP `tf_qq_chat_list`、`tf_qq_history_read`（任意 Bot 可访问目标）、
  `tf_qq_group_members`、`tf_qq_message_get`、`tf_post_compose`、
  `tf_player_onboard_from_group`；显式开启后 `tf_qq_send_private/group`。
- D-018 历史：未启用 Inbox 时装配曾有 `tf_ticket_board {"wait":true,"waitSeconds":300}`
  长轮询；Inbox Mode A 下该 Agent 入口不再暴露（代码仍保留为兼容/诊断工具类）。
- `tools/gate.sh full` **PASS**：全仓 **614 tests / 0 failures / 0 errors / 0 skipped**
  （connect 36、connect-agent 21、taskflow 252、app 305）；源码 target jar 已重建，
  sha256 `256944e469859f5bf84cb35a7850d5377a277cbbaef4c024a98c6effeeba8d52`。
- 证据：[`../superpowers/status/2026-10-24-mcp-platform-capability-and-autonomous-onboarding.md`](../superpowers/status/2026-10-24-mcp-platform-capability-and-autonomous-onboarding.md)。
- **smoke 后修复（源码，未部署）**：
  - `tf_post_compose` 正文守卫不再静默 fallback；返回 `TEXT_GUARD_REJECTED` +
    `guardViolations` + `guardSanitizedBody`，且不创建草稿；
  - `tf_qq_message_get` 在 NapCat `MessageUnique` live cache 未命中时，可带
    `chatType/targetId/maxScan` 回扫历史；明确返回
    `MESSAGE_NOT_IN_LIVE_CACHE` / `MESSAGE_NOT_FOUND_IN_SCAN`；
  - Connector 自定义连接的 tools_list/health_check 文档修正为
    `connectorId=__custom__`。
- 追加 D-019 外发节奏：所有逻辑外发消息共享最小间隔，默认 150ms，
  生产范围 100..200ms；ACK 与正式回复也串行拉开。
- D-020 当前修订：玩家秘书与 GM 推演秘书默认 **128 轮 / 5 分钟 / 单响应 128 工具**
  （2026-10-25 由 8 修订）；
  中央裁定 Agent 保持 8 轮/120 秒。预算耗尽回执会报告草案/查询/世界状态和是否需重发。
- 此前 D-020 / LLBot 临时会话修复后 full gate：**619 tests / 0 failures / 0 errors / 0 skipped**
  （connect 38、connect-agent 21、taskflow 255、app 305）；
  当时 target jar `f9f2e44885fbb65c9ab3a53fc739eaf137b0dfed7e8c8b2a98e984228bf69960`
  未替换 simulation/dist（2026-10-25 01:45 历史事实）。
- 01:45 时未启用 Inbox 的模拟 MCP 工具面 **77**；
  `control.platform.allowArbitrarySend=true` 后 **79**（历史事实）。
- **QQ 后端迁移**：LLOneBot 8.3.2 / OneBot v11，WS 3001、HTTP 3000；
  HTTP Bearer/query 与 WS 握手均验证；`get_recent_contact` 缺失时自动回退群目录；
  `tf_qq_chat_list` / `tf_qq_history_read` / `tf_qq_message_get` 已通过 LLBot 实测。
- **simulation/dist jar**：当前为
  `55212c2ddb272d42000bec28a71eaac722769fb3e55021da72b729a08a08f010`；
  本次替换前备份：
  `simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar.backup-20261025-224319-pre-tool-call-128`；
  Mode A 部署前备份 `...backup-20261025-221343-pre-inbox`；01:45 历史备份
  `...backup-20261025-010828`。runtime 备份
  `simulation/runtime.backup-20261025-010828-pre-llbot-compat`。
- **01:45 时运行态**：当时隔壁会话启动过 app（PID 30737）、回环代理与 watcher；
  该进程在 2026-10-25 22:10 收工核对时已不存在。LLBot 3000/3001 仍监听。
- **2026-10-25 群临时会话事故**：LLBot 不支持主动临时会话；
  `send_private_msg {user_id, group_id}` 在无 `temp_chat_info` 时降级为普通 C2C，
  返回 `ONEBOT_RETCODE_200` / code 16“请先添加对方为好友”；
  当前开局卡帖已发布但 deliveryState=FAILED，玩家未收到，未自动重试。
  适配器已把该错误映射为 `LLBOT_TEMP_CHAT_REQUIRES_INBOUND`；
  恢复需目标先主动私聊 Bot 一次，再显式 `tf_post_retry_delivery`。
  证据与恢复步骤：`simulation/dsh/LLBOT_MIGRATION.md` §7。
- 迁移细节：[`../../simulation/dsh/LLBOT_MIGRATION.md`](../../simulation/dsh/LLBOT_MIGRATION.md)。
- 下一步：用真实玩家消息验证 D-020 的“明确行动直接起草”和“资料缺口主动查询”；
  128 轮预算已通过 `javap` 在部署 jar 中核验。真实外发仍需用户明确目标与授权。

## 1. 当前目标

> **2026-10-28 重定位**：以下 P0–P8 文游目标为历史，不再决定默认产品；
> 当前目标以 D-025..D-028 和
> [统一 QQ Agent 客户端改造计划](../superpowers/plans/2026-10-28-unified-qq-agent-client-refactor-plan.md)
> 为准。实施前不要把以下文游内容当作待办。

- **玩家体验复盘与推演文档系统（2026-10-24）**：已登记首轮真实推演中的工程/状态/玩家体验/自动化问题，并形成 D-015 提议设计；未改生产代码、未部署 jar、未跑构建/playtest。事实底稿：[`../superpowers/reports/2026-10-24-first-live-run-player-experience-audit.md`](../superpowers/reports/2026-10-24-first-live-run-player-experience-audit.md)；提议：[`../superpowers/specs/2026-10-24-player-experience-interaction-document-system.md`](../superpowers/specs/2026-10-24-player-experience-interaction-document-system.md)。
- **玩家体验运行时本轮已实现**：D-016 保持最小 system prompt，详细规则写入秘书 `AGENTS.md` + 共享 Skill；玩家文本守卫、短确认码、ACTION_DRAFT 确认门、结构化裁定/NO_OP、opening 门禁、只读 watchdog、体验文档包均已落地；`tools/gate.sh full` **PASS**，全仓 **568 tests / 0 failures / 0 errors / 0 skipped**，shaded jar 成功，5 项变异验证通过。**未部署、未真实 playtest、未 commit。** 证据：[`../superpowers/status/2026-10-24-player-experience-runtime-implementation.md`](../superpowers/status/2026-10-24-player-experience-runtime-implementation.md)；架构：[`../superpowers/specs/2026-10-24-player-experience-runtime-architecture.md`](../superpowers/specs/2026-10-24-player-experience-runtime-architecture.md)；计划：[`../superpowers/plans/2026-10-24-player-experience-runtime-plan.md`](../superpowers/plans/2026-10-24-player-experience-runtime-plan.md)。
- **P0 已完成**：v3 行动池 + AgentLib 裁定包 + 48 MCP 工具已通过 full gate，提交为 `0695675`；应用保持停止。
- **P1 已完成**：`events.db` 完整内容基础（events + event_contents、`EventContentStore`/`EventAppendPort`、schema v1、迁移、hash/可见性/脱敏/事务）已通过 full gate 与阶段评审，提交为 `a52626f`。
- **P2 已完成**：Post/Complaint/ActionPool 已改 events.db 事件源；Taskflow 完整快照事件已接入；旧 posts/complaints/taskflow-state 已有两阶段迁移与 reconciled 对账；阶段评审 PASS，full gate 413 tests PASS，提交 `0fbceaa`。DecisionPackage/Batch 因 AgentLib 缺 restore/事务边界推迟 P5。
- **P3 已完成**：动态控制初始化、公开群、玩家/推演注册、秘书绑定、世界装载、动态 ACK/投递路由与 GM 私有文档已通过 full gate 和阶段评审，提交 `615595d`；完整控制面 55 工具。
- **P4 已完成**：统一 Ticket/消息板、秘书 submit/list/read、GM 8 个 Ticket 工具与 events.db 快照已通过 full gate 和阶段评审，提交 `5fb5d8c`；完整 MCP 60 工具。
- **P5 已完成**：ACTION_DRAFT ticket → ActionPool、DecisionPackage/Batch 命令事件与启动恢复、canonical action owner、ActionPool 投影 reconcile 已通过 full gate 和五轮对抗评审，提交 `06d9e1a`；完整 MCP 61 工具。
- **P6 已完成**：发布编排、Public/Private 推文草稿、GM 审核发布、records Markdown 投影、记录查询工具已通过 full gate 和多轮对抗评审，提交 `d3a9c91`；完整 MCP 65 工具。
- **P7 已由用户裁定跳过**（2026-10-24），不做全领域 events.db 回放/重建。
- **P8-A 已完成**：允许 `players=[]` 启动；新增 GM 独立 PUBLIC 开场推文 `tf_publication_opening`；`records/openings` 投影；完整 MCP 66 工具。full gate **526 tests PASS**，提交 `1480c55`。独立评审在用户要求提交时中止，评审结论未完成。
- **下一步 P8 真实模拟**：真实 DSH/Hermes MCP、真实 LLM/NapCat/QQ、零玩家公共开场→审核发布→动态注册玩家→多批次/发布/记录链路联调；按 M-004 需要用户协同与真实外部确认。
- **理想工作流仍按 P0–P8 计划推进**：`events.db` 是目标 canonical 领域事实源；P1 只完成内容基础，领域事件全量接入与回放尚未完成，Markdown 仍将是投影。
- **v2 功能切片已落地**：即时 ACK、每玩家秘书工作区、GM 工作区/秘书对话、推文草稿→GM 审核→GM 发布、多模型路由、只读 GM 推演秘书。
- **v1 真实单人 E2E 已完成**：玩家私聊 → 真实 LLM 秘书草稿 → 玩家 `/确认` → MCP 冻结 → 中央推演 Agent（真实 LLM）→ COMMITTED → MCP 发布 → 公共群公开叙事 + 玩家私聊 outcome 均已真实出站。
- 当前运行服务恢复状态为第 1 回合 `PUBLISHED`，`worldVersion=2`，`eventSeq=14`；v2 真实 QQ ACK/工作区/GM aide/私人推文发布已验证。
- 仍需用户协同：第二轮真实行动/决策、公共推文真实发布、多玩家与 DSH 外部会话验证。
- **已完成首个可运行纵向切片**：外部接入 Lib → Agent 工具扩展层 → 交互-推演任务流 → 组合根 → 认证 MCP 控制面。
- 本轮已实现：
  - `iibot-connect`：OneBot 11/NapCat 事件 WS + HTTP Action、鉴权、重连、心跳、去重、限速、分段、三态回执；
  - `iibot-connect-agent`：`qq_*` AgentTool、角色/资源权限、统一经 `ToolCallAuthorizer`；
  - `iibot-taskflow`：秘书/GM brief、自然语言决策草案、私聊硬确认、运行期设定提案/审核、回合状态机、中央推演、事件审计、18 个 taskflow 控制工具（app 另加 `tf_run_adjudication_agent`，MCP 共 19 个）；
  - `iibot-app`：严格配置、世界事实装载、Workspace/Docs、SQLite 事件审计、outbox、逐用户 mailbox、自动冻结、Bearer MCP 传输、可运行 Main；
  - Maven Wrapper 3.9.16、可执行 shaded jar。
- **下一步进入第 2 回合/多玩家/DSH host 真实验证**；当前第 1 回合已真实完成。

## 2. 源码基线

| 项 | 当前事实 |
|---|---|
| 仓库 | `/home/cna/DevMosire-iiBot/iiBotMosire` |
| Git | `origin git@github.com:c1589190/iiBotMosire.git`；当前分支 `refactor/bot-first-main-agent`，HEAD `8075396`，tag `v0.1.0-bot-first.1` |
| Maven reactor | `io.mosire.iibot:iibot-parent:0.1.0-SNAPSHOT`，5 个模块（含迁移期 legacy） |
| Java | OpenJDK 21.0.12.1 |
| Maven Wrapper | 3.9.16；`./mvnw -v` 实测 |
| 上游三库 | `io.mosire:{agentlib,workspace,docs}-mosire:0.2.4`；InboxMosire `f93e7e2`（`0.1.0-SNAPSHOT`）；InboxMosire-DSH `113a838` |
| 可运行产物 | `iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar` 与已部署的 `bot/iibot-app-0.1.0-SNAPSHOT.jar` 均为 sha256 `a1b42e7ec985e14638e2d69eb671c65afed34277f85809793fb225d874336e0a`；GitHub Release `v0.1.0-bot-first.1` 的 jar/sha256 已同步替换；旧 jar 已备份在 `bot/` |
| Git/提交 | `origin/refactor/bot-first-main-agent` 同到 `8075396`；tag 已推送；迁移路径文档与运维日志改动均未提交 |
| 本机工作树 | **dirty（迁移文档 + 运维日志代码/设计/测试）**；`target/`、`logs/`、`simulation/dist/`、`simulation/runtime/`、`.work/` 按 gitignore 不入库 |

## 3. 最近功能门禁

| 项 | 实际结果 |
|---|---|
| **2026-10-28 D-024 Inbox 未读列表 full** | **PASS**：`tools/gate.sh full`；全仓 **730 tests / 0 failures / 0 errors / 0 skipped**（connect 46、connect-agent 21、bot 67、taskflow 250、app 346）；`tools/gate.sh docs` PASS（files=517 links=208）；`messages` last-n 预览、source/trigger/unreadCount/hasMoreUnread、同 item 追加、partial ack、legacy content、SQLite 未读计数、snapshot cursor 竞态均已覆盖 |
| **2026-10-27 Bot 运维日志 + Inbox 可见性 full** | **PASS**：`tools/gate.sh full`；全仓 **731 tests / 0 failures / 0 errors / 0 skipped**（connect 46、connect-agent 21、bot 61、taskflow 256、app 347）；日志 `/tmp/iibot-inbox-allmsg-full.log`；低权重普通群聊仍生成 `BELOW_THRESHOLD` item，@ digest 同时包含此前普通消息，context 也不再被权重过滤 |
| **2026-10-27 consistency bot full reactor** | **PASS（full gate 内）**：bot **49 tests / 0 failures / 0 errors** |
| **2026-10-27 consistency app full reactor** | **PASS（full gate 内）**：app **325 tests / 0 failures / 0 errors** |
| **2026-10-27 weight routing full** | **PASS**：`tools/gate.sh full`；全仓 **705 tests / 0 failures / 0 errors**（connect 46、connect-agent 21、bot 55、taskflow 256、app 327）；日志 `.work/weight-full2.log` |
| **2026-10-27 default bot + multi config full** | **PASS**：`tools/gate.sh full`；全仓 **719 tests / 0 failures / 0 errors**（connect 46、connect-agent 21、bot 55、taskflow 256、app 341）；日志 `.work/config-full2.log`；默认 bot 与 `--config` 多文件合并已测 |
| **2026-10-27 Inbox embedded 参数化 full** | **PASS**：`tools/gate.sh full`；全仓 **723 tests / 0 failures / 0 errors**（connect 46、connect-agent 21、bot 55、taskflow 256、app 345）；日志 `.work/inbox-params-full2.log`；BotInboxRuntime 参数透传与配置字段已测 |
| **2026-10-27 first-run bootstrap full** | **PASS**：`tools/gate.sh full`；全仓 **725 tests / 0 failures / 0 errors**（connect 46、connect-agent 21、bot 55、taskflow 256、app 347）；日志 `.work/bootstrap-full.log`；空目录首启模板生成、已有配置不覆盖、二次启动 token fail-closed 已测 |
| **秘书工具上限 128 focused** | **PASS**：`tools/gate.sh focused iibot-taskflow test`；taskflow 256 tests / 0 failures / 0 errors / 0 skipped；日志 `.work/tool-limit-128-focused.log` |
| **秘书工具上限 128 full** | **PASS**：`tools/gate.sh full`；全仓 627 tests / 0 failures / 0 errors / 0 skipped（connect 38、connect-agent 21、taskflow 256、app 312）；日志 `.work/tool-limit-128-full.log` |
| **本轮 Inbox Mode A focused** | **PASS**：`tools/gate.sh focused iibot-app test`；app 312 tests / 0 failures / 0 errors / 0 skipped；日志 `.work/inbox-gate-focused-final.log` |
| **本轮 Inbox Mode A full** | **PASS**：`tools/gate.sh full`；全仓 626 tests / 0 failures / 0 errors / 0 skipped（connect 38、connect-agent 21、taskflow 255、app 312）；日志 `.work/inbox-gate-full-final.log` |
| **本轮 docs** | **PASS**：`tools/gate.sh docs`；modules=4 files=486 links=174，active skills=1；日志 `.work/inbox-gate-docs-final.log` |
| **本轮 Inbox live smoke** | **PARTIAL（隔离环境）**：Fake LLM/Fake adapter；MCP 83 工具、available=2、DSH bind/unbind occupancy PASS、端口关闭/dataDir 保留 PASS；真实 QQ/LLM 外发 `NOT_RUN`；证据 `.work/inbox-smoke/run-live-2/` |
| **本轮模拟区真实启动 smoke** | **PASS（启动/工具面）**：新 dist jar 实际启动，recovery=true，`tools/list=88`、11 个 `inbox_*` + descriptor、无 `tf_ticket_board`；`08-inbox-descriptor.sh` 生成 0600 descriptor；`inbox_status` available=1；未执行真实外发。日志 `.work/inbox-sim-start.log`、`.work/inbox-sim-tools-list.txt`、`.work/inbox-sim-descriptor.out` |
| 最近 full clean（D-014 cache/history） | **549 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**（connect 33、connect-agent 21、taskflow 236、app 259），`tools/gate.sh full` PASS；日志 `.work/cache-full-final3.log` |
| 最近 full clean（D-015 玩家体验运行时） | **568 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**（connect 33、connect-agent 21、taskflow 248、app 266），`tools/gate.sh full` PASS；日志 `/tmp/pe-final-gate-full-d016.log`。门禁后按同一源码重建并部署，当前 `simulation/dist` jar sha256 `ba7c617039a57370e89de32b547be6de8772e6af1e700a6406e78207c7099cc6`（`sha256sum -c` OK）。 |
| 前次 full clean（P8-A） | **526 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**（connect 29、connect-agent 21、taskflow 230、app 246），`tools/gate.sh full` PASS；日志 `.work/p8a-full.log` |
| P8-A focused / 评审 | focused **526/526 PASS**；独立评审已启动但在用户要求提交时主动中止，**无最终评审结论**；66 MCP 工具断言通过 |
| P8-A MCP 工具面 | `tf_publication_opening` 接入；`players=[]` 启动、GM 公共开场草稿、records/openings、动态注册后开回合已测 |
| 当前门禁状态 | D-014 源码/focused/full gate 已过；P8 真实模拟仍未完成；真实 NapCat 历史读取未跑，写 `NOT_RUN` |
| Shade | 产出可运行 `iibot-app-0.1.0-SNAPSHOT.jar`（约 21 MB），Main-Class 已核 |
| 文档/工具链 | `tools/gate.sh docs` **PASS**（modules=4 files=505 links=185；项目级 active Skill=0）；`python3 tools/skills-lint.py --root simulation` **PASS**（模拟区 active Skill=3）。`09-load-secretary-skill.sh` 已 `bash -n` 通过；app 停止状态下未实际加载。 |
| 文档 selftest | 当前受上述外部 Skill 失败影响，`tools/gate.sh docs` 未 PASS；不能写成全绿 |
| `./mvnw -v` | Apache Maven 3.9.16 + Java 21，rc=0 |
| `java -jar ... --help` | rc=0，输出用法 |
| 真实 NapCat 私聊收发 + 真实 LLM 秘书回复 | **PASS（部分真实 E2E）**：玩家 `<player-qq>` 私聊“你先说句话证明你能说和”，Bot 于 03:14:51 返回真实秘书文本；来源为用户提供的 QQ 对话记录 |
| 真实 LLM API 连通性 | **PASS**：基础文本 `OK`；真实 tool_call `get_time` 与第二轮工具结果回填成功 |
| 真实 NAPCAT WS 连接 | **PASS**：App 进程到 `127.0.0.1:3001` ESTAB；`get_login_info` 返回 Bot `<bot-qq>` |
| 真实本地 MCP client | **PASS**：v2 重启后 `tools/list=28`；工作区/GM aide/推文工具均真实调用 |
| 真实单人完整回合 | **PASS**：OPEN→LOCKED→ADJUDICATING→COMMITTED→PUBLISHED；中央推演约 14s；worldVersion 2；公开群消息 id 1536303685；玩家私聊 outcome id 138548704 |
| 真实 LLM 全 App 流程 | **PASS**：秘书 → draft → `/确认` → lock → 中央 LLM 推演 → commit → publish 已真实完成；见 `docs/superpowers/status/2026-10-24-real-single-player-e2e.md` |
| 真实 DSH/MCP E2E | **PARTIAL**：本地 Java MCP client 已连接 34 工具并完成共享 Skill、投诉提交/GM回应/真实私聊发送；DSH 外部会话未接入 |
| 长跑/崩溃恢复 | **NOT_RUN** |
| 应用启动 smoke（dummy 密钥） | 进程启动后保持运行至 6s timeout；生成 `events.db`、`world/bible.md`、审计 LOG |
| 真实 `NapCatAdapter` + 本地 Mock NapCat E2E | 通过；WS 入站→HTTP Action 出站全链路，连续 5 次通过 |
| 进程重启恢复 smoke（dummy 密钥） | 首启生成 `taskflow-state.json`/events.db；同 dataDir 第二次启动日志 `recovery=true worldVersion=1 eventSeq=4` |
| MCP 启用 smoke | 无 token GET → 401；带 token GET → 400（方法/协议头不符，符合预期）；进程保持运行 |

## 4. 当前有效设计/维护入口

| 类型 | 入口 |
|---|---|
| 根路由与常驻红线 | `AGENTS.md` |
| 文档系统规则 | `docs/AGENTS.md` |
| 项目维护裁定 | `docs/agents/MAINTENANCE_RULINGS.md` |
| 用户需求原话与 D-xxx | `docs/superpowers/specs/2026-10-24-user-requirements-source.md` |
| 接入 Lib / Agent 扩展层 | `docs/superpowers/specs/2026-10-24-connect-and-agent-extension-design.md` |
| 目标运行设计 | `docs/superpowers/specs/2026-10-24-target-runtime-design.md` |
| 外部 MCP 控制面 | `docs/superpowers/specs/2026-10-24-external-mcp-control-plane.md` |
| 实施计划与进度 | `docs/superpowers/plans/2026-10-24-next-implementation-plan.md` |
| 首个纵向切片历史快照 | `docs/superpowers/status/2026-10-24-first-vertical-slice.md` |
| 真实单人 E2E 历史快照 | `docs/superpowers/status/2026-10-24-real-single-player-e2e.md` |
| v2 功能切片历史快照 | `docs/superpowers/status/2026-10-24-v2-agent-workspace-permissions-models.md` |
| 共享 Skill / 投诉历史快照 | `docs/superpowers/status/2026-10-24-shared-skills-and-complaints.md` |
| v3 设计 | `docs/superpowers/specs/2026-10-24-decision-packages-action-pool-and-turn-records.md` |
| 理想工作流/events.db 设计 | `docs/superpowers/specs/2026-10-24-ideal-gm-control-workflow-and-events-db.md` |
| 理想工作流实施计划 | `docs/superpowers/plans/2026-10-24-ideal-workflow-implementation-plan.md` |
| Bot 运维日志设计 | `docs/superpowers/specs/2026-10-27-bot-operational-logging.md` |

## 5. 已实现与已验证的能力

- QQ 消息去重、重连、心跳、限速、文本分段、发送三态 `SENT/FAILED_BEFORE_SEND/UNKNOWN`；
- Agent 工具权限元数据与 `ToolCallAuthorizer` 统一执行；
- 通用账号信息查询 `qq_get_login_info`（AccountInfoProvider，不依赖 NapCat 具体类）；
- 完整领域状态快照原子持久化与重启恢复（身份/世界/回合/draft/decision/设定/幂等回执）；重启前校验事件 seq、promptVersion、身份映射，不一致 fail-closed；
- 玩家秘书与 GM brief 的可见性隔离；
- 自然语言生成结构化草案；只有同一私聊 QQ 的 `/确认 <draftId>` 才产生有效决策；
- 回合 OPEN→LOCKED→ADJUDICATING→COMMITTED→PUBLISHED、revision CAS、幂等；
- 发布后结果分发：公开叙事进入公共群，玩家私密 outcome 进入对应私聊，稳定幂等键且进程内不重复；
- 运行期设定：玩家 `/提议设定`，推演 `/设定`、`/待审设定`、`/通过设定`、`/拒绝设定`；批准后 worldVersion+1 并生成新 WorldFact；
- 非法提案/未知玩家/过期 worldVersion 零世界变更；
- 外部 MCP 48 工具（含行动池、裁定包、批次与 `tf_run_adjudication_agent`）、Bearer 常数时间校验、回环绑定、独立 allow-list、POST/GET SSE 代理；
- Workspace/Docs 实际写入世界圣经与审计 LOG；
- SQLite 事件审计与 `afterSeq` 只读游标；真实发布后由 `afterMutation` 触发结果分发。
- 真实单人回合的草稿、硬确认、冻结、中央 LLM 推演、提交、发布与分发均有实际出站证据；
- v2-A 即时 ACK 已实现：已登记玩家/推演私信先发独立 ACK，再异步处理正式回复；重复 dedupeKey 不重复 ACK。
- v2-B 秘书工作区已实现：每玩家独立 Workspace/Docs 四目录；秘书三工具；GM 四工具（list/read/dispatch/chat），路径逃逸/跨玩家 fail-closed。
- v2-C 推文已实现：PENDING_APPROVAL→APPROVED/REJECTED→PUBLISHED；仅 GM/DIRECTOR/SUPERVISOR 审核发布；`posts.json` 原子持久化；`PostDistributor` 发公共群/玩家私聊；UNKNOWN 不自动重发。
- v2-D 多模型与 GM 推演秘书已实现：`llm` default + `llmRoutes` + `agentRoutes(secretary/adjudicator/gmAide)`；`GmAideAgent` 9 个只读工具，可读全量世界/回合/brief/事件/所有工作区/所有推文，只返回 MCP/GM；无发送/写入工具。
- v2 真实本地 MCP/LLM 验证：工作区下发/读取、秘书真实 LLM 总结、GM aide 真实 LLM 全量读取、真实秘书 LLM 创建私人推文草稿、MCP supervisor 批准并通过 `tf_post_publish` 真实发送到玩家私聊（deliveryState=SENT）。
- v2 真实私信 ACK：玩家真实私信在 04:15:17 收到 ACK，正式秘书回复于 04:15:31 出站；ACL/dedupe/异步顺序成立。
- 真实共享 Skill：GM MCP 写入 `skills/secretary-response-guide.md`，真实秘书 LLM 读取并复述规则。
- 真实投诉闭环：玩家真实投诉 `0f43536a-8868-48c1-949a-16c8b3515372` → 秘书提交 → GM MCP 查看 → GM 回应 → ComplaintDistributor 私聊发送消息 id `1282368080`，`deliveryState=SENT`。秘书还正确拒绝 GM 代替玩家提交投诉。
- 重启重复发送修复：真实重启曾发现 v1 回合结果重复发送；已新增 `JsonDeliveryLedger` 逐消息持久化声明（`turn:<id>:public` / `turn:<id>:player:<playerId>`），并验证再次重启无重复外发。
- v2 full assembly MCP 34 工具（18 ExternalControlToolSet + tf_run_adjudication_agent + tf_gm_aide_chat + 4 GM 工作区 + 4 GM 推文 + 3 共享 Skill + 3 投诉）。
- 全局共享 Skill 区已实现：`dataDir/shared-skills/{public,skills}`；秘书只读两工具，GM MCP list/get/write，GmAide 只读；无需逐玩家复制。
- 投诉闭环已实现：秘书 submit/list/read；GM list/get/respond；`complaints.json` 持久化；ComplaintDistributor 真实私聊回应；SUBMITTED→RESPONDED；未知/其他玩家不可见。
- v3 行动池/裁定包已实现：秘书可跨回合提交自然语言行动；GM 可建包、追加/替换/删除、逐条/整包裁决；一个包 commit 产生一个推演批次；同一回合可多批次。当前行动池/包/批次仍为进程内存储。
- P1 `events.db` 完整内容基础已实现：headers + `event_contents` 同事务写入；JSON/Markdown/TEXT 严格二选一；`PRAGMA user_version=1`；旧 AgentLib 审计库幂等迁移为 `event_version=0/content_missing=1`；SHA-256 正文 hash 读取重算 fail-closed；大小上限；敏感键/重叠 secret 覆盖掩码脱敏；主库及 WAL/SHM best-effort 600。
- P2 现有领域事件接入已实现：Post/Complaint 使用 events.db 事件源 store；ActionPool 使用事件源快照；`TASKFLOW_SNAPSHOT` 保存完整 Taskflow 快照；`payload_json` summary 只含 `idempotencyKeyHash` 等索引；旧 posts/complaints/taskflow-state 使用 exact `migrated` + `reconciled` 两阶段标记，并校验目标事件 seq/type/contentHash；事件版本写侧/读侧均 fail-closed。
- P3 动态控制初始化已实现：MCP 可设置公开群、注册玩家/推演、幂等绑定秘书、装载世界；engine 是身份事实源；动态 QQ 路由、ACK、回合结果、私密推文/投诉回应实时生效并重启恢复；`CONTROL_STATE` 持久化公开群；GM 私有文档 `gm-private/{world,plot,settings,notes}` 安全隔离。
- P4 统一工单已实现：Ticket 类型 SETTING_QUERY/ACTION_DRAFT/COMPLAINT/GM_NOTE；秘书 submit/list/read 按 playerId 隔离；GM board/list/get/claim/reply/batch_reply/close/note_create；TicketSnapshot 写入 events.db，幂等结果带 actor/request fingerprint；batchReply 100 条整批原子。
- P5 行动/裁定包已实现：ACTION_DRAFT ticket 可 promote 为 ActionPool 行动；包命令 STARTED/APPLIED/FAILED 与 BATCH_COMPLETED 全状态写入 events.db；启动命令重放恢复包/批次（commit 不重跑 executor）；canonical action 单 owner；ActionPool 投影可 reconcile；ignore/create 同锁防 TOCTOU。
- P6 发布/记录已实现：COMMITTED batch 由 GM 触发生成 Public/Private 草稿；PostService 跨命令 fingerprint 与 publication-draft-aware 复用；`PUBLICATION_GENERATED` 确定性 ID/恢复；records/turns staging 原子投影、manifest/hash 对账、父级软链防护；`tf_publication_generate` + `tf_record_turn_list/get/query`。
- D-014 秘书会话 cache / NapCat 历史已实现（源码，未部署到 simulation/dist）：`SecretaryCacheStore` + `SqliteSecretaryCacheStore` 保存 raw `LlmMessage`、每玩家 activeCacheId、启动水合与 cache 切换；玩家工具 `secretary_cache_list/read`；GM 工具 `tf_gm_secretary_cache_list/create/read/switch`；`ChatHistoryProvider` + `NapCatAdapter.history` + `tf_qq_history_read`；`gmChat` 使用独立临时窗口，不写玩家 cache。完整 MCP 工具面在无 provider 时为 71，真实 `NapCatAdapter` 实现 provider 时为 72。
- P8-A 零玩家/开场已实现：`players=[]` 可启动，directors 仍非空；GM 通过 `tf_publication_opening` 创建 PUBLIC `PENDING_APPROVAL` 开场草稿，必须经 `tf_post_decide`/`tf_post_publish` 才发公共群；records/openings 独立投影；完整 MCP 66 工具。模拟区 jar 在 `simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar`（本地忽略，不在 Git）。

## 6. 未验证 / 风险 / blocker

1. **Inbox Mode A 真实外发未跑**：自动化与隔离 smoke 已过，但真实 DSH LLM +
   真实 NapCat/QQ + 真实发布链路 `NOT_RUN`。真实 smoke 需用户明确目标与授权。
3. **Inbox 重启去重未做跨进程 smoke**：服务端持久 dedupeKey、桥本地成功集合与
   SQLite 已实现，单进程周期扫描已测；app 重启后同 revision 不重复产 item 未实测。
4. **Inbox credential 生命周期**：每次 app 启动签发新 program/wake credential，
   旧 credential 留到 TTL 或手动 revoke；自动清理/轮换未实现。
5. **Mode A 端口随机**：app 重启后必须重新 `inbox_adapter_bind`；跨机器固定 daemon、
   TLS、远程绑定不在本切片范围。
6. **通知桥通道范围有限**：首版只推 ticket OPEN/CLAIMED/CONFIRMED 与
   publication-review PENDING_APPROVAL；COMMITTED batch、delivery FAILED/UNKNOWN
   等独立通道未覆盖。
7. **第二轮真实行动/公共推文未跑**：ACK/工作区/GM aide/私人推文/共享 Skill/投诉真实闭环已验证；第二轮决策与公共推文尚未跑。
7. **真实 DSH 完整 Agent 会话未回归**：本轮已实际通过 DSH adapter v2 完成
   bind/unbind 与 occupancy smoke，并验证当前项目 MCP；但真实 DSH LLM Agent
   在 app MCP 上完成一轮业务处理尚未运行。
8. **真实多玩家/多回合/长跑未运行**：当前仅 1 名真实玩家、1 个已发布回合。
9. **模型热切换未实现**：当前按 `agentRoutes` 配置切换模型并重启；无运行时 admin 工具。
10. **跨进程并发 ledger 未验**：`JsonDeliveryLedger` 保证同 JVM 多实例与重启场景；多进程同时写同一 ledger 未验。
11. **投诉状态有限**：当前只有 SUBMITTED→RESPONDED，无关闭/升级/多次回复。
12. **逐事件回放未实现，重启恢复依赖完整快照**：`taskflow-state.json` 原子持久化并在启动时恢复，事件库 seq 只做一致性锚点；快照与事件 seq 不一致时 fail-closed。Secretary 的 ContextWindow 在装配 `SecretaryCacheStore` 后已可从 cache 恢复，且 `AppRestartRecoveryTest` 有离线重启回归；cache 摘要/压缩、跨进程并发写与真实 app 重启仍未验证。
13. **公共群目前只作为公开广播边界**：群内玩家对话、@ 触发、群内决策未接入。
14. **外部 MCP 仅本地单 supervisor**：远程 TLS/多租户/长期 SSE/Last-Event-ID 重放未验；不要把端口暴露公网。
15. **events.db 尚未成为完整领域事实源**：P2 已接 Post/Complaint/ActionPool 和 Taskflow 完整快照；P3 控制/审计事件与 P4 Ticket 快照已写；P5 包/批次命令事件已写并从 events.db 恢复。事件回放仍未覆盖全部领域，AgentLibEventSink 仍为旧读取端口兼容双写 payload/payload_json。
16. **P4/P5/P6/P8-A 边界与残留**：旧 ComplaintService 的 GmAide 只读工具与 ComplaintDistributor 仍兼容旧投诉，完整 MCP 旧 complaint 写面已隐藏，但旧投诉与新 COMPLAINT ticket 仍分裂；Ticket 全量快照/包命令事件未来可能触 4MB 上限，需归档/分页策略；APPLIED 写失败发生在包内存变更后，只 fail-stop，不宣称跨域原子；BATCH_COMPLETED 缺失时恢复只从 APPLIED 重建 batch，不主动补写缺失事件；跨实例并发 publication generate 仍可能因同确定性 ID/不同 createdAt 冲突而 fail-closed（当前单实例组合根）；终态/无 turn 的 COMMITTED batch 生成 publication 时 turnId=null，不会进入 turn 投影，语义待明确；已有 publication drafts 但 LLM 重试本身失败时仍返回 LLM/OUTPUT 错误。P8-A：空 players + control 关闭只 WARN，仍可启动但没有运行期加人入口；opening 只允许 PUBLIC，不允许 GM 任意创建私密推文；独立评审未完成，不能把 focused/full gate 当作 P8-A 阶段评审 PASS。
17. **P2/P3 迁移/控制残留**：旧 JSON 迁移使用 exact reconciled 完成标记并校验目标事件 seq/type/contentHash；运行期 taskflow-state.json 与 TASKFLOW_SNAPSHOT 双写为 best-effort，启动 ensureRecorded 追平 stale/missing；JSON 保存失败而 snapshot event 成功时，重启仍会在 anchor mismatch 处 fail-closed。P3 控制注册在 session 创建前先发布 route，session 创建失败可能短暂半状态；GM 文档写幂等仍是进程内，chmod 失败与 parent fsync 未硬化；旧 engine 全局 idempotency namespace 仍可能让旧写工具用其他命令的 key 返回假成功。事件查询面的 per-player visibility scope 尚未实现。
18. **配置侧删除身份不撤销 engine 身份**：按当前非目标“不删除玩家/推演”，从启动配置移除身份不会删除已恢复/动态注册身份；需在后续控制面明确撤销语义。
19. **P1 非阻断硬化点**：极端自重叠 secret 的掩码填充复杂度未做基准；chmod 故障注入、并发 close/append 压力、真实历史 events.db 迁移演练未跑。
20. **依赖/静态质量门禁有限**：当前 `verify` 主要有 enforcer + tests + shade，尚未接入 Spotless/Checkstyle/SpotBugs；不要把它等同于完整质量门禁。
21. **上游编译 warning**：AgentLib 注解在 javac 编译期有 `SuppressFBWarnings` 缺失 warning，不影响当前构建，但说明注解仅编译期处理。
22. **D-014 未验证边界**：真实 NapCat `get_friend_msg_history/get_group_msg_history` 尚未调用；群临时会话的来源群歧义、短 `message_seq` LRU、历史清理、真实 QQ 发送副作用均未验证。`simulation/dist` 仍是旧 jar；本轮 626-tests target jar 尚未部署。cache 只做 raw append，不做摘要压缩。
23. **文档门禁当前通过**：`tools/gate.sh docs` 本轮 PASS（modules=4 files=486 links=174）；
   仍需保持新增 Skill/文档与登记一致。
24. **新 jar 已部署但未重启**：旧进程已停止、`bot/` 与 GitHub Release 资产已替换并校验；当前没有 Bot 常驻进程。真实运行期日志轮转、长跑容量、真实链路敏感值扫描均 `NOT_RUN`；重新启动会建立真实 QQ/Inbox/MCP 外部连接，需按用户当次指令执行。

## 7. 下一步

0. **先审阅并冻结本轮规划**：确认 AgentLib 0.2.4 发布/降级策略、dirty worktree
   处理方式、文游物理删除边界和 `iibot-connect-agent` 去留；确认后按计划 P0 开始。
1. **启动新 jar（需用户当次指令）**：当前旧进程已停止、新 jar 已部署且
   Release 资产已同步；用户要求启动时再运行 `java -jar iibot-app-0.1.0-SNAPSHOT.jar`，
   并检查 `bot/logs/iibot.log` 与 `bot/logs/iibot-ops.log` 是否出现
   `process_start`、`bot_runtime_ready`、`mcp_started`、`adapter_event`。
2. **首次启动/参数化验收**：空目录模板生成已实测；下一步用真实配置在干净
   runtime 启动新 jar，确认 embedded Inbox 与 Bot MCP 同时就绪，再按用户授权
   部署并绑定 DSH。
2. **Inbox Mode A 真实联调**：先停/备份当前运行面，再把本轮 target jar 以用户确认的方式
   部署到 `simulation/dist`；启动后用 `tf_inbox_binding_descriptor` → `inbox_adapter_bind`
   重建绑定，验证真实 DSH 唤醒、occupancy、宿主桥 item 与真实审核/外发边界。
3. **玩家体验真实 playtest**：先用新 target jar 在干净 runtime 上按 `simulation/player-experience/09-playtest-and-acceptance.md` 做 5 人 playtest；未通过前不进入多玩家长跑。下列 D-014/P8 项仍有效。
4. **D-014 收口**：在用户明确授权后，用新构建 jar 替换 `simulation/dist` 前先停/备份并按用户确认执行；不要在本轮自动重启 app。
5. **真实 NapCat 历史验证**：只对用户指定/已注册 QQ 和当前公共群调用 `tf_qq_history_read`，记录真实游标、临时会话、超时/1200 结果；未跑前保持 `NOT_RUN`。
6. **P8 真实模拟**：真实 DSH/Hermes MCP、真实 LLM/NapCat/QQ，零玩家公共开场→审核发布→动态注册玩家→多玩家/多工单/多批次/发布/记录全链路；真实外部发送按 M-004 先与用户确认。
7. P7 已跳过；P8-A 独立评审未完成，需在真实模拟前决定是否补评审；未跑项继续标 `NOT_RUN`，不得用 Mock/loopback 代替真实联调结论。
