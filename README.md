# iiBotMosire

面向各类 Agent 软件的**统一 QQ 消息客户端**：通过 OneBot 11 接入 QQ，把私聊/群聊写入消息板，提供统一 MCP 工具，并用 Inbox 唤醒外部 Agent。

> **当前阶段**：P1 代码收缩进行中。文游模块 `iibot-taskflow` 与旧文游 app 代码已删除；多账号路由、统一 `qq_*` 工具面、服务端读游标和 Inbox 自动同步仍在后续阶段实施。当前事实以 [`docs/agents/STATUS.md`](docs/agents/STATUS.md) 为准。

## 当前模块

| 模块 | 职责 |
|---|---|
| `iibot-connect` | OneBot 11 / NapCat / LLBot 接入、重连、去重、限速、发送三态 |
| `iibot-connect-agent` | 通用 QQ AgentTool 适配层；计划整合进正式模块后删除 |
| `iibot-bot` | QQ 消息板、thread、用户备注、摘要、Inbox digest、Bot MCP 工具 |
| `iibot-app` | 可运行组合根：配置、Adapter、embedded Inbox、Bot MCP、日志 |

最终目标模块为：

```text
iibot-connect -> iibot-bot -> iibot-app
```

`iibot-connect-agent` 的 Adapter Registry 与账号工具将迁入正式模块，原独立模块在 P6 删除。

## 当前能力

- OneBot 11 事件 WebSocket + HTTP Action；
- QQ 私聊/群聊入库、thread、历史、用户备注、媒体读取、好友申请；
- Inbox 来源未读预览与唤醒；
- 回环 Bot MCP，支持会话读取、回复、ack、媒体和 Inbox 工具；
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

配置说明见 [`config/README.md`](config/README.md)。当前配置仍是单账号结构，后续 P3 将升级为多账号 schema。

## Bot MCP

默认示例地址：

```text
http://127.0.0.1:8765/mcp/bot
Authorization: Bearer <IIBOT_CONTROL_TOKEN>
```

当前 MCP 是 `bot_* + inbox_* + tf_*` 的过渡工具面；P6 将收敛为统一 `qq_*` 业务工具，并把 Agent 不应维护的 revision/snapshot 等内部标记移出模型接口。

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
