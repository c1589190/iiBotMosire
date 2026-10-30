# iiBotMosire

面向各类 Agent 软件的**统一 QQ / B 站会话客户端**：通过 OneBot 11 接入 QQ、通过 B 站 Web API 接入私信与评论，把消息写入统一消息板，提供统一 MCP 工具，并用 Inbox 唤醒外部 Agent。B 站视频阅读/抽帧是独立的通用只读工具；抽帧需要本机 `ffmpeg`（可用 `IIBOT_FFMPEG` 指定）。

> **当前阶段**：D-035 V3 已完成视频质量/精确时间/评论查询（code-level），V2 已完成 app 单一 `BotToolCatalog`/tool packs，V1 已完成 `iibot-video-connect` 第 4 个模块抽取；B 站领域/登录/抽帧已从 connect/app 迁出。默认 4 个模块、`video_*` 标准工具与 `bili_*` 别名、`qq_*`/`conversation_*` 工具、多账号路由、服务端 read/ack、配置/日志 v2；下一步 D-035 V4 Playwright embedded 浏览器。真实 QQ/B站/DSH/OpenCode 外部联调与 AgentLib 0.2.4 公开发布仍待办。当前事实以 [`docs/agents/STATUS.md`](docs/agents/STATUS.md) 为准。

## 当前模块

| 模块 | 职责 |
|---|---|
| `iibot-connect` | OneBot 11 / NapCat / LLBot 接入、重连、去重、限速、发送三态；只保留外部消息连接与 core SPI |
| `iibot-video-connect` | B 站视频读取/播放/评论/登录/抽帧与 B 站会话 Adapter；不依赖 AgentLib 或 MCP |
| `iibot-bot` | QQ 消息板、thread、用户备注、摘要、Inbox digest、账号与 Bot MCP 工具 |
| `iibot-app` | 可运行组合根：配置、Adapter、embedded Inbox、Bot MCP、视频工具、日志 |

当前依赖图：

```text
iibot-connect
   ├──> iibot-bot ─────────┐
   └──> iibot-video-connect ─┤
                              ▼
                          iibot-app
```

## 当前能力

- OneBot 11 事件 WebSocket + HTTP Action；
- QQ 私聊/群聊入库、thread、历史、用户备注、媒体读取、好友申请；
- B 站账号凭据校验、WBI、私信轮询、评论回复通知轮询、私信/评论发送（写入默认关闭）；
- 通用 `video_read` / `bili_video_read`：任意来源的 B 站链接/BV/av/b23，返回元数据、简介、热评和可选字幕；
- 通用 `video_frames` / `bili_video_frames`：支持 `count` 均匀抽帧或 `time/times` 精确时间，
  可选 `quality`/`strictQuality`；下载视频流后用 ffmpeg 抽帧，把多张 JPEG 作为
  MCP ImageContent 返回，供多模态 Agent 真正看画面；
- 通用 `video_comments`：按需读取顶层评论或楼中楼，支持 hot/time、cursor/limit；
- `video.defaultQuality/maxHeight/defaultFrameCount/maxFrameCount/comments.*` 配置；
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

默认 MCP 暴露 QQ `qq_*` 业务工具、平台无关 `conversation_read/ack/send` 别名、B 站内容工具 `bili_video_read` / `bili_video_frames`、B 站扫码登录工具 `bili_account_login_start/poll`、唯一通用 Inbox 工具 `inbox_rest`，以及暂留宿主交接的 `qq_inbox_binding_descriptor`。不保留绕过消息板的公开直接发送工具。

消息来源统一为 canonical `source`：`qq_group/{account}/{group}`、`qq_private/{account}/{peer}`、`qq_system/{account}/{event}`、`app_system/{event}`、`bili_dm/{account}/{peer}`、`bili_comment/{account}/{bvid}/{rootRpid}`、`bili_video/{account}/{bvid}`。`conversation_read/ack/send` 与兼容的 `qq_conversation_*` 都只接受 `source`。短文本 `kind=digest_complete` 摘要可直接 ack；ack 结果只返回 `more`。

## 目标设计

- [统一 QQ Agent 客户端与消息板架构](docs/superpowers/specs/2026-10-28-unified-qq-agent-client-architecture.md)
- [日志分级与诊断](docs/superpowers/specs/2026-10-28-logging-levels-and-diagnostics.md)
- [配置文件系统](docs/superpowers/specs/2026-10-28-configuration-system.md)
- [P0–P7 改造计划](docs/superpowers/plans/2026-10-28-unified-qq-agent-client-refactor-plan.md)
- [当前状态](docs/agents/STATUS.md)
- [设计裁定索引](docs/superpowers/DESIGN_RULINGS.md)

## 安全边界

- 默认只绑定回环地址；
- 不在配置、日志、MCP 参数或仓库中存放 raw token、B 站 Cookie；B 站凭据文件必须 0600；
- 不向模型暴露 Authorization、完整 OneBot JSON 或聊天正文日志；
- 真实 QQ 外发必须由用户/主 Agent 明确决定，`UNKNOWN` 不得自动重试；
- 真实 QQ、DSH、长跑联调未跑时写 `NOT_RUN`，不把 fake/Mock 当真实验收。
