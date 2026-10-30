# iibot-video-connect —— 视频站接入 Lib

## 职责

- 承载视频站接入能力：B 站视频读取、评论读取/写入、扫码登录、抽帧与 B 站会话 Adapter。
- 对上层提供稳定的视频站领域 API；不依赖 AgentLib、MCP、Inbox 或 `iibot-bot` 业务模型。
- 依赖 `iibot-connect` 的核心消息契约（`MessagingAdapter`、`InboundMessage`、`OutboundMessage` 等）。

## 边界

- 根路由与常驻红线见 [../AGENTS.md](../AGENTS.md)。
- 本模块不注册 MCP 工具；MCP 工具统一在 `iibot-app` 组合根注册。
- 不实现 QQ/OneBot 协议；QQ 协议仍在 `iibot-connect`。
- 不实现业务工作流、消息板或 Inbox 路由；这些属于 `iibot-bot` / `iibot-app`。
- 真实 B 站写操作默认关闭，必须显式授权；Cookie/token 不得进入日志。

## 验证

```bash
mvn -B -ntp -pl iibot-video-connect -am test
```

> 未实际运行的命令不得在报告里写成通过。
