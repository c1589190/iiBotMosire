# OneBot 11 富消息、历史、媒体与好友申请

> **状态**：有效（2026-10-27 用户确认开始实现）
> **用户原话**：
> > 「C肯定是3 D也是两者都需要」
> > 「目前我们用的框架是LLBot，不是Napcat，后续可能会继续用Napcat，因此你要尽可能使用标准的Onebot11协议实现」
> > 「查看群聊/私聊历史聊天记录，包括通知版面，最好是，默认注入的消息就带有固定的几条上下文，方便Bot理解语义」
> > 「1 那不用了 2 可以 可以开始实现了」
> **最终裁定**：
> - C：引用回复树 + 合并转发树都做；
> - D：只做入站好友申请审批；主动添加好友不做（标准 OneBot 11 / LLBot 无该 action）；
> - 默认上下文：Inbox `content.context` 注入最近 6 条 IN/OUT 摘要，wake 摘要保持短；
> - 当前 LLBot 8.3.2，未来 NapCat；标准 OneBot 11 优先，扩展能力通过 provider capability 探测。

## 1. 现状事实

- `OutboundMessage` 只有 `textSegments`，`NapCatAdapter` 只构造 text 段。
- `bot_reply` 的 `replyToMessageId` 没有编码为 OneBot reply 段。
- `OneBot11Codec` 只解析 `post_type=message`；at/reply/image/forward 的 `data` 被压缩成文本占位。
- `ChatHistoryMessage` 只有文本；`NapCatAdapter.history` 固定
  `parse_mult_msg=false`、`disable_get_url=true`，并跳过非文本。
- LLBot 实测 `get_group_msg_history` 使用 `message_seq` 作为游标，
  当前适配器却把 `message_id` 当下一游标。
- LLBot 实测 `parse_mult_msg=true` 才返回 forward 段；
  `get_forward_msg` 返回 `data.messages[].content` 数组，不是 `message`。
- LLBot 图片段含 `file/subType/url/file_size`；`get_image` 可用 `file` 取图。
- `BotMcpServer` 启动 `AgentToMcpServer` 时未注入 `ToolAssetResolver`，
  因此 `ToolResult.assetDocIds` 目前不会变成 MCP `ImageContent`。
- Inbox done item 不可 `inbox_view`，通知历史需要 Bot 侧审计，不能依赖 Inbox 正文库。
- 标准 OneBot 11 没有主动添加好友 action；LLBot 文档也只有
  “处理好友申请 / 获取被过滤好友请求”，没有主动发起好友申请。

## 2. 分层

```text
OneBot 11 标准核心
  ├─ 消息段（text/at/reply/image/forward）
  ├─ send_msg / send_private_msg / send_group_msg
  ├─ get_msg / get_forward_msg
  └─ set_friend_add_request
LLBot/NapCat 可选扩展
  ├─ get_group_msg_history / get_friend_msg_history
  ├─ get_image / get_recent_contact / 临时会话
  └─ filtered friend requests
```

- 核心不拼接 CQ Code；所有出站/入站都使用标准消息段数组。
- 历史、媒体、好友过滤等 provider 扩展通过 capability 探测，核心与 Bot 不硬编码 action 名。
- `NapCatAdapter` 暂留兼容名，后续可迁移为 `OneBot11Adapter`；不阻塞本轮。

## 3. 数据形状

### 3.1 消息段

```text
MessageSegment
  index
  type: TEXT | MENTION | REPLY | IMAGE | FORWARD | FILE | FACE | OTHER
  text
  qqId
  targetMessageId
  fileId
  url（只存本地，不返回模型）
  mediaType
  byteSize
  forwardId
  rawType
```

- `text` 字段仍是摘要/正文的快速视图。
- 图片/转发元数据写入 `chat_message_segment` 表；消息正文仍是 `chat_message` 权威。
- URL 只用于程序内部下载，不进入 MCP 返回和日志。

### 3.2 出站

```text
OutboundSegment
  Text(text)
  Mention(qqId)
  Reply(messageId)
  Image(fileId/url)（后续）
```

- 保留现有 `textSegments` 兼容入口。
- 富消息按顺序发送；reply/at 只在第一段，长文本分段不重复。
- `At` 只允许群聊，目标由宿主从被回复消息的 sender 解析，不接受模型任意 QQ。

### 3.3 历史

```text
bot_thread_history(threadId, beforeSeq?, limit=50)
bot_message_thread(messageKey, depth=3)
bot_forward_read(messageKey, depth=2, limit=50)
```

- `bot_thread_read` 继续表示“从 afterSeq 向后读未读上下文”。
- 历史读取使用 `bot.db` 的 `local_seq`；需要索引
  `(thread_id, local_seq)` 和 `(thread_id, message_id)`。
- forward 树有深度、节点数、字符数、图片数上限；循环/缺失 fail-closed。

### 3.4 Inbox 默认上下文

```text
InboxDigest
  threadId
  fromSeq/toSeq
  context: 最近 6 条已处理消息（IN/OUT）
  messages: 本次未处理消息
  summary/priority
```

- `context` 只用于理解语义，不推进 cursor，不计入 unread；
- 上限 6 条 / 1200 字符，超出截断；
- `content.context` 由 `inbox_view` 返回；wake summary 不放大。

### 3.5 通知历史

新增 Bot 侧审计表：

```text
inbox_digest_audit(
  thread_id, item_id, from_seq, to_seq, summary, priority,
  status, revision, created_at, acked_at
)
```

对应 `bot_inbox_history(threadId?, before?, limit=50)`；不把 Inbox 当正文库。

### 3.6 好友申请

```text
FriendRequest(
  requestId, flag, userId, displayName, comment, receivedAt, status
)
```

- `requestId` 是宿主生成的不透明 ID；raw `flag` 只存本地 DB，不返回模型。
- `bot_friend_request_list` / `bot_friend_request_decide(requestId, approve, remark?)`。
- 审批是外部副作用；默认需要显式调用，不自动通过；重复 requestId 幂等。
- 不做主动加好友。

## 4. 失败语义

- 标准 action 返回 retcode/HTTP 错误时保留稳定 provider 错误码；不自动重试外发。
- 历史 `message_seq` 与 `message_id` 不混用；provider 返回顺序在适配层归一化。
- 图片下载限制 MIME/大小/重定向；失败返回稳定 `MEDIA_UNAVAILABLE`，不让工具整体失败。
- `get_forward_msg` 节点缺失/循环/超深返回截断标记，不无限递归。
- 好友审批成功/失败都审计；失败不改本地状态。

## 5. 实施顺序

1. 富消息基础 + At/Reply + replyTo 修复；
2. Inbox context 注入；
3. 历史分页 + 引用树 + forward 树；
4. 图片元数据 + 媒体存储 + MCP ImageContent；
5. 好友申请事件/存储/审批工具；
6. 文档、模拟区 handoff、full gate。

## 6. 验收判据

- mock OneBot HTTP 断言 at/reply/image 段形状和顺序；
- LLBot 历史游标使用 `message_seq`，分页无重复/无遗漏；
- `parse_mult_msg=true` 时 forward 段可见，`get_forward_msg` 树有界；
- 图片经 `ToolResult.assetDocIds` 在 MCP result 中成为 `ImageContent`；
- `inbox_view` 的 context 含最近 6 条 IN/OUT，且不推进 cursor；
- 好友申请 raw flag 不出现在 MCP 返回；审批重复不产生第二次副作用；
- 全仓 `tools/gate.sh full` 与 `tools/gate.sh docs` 通过。

## 7. LLBot 8.3.2 实测记录（2026-10-27）

- `get_version_info`：`app_name=LLOneBot`、`protocol_version=v11`、
  `app_version=8.3.2`。
- `get_msg`：
  - at 段含 `qq/name`；
  - reply 段含 `id`；
  - image 段含 `file/subType/url/file_size`。
- `get_image`：以 `file` 参数调用成功，返回图片 URL 和容器内本地路径；
  宿主只使用受控下载，不把路径或 URL 返回模型。
- `get_group_msg_history` / `get_friend_msg_history`：可用，属于 go-cqhttp
  风格扩展，不是标准 OneBot 11 核心。
- 历史游标：LLBot 返回 `message_seq` 数值；`reverse_order` 当前实测不改变返回
  顺序（仍按时间升序），`message_seq` 是包含式端点。适配器必须使用
  `message_seq`，不能把 `message_id` 当游标。
- `parse_mult_msg=false` 时 forward 段消失；`parse_mult_msg=true` 时出现
  `{"type":"forward","data":{"id":"..."}}`。
- `get_forward_msg`：`data.messages[].content` 是消息段数组，不是 `message`；
  需要独立解析器和递归上限。
- `get_recent_contact` 在 LLBot 8.3.2 返回 404；`bot_qq_recent` 使用
  `get_group_list/get_friend_list + 每个会话最新一条 history` 组合读取。
- LLBot 文档有“处理好友申请 / 获取被过滤好友请求”，没有主动发起好友申请的
  action；标准 OneBot 11 也没有。因此主动加好友不做；`bot_friend_request_*`
  只处理入站申请。

## 8. 新增工具面

```text
bot_reply(..., mentionSender=true?)          # At 被回复消息的 sender
bot_thread_history(threadId, beforeSeq?, limit)
bot_message_thread(messageKey, depth)
bot_forward_read(messageKey, segmentIndex?, depth, limit)
bot_media_get(messageKey, limit)
bot_qq_recent(limit, includePrivate?, includeGroups?)
bot_qq_history(chatType, targetId, cursor?, count?, reverseOrder?)
bot_inbox_history(threadId?, limit)
bot_friend_request_list(limit?)
bot_friend_request_decide(requestId, approve, remark?)
```

- `inbox_view` 的 `content.context` 默认包含最近 6 条 IN/OUT 摘要；
- `bot_qq_recent` 是 OneBot 客户端最近会话，不是 Inbox；
- `bot_qq_history` 是 OneBot/LLBot 历史扩展，不写入本地消息板；
- `bot_inbox_history` 是 Bot 侧审计，不返回正文；
- 所有 raw flag、图片 URL、token 不进入模型可读结果。

## 9. 实施状态（2026-10-27）

- 已实现源码：
  - `OutboundSegment`（Text/Mention/Reply）与 rich `OutboundMessage`；
  - `bot_reply` 的 reply/at 段与 `mentionSender` 宿主解析；
  - 入站 `MessageSegment`、SQLite `chat_message_segment`；
  - `bot_thread_history`、`bot_message_thread`、`bot_forward_read`；
  - `MediaCapability`、`NapCatAdapter.image`、Bot 媒体存储、`bot_media_get`；
  - MCP `ToolAssetResolver` 注入（图片以 ImageContent 返回）；
  - `bot_qq_recent`（客户端最近会话）、`bot_qq_history`（OneBot 历史扩展）；
  - `inbox_view.content.context` 最近 6 条 IN/OUT；
  - `inbox_digest_audit` + `bot_inbox_history`；
  - 入站好友申请事件、审批存储/工具；主动加好友不做。
- 门禁：
  - `tools/gate.sh docs` PASS（modules=5 files=512 links=201）；
  - `tools/gate.sh full` PASS，全仓 **691 tests / 0 failures / 0 errors**
    （connect 45、connect-agent 21、bot 45、taskflow 256、app 324）；
    日志 `.work/rich-full.log`；
  - shaded jar sha256：
    `ab06e4f4605bd940856c9516eaddabde64b1c85b0548af7522a8c8d958d6ded2`。
- 运行中 `simulation2` bot 仍使用旧 jar；尚未停服部署/真实 LLBot 联调。
- 未验证：真实 QQ 入站/外发、真实图片 MCP ImageContent、真实好友申请审批、
  真实嵌套 forward 样本、长跑。

## 10. view-ack 重放/归档竞态修复（2026-10-27 01:36）

### 10.1 复核结论

运行中的旧 jar 确实是报告 A 的直接原因：`javap` 显示旧
`InboxDigestService.markHandled` 在 `archive()` 后调用 `states.clear(threadId)`。
新源码改为 `cursorOnly(threadId,lastSeq)`，但旧 jar 未部署时问题仍存在。

报告 B/C 是独立的可变 item 设计问题：`flushThread` 在 active item 上原地 update，
新消息推进 revision；`inbox_view` 与 `bot_thread_ack` 之间没有冻结，导致 CAS
永远追不上，wake summary 与正文漂移。

### 10.2 修复

- **active item 冻结**：thread 有 available item 时，`flushThread` 不再原地更新
  item，只同步旧 state 与补齐空洞；新消息留在消息板，等 ack 后重新发布。
- **ack 后立即发布下一版**：`archiveAndCursor` 保存 `cursorOnly` 后调用
  `flushThread`，把冻结期间到达的消息生成下一版 item。
- **snapshot ack**：`bot_thread_ack` 新增 `snapshotToSeq`，使用同一次
  `inbox_view` 的 `content.toSeq`；item revision 即使变化，也按 snapshot
  消费，剩余消息保留（legacy item 的 partial ack）。
- **旧空洞修复**：启动时 `flushAll` 会检查 `state.lastSeq` 与 item
  `fromSeq`，发现 `fromSeq > lastSeq+1` 时先按消息板补齐 item，再冻结；
  避免部署后 ack 跳过 602-605 这类空洞消息。
- **稳定错误**：新增 `SNAPSHOT_STALE` / `SNAPSHOT_AHEAD`；无 snapshot 的旧调用
  仍保留 `ITEM_REVISION_CONFLICT` 兼容。
- **D 旧 claim**：当前 `inbox_claim` 为空、`claimed=0`；历史 claimed 事件已由
  lease 过期清理。Bot 不持有 claim token，不能自动 release；view-ack 继续
  fail-closed，等待 lease 到期，不添加 claim 绕过路径。

### 10.3 验证

- `tools/gate.sh full` PASS：全仓 **697 tests / 0 failures / 0 errors**
  （connect 46、connect-agent 21、bot 49、taskflow 256、app 325）；
  日志 `.work/consistency-full.log`。
- `tools/gate.sh docs` PASS。
- shaded jar sha256：
  `8cbd348cd3d2f81254029187daf559f06781ad7054c2341680c752bbc2614162`。
- 已部署到 `simulation2/dist`，启动 smoke：
  - Bot MCP 33 tools；
  - 启动后 7 个 thread 各生成 1 个 available item，`claimed=0`；
  - 对 `itm_8fd...` 连续两次 `inbox_view`（间隔 6 秒）revision 均为 739、
    content 仍为 `734..734`，item 未被新消息改写。
- 备份：
  - 首次冻结部署前：`simulation2/dist/iibot-app-0.1.0-SNAPSHOT.jar.backup-20261027-013609-pre-inbox-freeze`
    / `simulation2/runtime.backup-20261027-013609-pre-inbox-freeze`；
  - 最终部署前：`simulation2/dist/iibot-app-0.1.0-SNAPSHOT.jar.backup-20261027-013907-pre-final-freeze`
    / `simulation2/runtime.backup-20261027-013907-pre-final-freeze`。

## 11. 正文一致性、OUT 富段与旧图片回退（2026-10-27 01:57）

### 11.1 根因

- `PlayerFacingTextGuard` 在 `OutboxSender` 发送时才执行，但
  `BotReplyService` 在此之前已把原始正文写入 `chat_message`；
- guard 可能删行或返回 fallback，导致本地正文与实际 QQ 正文不同；
- `bot_thread_history` 读本地，`bot_qq_history`/`bot_qq_recent` 读 OneBot，
  因而出现同 messageId、同时间、不同正文；
- 旧图片消息只有 `non_text_summary=[图片×1]`，没有 `chat_message_segment`；
  `bot_media_get` 只查本地 segment，直接返回 `MEDIA_NOT_FOUND`。

### 11.2 修复

- 新增 `BotReplyTextNormalizer`，`BotRuntime` 注入
  `PlayerFacingTextGuard.normalizeForChat/normalizeForPost`；
- `BotReplyService` 先规范化，再写 `chat_message`、再发送；
- `BotReplyService` 同时把 `REPLY/MENTION/TEXT` 段写入 `chat_message_segment`；
- `MediaCapability.imageSegmentsForMessage(messageId)` 默认空，`NapCatAdapter`
  通过 `get_msg` 解析图片段；
- `BotMediaGetTool` 本地无 IMAGE segment 时按 messageId 回退，结果标记
  `source=onebot_get_msg`，不向模型暴露 URL。
- 一次性修复历史 OUT 正文 10 条：8 条 guard fallback、2 条删行；修复后
  dry-run 为 0 changes。

### 11.3 验证

- `tools/gate.sh full` PASS：全仓 **697 tests / 0 failures / 0 errors**
  （connect 46、connect-agent 21、bot 49、taskflow 256、app 325）；
  日志 `.work/consistency-full.log`。
- 新 jar sha256：
  `8cbd348cd3d2f81254029187daf559f06781ad7054c2341680c752bbc2614162`。
- 启动 smoke：旧图片 `1472304806` 通过 `get_msg` 回退成功，
  `source=onebot_get_msg`、`image/jpeg`、`size=21561`。
- 历史正文修复后对 72 条 OUT 再跑对账：**0 changes**。
- 引用/@ 真实外发仍未测试，等待用户指定目标。

## 12. 来源权重路由（2026-10-27 03:00）

### 12.1 核心模型

- `ChatSourceKey` + `ChatThread.threadId` 作为通用来源；
- `SourceWeightPolicy` 保存 `sourceWeight`、`notifyThreshold`、结构信号权重、
  关键词权重、quiet/cooldown、首次引导状态和 revision；
- `MessageWeightDecision` 保存每条消息的 source/signal/keyword/total score、
  wakeReason、matchedSignals/Keywords、actionable 和 policy revision；
- 表：`source_weight_policy`、`chat_message_weight`。

计分：

```text
totalScore = clamp(
  sourceWeight
  + max(matched structural signal weights)
  + sum(matched keyword weights),
  -1000, 1000)

actionable = bootstrap || totalScore >= notifyThreshold
priority   = totalScore
```

### 12.2 默认行为

- 私聊：`sourceWeight=300`，阈值100，DM 信号200，普通私聊为500；
- 群聊：`sourceWeight=0`，阈值100；普通群聊0不生成 item；
- 群聊 `MENTION=200`、`REPLY_TO_BOT=180`、`NAME=120`、`COMMAND=250`；
- 关键词默认不内置，Agent 可通过 `bot_source_weight_set` 设置正负权重；
- 新来源首次消息强制生成 `FIRST_CONTACT` item，之后按权重策略生效；
- 已有 thread 启动时回填策略并标记 `bootstrapDelivered=true`，避免旧群全量引导。

### 12.3 Inbox 排序

无需隔壁 Inbox 改权重字段：`ItemSummary/PushItem/UpdateItem` 已有 `priority`，
`SqliteInboxStore.listAvailable()` 使用：

```sql
ORDER BY priority DESC, created_at ASC
```

Bot 把 `totalScore` 写入 `InboxDigest.priority`，DSH summary 读取 `/items`
的返回顺序，因此高权重 item 会先显示。修改来源权重时通过
`InboxDigestPort.updatePriority` 可更新当前 active item 的 priority。

### 12.4 新 Inbox lib

- 本地 `inbox-mcp-tools-client` 新版本包含 `inbox_rest`；
- `BotInboxRuntime` 通过 `InboxClientMcpTools.all()` 自动获得；
- 当前生产工具面 37 个，Inbox 客户端工具 16 个 + descriptor；
- `inbox_rest` 是 session/binding 休息提示，不修改 item。

### 12.5 验证

- `tools/gate.sh full` PASS：全仓 **705 tests / 0 failures / 0 errors**
  （connect 46、connect-agent 21、bot 55、taskflow 256、app 327）；
  日志 `.work/weight-full2.log`。
- 新 jar sha256：
  `0d25749e0f790e40195408fe4c7d8398bc07b86671ae95f0a1ccff6bb6ffbf74`。
- 运行态已迁移 7 个现有 thread；实测群 sourceWeight 0→500 后 active item
  priority 0→500，恢复 0 后回到 0。
- E2E：首次来源 `FIRST_CONTACT`，ack 后普通群聊不生成 item，@ 消息生成
  `wakeReason=MENTION`、`totalScore=200`。
- 引用/@ 真实外发仍等待目标授权。
