# 视频站 Connect 模块、无头浏览器与配置/日志设计

> **状态**：有效（2026-10-31；用户已确认模块拆分与无头浏览器兼容；V1 已完成，V2 待开始）
> **用户原话**：
> - 「首先我建议把视频网站的视频抽取、评论区查询和整合，以及视屏网站的交互全都集中到一个统一的connect包里面方便管理，然后做标准MCP工具，统一在APP做注册，而不是分散在各个模块」
> - 「此外还有易用性，允许选择分辨率吗？」
> - 「无头浏览器是是必定要兼容的」
> - 「这个设计可以；然后是无头浏览器，能上吗？」
> - 「没问题，记文档，告诉我你的开发阶段规划」
> - 「哦对了，配置项与易维护改进也必须提上日程，相关灵活配置与日志要做好」
> **设计索引**：[`../DESIGN_RULINGS.md`](../DESIGN_RULINGS.md)
> **实施计划**：[`../plans/2026-10-31-video-site-connect-and-browser-backend-plan.md`](../plans/2026-10-31-video-site-connect-and-browser-backend-plan.md)

## 1. 目标与非目标

### 1.1 目标

1. 视频站的视频抽取、元数据、评论区查询/整合、播放流和交互能力集中到一个独立的
   `iibot-video-connect` Maven 模块。
2. `iibot-connect` 继续保持“外部消息连接”职责，只保留 `core` 通用消息 SPI 和
   OneBot/NapCat 等消息协议实现；不引入 B 站、视频、ffmpeg 或 Chromium 依赖。
3. MCP 工具统一由 `iibot-app` 的单一 `BotToolCatalog` 注册；`iibot-bot` 不再直接
   注册 B 站工具，`BotRuntime`/`BotMain` 不再各自 `extraTools.add(...)`。
4. 视频工具支持显式分辨率选择，并返回请求清晰度、实际清晰度、可用清晰度和回退状态。
5. 视频工具支持指定分P与精确时间点抽帧。
6. 评论区支持按需查询顶层评论/楼中楼，与现有“评论回复通知入 Inbox”路径分层。
7. 无头浏览器作为正式兼容的 `BrowserBackend` 接入，支持 Linux headless、服务器
    `remote-cdp` 模式和本地 `embedded` 模式。
8. 配置项和日志在实现前就有明确形状、校验、脱敏和验收要求；不能后补一堆硬编码。
9. 保持现有 QQ 消息、B 站私信/评论回复、Inbox 和 `conversation_*` 行为不回归。

### 1.2 非目标

- 不在 iibot 内运行 LLM、业务主 Agent 或自动刷视频循环；
- 不把无头浏览器做成通用网页抓取器、任意 URL 导航器或任意 JavaScript 执行器；
- 不默认全量扫描视频评论区并灌入消息板；
- 不在未确认前实现点赞/投币/收藏/关注等额外交互；
- 不把 Cookie、Authorization、视频流 URL、浏览器 profile 或截图临时路径写入日志或 MCP 文本。

## 2. 现状事实

### 2.1 模块与注册

- V1 后 reactor 为 4 模块：`iibot-connect` / `iibot-video-connect` / `iibot-bot` /
  `iibot-app`，见 `pom.xml` 的 `<modules>`。
- `iibot-connect` 已退出 B 站实现，只保留 `core`、`napcat`、`ops`：
  `iibot-connect/src/main/java/io/mosire/iibot/connect/{core,napcat,ops}`。
- B 站领域、登录、抽帧与 Adapter 在
  `iibot-video-connect/src/main/java/io/mosire/iibot/connect/video/{core,bilibili,frame}`；
  V1 实际类名保留 `BiliFrameExtractor` / `FfmpegBiliFrameExtractor`，`browser/` 尚未创建。
- `BiliVideoReadTool`、`BiliVideoFramesTool`、登录 MCP 工具仍在
  `iibot-app/src/main/java/io/mosire/iibot/app/bilibili/`；`BiliLoginService` 已迁入
  `iibot-video-connect/.../bilibili/`，app 只做 MCP 适配。
- `BotMain.java` 直接注册 `bili_video_read` 和登录工具；
- `BotRuntime.java` 直接构造 QQ 工具、媒体工具并注册 `BiliVideoFramesTool`；
- `BotMcpTools.java` 在 bot 模块内装配部分 `qq_*`/`conversation_*` 工具。
- V1 已把视频领域收拢到独立模块；MCP 注册点仍分散，V2 才收敛到 app
  `BotToolCatalog`。

### 2.2 视频能力

- `BilibiliHttpClient.java:143` 视频元数据；
- `BilibiliHttpClient.java:173` 顶层评论查询；
- `BilibiliHttpClient.java:263` `playStream()` 当前硬编码 `qn=64`，只按
  `maxHeight` 选流，没有显式 `quality` 参数、可用清晰度列表和严格回退策略；
- `BilibiliHttpClient.java:398` 评论回复写入；
- `BilibiliAdapter.java:476` 评论回复通知轮询；
- `BiliVideoFramesTool.java:81` 抽帧工具，当前仅 `count` 均匀抽帧；
- `FfmpegBiliFrameExtractor` 使用本机 `ffmpeg` 抽帧，当前无浏览器后端。

### 2.3 配置与日志

- `BotConfig.Bilibili` 在 `iibot-app/.../bot/BotConfig.java:109`，只有账号轮询、
  收信和写入开关；没有视频质量、抽帧、浏览器、评论查询等配置。
- `config/schema/bot-config.schema.json` 已支持 `adapter=bilibili`，但尚无
  `video`/浏览器配置定义。
- 配置是启动时快照；`--check-config` 和 `--print-effective-config` 已存在。
- 现有日志使用 `LogContext`/`OpsLog`；视频和浏览器目前没有独立 ops 事件与包级级别。
- 现有日志规则：正文、Cookie、Authorization、完整协议 JSON 不入日志；文件 0600、
  按天/大小滚动轮转。

## 3. 目标模块边界

### 3.1 Maven 模块

V1 已实现 4 模块：

```text
iibot-connect          外部消息连接（QQ/OneBot + core SPI）
iibot-video-connect    视频站领域（B站、评论、播放、抽帧、浏览器）
iibot-bot              消息板、Inbox、QQ 业务服务与 QQ 工具类
iibot-app              组合根：配置、服务装配、MCP 工具目录、MCP server
```

依赖方向：

```text
iibot-connect
      ↑
iibot-video-connect
      ↑
iibot-bot ──→ iibot-connect
      ↑
iibot-app ──→ iibot-video-connect + iibot-bot + inbox-*
```

约束：

- `iibot-connect` 不依赖 `iibot-video-connect`；
- `iibot-video-connect` 可依赖 `iibot-connect` 的 `core` 消息 SPI；
- `iibot-video-connect` 不依赖 AgentLib/MCP；
- `iibot-bot` 不依赖 `iibot-video-connect`；
- `iibot-app` 是唯一同时看到两侧并装配 MCP 工具的位置。

### 3.2 包结构

```text
iibot-video-connect/src/main/java/io/mosire/iibot/connect/video/
  core/
    VideoSiteId
    VideoTarget
    VideoAccountRef
    VideoMetadata
    VideoPage
    VideoQuality
    VideoStreamInfo
    VideoSubtitle
    VideoComment
    VideoCommentPage
    VideoCommentQuery
    VideoFrame
    VideoFrameRequest
    VideoFrameResult
    VideoInteractionResult
  spi/
    VideoSite
    VideoCatalogReader
    VideoCommentReader
    VideoPlaybackReader
    VideoFrameCapture
    VideoCommentWriter
    VideoAuthService
  bilibili/
    BilibiliVideoSite
    BilibiliApiClient
    BilibiliCredential
    BilibiliCredentialProvider
    BilibiliLoginClient
    BilibiliTargetResolver
    BilibiliAdapter
    BilibiliAdapterConfig
    BilibiliModels
  frame/
    VideoFrameExtractor
    FfmpegVideoFrameExtractor
  browser/
    BrowserBackend
    BrowserSession
    BrowserProfile
    BrowserFrameRequest
    BrowserFrameResult
    BrowserGuard
    BrowserPolicy
    PlaywrightBrowserBackend
```

V1 已把原 `iibot-connect/.../bilibili` 的类迁移到 `iibot-video-connect` 的
`connect.video.bilibili`，并把 `BiliLoginService`、抽帧接口/ffmpeg 实现迁入。
`iibot-connect` 不再包含 B 站类；V1 没有保留 deprecated 转发类。V1 实际落地
`core/VideoAccountRef`、`bilibili/*`、`frame/BiliFrameExtractor` 与
`frame/FfmpegBiliFrameExtractor`；`browser/*` 及下文 `VideoFrameExtractor` 等
未来命名尚待后续阶段创建或统一。

### 3.3 领域接口

视频站能力按读/写拆分，不做一个上帝接口：

```java
interface VideoCatalogReader {
    VideoMetadata metadata(VideoTarget target, int page, VideoAccountRef account);
}

interface VideoPlaybackReader {
    VideoStreamInfo playback(VideoTarget target, int page, VideoQuality quality,
                             boolean strictQuality, VideoAccountRef account);
}

interface VideoCommentReader {
    VideoCommentPage comments(VideoCommentQuery query, VideoAccountRef account);
}

interface VideoFrameCapture {
    List<VideoFrame> capture(VideoFrameRequest request, VideoAccountRef account);
}

interface VideoCommentWriter {
    VideoInteractionResult reply(VideoCommentReply request, VideoAccountRef account);
}

interface VideoAuthService {
    LoginStart startLogin(VideoAccountRef account);
    LoginPoll pollLogin(String loginId);
}
```

模型不得包含 Cookie、Authorization、签名参数或可复用的带 token 媒体 URL。

## 4. MCP 工具与注册

### 4.1 单一注册点

`iibot-app` 新增：

```text
io.mosire.iibot.app.mcp.BotToolCatalog
io.mosire.iibot.app.mcp.BotToolContext
io.mosire.iibot.app.mcp.toolpack.QqToolPack
io.mosire.iibot.app.mcp.toolpack.MediaToolPack
io.mosire.iibot.app.mcp.toolpack.InboxToolPack
io.mosire.iibot.app.mcp.toolpack.VideoSiteToolPack
```

目标流程：

```text
BotMain
  → 读取 BotConfig
  → 构建 connect/video/account 服务
  → BotRuntime.prepare(...) 返回运行时服务上下文
  → BotToolCatalog.create(context)
  → BotMcpServer(tools)
```

`BotRuntime` 只提供运行时服务，不 import 任何 B 站/视频/浏览器类；
`BotMain` 不再 `extraTools.add(...)`。QQ 工具类可以继续留在 `iibot-bot`，
但它们的“选择与注册”由 app catalog 调用；MCP 工具实现不得直接持有视频站 API 客户端。

### 4.2 工具命名

标准工具名：

```text
video_read
video_frames
video_comments
video_comment_reply
video_account_login_start
video_account_login_poll
```

兼容别名：

```text
bili_video_read
bili_video_frames
bili_account_login_start
bili_account_login_poll
```

工具描述必须自解释：`target`、`page`、`quality`、`time/times`、`count`、
`rootRpid`、`sort`、`cursor`、`strictQuality`、`accountId` 的语义；
`video_read` 无字幕或需要看画面时提示继续调用 `video_frames`。

## 5. 分辨率与精确时间

### 5.1 分辨率

```json
{
  "target": "BV...",
  "page": 1,
  "quality": "720p",
  "strictQuality": false
}
```

- `quality` 枚举：`auto/360p/480p/720p/1080p/best`；
- 内部映射 B 站 `qn`，并保留 `maxHeight` 兼容参数；
- 返回 `requestedQuality`、`actualWidth`、`actualHeight`、`availableQualities`、
  `fallback`；
- `strictQuality=false` 时允许回退并给 warning；`true` 时返回
  `QUALITY_UNAVAILABLE`；
- 账号登录态决定可用清晰度；匿名和登录态分别验收。

### 5.2 精确时间

```json
{
  "target": "BV...",
  "page": 2,
  "time": "01:23"
}
```

```json
{
  "target": "BV...",
  "page": 2,
  "times": ["00:10", "01:23", "02:05"]
}
```

- 时间相对指定分P开头；
- 支持 `SS`、`MM:SS`、`HH:MM:SS`；
- `time`/`times` 与 `count` 互斥；
- `times` 最多 9 个；
- 超出当前分P时长返回 `INVALID_TIMESTAMPS`，不静默截断；
- 输出 `mode=exact|uniform` 和每帧 `timestampSeconds`。

## 6. 评论区查询与交互边界

### 6.1 评论查询

新增按需工具：

```text
video_comments(
  target,
  page?=1,
  rootRpid?       # 有值查楼中楼
  sort?=hot|time
  cursor?         # 不透明游标
  limit?=20
)
```

- 只按需查询，不自动全量扫描；
- 顶层评论和楼中楼使用不同查询参数；
- 结果包含作者、内容、点赞、时间、root/parent rpid，不包含原始请求头；
- 默认 `limit<=20`，硬上限 100；分页游标由 connect 生成，Agent 不维护内部 index。

### 6.2 评论通知入站

现有 `BilibiliAdapter` 的评论回复通知轮询继续只处理“回复我的/提及我的”通知，
进入消息板/Inbox。它和 `video_comments` 是两条路径：

- 通知路径 = 有人回复我 → 唤醒；
- 查询路径 = Agent 主动读评论区 → 不产生未读。

### 6.3 交互范围

当前项目既有非目标包括“自动刷视频、自动评论、自动私信、自动点赞/关注”。
D-035 允许**显式、单次、可审计**的评论写入能力，但点赞/投币/收藏/关注的范围
仍待用户确认：

- 方案 A：只统一评论读写、私信、账号登录；
- 方案 B：点赞/投币/收藏/关注也进 connect，但仍默认关闭、每次显式调用、不得自动循环。

在该问题确认前，不实现额外交互写接口。

## 7. 无头浏览器后端

### 7.1 定位

浏览器是正式兼容的 **可选 backend**，不是所有视频请求的默认路径：

- `ffmpeg` + 原生 HTTP 继续作为公开视频抽帧默认；
- 浏览器用于 JS 页面、登录态、页面交互、评论 fallback 和需要真实浏览器的场景；
- 不把浏览器能力直接暴露成 Agent 工具。

### 7.2 接口

```text
BrowserBackend
  open(BrowserProfile) -> BrowserSession
  captureFrame(BrowserFrameRequest) -> BrowserFrameResult
  close()
```

`BrowserProfile` 只包含站点、账号引用、headless、viewport、语言、超时；
不包含模型可传的任意 URL/JS。

`BrowserGuard` 强制：

- 只允许 B 站域名及必要登录域名；
- 禁止 `file:`, `data:`, `javascript:` 和任意外部 URL；
- 不提供通用 `evaluate` 给 Agent；页面脚本只来自 B 站专用适配器；
- 浏览器进程 stdout/stderr 有界、脱敏。

### 7.3 后端模式

- `embedded`：Playwright Java 启动系统 Chromium，适合本地/单机；
- `remote-cdp`：Playwright `connectOverCDP` 连接独立 Chromium sidecar，适合服务器；
- 两种模式共用 `BrowserBackend` 和 B 站页面脚本；
- 首版选 Playwright Java，Chromium 在镜像构建时安装，不在启动时下载。

### 7.4 服务器约束

- Linux `headless=true`，不依赖 Xvfb；
- 非 root 运行；`--no-sandbox` 仅在容器非 root 场景允许，不默认；
- `--disable-dev-shm-usage`；
- 临时 profile 0700，退出清理；
- CPU/内存/并发/页面超时限制；
- CDP 只绑回环或 Unix socket，不暴露公网；
- 浏览器崩溃不拖垮 Java 进程，返回稳定错误码。

### 7.5 视频帧与浏览器

浏览器抽帧路径：

```text
navigate B站视频页
→ 等待 video/player
→ 设置 currentTime
→ 等待 seeked
→ 截取 video 元素或页面
→ 返回 PNG/JPEG 字节
```

风险：

- 部分 Chromium 构建不带 H.264 解码，B 站 AVC 视频可能黑屏；
- MSE/Canvas/DRM 可能阻止元素截图；
- 失败返回 `BROWSER_VIDEO_DECODE_UNAVAILABLE` / `BROWSER_FRAME_TIMEOUT`，
  不伪造成功。

## 8. 配置设计

新增顶层 `video` 配置（示意，最终以 schema 为准）：

```json
{
  "video": {
    "frameBackend": "ffmpeg",
    "defaultQuality": "720p",
    "maxHeight": 720,
    "defaultFrameCount": 6,
    "maxFrameCount": 9,
    "maxVideoBytes": 125829120,
    "maxFrameBytes": 8388608,
    "tempDir": "../data/video-tmp",
    "comments": {
      "defaultLimit": 20,
      "maxLimit": 100,
      "allowFullScan": false
    },
    "browser": {
      "enabled": false,
      "mode": "embedded",
      "executablePath": "/usr/bin/chromium",
      "cdpEndpoint": "",
      "headless": true,
      "maxConcurrentSessions": 2,
      "timeoutSeconds": 60,
      "profileRoot": "../data/browser-profiles",
      "viewportWidth": 1280,
      "viewportHeight": 720
    }
  }
}
```

规则：

- 未知字段 fail-closed；
- `browser.mode=remote-cdp` 时 `cdpEndpoint` 必须存在且为 loopback ws/http；
- `browser.enabled=false` 时不得启动浏览器进程，工具返回明确 `BROWSER_DISABLED`；
- `frameBackend=ffmpeg|browser|auto`，`auto` 先 ffmpeg 再 browser；
- 所有上限有硬编码安全上限，配置不能超过；
- `--check-config` 和 `--print-effective-config` 必须覆盖新字段和脱敏；
- 账号凭据仍只存 env 名称，Browser profile 不放仓库。

### 8.1 热更新矩阵

| 配置 | 启动生效 | 可热更新 |
|---|---|---|
| accounts/credentials | 是 | 否（需重启） |
| browser executable/CDP | 是 | 否 |
| logging levels | 是 | 是 |
| quality/frame counts/timeouts | 是 | 是（下一请求） |
| comment limits | 是 | 是（下一请求） |
| browser concurrency | 是 | 是（队列生效） |

不实现“部分账号 hot reload”作为第一阶段；账号/凭据变更重启，避免半状态。

## 9. 日志设计

新增包级 logger：

```text
io.mosire.iibot.video
io.mosire.iibot.video.bilibili
io.mosire.iibot.video.browser
```

必须记录：

- `video_read_started/completed`：site、bvid、page、accountId、耗时、结果码；
- `video_frames_started/completed`：count/mode/quality、实际分辨率、帧数、字节、耗时；
- `video_comments_query`：count、rootRpid 是否存在、cursor 是否存在、耗时；
- `video_comment_write`：accountId、bvid、root 是否存在、结果码、是否 UNKNOWN；
- `browser_started/stopped/failed`：mode、backend、进程/上下文数量；
- `browser_frame`：backend、viewport、耗时、结果码；
- `browser_cleanup`：profile 是否清理成功。

禁止记录：

- 完整正文、评论正文、字幕正文；
- Cookie、Authorization、CDP URL token、带签名的视频 URL；
- 浏览器 profile 文件内容、截图内容；
- 完整页面 HTML。

日志级别：

- INFO：生命周期、最终结果、发送/写入、失败；
- DEBUG：耗时、选择的 backend/quality、队列等待；
- TRACE 仍不得输出正文/凭据。

浏览器子进程 stdout/stderr 只保留有界、脱敏后的最后 N 行，禁止直接继承到主日志。

## 10. 验收判据

### 10.1 模块与注册

- `iibot-connect` 的 main 依赖树中没有 Playwright、Chromium、ffmpeg、B 站视频类；
- `iibot-video-connect` 不依赖 AgentLib/MCP；
- `iibot-bot` 不依赖 `iibot-video-connect`；
- V2 完成后 `iibot-app` 是唯一 MCP 工具注册点；V1 仍在 BotMain/BotRuntime 注册；
- V2 完成后 `BotRuntime`/`BotMain` 无 Bili/Video import；V1 当前仍直接注册；
- 工具名无重复，兼容别名可同时调用；
- 旧 B 站私信/评论通知、conversation_* 和 QQ 工具行为不回归。

### 10.2 视频与评论

- `quality` 可请求 360p/480p/720p/1080p/best；
- 不可用清晰度在 strict=true 时失败、strict=false 时回退并返回实际值；
- `time/times` 精确抽帧时间落在 `timestampSeconds`；
- 越界时间返回 `INVALID_TIMESTAMPS`；
- 评论顶层/楼中楼分页游标可继续；`allowFullScan=false` 时不能全量扫描；
- 评论通知仍只入 Inbox 一次，不重复。

### 10.3 浏览器

- Linux headless Playwright 启动成功，无 Xvfb；
- `embedded` 和 `remote-cdp` 至少各一个 smoke；
- 本地 fake 页面截图非空白；
- 真实 B 站公开视频浏览器抽帧有非空白结果或明确 codec 错误；
- 超时、崩溃、profile 清理、并发上限有负向测试；
- 日志/异常零 Cookie、零 token、零正文；
- 服务器镜像可构建、可启动、可优雅关闭。

### 10.4 配置与日志

- schema 严格校验未知字段；
- `--check-config` 对每种非法 browser/quality/cap 组合给来源文件和 JSON pointer；
- `--print-effective-config` 脱敏且包含新配置；
- 包级日志级别可配置；
- 视频/浏览器路径日志正文与凭据零命中；
- 配置热更新矩阵按第 8.1 节验收。

## 11. 开发阶段

详见实施计划。阶段顺序固定为：

```text
V0 文档冻结
V1 新模块抽取（行为不变）
V2 app 单一工具目录（行为不变）
V3 精确时间 + 分辨率 + 评论查询
V4 Playwright embedded 浏览器 backend
V5 remote-cdp + 服务器部署
V6 交互写入（已选范围 B）
V7 真实 B 站/服务器验收
```

任何阶段未通过 docs/full/负向验收，不得进入下一阶段。

## 12. 未定项

1. 视频站交互范围：用户已选 **B**（A + 点赞/投币/收藏/关注），V6 实现，默认关闭、单次显式、禁止自动循环；
2. `video_*` 标准名与 `bili_*` 兼容别名的最终保留周期；
3. 服务器默认采用 `remote-cdp` 还是 `embedded`（建议 remote-cdp）；
4. 浏览器是否参与评论查询 fallback，还是只做页面/帧 fallback；
5. 是否允许热更新账号凭据（当前设计为重启）。
