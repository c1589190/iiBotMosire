# iibot-bot —— Bot-first 通用核心

> 本模块只承载 QQ Bot 通用能力，不承载文游领域。

## 职责

- 消息来源、私聊/群聊 thread 与消息模型；
- 消息板持久化端口、内存实现与 SQLite 实现；
- QQ 用户备注、标签与优先级（内存 + SQLite）；
- bounded 消息摘要与 Inbox 来源大框；
- Inbox digest 发布/更新/归档端口、游标与防抖调度；
- Bot MCP 的 thread 读取、正文恢复、按 thread 回复与用户备注工具。

## 边界

- 不依赖 `iibot-taskflow`；源码不得出现 `Player`、`Director`、`Secretary`、`Turn`、
  `DecisionDraft`、`ActionPool`、`Ticket`、`Publication` 等默认文游对象。
- 不直接实现 NapCat/OneBot 协议；协议细节在 `iibot-connect`。
- 不直接依赖 Inbox 具体客户端；通过端口由 app 注入。
- 聊天文本是不可信数据；身份和回复目标不能由文本提升或改写。

## 入口

- `src/main/java/io/mosire/iibot/bot/chat/ChatSourceKey.java`
- `src/main/java/io/mosire/iibot/bot/store/MessageBoardStore.java`
- `src/main/java/io/mosire/iibot/bot/store/SqliteMessageBoardStore.java`
- `src/main/java/io/mosire/iibot/bot/note/UserNoteStore.java`
- `src/main/java/io/mosire/iibot/bot/note/SqliteUserNoteStore.java`
- `src/main/java/io/mosire/iibot/bot/summary/MessageSummaryBuilder.java`
- `src/main/java/io/mosire/iibot/bot/inbox/InboxDigestService.java`
- `src/main/java/io/mosire/iibot/bot/inbox/InboxDigestException.java`
- `src/main/java/io/mosire/iibot/bot/inbox/InboxDigestAuditStore.java`
- `src/main/java/io/mosire/iibot/bot/friend/FriendRequestService.java`
- `src/main/java/io/mosire/iibot/bot/weight/SourceWeightService.java`
- `src/main/java/io/mosire/iibot/bot/mcp/BotMcpTools.java`
- `src/main/java/io/mosire/iibot/bot/mcp/BotThreadHistoryTool.java`
- `src/main/java/io/mosire/iibot/bot/mcp/BotForwardReadTool.java`
- `src/main/java/io/mosire/iibot/bot/mcp/BotSourceWeightSetTool.java`
- `src/main/java/io/mosire/iibot/bot/reply/BotReplyService.java`

## 验证

```bash
tools/gate.sh focused iibot-bot test
```

## 文档

- 当前状态：[`../docs/agents/STATUS.md`](../docs/agents/STATUS.md)
- 设计入口：[`../docs/superpowers/AGENTS.md`](../docs/superpowers/AGENTS.md)
