# iiBotMosire

面向各类 Agent 软件的**统一 QQ / B 站会话客户端**：通过 OneBot 11 接入 QQ、通过 B 站 Web API 接入私信与评论，把消息写入统一消息板，提供统一 MCP 工具，并用 Inbox 唤醒外部 Agent。B 站视频阅读/抽帧是独立的通用只读工具；抽帧默认需要本机 `ffmpeg`（可用 `IIBOT_FFMPEG` 指定），启用 `video.browser.*` 时可用 Playwright embedded 或 remote-cdp 作为失败补帧。


## 快速开始（5 分钟）

> 只想让 Agent 能用 QQ？按这里做，不需要读后面的设计与阶段说明。
> 要把这一步交给 Agent 自己执行，直接粘 [Hermes 对接提示词](https://github.com/c1589190/InboxMosire-Hermes/blob/main/docs/ONBOARD.md) 里的提示词。

### 1. 前置

| 需要 | 说明 |
|---|---|
| JDK 21 | `java -version` 要 ≥ 21 |
| NapCat（或 LLBot） | QQ 端的 OneBot 11 实现，独立进程；iiBot 只连它的 WebSocket/HTTP |
| 一个 QQ 号 | 由 NapCat 登录，iiBot 本身不碰 QQ 密码 |

### 2. 下载并校验

从 [Releases](https://github.com/c1589190/iiBotMosire/releases) 下载 `iibot-app-<版本>.jar`
与 `SHA256SUMS`，校验后放到运行目录：

```bash
mkdir -p ~/bot && cd ~/bot
curl -LO https://github.com/c1589190/iiBotMosire/releases/download/<版本>/iibot-app-<版本>.jar
curl -LO https://github.com/c1589190/iiBotMosire/releases/download/<版本>/SHA256SUMS
sha256sum -c SHA256SUMS --ignore-missing
```

> jar 是 **shaded 全依赖包**，类在 `io/mosire/...` 根路径（不是 Spring Boot 的 `BOOT-INF/`），
> 不需要额外 classpath；只要 JDK，不需要 Maven。

### 3. 写配置

运行目录只需要三样东西：`config/bot/bot.json`、`data/`（自动创建）、`logs/`。

```json
{
  "dataDir": "./data",
  "accounts": {
    "main": {
      "enabled": true,
      "adapter": "onebot11",
      "accountId": "<QQ号>",
      "oneBot": {
        "eventWebSocketUri": "ws://127.0.0.1:3001/",
        "httpBaseUri": "http://127.0.0.1:3000/",
        "accessTokenEnv": "IIBOT_NAPCAT_ACCESS_TOKEN"
      }
    }
  },
  "control": { "host": "127.0.0.1", "port": 8765, "path": "/mcp/bot",
               "bearerTokenEnv": "IIBOT_CONTROL_TOKEN" },
  "inbox":   { "inboxId": "iibot-bot", "channels": ["qq"],
               "bindingTtlHours": 0,
               "apiHost": "127.0.0.1", "apiPort": 46500,
               "mcpHost": "127.0.0.1", "mcpPort": 46501 },
  "messageBoard": { "maxPreviewMessages": 50, "summaryMaxChars": 4000,
                    "contextMessages": 0, "contextMaxChars": 0 }
}
```

- `bindingTtlHours: 0` = wake token **永不过期**；填正数就按小时过期。
- `messageBoard.contextMessages: 0` = 唤醒正文只放**未读**，不再补历史（历史让 Agent 自己 `qq_conversation_read`）。
- 完整字段见 [`config/README.md`](config/README.md) 与 [`config/schema/bot-config.schema.json`](config/schema/bot-config.schema.json)。

### 4. 凭据走环境变量（不进配置文件）

```bash
mkdir -p ~/.config/iibot && chmod 700 ~/.config/iibot
cat > ~/.config/iibot/control.env <<'EOF'
IIBOT_CONTROL_TOKEN=<随机长串，MCP 的 Bearer>
EOF
chmod 600 ~/.config/iibot/control.env
# NapCat 的 access token（如果它开了鉴权）
echo 'IIBOT_NAPCAT_ACCESS_TOKEN=<NapCat 的 token>' >> ~/.config/iibot/control.env
```

启动器（`run/boot-env.sh`）负责 `set -a; . ~/.config/iibot/*.env; set +a` 再 `exec java -jar`，
**不要**直接裸 `java -jar`，否则环境变量缺失、token 为空。

### 5. 起服务

```bash
cd ~/bot
nohup ./run/boot-env.sh > run/console.log 2>&1 &
# 或者用仓库里的 systemd 封装（崩溃 5 秒自愈）
./start-bot.sh start
```

### 6. 确认活着

```bash
ss -lnt | grep -E '8765|46500|46501'      # 三个端口都在听
curl -s localhost:46500/healthz           # 200
curl -s -H "Authorization: Bearer $IIBOT_CONTROL_TOKEN"      localhost:46500/v1/inboxes/iibot-bot/status   # 有 revision
grep inbox_embedded_started logs/*.log    # event=inbox_embedded_started ... apiPort=46500
```

### 7. 接 Agent

iiBot 一次启动同时提供三个面：

| 面 | 地址 | 用途 | Bearer 从哪来 |
|---|---|---|---|
| 业务工具面 | `http://127.0.0.1:8765/mcp/bot` | `qq_*` 工具（读消息、发消息、取图） | `IIBOT_CONTROL_TOKEN`（固定） |
| Inbox HTTP | `http://127.0.0.1:46500` | 通知板读写、SSE 事件 | `data/inbox/dsh-wake.token` |
| Inbox MCP | `http://127.0.0.1:46501/mcp` | `inbox_*` 工具（认领/查看/更新板条目） | `data/inbox/program-mcp.token` |

以 Hermes 为例：

```bash
hermes mcp add iibot --url http://127.0.0.1:8765/mcp/bot --auth header
hermes mcp add inbox-mosire --url http://127.0.0.1:46501/mcp --auth header
```

两个 Bearer 分别填上面的值；唤醒插件（`inbox-wake`）的安装见
[InboxMosire-Hermes](https://github.com/c1589190/InboxMosire-Hermes)。
DSH 侧见 [InboxMosire 的 upstream 契约](https://github.com/c1589190/InboxMosire/tree/main/docs/upstream)（`wake-adapter-contract.md`、`DSH-HANDOFF.md`）。

> 两个 token 都是**文件即配置**：`data/inbox/*.token` 存在且有效就复用，重启不变；
> 想彻底固定，用 `inbox.programTokenEnv` / `inbox.wakeTokenEnv` 指定环境变量名（见 `config/README.md`）。

### 8. 常见问题

| 症状 | 原因 |
|---|---|
| 8765 返回 401 | `IIBOT_CONTROL_TOKEN` 没进进程环境（裸 `java -jar` 起的） |
| Agent 说「读不了图」/只看到 `MEDIA:` 路径 | 宿主没声明视觉：`hermes config set model.supports_vision true` 后重启 gateway |
| 唤醒一直 401 | `dsh-wake.token` 与运行实例不匹配，或 `bindingTtlHours` 正数且已过期 |
| 端口被占 | 同一台机器跑了两份 iiBot（`bot/` 与 `bot2/` 之类），停掉其中一份 |

## 当前阶段

> **当前阶段**：D-036 已完成 code-level 的唤醒正文注入（DSH `wakeContent=bounded`）、摘要 512 边界、5 秒合并窗口，以及媒体 source 去重、图片备注和 `qq_media_inspect` 首帧/分块检查；D-035 V6 已完成显式、单次、可审计的 B 站评论写入与点赞/投币/收藏/关注 interaction（范围 B；写入仍默认关闭）；V5 已完成 Playwright `remote-cdp` 浏览器 backend，V4 已完成 embedded backend 与 ffmpeg 失败 fallback，V3 已完成视频质量/精确时间/评论查询，V2 已完成 app 单一 `BotToolCatalog`/tool packs，V1 已完成 `iibot-video-connect` 第 4 个模块抽取。默认 4 个模块；下一步是 D-036 真实 DSH reload/大图样本验收与 D-035 V7 真实验收。真实 QQ/B站/DSH/OpenCode 外部联调、真实 B 站写入/浏览器抽帧、服务器部署与 AgentLib 0.2.4 公开发布仍待办。当前事实以 [`docs/agents/STATUS.md`](docs/agents/STATUS.md) 为准。

## 当前模块

| 模块 | 职责 |
|---|---|
| `iibot-connect` | OneBot 11 / NapCat / LLBot 接入、重连、去重、限速、发送三态；只保留外部消息连接与 core SPI |
| `iibot-video-connect` | B 站视频读取/播放/评论/登录/抽帧/显式写入与 B 站会话 Adapter；不依赖 AgentLib 或 MCP |
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
- 媒体读取按 fileId/URL/message source 去重，返回 `assetId`/`sha256`/`cached`；
- `qq_media_inspect` 支持首帧/网格降采样，`qq_media_note` 按 assetId 持久化图片备注；
- DSH wake 默认注入 bounded detail content，摘要/正文预算独立，content 明确标为不可信；
- B 站账号凭据校验、WBI、私信轮询、评论回复通知轮询、私信/评论发送（写入默认关闭）；
- 通用 `video_comment_reply`：显式单次评论/楼中楼回复，写入默认关闭，经消息板/出站与账号配额；
- `video_like` / `video_coin` / `video_favorite` / `video_follow`：显式单次互动，
  默认关闭，经 Adapter 审计、账号配额与幂等；不提供自动循环；
- 通用 `video_read` / `bili_video_read`：任意来源的 B 站链接/BV/av/b23，返回元数据、简介、热评和可选字幕；
- 通用 `video_frames` / `bili_video_frames`：支持 `count` 均匀抽帧或 `time/times` 精确时间，
  可选 `quality`/`strictQuality`；默认下载视频流后用 ffmpeg 抽帧，把多张 JPEG 作为
  MCP ImageContent 返回；`video.browser.enabled=true` 时 ffmpeg 失败可按 B 站页面
  seek 后用 Playwright + 系统 Chromium 补帧；服务器可配置 `mode=remote-cdp` 连接同网络命名空间的 Chromium sidecar（CDP 仅回环）；
- 通用 `video_comments`：按需读取顶层评论或楼中楼，支持 hot/time、cursor/limit；
- `video.defaultQuality/maxHeight/defaultFrameCount/maxFrameCount/comments.*/browser.*` 配置；
  `browser.enabled` 默认 false；embedded 使用系统 Chromium，remote-cdp 连接回环 CDP
  sidecar（部署草案见 [`deploy/browser/README.md`](deploy/browser/README.md)）；
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

默认 MCP 暴露 QQ `qq_*` 业务工具（含 `qq_media_inspect` / `qq_media_note`）、平台无关 `conversation_read/ack/send` 别名、B 站内容工具 `video_read` / `video_frames` / `video_comments`（兼容 `bili_*` 别名）、显式写入工具 `video_comment_reply` / `video_like` / `video_coin` / `video_favorite` / `video_follow`、B 站扫码登录工具 `bili_account_login_start/poll`、唯一通用 Inbox 工具 `inbox_rest`，以及暂留宿主交接的 `qq_inbox_binding_descriptor`。写入工具默认关闭，不保留绕过消息板/审计的公开直接发送工具。

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
