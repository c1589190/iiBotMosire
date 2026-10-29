# iiBotMosire

面向各类 Agent 软件的**统一 QQ 消息客户端**：通过 OneBot 11 接入 QQ，把私聊/群聊写入消息板，提供统一 MCP 工具，并用 Inbox 唤醒外部 Agent。

> **当前阶段**：P8 统一来源地址与全唤醒已实施（P8.6 修复所有入站消息共用合并窗口，不再只有 @ 快通道）；真实 QQ/DSH/OpenCode 外部联调与 AgentLib 0.2.4 公开发布仍待办。默认 3 模块、统一 `qq_*` MCP、多账号路由、服务端 read/ack、配置/日志 v2 与文档归档；当前事实以 [`docs/agents/STATUS.md`](docs/agents/STATUS.md) 为准。

## 当前模块

| 模块 | 职责 |
|---|---|
| `iibot-connect` | OneBot 11 / NapCat / LLBot 接入、重连、去重、限速、发送三态 |
| `iibot-bot` | QQ 消息板、thread、用户备注、摘要、Inbox digest、账号与 Bot MCP 工具 |
| `iibot-app` | 可运行组合根：配置、Adapter、embedded Inbox、Bot MCP、日志 |

当前依赖图：

```text
iibot-connect -> iibot-bot -> iibot-app
```

## 当前能力

- OneBot 11 事件 WebSocket + HTTP Action；
- QQ 私聊/群聊入库、thread、历史、用户备注、媒体读取、好友申请；
- Inbox 来源未读预览与**全入站消息唤醒**（权重只排序/诊断，不 gate）；
- 回环 Bot MCP，默认暴露统一 `qq_*` 业务工具、`inbox_rest` 与宿主交接 descriptor；
- 出站三态：`SENT` / `FAILED_BEFORE_SEND` / `UNKNOWN`，`UNKNOWN` 不自动重发；
- 默认只监听回环地址，token 只来自环境变量。

## 构建与运行

```bash
./mvnw -B -ntp clean verify

# 首次启动会在 config/bot/ 无配置时生成模板并退出
java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar
```

设置 token 环境变量后再次启动：

```bash
export IIBOT_NAPCAT_ACCESS_TOKEN='...'
export IIBOT_CONTROL_TOKEN='...'
java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar
```

配置说明见 [`config/README.md`](config/README.md)。当前已是多账号 schema；runtime 启动全部 enabled account，多账号下 direct-provider 工具需显式传 `accountId`。

## Bot MCP

默认示例地址：

```text
http://127.0.0.1:8765/mcp/bot
Authorization: Bearer <IIBOT_CONTROL_TOKEN>
```

默认 MCP 只暴露统一 `qq_*` 业务工具（含账号、会话、消息、媒体、好友申请、用户备注和来源权重）、唯一通用 Inbox 工具 `inbox_rest`，以及暂留宿主交接的 `qq_inbox_binding_descriptor`。不再全量暴露通用 `inbox_*` 工具，也不保留绕过消息板的公开直接发送工具。

消息来源统一为 canonical `source`：`qq_group/{account}/{group}`、`qq_private/{account}/{peer}`、`qq_system/{account}/{event}`、`app_system/{event}`；`qq_conversation_read/ack/send` 优先接受 `source`，旧 `threadId` 仅兼容。`ack(source)` 不要求先 `read`，并支持可选整数 `seq` 精确 ack 到消息序号。

## 目标设计

- [统一 QQ Agent 客户端与消息板架构](docs/superpowers/specs/2026-10-28-unified-qq-agent-client-architecture.md)
- [日志分级与诊断](docs/superpowers/specs/2026-10-28-logging-levels-and-diagnostics.md)
- [配置文件系统](docs/superpowers/specs/2026-10-28-configuration-system.md)
- [P0–P7 改造计划](docs/superpowers/plans/2026-10-28-unified-qq-agent-client-refactor-plan.md)
- [当前状态](docs/agents/STATUS.md)
- [设计裁定索引](docs/superpowers/DESIGN_RULINGS.md)

## 安全边界

- 默认只绑定回环地址；
- 不在配置、日志、MCP 参数或仓库中存放 raw token；
- 不向模型暴露 Authorization、完整 OneBot JSON 或聊天正文日志；
- 真实 QQ 外发必须由用户/主 Agent 明确决定，`UNKNOWN` 不得自动重试；
- 真实 QQ、DSH、长跑联调未跑时写 `NOT_RUN`，不把 fake/Mock 当真实验收。
