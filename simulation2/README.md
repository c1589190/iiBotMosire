# simulation2 —— bot-first 一夜运行区

本目录与 legacy `simulation/` 完全分离：

- 使用 `simulation2/runtime`、`simulation2/logs`、`simulation2/dist`；
- 默认启动 bot-first，不需要 `--mode bot`；
- 不加载秘书、玩家、回合、Ticket、ActionPool、Publication 或 LLM 配置；
- 所有 Bot 可见私聊/群聊进入 SQLite 消息板，按来源生成 Inbox 摘要；
- 外部 DSH 主 Agent 通过 Bot MCP 读取摘要、恢复正文、回复和更新用户备注；
- `inbox.apiPort/mcpPort=0` 表示随机回环端口，也可显式固定端口；
  固定端口冲突会 fail-closed，`mcpPath/heartbeatMillis/claimSweepIntervalMillis`
  由配置传入 embedded server。

## 1. 前置

已存在真实 NapCat 环境和以下文件之一：

- `simulation/config/iibot.sim.local.json`（默认源）
- 或 `~/.config/iibot/iibot.local.json`
- 或通过 `IIBOT_BOT_SOURCE_CONFIG` 指定

环境变量：

```bash
export IIBOT_NAPCAT_ACCESS_TOKEN='...'
export IIBOT_CONTROL_TOKEN='...'
```

`IIBOT_CONTROL_TOKEN` 同时也是 Bot MCP 的 Bearer token。

外部 DSH 会话还必须把 Bot MCP 挂成独立的程序 MCP connector：

```text
URL:  http://127.0.0.1:8765/mcp/bot
Auth: Bearer <IIBOT_CONTROL_TOKEN>
Scope: 当前 simulation2 / iiBotMosire workspace
```

不是把地址塞进 Inbox descriptor；descriptor 只负责 wake，固定五字段。

## 2. 构建并生成 bot 配置

```bash
cd ~/iiBotMosire
simulation2/scripts/00-build.sh
simulation2/scripts/01-prepare.sh
simulation2/scripts/00-check.sh
```

生成：

```text
simulation2/config/bot.sim.local.json
simulation2/runtime/bot.db
simulation2/runtime/inbox/
```

`bot.sim.local.json` 只保存环境变量名，不保存 raw token。

## 3. 启动

```bash
simulation2/scripts/02-start.sh
```

启动成功后会打印：

```text
BOT_STARTED pid=...
BOT_MCP_URL=http://127.0.0.1:8765/mcp/bot
INBOX_WAKE_MODE=view-ack
INBOX_HANDOFF_READY=.../simulation2/runtime/inbox-binding-descriptor.json
```

## 4. DSH 绑定

让外部 DSH 会话读取：

```text
simulation2/dsh/HANDOFF_PROMPT.md
```

或直接：

1. 调 `tf_inbox_binding_descriptor`；
2. 把 descriptor 原样传给 `inbox_adapter_bind`，并显式传
   `wakeMode: "view-ack"`；
3. 用 `inbox_adapter_status` 确认 `isBound=true`、`wakeMode=view-ack`、
   `programMcp=available`、SSE/occupancy 正常；
4. 后续从 Inbox summary 进入 `inbox_view`，再用 `bot_thread_read` 恢复正文，
   最后用 `bot_thread_ack(..., expectedRevision, snapshotToSeq=content.toSeq)` 归档。
   active item 发布后冻结；处理期间的新消息在 ack 后形成下一版 item。

view-ack 不使用 `claim/release/complete`。当前唤醒摘要会按 view-ack 模式生成；
程序 MCP 的工具名可能带 `mcp__iibot__` 前缀。

## 5. 观察与停止

```bash
simulation2/scripts/04-status.sh
tail -f simulation2/logs/app.log
simulation2/scripts/03-stop.sh
```

## 6. 当前工具面

view-ack 主流程只使用：

```text
inbox_adapter_status
inbox_status
inbox_list
inbox_view
bot_thread_read
bot_thread_history
bot_message_get
bot_message_thread
bot_forward_read
bot_media_get
bot_qq_recent
bot_qq_history
bot_inbox_history
bot_friend_request_list
bot_friend_request_decide
bot_source_weight_list
bot_source_weight_get
bot_source_weight_set
inbox_rest
bot_reply
bot_thread_ack
tf_inbox_binding_descriptor
```

`bot_qq_recent` / `bot_qq_history` 读取 OneBot 客户端侧消息，和 Inbox 通知板
不是同一来源；`bot_inbox_history` 读取 Bot 侧通知审计。`inbox_view` 的
`content.context` 默认带最近 6 条 IN/OUT 摘要。

## 6.1 来源权重

- 每个来源有 `sourceWeight`、`notifyThreshold`、结构信号权重和关键词权重；
- `priority` 使用总分，Inbox 按 priority 降序展示；
- 新来源第一次出现产生 `FIRST_CONTACT` item，Agent 可调用
  `bot_source_weight_set` 写规则；
- 群聊默认 `sourceWeight=0 / threshold=100`，普通消息只存档；
- 私聊默认 `sourceWeight=300 / threshold=100`；
- 低于 threshold 的消息不生成 Inbox item，但仍在 `bot.db`；
- `inbox_rest` 是 session 级休息提示，不修改 item。

Bot MCP 仍会暴露通用 `inbox_*` 工具和其他 bot 工具，但外部主 Agent 不得使用
`inbox_claim/release/complete`。这些工具留给其他 claim-based 程序，不属于本
view-ack 程序的生命周期。

不包含任何 `tf_ticket_*`、`tf_action_*`、秘书或回合工具。

## 7. 重要边界

- 这是 bot-first 真实运行区，连接 LLBot（当前）或 NapCat 的 OneBot 11 接口；
  启动本身不代表已授权群发。
- 外部主 Agent 的 `bot_reply` 是真实 QQ 外发；未知投递状态 `UNKNOWN` 不自动重发。
- app 重启后 embedded Inbox 端口会变化，必须重新 bind。
- 本程序固定使用 view-ack：不要 claim；`ITEM_REVISION_CONFLICT` 时重新 view
  并带上新的 `snapshotToSeq`，`ITEM_STATUS_CONFLICT` 时停止并报告，不要用
  release/claim 绕过。
- `programMcp=missing` 表示 DSH 会话没有挂程序 MCP，不是 Inbox 鉴权失败；
  此时应停止，不要回退到 `scripts/06-mcp.sh` 绕过。
- `bot_friend_request_decide` 是真实外部副作用；不得自动通过好友申请。
- `bot_qq_recent` / `bot_qq_history` 是 OneBot 客户端侧读取，不是 Inbox；
  历史扩展是否可用取决于 LLBot/NapCat provider。
- `bot_media_get` 对旧的无本地 segment 图片会按 messageId 调 `get_msg` 回退；
  旧消息仍可用，但可能比新消息慢。
- `bot_reply` 的正文先规范化再落库，保证本地 board 与 QQ 实际正文一致。
- `simulation2/runtime` 和 `simulation2/logs` 不提交、不对外暴露。
