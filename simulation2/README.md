# simulation2 —— bot-first 一夜运行区

本目录是 bot-first 运行区；旧 `simulation/` 文游运行区已从工作树删除：

- 使用 `simulation2/runtime`、`simulation2/logs`、`simulation2/dist`；
- 默认启动 bot-first，不需要 `--mode bot`；
- 不加载秘书、玩家、回合、Ticket、ActionPool、Publication 或 LLM 配置；
- 所有 Bot 可见私聊/群聊进入 SQLite 消息板，按来源生成 Inbox 摘要；
- 外部 DSH 主 Agent 通过 Bot MCP 读取摘要、恢复正文、回复和更新用户备注；
- `inbox.apiPort/mcpPort=0` 表示随机回环端口，也可显式固定端口；
  固定端口冲突会 fail-closed，`mcpPath/heartbeatMillis/claimSweepIntervalMillis`
  由配置传入 embedded server。

## 1. 前置

已存在真实 NapCat 环境，并满足以下任一条件：

- `simulation2/config/bot.sim.local.json` 已存在（直接复用）；
- 或通过 `IIBOT_BOT_SOURCE_CONFIG` 指向现有配置；
- 或 `~/.config/iibot/iibot.local.json` 存在；
- 都没有时，`01-prepare.sh` 从 `bot.sim.example.json` 生成模板并退出，等待编辑。

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
cd ~/DevMosire-iiBot/iiBotMosire
simulation2/scripts/00-build.sh
simulation2/scripts/01-prepare.sh   # 首次无配置时生成模板并 rc=2
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

1. 调 `qq_inbox_binding_descriptor`；
2. 把 descriptor 原样传给 `inbox_adapter_bind`，并显式传
   `wakeMode: "view-ack"`；
3. 用 `inbox_adapter_status` 确认 `isBound=true`、`wakeMode=view-ack`、
   `programMcp=available`、SSE/occupancy 正常；
4. 后续从 Inbox summary 进入 `inbox_view`，再用 `qq_conversation_read(threadId)`
   读取未 ack 批次；处理完成后调 `qq_conversation_ack(threadId)`。
   Agent 不传 revision/snapshot/itemId 等内部标记；read 只记录服务端 delivered
   位置，ack 才推进已读并同步 Inbox。处理期间的新消息留到下一次未读批次。

view-ack 不使用 `claim/release/complete`。当前唤醒摘要会按 view-ack 模式生成；
程序 MCP 的工具名可能带 `mcp__iibot__` 前缀。

## 5. 观察与停止

```bash
simulation2/scripts/04-status.sh
tail -f simulation2/logs/app.log
simulation2/scripts/03-stop.sh
```

## 6. 当前工具面

DSH 宿主提供的 Inbox 工具用于绑定和查看 summary：

```text
inbox_adapter_status
inbox_list
inbox_view
```

Bot MCP 默认只暴露统一业务工具、唯一通用 Inbox 工具和暂留宿主交接的
descriptor 工具：

```text
qq_account_list
qq_account_status
qq_account_login_info
qq_conversation_list
qq_conversation_read
qq_conversation_ack
qq_conversation_history
qq_conversation_send
qq_message_get
qq_message_thread
qq_forward_read
qq_media_get
qq_recent_messages
qq_remote_history
qq_inbox_history
qq_friend_request_list
qq_friend_request_decide
qq_user_note_get
qq_user_note_list
qq_user_note_upsert
qq_source_weight_list
qq_source_weight_get
qq_source_weight_set
inbox_rest
qq_inbox_binding_descriptor
```

`qq_recent_messages` / `qq_remote_history` 读取 OneBot 客户端侧消息，和 Inbox 通知板
不是同一来源；`qq_inbox_history` 读取 Bot 侧通知审计。`inbox_view` 的
`content.context` 默认带最近 6 条 IN/OUT 摘要。

`qq_inbox_binding_descriptor` 只返回五字段 descriptor 与本地文件路径，不返回
raw token；它暂留作宿主交接用途。真实绑定仍由 DSH adapter 完成。

## 6.1 来源权重

- 每个来源有 `sourceWeight`、`notifyThreshold`、结构信号权重和关键词权重；
- `priority` 使用总分，Inbox 按 priority 降序展示；
- 新来源第一次出现产生 `FIRST_CONTACT` item，Agent 可调用
  `qq_source_weight_set` 写规则；
- 群聊默认 `sourceWeight=0 / threshold=100`，普通消息只存档；
- 私聊默认 `sourceWeight=300 / threshold=100`；
- 低于 threshold 的消息不生成 Inbox item，但仍在 `bot.db`；
- `inbox_rest` 是 session 级休息提示，不修改 item。

Bot MCP 不再全量暴露通用 `inbox_*` 工具；只保留 `inbox_rest`。DSH 的
claim/release/complete 也不属于本 view-ack 程序的生命周期。

不包含任何 `tf_ticket_*`、`tf_action_*`、秘书或回合工具。

## 7. 重要边界

- 这是 bot-first 真实运行区，连接 LLBot（当前）或 NapCat 的 OneBot 11 接口；
  启动本身不代表已授权群发。
- 外部主 Agent 的 `qq_conversation_send` 是真实 QQ 外发；未知投递状态 `UNKNOWN` 不自动重发。
- app 重启后 embedded Inbox 端口会变化，必须重新 bind。
- 本程序固定使用 view-ack：不要 claim。`qq_conversation_ack(threadId)` 不接受
  revision/snapshot；Inbox 同步失败时报告真实错误，不要手工维护游标或用
  release/claim 绕过。
- `programMcp=missing` 表示 DSH 会话没有挂程序 MCP，不是 Inbox 鉴权失败；
  此时应停止，不要回退到 `scripts/06-mcp.sh` 绕过。
- `qq_friend_request_decide` 是真实外部副作用；不得自动通过好友申请。
- `qq_recent_messages` / `qq_remote_history` 是 OneBot 客户端侧读取，不是 Inbox；
  历史扩展是否可用取决于 LLBot/NapCat provider。
- `qq_media_get` 对旧的无本地 segment 图片会按 messageId 调 `get_msg` 回退；
  旧消息仍可用，但可能比新消息慢。
- `qq_conversation_send` 将调用方原文落库并发送；`idempotencyKey` 可省略，省略时
  服务端生成 UUID 并在结果中返回，调用方仅在显式重试时复用该键。
- `simulation2/runtime` 和 `simulation2/logs` 不提交、不对外暴露。
