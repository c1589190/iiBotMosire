# AGENTS.md —— iiBotMosire 常驻索引

> **更新时间**：2026-10-31（D-035 V4 Playwright embedded 浏览器 backend 完成）
> **本文件只做三件事**：项目边界、常驻红线、文档与模块路由。
> **规则正文不在本文件**：项目设计看 `docs/superpowers/`，项目维护看 `docs/agents/`。
> **禁止用 `@` 导入长文**；按需打开链接。

## 0. 冲突顺序

1. 用户当次明确指令。
2. 用户设计裁定：D-025..D-035、统一 QQ Agent 客户端目标架构、来源地址与 direct ack。
3. 项目维护裁定：`docs/agents/MAINTENANCE_RULINGS.md`、`docs/AGENTS.md`。
4. 当前有效设计与契约：`docs/superpowers/specs/**`。
5. 本文件。
6. `docs/agents/STATUS.md`（当前事实，不覆盖设计/维护规则）。
7. 旧文游文档、历史 status 与调查报告（只作证据，不据此开工）。

## 1. 项目定位

**iiBotMosire 是 Agent 无关的统一 QQ / B 站会话客户端，不是文游服务。**

默认产品只负责：

- 一个或多个 OneBot 11 / NapCat / LLBot 账号接入；
- 一个或多个 B 站账号的凭据校验、私信/评论回复通知轮询与显式发送；
- QQ / B 站统一消息板、thread、历史、用户备注和媒体；
- 通用只读 `video_read`/`bili_video_read`、`video_frames`/`bili_video_frames`、`video_comments`（任意来源 B 站链接/BV/av/b23）；
- D-035 V4：Playwright embedded 浏览器 backend、`video.browser.*` 配置、B 站页面 seek/截图与 ffmpeg 失败 fallback 已完成；V3 的分辨率 `quality`/`strictQuality`、精确 `time/times`、评论分页查询；V2 单一 `BotToolCatalog`/tool packs；V1 视频站抽取/评论/交互在 `iibot-video-connect`；后续 V5 接入 remote-cdp/服务器；
- 发送三态与 Inbox 唤醒；
- 统一 MCP 工具面，供外部 Agent 读取、回复和管理 QQ/B 站会话。

非目标：

- 玩家、秘书、回合、行动、裁定、世界、Ticket、ActionPool、Publication 等文游语义；
- 在 iibot 内运行 LLM、业务主 Agent 或推演循环；
- 自动刷视频、自动评论、自动私信、自动点赞/关注；
- 任意 OneBot action、原始 JSON、任意 SQL/shell；
- 把内部 revision、snapshot、游标维护责任转嫁给 Agent。

## 2. 常驻红线

1. **默认只绑定回环地址。** 非回环监听必须 fail-closed。
2. **密钥零泄露。** token、Authorization、QQ/B 站凭据不得进入日志、MCP 结果、配置或提交；
   B 站 Cookie 文件必须是 0600。
3. **正文不落日志。** 日志只记录 ID、状态、计数、错误码、耗时和参数 key。
4. **消息板是唯一业务事实源。** 完整消息、历史、未读和发送状态以消息板为准；Inbox 只做唤醒和预览。
5. **Agent 不维护内部标记。** revision、snapshot、itemId、内部 seq 和幂等键由服务端管理；Agent 只能使用业务实体和语义工具。
6. **发送必须经消息板。** 不允许 MCP 工具直接调用 Adapter 绕过出站意图、幂等和投递三态。
7. **账号不能串号。** 出站必须按 thread 的 accountId 路由；账号不匹配 fail-closed。
8. **外部副作用不自动重试。** `UNKNOWN` 表示可能已发送，禁止自动重发。
9. **真实联调不冒充通过。** Mock/fake/回环测试不能写成真实 QQ/DSH 验收；没跑写 `NOT_RUN`。
10. **不擅自 commit/push/合并。** 遵循用户当次指令与 M-005；不 `git add -A`，不强制推送。

## 3. 当前模块（4 模块）

根 `pom.xml` 当前 4 个模块；D-035 V1 已创建第 4 个 `iibot-video-connect`：

| 模块 | 职责 | 依赖 |
|---|---|---|
| `iibot-connect` | OneBot 11 接入、事件/发送 SPI、三态回执；只保留外部消息连接与 core 契约，B 站已迁出 | Jackson、SLF4J |
| `iibot-video-connect` | B 站视频元数据/播放/评论/登录/抽帧与 B 站会话 Adapter；不依赖 AgentLib/MCP | connect + Jackson |
| `iibot-bot` | 统一消息板、thread、用户备注、摘要、Inbox digest、conversation/QQ MCP 工具 | connect + AgentLib + SQLite |
| `iibot-app` | 可运行组合根：配置、Adapter、embedded Inbox、唯一 MCP 工具目录、B 站视频工具、日志 | bot + video-connect + Inbox artifacts |

当前依赖图：

```text
iibot-connect
      │
      ├──────────────┐
      ▼              ▼
iibot-bot     iibot-video-connect
      │              │
      └──────┬───────┘
             ▼
         iibot-app
```

账号工具已进入 `iibot-bot` 的统一 MCP 工具包；独立 `iibot-connect-agent` 模块已删除。
D-035 V1 已完成模块抽取；V2 已把 QQ/媒体/Inbox/视频站工具收敛到 app 的
`BotToolCatalog` + `BotToolContext` + 四个 tool pack；V3 已补 `quality`/`strictQuality`、
`time/times`、`video_comments` 与 `video.*` 配置；V4 已补 Playwright embedded
`browser/` SPI、`video.browser.*` 配置和 ffmpeg 失败 fallback，保留 `bili_*` 兼容名；
remote-cdp 仍待 V5。

## 4. 当前有效设计与计划

| 需要找什么 | 入口 |
|---|---|
| 当前状态 / 下一步 | [`docs/agents/STATUS.md`](docs/agents/STATUS.md) |
| 统一 QQ Agent 客户端目标 | [`docs/superpowers/specs/2026-10-28-unified-qq-agent-client-architecture.md`](docs/superpowers/specs/2026-10-28-unified-qq-agent-client-architecture.md) |
| 日志分级与诊断 | [`docs/superpowers/specs/2026-10-28-logging-levels-and-diagnostics.md`](docs/superpowers/specs/2026-10-28-logging-levels-and-diagnostics.md) |
| 配置文件系统 | [`docs/superpowers/specs/2026-10-28-configuration-system.md`](docs/superpowers/specs/2026-10-28-configuration-system.md) |
| P0–P7 改造计划 | [`docs/superpowers/plans/2026-10-28-unified-qq-agent-client-refactor-plan.md`](docs/superpowers/plans/2026-10-28-unified-qq-agent-client-refactor-plan.md) |
| 设计裁定索引 | [`docs/superpowers/DESIGN_RULINGS.md`](docs/superpowers/DESIGN_RULINGS.md) |
| 用户需求原话 | [`docs/superpowers/specs/2026-10-24-user-requirements-source.md`](docs/superpowers/specs/2026-10-24-user-requirements-source.md) |
| 统一消息来源地址 | [`docs/superpowers/specs/2026-10-28-unified-message-source-address.md`](docs/superpowers/specs/2026-10-28-unified-message-source-address.md) |
| B 站通用视频与 Inbox 会话（D-034） | [`docs/superpowers/specs/2026-10-31-bilibili-generic-video-and-conversation-adapter.md`](docs/superpowers/specs/2026-10-31-bilibili-generic-video-and-conversation-adapter.md) |
| 视频站 Connect 与无头浏览器（D-035） | [`docs/superpowers/specs/2026-10-31-video-site-connect-and-browser-backend.md`](docs/superpowers/specs/2026-10-31-video-site-connect-and-browser-backend.md) |
| D-035 V0–V7 实施计划 | [`docs/superpowers/plans/2026-10-31-video-site-connect-and-browser-backend-plan.md`](docs/superpowers/plans/2026-10-31-video-site-connect-and-browser-backend-plan.md) |
| P8 来源地址/wake 计划 | [`docs/superpowers/plans/2026-10-28-p8-source-address-and-wake-contract.md`](docs/superpowers/plans/2026-10-28-p8-source-address-and-wake-contract.md) |
| 文档规则 | [`docs/AGENTS.md`](docs/AGENTS.md) |
| 工作流 | [`docs/agents/WORKFLOW.md`](docs/agents/WORKFLOW.md) |
| 验证与证据 | [`docs/agents/VERIFICATION.md`](docs/agents/VERIFICATION.md) |
| 环境与工具链 | [`docs/agents/ENVIRONMENT.md`](docs/agents/ENVIRONMENT.md) |
| 配置入口 | [`config/README.md`](config/README.md) |
| 门禁 | [`tools/gate.sh`](tools/gate.sh) |

## 5. 工作树与环境

- JDK 21；仓库内 `./mvnw`。
- 门禁：`tools/gate.sh docs|selftest|pre|focused|full`。
- D-035 V1 新模块抽取、V2 单一工具目录、V3 分辨率/精确时间/评论查询、V4 embedded 浏览器 backend 已完成（第 4 模块 `iibot-video-connect` + app `BotToolCatalog`），下一步 V5 remote-cdp/服务器；真实 B 站浏览器抽帧/remote-cdp 联调仍 `NOT_RUN`。
- 当前产品分支：`refactor/bot-first-main-agent`；按责任区提交并推送，全量验证后再合并（M-005）。
- worktree 统一放 `<仓库根>/worktree/<name>/`。
- 同一 checkout 不并发跑 Maven；不覆盖正在运行的 jar；不提交 `.work/`、`target/`、数据库、日志和 secret。
- 真实 QQ/DSH/OneBot 连接和外发必须先获得用户当次明确授权（M-004）。
