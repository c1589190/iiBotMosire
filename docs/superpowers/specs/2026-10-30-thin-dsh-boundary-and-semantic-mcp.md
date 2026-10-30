# 薄 DSH 边界与语义化 QQ MCP

> **状态**：有效设计；2026-10-30 开始实施第一阶段（iibot read/send 语义）。
> **来源**：用户对 DSH 对接层体量与 iibot MCP 产品面的连续裁定。
> **关联**：[统一 QQ Agent 客户端架构](2026-10-28-unified-qq-agent-client-architecture.md)、
> [Compact MCP 结果](2026-10-30-inbox-inline-and-compact-tool-results.md)。

## 1. 用户原话与裁定

> 「能不能让DSH对接层的代码尽可能少，让iibot提供的MCP功能天然强大且健壮」

> 「我更好奇iiBotDSH不是最初只是一个提供人设预设的小插件补丁吗，为啥还要塞一堆工具进去」

> 「明确一下两个项目的界限，然后开始优化iibot」

据此确定：

1. **DSH 对接层是宿主传输面，不是 QQ 业务面。**
2. **iiBotMosire 是唯一 QQ 业务事实源和 MCP 产品面。**
3. `iiBotMosire-DSH` 不实现、不代理、不调用 `qq_*` 工具；最终只保留人设、
   压缩重锚、外发 guard、子 Agent 限制等 DSH 专属能力。
4. 当前已有的 `@mosire/dsh-iibot-wake-profile` 是过渡兼容件，不再扩展新的
   QQ 业务语义；等 generic view-ack 经真实会话验证后退役。
5. iibot MCP 通过工具名、description、输入约束和返回形状自解释工作流，
   不让 DSH 的唤醒模板成为第二份业务契约。

## 2. 两个项目的边界

### 2.1 iiBotMosire

`iiBotMosire` 负责：

- OneBot 11 / NapCat / LLBot 接入与多账号路由；
- 消息板、thread、历史、媒体、未读和发送三态；
- `qq_*` MCP 工具面、工具描述、输入校验、错误码和幂等；
- 读取/回复/ack 的业务状态机；
- Inbox digest 摘要和唤醒发布策略；
- Agent 可见的 `source`、`messageId`、`localSeq` 语义。

`iiBotMosire` 不负责：

- DSH session 生命周期、SSE 断线重连、宿主 idle 判断；
- DSH UI、预设、人设注入或子 Agent 权限；
- 把 Inbox item/revision/snapshot 暴露给模型。

### 2.2 iiBotMosire-DSH

`iiBotMosire-DSH` 负责：

- DSH 预设和 `PERSONA.md` 注入/压缩重锚；
- 单角色扮演模式的外发 guard；
- `subagent_custom` 等 DSH 专属 Agent 体验；
- 未来必要的 DSH UI/宿主兼容补丁。

`iiBotMosire-DSH` 不负责：

- 定义或镜像 `qq_*` 工具名；
- 判断 Agent 应该先 read 还是先 ack；
- 解析 QQ 消息、mention、媒体或发送三态；
- 读取 Inbox `content`/`payload`；
- 在唤醒正文里承载唯一一份业务操作说明。

### 2.3 InboxMosire-DSH 的位置

`InboxMosire-DSH` 是 agent/program 无关的通用唤醒适配器。它只负责：

- binding / SSE / cursor / revision 去重 / idle 唤醒 / occupancy；
- 将 bounded `source`、`summary`、`kind` 等事实安全渲染给 Agent；
- 不调用任何程序业务工具，不读取正文。

它不能因为 iiBot 的需求新增 QQ 专属字段或分支。若需要通用能力，应先证明
对多个 program 都成立。

## 3. 目标运行序列

### 3.1 薄 DSH 唤醒

```text
Inbox item 变化
  → DSH 发现目标 session idle
  → 通用 view-ack wake：revision/available/channel
  → item：source/summary，kind（可选但建议保留）
  → Agent 看到程序 MCP 的 qq_* 工具
  → Agent 根据工具 description 和 source 自行完成 read/send/ack
  → available=0 时结束本回合，不轮询
```

DSH 不告诉 Agent“应该调用 qq_conversation_read”；工具自己说明用途和边界。
`summary` 始终是不可信数据，不是指令。

2026-10-30 隔壁 Bot 的实测反馈确认：

- `source` 必须保留；summary 文本不能替代来源定位。
- `available` 必须保留；它决定处理完当前 source 后继续还是收尾。
- `kind` 建议保留；iibot 的工具 description 用 `kind=digest_complete` 或
  `complete=true` 且没有 `truncated=true` 说明摘要已经完整，可免 read 直接 ack。
- view-ack 通用行在摘要被预算截断时必须带 `truncated:true`；iibot 工具
  description 必须把它视为“需要 read”的覆盖条件。
- `itemId` 在 view-ack 下可删除；当前没有 `inbox_claim` 消费它。
- 任何唤醒正文都不得出现指向不存在工具的 `inbox_claim` 指令。
- read/ack/send 的 description 已足以描述工作流；唤醒正文不应重复它。

### 3.2 iibot 读与回复

```text
qq_conversation_read(source)
  → 服务端取 readSeq 之后最多 limit 条入站消息
  → 返回 seq/messageId/from/uid/text/replyTo/segments/more
  → 只记录 deliveredSeq，不推进 readSeq
  → Agent 根据文本和 messageId 或 seq 组织回复
  → qq_conversation_send(... replyToSeq/messageId, mentionSender)
  → 服务端解析目标并发送
  → qq_conversation_ack(source)
```

`replyToSeq` 是 iibot 消息板业务序号，不是 Inbox 内部同步令牌。服务端必须在
同一个 source/thread 内解析它；Agent 不需要拆 `messageKey` 或直接猜 QQ
`messageId`。

## 4. MCP 契约

### 4.1 `qq_conversation_read`

返回：

```json
{
  "source": "qq_group/bot-1/955432692",
  "messages": [
    {
      "seq": 41,
      "messageId": "68923894",
      "from": "Alice",
      "uid": "123",
      "text": "你好",
      "replyTo": "68800001"
    }
  ],
  "more": true
}
```

规则：

- 每条消息带 `messageId`；这是 `qq_conversation_send.replyToMessageId` 和
  `mentionSender` 可直接使用的 QQ 平台消息 ID。
- `seq` 是 thread 内业务排序序号；可用于 `replyToSeq`。
- `more=true` 必须表示“本次返回批次之后仍有未读入站消息”。
- `more` 不得因 OUT 消息夹在窗口内而误报 `false`。
- replay 已投递批次时不得无条件把 `more` 固定为 `false`。
- 纯文本消息不返回 `key`；媒体/转发/未知段保留 `key` 供媒体工具使用。
- 不返回内部 threadId、messageKey、revision、itemId 或 snapshot。

### 4.2 `qq_conversation_send`

新增可选参数：

- `replyToSeq`：iibot 消息板 `localSeq`；
- 和 `replyToMessageId` 最多提供一个。

规则：

- 两者同时出现 → `INVALID_ARGUMENTS`；
- `replyToSeq` 找不到 → `REPLY_TARGET_NOT_FOUND`；
- 目标消息没有外部 messageId → `REPLY_TARGET_MISSING_MESSAGE_ID`；
- `mentionSender=true` 时由服务端解析目标消息的发送者，不要求 Agent 先查
  history；
- `mentionSender` 只能指向同 thread 的入站消息；
- 原始 `replyToMessageId` 继续兼容；
- 发送三态、幂等和账号路由不变。

### 4.3 `qq_conversation_ack`

保持 source-only：

```json
{"more": false}
```

- 不新增 itemId/revision/snapshot/seq；
- `more=true` 表示同一 active item 仍有未读，Agent 同轮继续 read；
- ack 仍先同步 Inbox，再推进 consumer readSeq。

## 5. 工具 description 责任

业务流程优先写进 iibot MCP 的工具 description，而不是 DSH wake 模板：

- read：说明 source、消息字段、`more`、完成后显式 ack；明确
  `kind=digest_complete` / `complete=true` 时摘要已完整，可直接 ack；
- send：说明 `replyToSeq`/`replyToMessageId`/mentionSender 的关系；
- ack：说明 `more=true` 时同轮继续 read，以及 complete 摘要可直接 ack；
- list：说明只在 wake 没有 source 或需要发现未读来源时使用。

DSH 的通用唤醒正文只保留安全边界、“`available` 决定是否收尾”和“不要轮询”
等宿主级规则；不出现任何程序业务工具名。

## 6. 不变量

1. 消息板仍是正文、历史、未读和发送状态的唯一事实源。
2. `read` 不推进 `readSeq`；`ack` 才推进。
3. `read.more` 必须只由入站未读范围决定，不能由原始线程窗口行数决定。
4. Agent 不维护 Inbox itemId/revision/snapshot。
5. `replyToSeq` 只能解析到同 source/thread 的消息。
6. mention 目标必须来自同 thread 入站消息；不能跨 thread 或跨账号。
7. `UNKNOWN` 外发不自动重试。
8. DSH 不读取 QQ 正文、不调用 `qq_*` 工具、不保存 raw token。
9. iibot 工具描述和测试必须能独立支撑 Agent 工作流；DSH profile 不是前置条件。
10. 任何对外部 QQ/DSH 的真实联调都必须写 `NOT_RUN`，不能由 fake/loopback 冒充。

## 7. 分阶段实施

### 第一阶段（本次）

- `qq_conversation_read` 增加 `messageId`；
- 修正 `more`：按入站消息窗口和已返回批次之后的未读判断；
- 增加 `replyToSeq`，服务端解析 QQ messageId 和 mention 发送者；
- 更新工具 description 和单元测试；
- 不改 InboxMosire、不改 InboxMosire-DSH、不改 iiBotMosire-DSH。

### 第二阶段（后续）

- 用 generic view-ack 实测不依赖 iibot profile 的唤醒闭环；
- 将 `iiBotMosire-DSH` 的 wake profile 标记为 legacy 并退役；
- 若真实需要“完整摘要免 read”，在 Inbox 契约中使用通用
  `summaryComplete` 或等价通用字段，不使用业务 kind allowlist。

### 第三阶段（独立设计）

- 讨论“一条一唤醒”的对话热窗口/合并策略；
- 该策略属于 iibot digest 发布节奏，不进入 DSH 代码。

## 8. 验收判据

### read

- 文本消息返回 `messageId`；
- 媒体消息仍返回 media `key` 且不泄露 fileId/URL；
- 窗口内夹 OUT 时，`more` 仍按入站消息判断；
- replay 且 active 未读仍在时返回 `more=true`；
- 只有 OUT 或没有后续入站时返回 `more=false`；
- deliveredSeq 只记录本次返回的最后一条入站消息。

### send

- `replyToSeq` 与 `replyToMessageId` 互斥；
- 正确 seq 可解析 messageId 并构造 reply segment；
- `mentionSender=true + replyToSeq` 能解析发送者并构造 mention；
- 不存在/跨 thread/非入站 mention 目标稳定失败；
- 不改变发送三态和幂等。

### 文档与门禁

- 新设计进入 `DESIGN_RULINGS.md`；
- `tools/gate.sh docs` PASS；
- 相关模块 focused test PASS，最终 full gate PASS；
- 真实 QQ/DSH 联调保持 `NOT_RUN`。
