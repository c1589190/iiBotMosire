# DSH「看 B 站视频」热门项目调查与 iibot 整合建议

> **状态**：调查（2026-10-29，Asia/Shanghai）
> **用户原话**：
> > 「先调查一下网上很火的那个给dsh看b站视频的相关项目，我想把这个功能整合进iibot里」
> **本文性质**：外部项目与本机复现证据报告，不是设计裁定，也不是实施计划。
> **关联边界**：[D-025 统一 QQ Agent 客户端](../specs/2026-10-24-user-requirements-source.md)、
> [统一 QQ Agent 客户端目标架构](../specs/2026-10-28-unified-qq-agent-client-architecture.md)。

## 一、先纠正一个容易混淆的点

用户说的「给 DSH 看 B 站视频」在网上至少对应两条不同的产品线：

1. **自主刷 B 站并参与互动**：`dsh-bili-scroll`、`dsh-bili-whale`。它们把
   「找视频 → 读字幕/评论/弹幕/画面 → 让模型判断 → 写日报或 @ 主人/评论/私信」
   串成一个 DSH 内的自主 Agent 循环。
2. **把单条 B 站视频变成模型可读内容**：`dsh-bili-watch`、`bilibili-mcp`。
   它们是内容提取器，不决定模型该不该评论，也不运行 LLM。

真正「看视频」的底层能力在第二类；第一类爆火主要来自 **自主作息、人格、日报和评论区互动**，
而不是视频理解算法本身。

## 二、项目对比（读取日期 2026-10-29）

GitHub 元数据来自各仓 API；star/fork/issue 是读取瞬间的值，会变化。

| 项目 | 热度（stars/forks/issues） | 当前版本与许可 | 定位 | 对 iibot 的关系 |
|---|---:|---|---|---|
| [c1overzz/dsh-bili-scroll](https://github.com/c1overzz/dsh-bili-scroll) | 1007 / 109 / 113 | 2.3.1，MIT | DSH 插件：按关键词/推荐刷 B 站，读字幕原文筛视频，写 Markdown 日报，可选评论并 @ 主人；支持扫码登录、定时、谷时执行、风控保护 | 爆火主线；但包含 DSH 宿主生命周期、人格、外发互动，不能直接搬进 QQ 客户端核心 |
| [Alterssss/dsh-bili-whale](https://github.com/Alterssss/dsh-bili-whale) | 737 / 74 / 18 | 0.4.6，MIT | DSH 插件：自主生物钟、六路看懂（封面/简介/字幕/弹幕热区/热评/抽帧）、双日记、评论/私信/直播互动、偏好与克制 | 产品理念最完整，但耦合 DSH `sessionController.prompt`、人格和 B 站写操作，不适合作为 iibot 通用层 |
| [Sacredbluedragon/dsh-bili-watch](https://github.com/Sacredbluedragon/dsh-bili-watch) | 5 / 0 / 0 | 0.3.5，MIT | DSH 插件 + CLI：元数据、评论、弹幕、AI 字幕、本地 ASR、抽帧 OCR、九宫格、字幕带；15 个子命令，四级降级链 | 最接近「给模型看视频」的可复用提取器；CLI 边界清楚，最适合做 iibot 后端候选 |
| [nuko777/bilibili-mcp](https://github.com/nuko777/bilibili-mcp) | 0 / 0 / 0 | 0.1.0-beta.5，MIT | 通用 stdio MCP Server，Node ≥20.10，零运行时依赖；只读榜单/视频/字幕/关键帧/评论/搜索等，写操作默认 dry-run | 协议最通用，但项目很新；完整工具面含大量账号写操作，接入时应只暴露只读子集 |

补充：`dsh-bili-watch` 的 star 数不高，但功能形态与用户原话最贴合；
`dsh-bili-scroll` 是近期传播量最大的「让 DSH 自己刷 B 站」项目。

## 三、各项目真正可复用的技术形状

### 3.1 dsh-bili-scroll

一手资料：仓库 README、`package.json`、主插件源码与随包 vendored MCP。

- Node.js ≥22；DSH 插件 bundle patch；`index.js` 约 3000 行，另有约 58 KB 的 Web 设置页。
- 视频数据层是随包的 Python `vendor/bilibili-mcp/mcp_server.py`；依赖
  `bilibili-api-python>=17.4.1,<18`、`aiohttp`、`mcp<2`、`qrcode`、Pillow。
- README 给出约 84 MB（不装本地转写）到约 1 GB（含 Whisper 模型）的空间开销。
- 插件层提供搜索、字幕、评论、封面、抽帧、弹幕、推荐、转写、回复、登录、状态、配置等工具。
- 「值得」的判断、日报和评论都由宿主模型完成；插件负责调度、工具白名单、额度、风控和落盘。
- 风险点：DSH 版本敏感；有 113 个 open issues；写操作涉及真实 B 站公开互动。

### 3.2 dsh-bili-whale

一手资料：仓库 README、`package.json`、源码树、v0.4.6 文档。

- Node.js ESM；DSH 插件，自主生物钟通过定期 tick 调 `sessionController.prompt` 叫醒助手。
- 六路内容证据：封面、简介、官方字幕、弹幕热区、热评、抽帧。
- 有独立的评论/私信/直播监听、两个日记、偏好评分、分享额度、风控退避。
- 外部互动默认值按「刚创的小号」设定，说明其真实副作用和风控成本。
- 主要价值在 Agent 行为设计与节制策略，不在可独立抽出的视频解析库。

### 3.3 dsh-bili-watch

一手资料：仓库 README、`package.json`、`tools/look.mjs`、`tools/ocr.py`、依赖脚本。

- Node.js ≥22；既注册 DSH `bili_watch` 工具，也提供可直接执行的 `bili-watch` CLI。
- 四个降级层：B 站 AI 字幕 → 本地 faster-whisper ASR → 抽帧 OCR → 交给宿主读图。
- 匿名可用元数据、评论、弹幕、搜索、热榜；字幕需登录；ASR/OCR/ffmpeg 是可选依赖。
- CLI 有明确命令白名单：`view/peek/sub/comments/danmaku/asr/frames/sheet/substrip/ocr/text/search/hot/clean/login`。
- 输出有 JSON 模式、全文落盘与摘要上限，适合程序消费。
- 局限：DSH 插件形态依赖 DSH；CLI 仍需 Node，ASR/OCR 需要额外 Python 环境和模型。

### 3.4 bilibili-mcp

一手资料：仓库 README、`src/tools/index.mjs`、`src/media/*`、`package.json`。

- stdio MCP JSON-RPC；Node ≥20.10；零运行时依赖；MIT。
- 最新源码中实际注册 43 个工具，其中大量是账号写操作；不能把全部工具直接暴露给 QQ 场景。
- 只读侧包含 `video_info`、`video_transcript`、`video_frames`、`comments`、`danmaku`、
  `search`、榜单等，能覆盖第一版「看一个视频」的核心。
- WBI 签名、字幕选择、DASH 播放流、ffmpeg 抽帧等均已有实现，适合作为设计参考或只读 sidecar。

## 四、本机复现证据

调查使用的仓库均浅克隆到 `/tmp/bili-investigate/`，未改动工作区源码。

| 命令 | 结果 | 说明 |
|---|---|---|
| `npm test`（`bilibili-mcp`） | PASS：35 tests / 0 fail | 离线协议、WBI、参数校验、弹幕、评论、动态等 |
| `node tools/verify.mjs`（`dsh-bili-watch`） | PASS：6 通过 / 0 失败 / 2 联网断言跳过 | 版本、锁、缓存、JSON 数据模式等静态检查 |
| `node tools/look.mjs view BV1EPnG6DEJC` | rc=0 | 匿名取到标题、UP、时长、简介、热评 |
| `node tools/look.mjs comments BV1EPnG6DEJC 3 --format json` | rc=0 | 匿名取到结构化热评 |
| `node tools/look.mjs danmaku BV1EPnG6DEJC --format json` | rc=0；181 条 | 匿名取到结构化弹幕 |
| `node src/cli.mjs video BV1EPnG6DEJC`（`bilibili-mcp`） | rc=0 | 匿名取到完整视频元数据 JSON |
| `node src/cli.mjs transcript BV1EPnG6DEJC` | rc=0；`has_subtitle=false` | 无登录态时字幕为空，符合其设计 |

本机工具链：JDK 21、Node.js、Python 3.12、ffmpeg 6.1、yt-dlp 均存在；这只能证明
当前机器可做 PoC，不能替代目标部署环境的依赖验收。

**结论**：匿名视频元数据/评论/弹幕链路当前可用；字幕、ASR、OCR、抽帧需要额外登录态
或外部依赖，必须作为可降级能力，不能在 iibot 启动时强依赖。

## 五、与 iibot 现有边界的冲突

当前 iibot 的事实边界：

- D-025：默认产品是「面向各类 Agent 软件的统一 QQ 消息客户端」；
- 不在 iibot 内运行 LLM 或业务主 Agent；
- 默认 MCP 业务命名空间是 `qq_*`，工具不得让模型构造任意 URL、配置、文件路径或 shell；
- 真实 QQ 外发和 B 站公开互动都属于外部副作用，必须有明确决策；
- 默认链路必须可 fail-closed、可脱敏、只绑回环地址。

因此不能把 `dsh-bili-scroll` / `dsh-bili-whale` 原样搬入 iibot：

1. 它们强依赖 DSH 的 session、插件设置页和 bundle 生命周期；
2. 自动评论、私信、点赞、关注会引入 B 站账号风控和公开外发，不属于「QQ 客户端」；
3. 把 B 站监控和人格循环塞进 iibot，会把 iibot 变成第二个 Agent 宿主，违反 D-025；
4. 直接执行插件仓库里的脚本，会突破「不允许任意 shell 执行口」的红线。

可复用的是「把 B 站单条视频转成有界、结构化、模型可消费内容」的能力，以及其限流、
缓存、降级和风控经验。

## 六、建议的整合方案

### 推荐：只读 B 站内容工具，与 Agent 循环分离

目标数据流：

```text
QQ 消息中的 B 站链接 / BV 号
        │
        ▼
iibot 消息板与 Inbox（不阻塞入站）
        │  Agent 被唤醒后调用只读工具
        ▼
BiliContentProvider（iibot 扩展）
        │
        ├── 元数据 / 简介 / 热评 / 弹幕：匿名可读
        ├── AI 字幕：可选登录态
        ├── 本地 ASR / 抽帧 OCR：可选外部 backend
        └── 画面帧：可选 ffmpeg；只返回受控引用或文本摘要
        │
        ▼
外部 Agent（DSH / OpenCode / 其他）自行总结、判断、决定是否回复 QQ
        │
        ▼
iibot 的 qq_conversation_send（仍需 Agent/用户明确决策）
```

建议的第一版产品边界：

- 只读，不自动评论、不点赞、不私信、不关注、不改 B 站账号状态；
- 不自动遍历搜索或生成日报；由 Agent 对用户给出的目标视频按需调用；
- 不把视频全文直接塞进 Inbox 摘要；Inbox 仍只做 bounded preview，详情走工具；
- 不阻塞 QQ 入站；网络失败、无字幕、无 ffmpeg 都有稳定错误码；
- 输出有字符上限、超时、缓存和脱敏；不返回 Cookie、Authorization、完整协议 JSON；
- 外部命令只允许固定可执行文件和固定参数白名单，不能让模型拼 shell。

### 架构位置

有两个可选位置，建议在写实现前由用户确认：

**方案 A：扩展现有 Bot MCP（一个 connector）**

- 在 `iibot-bot` 增加 `bili_*` 只读工具，例如 `bili_video_read`、`bili_video_frames`；
- DSH/OpenCode 继续只挂 iibot 的 Bot MCP；
- 改动小、使用简单；
- 但把非 QQ 领域混进 Bot MCP，需要在设计上明确给 D-025 增加边界。

**方案 B：独立 `iibot-bili` 伴侣 MCP（两个 connector，推荐）**

- 新增一个只读 MCP 模块/进程，独立回环地址和 token；
- 与 iibot 共享配置目录但独立生命周期；
- 保持 QQ 核心纯净，任何 MCP 客户端都可单独挂载；
- 代价是外部 Agent 需要挂第二个 connector，部署多一步。

若必须坚持「一个 iibot 进程、一个 Agent connector」，选方案 A；若优先保持
「iibot 只做 QQ 客户端」的长期边界，选方案 B。

### 后端选择

**v1：固定 CLI backend（最快落地）**

- 把 `dsh-bili-watch` 的 CLI 作为可选 `BiliContentProvider`；
- 配置里只保存可执行文件路径、超时、可选凭证文件路径和缓存目录；
- iibot 只调用 `view/comments/danmaku/text` 等只读子命令，禁止透传任意参数；
- 优点：立刻获得字幕、ASR、OCR、抽帧、短链展开和缓存；MIT 可组合；
- 缺点：部署需要 Node 22；ASR/OCR 另需 Python/ffmpeg；需要进程超时和输出上限保护。

**v2：纯 Java 原生 provider（长期）**

- 直接实现 B 站公开 API、WBI、字幕与评论/弹幕读取；
- 只读子集较可控，可摆脱 Node 依赖；
- ASR/OCR 仍需要外部能力；不要为了“纯 Java”先在 iibot 内塞一个模型运行时。

不建议把 `dsh-bili-scroll` 的整个 vendored Python MCP 原样复制进本仓：它会显著扩大
依赖、镜像和运维面，而且 113 个 open issues 的上游升级风险不应转嫁给 iibot。

## 七、建议的验收判据（提案，非已实施）

1. 接受 `BV`、`av`、`bilibili.com/video/...`、`b23.tv/...`，拒绝非白名单 host 和任意本地路径；
2. 无登录态时能返回元数据、简介、热评、弹幕；无字幕返回稳定的 `SUBTITLE_UNAVAILABLE`，
   不把“无字幕”伪装成空内容成功；
3. 外部 backend 不可执行、超时、非零退出、输出超限时 fail-closed，返回稳定错误码；
4. 工具输出按字符/条数截断，完整正文只落受控缓存，不进入日志或 Inbox 摘要；
5. Cookie/token/Authorization 在工具结果、日志、异常中零出现；
6. 入站 QQ 消息处理不因 B 站网络慢而阻塞；B 站工具调用不自动触发任何 B 站写操作；
7. 测试覆盖短链展开、host 校验、参数注入、超时、截断、缓存命中和降级链；
8. 真实 B 站匿名读取可做隔离 smoke；登录态、ASR、OCR、真实 QQ/DSH 闭环分别标记，
   没有真实运行时证据不得写 PASS。

## 八、用户已选与后续待定

1. **已选方案 A（2026-10-30）**：并入现有 Bot MCP，不新增第二个 connector；
   上文对方案 B 的推荐仅保留为架构对照，不再是当前选择。
2. 后续待定：第一版是否接受 Node/CLI 外部 backend，还是必须纯 Java 起步；
3. 后续待定：第一版范围是否只做「按需读单条视频」，还是还要在 QQ 入站时
   自动附加 B 站链接预览；
4. 后续待定：评论区 Adapter 是否只做只读/显式发送，还是允许自主评论；
   当前建议明确排除自动点赞、私信、关注和批量评论。

## 九、追加调查：B 站 Adapter 是否存在（2026-10-29）

这一节只回答「评论区收发能不能走 iibot 通用消息渠道，以及有没有现成 B 站 Adapter 可直接用」。

### 9.1 iibot 本仓

本仓目前没有任何 B 站 Adapter：

- `iibot-connect` 里唯一的 `MessagingAdapter` 实现是
  [`NapCatAdapter`](../../../iibot-connect/src/main/java/io/mosire/iibot/connect/napcat/NapCatAdapter.java)；
- 配置加载器只接受 `adapter = onebot11`；
- `ChatType` 只有 `PRIVATE` / `GROUP`；
- `InboundMessage.SessionType` 和 `OutboundMessage.TargetType` 也只有私聊/群聊；
- `MessageSourceAddress` 已经能解析 `bili_video/...` 这种地址，但
  `toChatSourceKey()` 只接受 QQ 群/私聊。

所以答案不是「已有 B 站 Adapter 没接上」，而是**本仓确实没有，需要新增**。
新增 Adapter 不等于重写全部 B 站协议：可以把 B 站网页 API 封装成
`BilibiliAdapter implements MessagingAdapter`。

### 9.2 外部是否有可复用 Adapter

| 项目 | 实现 | 能力 | 可复用性结论 |
|---|---|---|---|
| [ncatbot/NcatBot](https://github.com/ncatbot/NcatBot) 的 `BilibiliAdapter` | Python，依赖 `bilibili-api-python >=17` | 直播弹幕、私信、视频评论监听；评论轮询与发送都有实现 | 架构参考价值高，但不能直接放进 Java iibot；其仓库为 **非商业许可证**，不适合直接复制代码 |
| [Hina-Chat/astrbot_plugin_bilibili_adapter](https://github.com/Hina-Chat/astrbot_plugin_bilibili_adapter) | Python，aiohttp | B 站私信收发、图片、轮询、WBI；不覆盖评论区 | 只能参考私信/轮询实现；不是评论区 Adapter，许可证也不清晰 |
| [nuko777/bilibili-mcp](https://github.com/nuko777/bilibili-mcp) | Node.js，零运行时依赖 | 评论列表/发评论、通知、视频信息、字幕、关键帧等 MCP 工具 | 可作为 FaaS/CLI sidecar 的候选；但它是 MCP/CLI，不是 iibot 的 `MessagingAdapter`，仍需 poller + 映射层 |
| [dsh-bili-watch](https://github.com/Sacredbluedragon/dsh-bili-watch) | Node.js CLI/DSH 插件 | 视频内容提取、字幕、弹幕、评论读取；**没有发送能力** | 只适合做只读内容后端，不能满足评论区收发 |
| [abcLiyew/BiliBili-API](https://github.com/abcLiyew/BiliBili-API) | Java 17/Maven，MIT | 登录、Cookie、WBI、视频、评论列表、楼中楼；**明确不做发布/删除/点赞评论等写操作** | 是较好的 Java B 站读取参考；不能直接当完整评论 Adapter，写接口需要另加 |
| [SDLMoe/Yabapi](https://github.com/SDLMoe/Yabapi) | Kotlin/JVM，CC0 | 第三方 B 站 API，支持 JVM | 2023 年后停止维护且已 archived，不适合作为新依赖 |
| [lengpucheng/BilibiliClient](https://github.com/lengpucheng/BilibiliClient) | Java，MIT | 2021 年的匿名只读 API | 过旧，不支持登录态评论写操作，不采用 |

**结论：没有可直接搬进 iibot Java 代码的成熟 B 站评论 Adapter。**

### 9.3 直接用 B 站网页 API 是否可行

可行。评论区需要的核心端点很少：

1. **BVID → AID**：`GET /x/web-interface/view`
2. **读评论**：`GET /x/v2/reply`，参数 `type=1&oid=<aid>&pn&ps&sort`
3. **发评论/回复**：`POST /x/v2/reply/add`，Cookie 登录态，
   表单包含 `oid`、`type=1`、`message`、`csrf`，楼中楼还需要 `root` / `parent`
4. **回复我的**：`GET /x/msgfeed/reply`，需要 Cookie，返回 `root_id`、
   `source_id`、`target_id`、回复者和内容

本机匿名读取实测（不代替真实登录态验收）：

```text
GET /x/v2/reply?type=1&oid=117461382864435&pn=1&ps=20&sort=2
→ HTTP 200, code=0, 返回评论列表

GET /x/v2/reply/wbi/main（未签名）
→ HTTP 200, code=-403, 说明新版 WBI 端点需要签名

GET /x/msgfeed/reply（无 Cookie）
→ HTTP 200, code=-101 账号未登录
```

这说明：

- 匿名「看视频评论」可以直接做；
- 「看我收到的回复」需要登录态；
- 发评论需要登录 Cookie + `bili_jct` 作为 CSRF，真实写操作尚未执行，
  必须等用户提供独立测试账号并明确授权。

### 9.4 推荐实现路线

不建议为了评论发送引入 GPLv3 / 非商业 / 已停止维护的 Python SDK，也不建议新增
Node sidecar 只为调一个评论接口。推荐：

```text
iibot-connect/
  bilibili/
    BilibiliAdapter.java       # MessagingAdapter：轮询入站 + 评论出站
    BilibiliHttpClient.java    # JDK HttpClient + Jackson + Cookie/CSRF
    BilibiliCommentPoller.java # msgfeed/reply 或指定视频评论轮询
    BilibiliModels.java        # 只解析需要的字段
```

统一来源建议：

```text
bili_video/{accountId}/{bvid}                 # 视频评论区 thread
bili_comment/{accountId}/{bvid}/{rootRpid}    # 某个评论树的对话 thread（可选细化）
```

`BilibiliAdapter` 只实现传输与协议映射，不绕过 `BotReplyService`、`OutboxSender` 或消息板。
发送结果映射建议：

- B 站返回 `code=0` + `rpid` ⇒ `SENT`
- `-101/-111/12002/12003/12015/12016/12025/12035/12051` 等明确拒绝 ⇒ `FAILED_BEFORE_SEND`
- HTTP 超时/连接中断且不能确认请求是否到达 ⇒ `UNKNOWN`，禁止自动重发

入站不能假装是实时推送：B 站评论区只能轮询。第一版建议默认只轮询
`/x/msgfeed/reply`（别人回复/`@` 当前账号），不要默认扫描整个视频的全部评论，
否则请求量和风控成本都不可控。
