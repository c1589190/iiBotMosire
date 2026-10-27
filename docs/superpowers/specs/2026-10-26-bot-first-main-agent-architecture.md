# Bot-first QQ 消息板与外部主 Agent 架构

> **状态**：提议（2026-10-26；用户方向已明确，红线措辞、旧模块处置与摘要生命周期待最终确认）
> **日期**：2026-10-26
> **用户原话**：
> > 「1 我不是用户？以前的我管用还是现在的我管用」
> > 「2 是的，所以是大修改，让一个外部Agent能看清所有东西」
> > 「3 是全部消息写摘要入inbox给大Agent看，大Agent挑选恢复」
> > 「4 搞个用户备注机制得了，可以像加标签一样给用户加备注优先从inbox看， 纯文游语义直接取缔；」
> > 「Agentlib、Inbox包都更新了，你应该先规划新的架构实现，然后先更新依赖再写代码」
> > 「开始规划吧，有裁定和我说」
> **本文取代**：在 **bot core / 默认运行** 范围内，取代 D-011 的每玩家秘书、D-012 的宿主侧文游硬确认、
> D-014 的秘书 cache、D-016 的秘书提示词与旧目标运行设计中的“玩家秘书/GM 子 Agent”拓扑。
> 旧文档继续保留为历史；是否物理删除旧模块另见 §12 的待裁定项。
> **不取代**：D-001 Adapter 可替换、D-006 Java/Maven、D-007 三库优先、D-008 外部 MCP 方向、
> D-013 受控外部控制、D-019 外发节奏；这些作为通用接入/安全/投递约束继续有效。

## 1. 结论与边界

本项目默认运行从“文游回合制任务流”改为：

```text
QQ 私聊/群聊
    -> iibot-connect 统一入站事件
    -> iibot-bot 消息板（权威原文 + 来源线程 + 用户备注）
    -> iibot-bot Inbox 摘要（每条消息的 bounded summary）
    -> 外部主 Agent（DSH）读取摘要、挑选恢复、通过 MCP 回复
    -> iibot-bot 出站路由 + 三态回执 + 入板
```

**文游是外部主 Agent 的一层 Skill，不再是 Java 宿主的默认领域语义。**
Bot core 不得出现 `Player`、`Director`、`Secretary`、`Turn`、`DecisionDraft`、`ActionPool`、
`Ticket`、`Publication` 等默认业务对象。

### 1.1 目标

1. 一个 QQ Bot 账号的私聊、群聊文本消息全部进入本地消息板；非文本消息以可识别占位/段摘要入板。
2. 同一 QQ 来源（私聊对象或群）在消息板上表现为一个 thread；同一来源的消息不与其他来源混合。
3. 每条入站消息产生一条 bounded summary，汇总进 Inbox 的“来源大框”；
   主 Agent 先看 summary，再通过工具恢复原文/上下文。
4. 一个外部主 Agent（受信任 supervisor）拥有全量可见性，能读取所有消息、thread 与用户备注。
5. 用户备注/标签是一等通用能力；标签没有宿主内置含义，优先级用于 Inbox 排序和提示。
6. 主 Agent 回复必须绑定 thread，由宿主反解目标；不允许模型从聊天内容自行提升身份或选择任意目标。
7. AgentLib / InboxMosire 先升级并验证，再开始 bot core 编码。

### 1.2 非目标

- 不恢复每玩家模型上下文隔离；私聊只保证投递隔离，不保证“主 Agent 不知道其他私聊”。
- 不在 bot core 中实现文游角色、回合、确认、裁定、发布状态机。
- 不做自动回复所有群消息；入库、摘要、唤醒、回复是四个独立策略。
- 不解析图片/语音/文件内容；首版只保存类型/数量占位，不下载资源。
- 不承诺跨机器/公网 Inbox；继续使用回环 MCP + 0600 token。
- 不把 Inbox 当作永久消息正文库；正文权威仍在本地消息板。

## 2. 拓扑与模块

### 2.1 目标模块

| 模块 | 职责 | 依赖 |
|---|---|---|
| `iibot-connect` | QQ 协议接入、统一入站事件、发送、三态回执 | Jackson、SLF4J |
| `iibot-connect-agent` | 通用 AgentTool 适配（保留；不再承载文游语义） | connect + AgentLib |
| **`iibot-bot`**（新增） | 消息板、thread、摘要、用户备注、回复路由、Bot MCP 工具、Inbox 端口 | connect + AgentLib |
| **`iibot-bot-app`**（新增，过渡名） | bot-first 组合根：配置、SQLite、NapCat、Inbox embedded、MCP 生命周期 | `iibot-bot` + Inbox 客户端 |
| `iibot-taskflow`（legacy） | 旧文游领域：回合/行动/裁定/发布；不进入 bot 默认装配 | 保持旧依赖，移出默认路径 |
| `iibot-app`（legacy） | 旧文游组合根；只作为回滚/历史证据保留，最终归档 | `iibot-taskflow` |

> 过渡期先新增 `iibot-bot` / `iibot-bot-app`，不改旧 app 的编译事实。
> bot 路径验收通过后，再把可运行入口切到 bot app；旧 app 的物理移动/删除由用户确认。

### 2.2 bot core 不依赖旧文游

`iibot-bot` 不得依赖 `iibot-taskflow`。它只拥有以下通用概念：

```text
BotAccount
ChatSourceKey
ChatMessage
ChatThread
UserNote
MessageSummary
InboxDigest
OutboundReply
```

这些概念不包含玩家、推演、秘书、回合或世界事实。

## 3. 数据所有权

### 3.1 权威边界

| 数据 | 权威位置 | 说明 |
|---|---|---|
| QQ 原始消息/段摘要 | `bot.db` 的 `chat_message` | 不可变，唯一键持久去重 |
| 来源线程投影 | `bot.db` 的 `chat_thread` | 可按来源聚合、分页、未读游标 |
| 用户备注/标签 | `bot.db` 的 `user_note` | 可按 QQ 用户跨私聊/群聊复用 |
| Inbox 摘要 item | InboxMosire `inbox.db` | 派生通知；summary/content 只放摘要和恢复引用 |
| 外部 Agent 的操作历史 | bot MCP 审计/消息表 | 回复、备注更新、ack 必须可追踪 |
| 旧世界/回合/工单 | legacy `events.db` | 不再进入 bot core 默认路径 |

### 3.2 `ChatMessage`

```text
messageKey        稳定键：accountId + sessionType + groupId/targetId + messageId
accountId         Bot 账号
chatType          PRIVATE | GROUP
targetId          私聊对方 QQ / 群号
threadId          宿主生成的不透明来源 ID
direction         IN | OUT
senderId          发送者 QQ
senderName        最近一次昵称/群名片
messageId         QQ 协议消息 ID
localSeq          SQLite 单调序号，消息板排序唯一依据
sentAt            外部时间，可空
receivedAt        本机接收时间
text              纯文本，可空
nonTextSummary    非文本段占位，例如 “[图片×1][文件×1]”
replyToMessageId  可选
deliveryState     仅 OUT：SENT / FAILED_BEFORE_SEND / UNKNOWN
idempotencyKey    仅 OUT
```

- `messageKey` 必须持久唯一；adapter 的内存去重不能作为跨重启唯一性保证。
- 排序以 `localSeq` 为准，不以 `sentAt` 为准；同一毫秒/无时间戳不破坏顺序。
- 群聊 thread 以 `(accountId, GROUP, groupId)` 为来源；群内不同 sender 保留各自 `senderId`。
- 私聊 thread 以 `(accountId, PRIVATE, peerQq)` 为来源。
- Bot 自己的出站消息也写入 thread；是否需要为出站消息产生 Inbox 摘要见 §8，默认不产生，避免自唤醒。

### 3.3 `ChatThread`

```text
threadId
accountId
chatType
targetId
displayName        群名/私聊备注或最近昵称
lastSeq
lastMessageAt
messageCount
```

### 3.4 `UserNote`

`UserNote` 绑定 `(accountId, qqId)`，不是 thread；同一个用户在不同群和私聊中共享备注。

```text
accountId
qqId
displayName        最近观察到的展示名；不覆盖人工备注
tags               List<String>，原样保存，宿主不解释
note               自由文本
priority           整数，越大越优先；用于 Inbox 排序
revision           乐观锁
updatedAt
```

标签没有内置语义。`player`、`gm`、`vip` 等只是操作者自己写的字符串；宿主不得据此改变工具权限。

## 4. 入站链路

```text
NapCatAdapter
  -> OneBot11Codec.decode...
  -> InboundEnvelope
  -> MessageBoardService.recordInbound()
      1. 持久去重
      2. 事务写 chat_message + 更新 chat_thread
      3. 读取 sender/thread 的 UserNote
      4. 生成 MessageSummary
      5. 更新该 thread 的 Inbox digest item
  -> Inbox revision change -> DSH wake
```

### 4.1 Adapter 需要补齐的中立字段

当前 `InboundMessage` 只有 `text`，且群聊在 app 业务层被丢弃。首版升级为通用 envelope：

- `senderName`（群名片优先、昵称兜底）；
- `nonTextSummary`（图片/文件/语音/表情等只记类型和数量，不下载）；
- `replyToMessageId`（若 OneBot 提供）；
- `mentionedSelf`（群消息是否 @Bot，用于摘要提示，不影响身份）；
- `messageType` / `subType` 可选诊断字段；
- 无文本但有非文本段的消息仍然入板，不能因 `text.isEmpty()` 直接丢弃。

连接层仍不得出现游戏语义。QQ 私聊/群聊、发送者、群号、消息 ID 是平台事实；玩家/角色/回合不是。

### 4.2 去重与顺序

- adapter 内存去重保留为快速路径；
- `chat_message.message_key` 唯一约束是跨重启的最终去重；
- `chat_thread` 的串行粒度是 `threadId`，不是 `userId`；同一群内不同发送者必须保持同一顺序；
- 入站事务成功后才更新 Inbox；Inbox 失败不回滚消息板状态，下一次摘要重试；
- 事务失败不得向 QQ 回复“已收到”伪装成功。

## 5. 消息摘要与 Inbox“来源大框”

### 5.1 摘要内容

每条入站消息生成一条确定性摘要，不依赖 LLM：

```text
[类型] senderName [标签/备注]：正文前 N 字 [非文本占位]（+ 同来源更多消息计数）
```

- summary 上限建议 200–512 字符；换行/控制字符压平；
- 全文永远留在 `chat_message`，summary 不承担事实源职责；
- 长文本截断必须带明确标记；
- 用户备注、标签和 priority 参与摘要头部与 Inbox item priority，但不得改写原始消息。

### 5.2 一来源一个 Inbox item

同一 `threadId` 只维护一个 active digest item：

```text
channel       qq
kind          dm | group
dedupeKey     qq-thread:<threadId>:active
summary       最近一条消息的 bounded summary
content       JSON：threadId + fromSeq/toSeq + 未处理消息 summary 列表
contentRef    可选：message-board://thread/<threadId>
priority      max(0, 当前 digest 中最高用户 priority)
```

`content` 示例：

```json
{
  "threadId": "thread:...",
  "chatType": "GROUP",
  "targetId": "123456",
  "fromSeq": 1001,
  "toSeq": 1008,
  "messages": [
    {
      "seq": 1001,
      "messageId": "qq-msg-1",
      "senderId": "20001",
      "senderName": "张三",
      "tags": ["盟友"],
      "note": "负责后勤",
      "summary": "张三：前线缺粮，能否今晚补给？",
      "sentAt": "2026-10-26T12:00:00Z"
    }
  ],
  "restore": "bot_thread_read"
}
```

- summary 只用于扫描/选择；content 是有限的 digest manifest；完整正文用 `bot_thread_read` / `bot_message_get` 恢复。
- 一个 thread 的连续消息合并为一个大框，不把群内不同发送者拆成多个 thread。
- 如果 digest 超过条数上限，保留边界和 `fromSeq`，较早消息仍可从消息板分页读取。

### 5.3 生命周期

- 入站消息到达时，宿主在 `threadId` 锁内更新/追加 digest；
- 主 Agent 先 `inbox_list` 看 summary，再 `inbox_view` 看 digest，不要求 claim；
- 主 Agent 处理完调用 bot 侧 `bot_thread_ack` 归档；ack 只允许 available item，
  成功后保留该 thread 已处理到的 `lastSeq`，不得把游标清回 0；
- 若主 Agent 正在查看/回复期间又有新消息，item revision 变化使旧 ack 失败，
  返回 `ITEM_REVISION_CONFLICT`，必须重新 `inbox_view`；
- 已归档 item 不物理删除，Inbox 保留 done 审计；新消息从保留的 cursor 之后
  重新创建 active digest，不得重放已处理历史；
- Inbox 摘要失败不回滚消息入库；下一次扫描/重建按 thread 的 `lastDigestedSeq` 重试。

## 6. 外部主 Agent 与 MCP

### 6.1 主 Agent 身份

- 一个外部 DSH 主 Agent，使用一个 program MCP token，身份固定为 `bot-supervisor`；
- 宿主不提供按玩家/按群的 MCP 身份，也不把聊天内容当身份提升；
- 主 Agent 可看见全部 bot 可访问的私聊、群聊、thread、用户备注和摘要；
- 工具授权仍是宿主强制的：token、回环、工具白名单、资源范围、幂等、外发 ledger 不变。

### 6.2 首版工具面

```text
bot_status
bot_thread_list
bot_thread_read
bot_message_get
bot_reply
bot_thread_ack
bot_user_note_get
bot_user_note_list
bot_user_note_upsert
inbox_*                     # 15 个客户端工具
bot_inbox_binding_descriptor
```

保留为高风险、默认关闭的独立工具（不属于普通回复路径）：

```text
bot_send_private_raw
bot_send_group_raw
```

### 6.3 `bot_reply`

参数：

```json
{
  "threadId": "thread:...",
  "replyToMessageId": "qq-msg-1",
  "text": "回复正文",
  "idempotencyKey": "稳定键"
}
```

- 目标由宿主从 `threadId` 反解，模型不能传任意 QQ 号/群号；
- 出站走统一 Outbox、150ms 节奏、三态回执、delivery ledger；
- `SENT` 后把 outbound message 写入同一 thread；
- `FAILED_BEFORE_SEND` 记录失败，不写入伪造的已发消息；
- `UNKNOWN` 记录不确定态，禁止自动重发；
- 回复后是否让 Inbox 摘要更新由宿主策略决定，默认不因 Bot 自己的回复唤醒主 Agent。

### 6.4 `bot_thread_read`

参数：

```json
{
  "threadId": "thread:...",
  "afterSeq": 0,
  "limit": 50,
  "includeOutbound": true
}
```

返回按 `localSeq` 排序的完整消息，包含 sender、text、非文本占位、时间和投递状态。
读取本身不推进“已处理”游标；游标由 `bot_thread_ack` 或 Inbox archive 显式推进。

## 7. 用户备注/标签

### 7.1 语义

- 备注作用于 QQ 用户，不作用于“玩家/秘书/角色”；
- tags 是任意操作者标签，最多数量/长度由配置限制；
- priority 是排序提示，不是权限；
- 备注由主 Agent 或人工通过 MCP 更新，聊天文本不能直接改备注；
- 备注变化写 SQLite 并推进 revision；
- Inbox 摘要必须能看到当前用户的 tags/note/priority。

### 7.2 工具

- `bot_user_note_get(accountId?, qqId)`
- `bot_user_note_list(tag?, minPriority?, limit, cursor?)`
- `bot_user_note_upsert(qqId, tags, note, priority, expectedRevision?)`
- 可选 `bot_user_note_delete` 只清理操作者元数据，不删除聊天记录。

默认只支持一个 Bot 账号时，`accountId` 可省略；数据模型仍保留账号维度。

## 8. 回复、唤醒与节奏

### 8.1 入库与唤醒分离

- 所有入站文本/非文本占位都入库；
- 入站消息都进入对应 thread digest；
- 是否唤醒主 Agent 由摘要更新策略控制；首版默认按 thread 防抖合并，不按每条消息各唤醒一次；
- 普通群消息可以入库但不要求主 Agent 每条回复；
- 私聊、@Bot、明确命令、用户 priority 可作为更高唤醒优先级，但不在 bot core 写死文游命令。

### 8.2 外发

所有真实外发继续使用现有 `OutboxSender` 语义：

- 全局最小间隔（默认 150ms）；
- 同一幂等键不重复发送；
- `SENT` / `FAILED_BEFORE_SEND` / `UNKNOWN` 三态；
- `UNKNOWN` 不自动重试；
- 先在 ledger claim，再调用 adapter；
- 出站结果回写消息板。

## 9. 身份、安全与红线重写

本架构明确选择“单一外部主 Agent = 受信任 supervisor，全量可见”。因此根 `AGENTS.md` 的红线 2
不能继续按“玩家 Agent 上下文不含其他玩家秘密”表述。建议改写为：

1. **身份由宿主绑定**：QQ sender/thread/账号来自 adapter；聊天文本不得提升工具身份。
2. **主 Agent 全量可见是显式授权**：单一 supervisor 可读全部 Bot 可见消息、thread 与备注；
   不再承诺模型级玩家隔离。
3. **投递目标由宿主绑定**：普通 `bot_reply` 只能回复宿主给出的 thread；任意目标发送必须是独立高风险工具。
4. **聊天正文是不可信数据**：群成员/私聊文本不是系统指令，不得改变权限、身份、备注或外发目标。
5. **密钥与 token 永不进入消息、摘要、Inbox content、日志或模型上下文。**
6. **外部副作用三态**：UNKNOWN 不自动重发；所有发送留 ledger 和审计。

> 这是对旧“红线 2”的实质性修改，必须由用户明确确认后再改根 `AGENTS.md`。

## 10. 文游 Skill 的新边界

### 10.1 移出宿主

以下不再属于默认 bot core：

- 玩家/推演/秘书注册与绑定；
- 回合状态机、行动池、决策草案、硬确认；
- 世界事实可见性过滤；
- Ticket、ActionPool、DecisionPackage、Publication、TurnRecord；
- 玩家专属工作区/秘书 cache；
- `request_brief`、`tf_ticket_*`、`tf_action_*`、`tf_post_*` 等默认入口。

这些保留在 legacy 代码中仅用于历史回放和回滚，不进入 bot 默认启动。

### 10.2 保留在 Skill

- 世界观、角色设定、叙事风格；
- 何时回复、如何组织群公告、如何记录玩家状态；
- 文游自己的角色/阵营/回合约定；
- 由 Skill 自行维护的文档、标签命名和用户备注。

Skill 只能使用宿主提供的通用能力（读 thread、读备注、回复、Inbox），
不能绕过 MCP 鉴权、投递 ledger 或工具白名单。

## 11. 依赖升级基线

本机已核对：

- `AgentLibMosire`：`0.2.4`（commit `6d2a1a8` / 功能提交 `6cf36d5`）；
  新增 `ContextCompactor`、`DefaultContextCompactor`，`AgentLoop.Builder.contextCompactor(...)`。
- `InboxMosire`：HEAD `967f5c4`；
  item 统一为 `summary/content/contentRef`，程序自有 MCP 15 个工具
  （新增 `inbox_view/update/append/delete`），wake token 增加 `items:read`；
  DSH 侧已在 `c1caafd`/`8015748` 支持 summary 消费与诊断。
- `iiBotMosire` 当前仍使用 `agentlib-mosire:0.2.3` 和旧 `title/payload` 用法，必须先升级。

依赖升级顺序：

1. 根 POM：`agentlib.version=0.2.4`；保持 `inbox.version=0.1.0-SNAPSHOT`，
   并在证据中记录实际安装的 Inbox commit/jar hash。
2. 重新安装 `/home/cna/InboxMosire`，确认所有 15 个工具类可用。
3. 将 `InboxNotificationBridge` 从 `title/payload` 迁移到 `summary/content`。
4. 更新 `InboxHostRuntime` 工具数/注释/测试为 15 个客户端工具 + descriptor。
5. 用 `tools/gate.sh focused iibot-app test` 验证升级，不先写 bot core。
6. 若保留任何内部 Agent，使用 AgentLib 0.2.4 的 `contextCompactor` 注入点；
   bot core 本身不运行内部主 LLM，不强行引入压缩器。

## 12. 迁移与旧模块处置

建议采用 strangler 迁移：

1. 新 `iibot-bot` / `iibot-bot-app` 先独立通过测试；
2. 默认启动切换为 bot app；
3. `iibot-taskflow` / `iibot-app` 保持可编译但标记 legacy，不进入默认 classpath；
4. 旧 `secretary` / `ticket` / `turn` 测试不再作为 bot 验收前置；
5. 旧 Skill 归档，不直接删除历史；
6. 待 bot 路径真实 smoke 通过后，再由用户确认是否物理删除旧模块、迁移历史事件和清理数据。

当前 `events.db` 里还有 `EventVisibility.SECRETARY` 等历史事件；在决定物理删除前必须保留读取兼容。

## 13. 不变量与失败语义

1. 消息唯一键重复：不重复入库、不重复摘要、不重复唤醒。
2. Inbox 更新失败：消息仍入库，thread 的 `lastDigestedSeq` 保留，后续重试。
3. 摘要与消息正文不一致：正文以 `chat_message` 为准。
4. Bot 自己的出站消息：不产生自我唤醒；UNKNOWN 不得写成 SENT。
5. 主 Agent 读取不产生副作用；ack/archive 才推进处理状态。
6. 任意发送工具失败不得回退到普通 `bot_reply` 或换目标重试。
7. 用户备注更新不得删除聊天记录；标签不得转化为工具权限。
8. Inbox item claim/update/delete 冲突：返回 `ITEM_STATUS_CONFLICT` 或
   `ITEM_REVISION_CONFLICT`；主 Agent 按 code/details 重新读取或停止，不丢消息；
   不得因一次冲突为同一 thread 再建第二个 available digest。
9. 所有 token/密钥/完整原始 OneBot JSON 不进入摘要、content、日志或 MCP 返回。
10. Bot core 不依赖 legacy 文游模块，不起 LLM 也不会因游戏配置缺失而拒绝启动。

## 14. 验收判据

### 14.1 依赖升级

- `agentlib-mosire:0.2.4` 解析成功；
- Inbox client 暴露 15 个工具，app 启动 `tools/list` 数量与实现一致；
- 旧 `title/payload` 不再作为新代码主路径；summary/content round-trip 成功；
- focused gate 通过，未用旧测试数字冒充新基线。

### 14.2 消息板

- 私聊和群聊消息都能入库；群内不同 sender 不混 thread；
- 无文本但有图片/文件的消息以占位入板；
- 重启后重复 messageId 不重复入库；
- 同来源多条消息按 `localSeq` 稳定排序；
- outbound SENT/UNKNOWN 状态可区分。

### 14.3 Inbox

- 同一 thread 只维护一个 active digest；
- `inbox_list` 只见 summary；`inbox_view` 见 digest content；
- digest 中每条入站消息都有 summary 和 messageId；
- 新增消息推进 revision；旧 revision 的 archive 被拒，并返回
  `ITEM_REVISION_CONFLICT` + expected/actual；
- ack 成功后保留 thread cursor；下一条消息生成的 digest 只包含未处理 seq；
- 主 Agent 可选择恢复 full thread 并回复；
- 失败/UNKNOWN 不产生虚假已发送摘要。

### 14.4 用户备注

- 同一 QQ 用户跨私聊和群聊读取同一备注；
- tags/note/priority 出现在 Inbox 摘要；
- priority 影响 item 排序但不改变工具权限；
- 聊天文本不能自行修改备注。

### 14.5 Bot-first 启动

- 无玩家、无推演、无世界事实、无回合配置时仍能启动 Bot；
- 不加载 secretary/turn/ticket/publication 工具；
- MCP 只有 bot 通用工具 + Inbox 工具；
- 旧 legacy 模块不进入默认依赖图；
- 无 `--mode` 时默认 bot；legacy 必须显式 `--mode legacy`。

### 14.6 多配置源

- 根 `config/bot/*.json` 可直接作为默认 bot 配置；
- `--config` 可重复，值可以是文件或目录；
- 目录源按文件名升序读取直接子级 `*.json`；
- 多源对象递归合并，数组/标量由后文件覆盖；
- 多源必须共享同一配置目录，相对路径以该目录为基准；
- 配置仍只保存 secret 的环境变量名，不保存 raw token；
- `inbox.apiHost/apiPort/mcpHost/mcpPort/mcpPath/heartbeatMillis/
  claimSweepIntervalMillis` 透传给 inbox-embedded；host 仅允许 loopback，
  port=0 表示随机端口，固定端口冲突 fail-closed；
- 默认 `config/bot/` 无 JSON 时首次启动生成四个模板后退出，不启动 QQ/Inbox/MCP；
  第二次启动才按配置运行。

## 15. 未定项 / 需要用户裁定

1. **旧代码处置**：bot 验收后，`iibot-taskflow`/`iibot-app` 是保留为 legacy 可回滚，还是一次性物理删除？
   推荐先归档、后删除。
2. **红线 2 重写**：是否批准 §9 的“单一受信任主 Agent 全量可见、私聊不保证模型隔离”措辞？
3. **摘要粒度**：一来源一个 Inbox 大框（本文推荐）是否符合“全部消息写摘要”的原意；
   还是希望每条消息各占一个 Inbox item？
4. **群聊唤醒**：默认是否所有群消息都唤醒主 Agent，还是摘要都入 Inbox、但只对私聊/@Bot/高优先级用户唤醒？
   本文推荐“全部入摘要，按 thread 防抖唤醒，不自动逐条唤醒”。
5. **原始消息保留期**：永久保留，还是设置按天数/容量的保留策略？本文默认永久保留，需在部署配置中显式设置上限。
6. **非文本消息**：首版只做占位是否足够；图片/文件是否需要下载/解析，另立设计。

## 16. 修订记录

- 2026-10-27：增加首次无配置启动的模板生成与退出流程；生成后不启动 QQ/Inbox/MCP，
  第二次启动才进入运行。
- 2026-10-27：接入 InboxMosire embedded 参数化 API（`6013cde` / `f93e7e2`）；
  Bot 配置增加 Inbox host/port/path/heartbeat/sweeper，并保持回环与 fail-closed 约束。
- 2026-10-27：增加默认 bot 启动与多配置文件合并；新增
  `config/` 目录约定，并把 Inbox embedded 参数化初始化作为下一项上游依赖。
- 2026-10-27：按 InboxMosire 的 `ITEM_STATUS_CONFLICT` /
  `ITEM_REVISION_CONFLICT` 契约收口 view-ack 生命周期；明确 ack 保留 cursor、
  冲突不得生成第二个 active digest。
- 2026-10-26：首次提出 bot-first 消息板 + 外部主 Agent 架构；记录用户对秘书机制、全量可见、
  Inbox 摘要/恢复、用户备注和依赖升级的当前裁定。
