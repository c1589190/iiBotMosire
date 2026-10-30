# iibot-app —— QQ / B 站客户端组合根

## 职责

- 加载配置并创建 QQ / B 站 Adapter；
- 装配 SQLite 消息板、embedded Inbox 与 Bot MCP；
- 通过 app 的单一 `BotToolCatalog`/`BotToolContext` 注册 QQ、媒体、Inbox、B 站视频读取/分帧/评论/登录 MCP 工具；
- 解析 `video.browser.*`，在 browser 启用时向分帧工具注入 Playwright embedded fallback，并统一关闭工具资源；
- 管理进程生命周期、日志和安全创建；
- 提供唯一可运行入口 `io.mosire.iibot.app.Main`。

## 索引

- 当前状态：[`../docs/agents/STATUS.md`](../docs/agents/STATUS.md)
- 设计入口：[`../docs/superpowers/AGENTS.md`](../docs/superpowers/AGENTS.md)

## 边界

- 默认产品只做统一 QQ / B 站 Agent 客户端，不包含文游服务。
- 不承载玩家、秘书、回合、Ticket、Publication、世界等业务。
- 不直接实现 OneBot 协议；协议在 `iibot-connect`。
- 不绕过 `iibot-bot` 的消息板直接向 Adapter 发送业务消息。
- Agent 内部标记（revision/snapshot/itemId/seq）由服务维护，不进入 Agent 必填参数。
- `video.*` 配置解析、默认值与脱敏 effective config 在 app 完成；真实 B 站/浏览器外部验收未跑时写 `NOT_RUN`。
- 配置/日志目标见：
  - [`docs/superpowers/specs/2026-10-28-configuration-system.md`](../docs/superpowers/specs/2026-10-28-configuration-system.md)
  - [`docs/superpowers/specs/2026-10-28-logging-levels-and-diagnostics.md`](../docs/superpowers/specs/2026-10-28-logging-levels-and-diagnostics.md)

## 入口

- `src/main/java/io/mosire/iibot/app/Main.java`
- `src/main/java/io/mosire/iibot/app/bot/BotMain.java`
- `src/main/java/io/mosire/iibot/app/bot/BotRuntime.java`
- `src/main/java/io/mosire/iibot/app/mcp/BotToolCatalog.java`
- `src/main/java/io/mosire/iibot/app/mcp/BotToolContext.java`
- `src/main/java/io/mosire/iibot/app/inbox/BotInboxRuntime.java`

## 验证

```bash
tools/gate.sh focused iibot-app test
```

> 未实际运行的命令不得在报告里写成通过；真实 QQ/DSH 联调未跑写 `NOT_RUN`。
