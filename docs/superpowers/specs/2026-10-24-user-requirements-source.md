# iiBotMosire 用户需求原话与裁定

> **状态**：有效（2026-10-28 重构）
> **历史原稿**：[`../../archive/legacy-game/specs/2026-10-24-user-requirements-source.md`](../../archive/legacy-game/specs/2026-10-24-user-requirements-source.md)
> **规则**：用户原话优先；历史 D-001..D-024 的完整正文已归档，本文只保留当前产品仍有效的裁定。

## 一、当前产品裁定

| 编号 | 主题 | 当前结论 |
|---|---|---|
| D-001 | QQ 接入与可替换 Adapter | 保留；OneBot 11 为当前协议，Adapter 必须可替换 |
| D-006 | Java + Maven | 保留 |
| D-009 | 文档体系先行 | 保留；设计/维护分账、唯一 STATUS、机械门禁 |
| D-010 | 私聊 + 群聊 | 保留；消息板按来源分 thread |
| D-019 | 外发最小间隔 | 保留；逻辑消息共享节奏限制 |
| D-023 | 正文约束只在 Skill/AGENTS.md | 保留；程序不得清洗、静默替换或 fallback |
| D-024 | Inbox 是来源未读桶 | 未读桶/预览/unreadCount 保留；Agent 手动 revision/snapshot ack 由 D-026 服务端游标替代 |
| D-025 | iibot 只做统一 QQ Agent 客户端 | 文游模块、旧 app 代码、旧运行区退出默认产品 |
| D-026 | 通用 QQ 工具 + 消息板服务端状态 | connect-agent 整合删除；多账号路由；read/ack 由服务端维护 |
| D-027 | debug/info 日志与易用配置 | 已实现 P2/P3；后续只维护 |
| D-028 | 依赖最新且可复现、文档一致 | 已完成 P6/P7a 主要依赖刷新；AgentLib 0.2.4 公开发布仍待上游 |
| D-034 | B 站通用视频阅读 + 账号/私信/评论 Inbox 会话 | 第一批已实施（code-level）；视频工具与 B 站 Adapter 分离，统一走 source/conversation |
| D-035 | 视频站 Connect 独立模块、无头浏览器与配置/日志 | V1/V2/V3 已完成；V4 待开始；当前 reactor 4 模块 |

## 二、当前用户原话

### D-025 产品重新定位

> 「首先，iibot模块不额外提供程序化的文游服务，只作为给各类Agent软件提供一个统一QQ消息客户端的功能，因此文游相关模块可以标弃用删除了」

裁定：

1. iibot 默认产品是为各类 Agent 软件提供统一 QQ 客户端能力；
2. 文游/legacy 代码、旧 MCP、旧模拟区、旧配置退出默认构建和运行；
3. 物理删除后的历史由 Git 保留。

### D-026 消息板、通用 QQ 工具与内部标记

> 「那这玩意不应该做成现有iibot的上游实现吗，天然的MCP工具，为啥没接进去」

> 「你的意思是，iibot的消息板适配相关lib，自己独立实现了一套发消息，原本通用的qq对接lib给晾在一边了，导致现在Agent一个iibot都不能切换Onebot配置，工具调用也不统一？你不难猜我的需求吧，就是消息板这块怎么办，总不能让Agent自己维护一个额外的标记系统吧」

裁定：

1. 一个 iibot 进程必须支持多个已配置 OneBot/账号，入站按账号隔离，出站按 thread.accountId 路由；
2. 消息板是正文、历史、未读、幂等和发送状态唯一事实源；Inbox 只做唤醒/预览；
3. Agent 不维护 revision、snapshot、itemId、内部 seq 或必填幂等键；
4. 读取语义：`qq_conversation_read(source)` 只预览并记录服务端 deliveredSeq；
   Agent 处理完成后调用 `qq_conversation_ack(source)`，ack 不接受内部标记；
5. 默认 MCP 统一为 `qq_*` 业务工具；`iibot-connect-agent` 整合删除。

### D-027 debug/info 日志与配置系统

> 「此外还有之前一直没补的运维，包括完善的debug/info log、完善且易用的配置文件系统，规划一下，出修改计划，写文档」

裁定：

1. 日志覆盖 INFO 运维事件和可配置 DEBUG 细节；技术日志与 ops 事件分文件；
2. 日志只记录 ID、状态、计数、错误码、耗时和参数 key，不记录正文、token、Authorization、完整协议 JSON；
3. 配置严格、可解释、可覆盖、可脱敏输出、启动前可诊断，并支持多账号；
4. 首次生成、schema、示例和 README 让用户不读源码即可启动；
5. 密钥只通过环境变量名引用。

### D-029 全唤醒与来源地址

> 「4 问题来了，你以什么定义消息值得wake？我认为还是目前保持全唤醒」

> 「5 记得老DevMosire的消息来源系统吗？我们需要一个统一的消息来源字段，例如qq_group/xxxx/xxxx、qq_private/xxxx、qq_system/xxxx等等，地址系统要做好」

> 「app的系统消息应该直接叫app_system」

裁定：

1. 新的入站消息全部唤醒，不做权重/阈值/静默 gate；
2. 统一 source 形式为 `qq_group/{account}/{group}`、`qq_private/{account}/{peer}`、
   `qq_system/{account}/{event}`、`app_system/{event}`；
3. 地址使用严格 parser、百分号编码和旧 threadId 兼容迁移；
4. 未来 `wechat_*` / `discord_*` 按同一 `family_kind` 语法扩展。

### D-030 ack 不依赖 read、支持消息序号

> **状态**：部分由 D-032 取代；当前默认 ack 只接受 source、返回 `more`，不再公开 seq。

> 「发现优化项，为啥必须要先read才能ack？inbox面板已经包含了agent需要的大部分消息信息，不管怎么样，应当允许agent直接ack处理掉消息」

> 「哦对了，还要允许agent直接用序号来ack，不要让agent必须传一串繁琐的hash」

裁定：

1. `qq_conversation_ack(source)` 不要求先 `read`；未 read 时直接归档当前 active Inbox item。
2. 已 read 时仍按 read 固定的 delivered 批次 ack，保留批次语义。
3. ack 不要求 itemId、revision、snapshot 或任何不透明 hash；可选 `seq` 为消息板 localSeq，
   `ack(source, seq=N)` 精确 ack 到该消息序号。
4. DSH view-ack 提示词必须说明 read 可选、可直接 ack、可按 seq ack。

### D-028 依赖与文档基线

> 「其次，iibot、inbox依赖的agentlib是最新版本吗？去调查一下，项目设计基本上没有其他花头了，主要就是依赖是否为最新版本、文档是否完善等」

> 「1 继续用0.2.4」

裁定：

1. iibot 与 inbox 固定 `agentlib-mosire:0.2.4`，不降级；
2. 上游 0.2.4 未公开 tag/Pages 属于 `PUBLICATION_GAP`，干净仓库可复现前必须补发布；
3. 其他依赖按最新稳定版收口；JUnit 6 单独迁移；
4. 文档必须与统一 QQ 客户端目标一致，旧文游文档归档，不进入当前导航。

### D-031 Bot 试运行高风险能力授权

- **日期**：2026-10-30
- **来源**：Bot 试运行摩擦 F-006/F-007/F-012/F-017/F-018/F-019 的拍板。
- **用户选择（逐字保留）**：
  > 「允许 Agent 常规使用」
  > 「普通 Agent 随时可用」
  > 「允许主动私聊任意人」
  > 「先做一个音频提取，后续写模型识别配置等，主要是现在我手头上没有」
  > 「宿主或系统调度器负责（推荐）」
  > 「不用禁止绕过MCP，但是MCP层面的发图片/表情/文件必须做」
  > 「实现，并且Agent想撤啥撤啥」

- **裁定**：
  1. **Raw LLBot 兜底**：允许 Agent 在日常生活中直接调用 LLBot HTTP，不要求所有动作先有 MCP
     封装。仍须遵守账号路由、回环默认、外发副作用不自动重试和 token 零泄露；
     raw token 不得进入模型上下文、日志、仓库或最终答复。
  2. **@全体成员**：允许普通 Agent 在需要时调用；不增加主人逐次确认门槛。MCP 应提供
     `mentionAll` 语义，不允许通过拼接任意 CQ Code 实现。
  3. **主动私聊**：允许 Bot 主动向任意 QQ 发起私聊，不设白名单。仍保留账号级技术限速、
     `UNKNOWN` 不自动重发和稳定错误码；每次外发必须写消息板并带幂等键。
  4. **语音**：第一阶段只做音频提取/落库和稳定媒体引用；ASR 模型识别配置后补。
     当前不宣称语音可直接转写成文字。
  5. **定时/延迟动作**：由宿主或系统调度器负责，iibot 不自建持久化调度器。
  6. **外发富消息**：不禁止 Agent 使用 Raw LLBot 兜底，但 MCP 层面必须实现图片、表情、
     文件发送；富消息走消息板、幂等、三态和审计，不允许只加一个裸 URL/path 参数。
  7. **撤回**：实现 `qq_message_recall` 语义工具；Agent 可自行决定撤回目标，不要求逐次
     主人确认。必须按消息所属账号路由、记录结果，不得伪造成功。

- **安全不变量（未被 D-031 取消）**：
  1. 默认监听仍只允许回环；
  2. raw token/Bearer/claim token/QQ 凭据不得进入日志、模型上下文、仓库或最终答复；
  3. `UNKNOWN` 外发不得自动重试；
  4. 多账号出站必须按来源 thread/account 路由，不能串号；
  5. 外部动作结果必须可审计，不能把 Raw HTTP 的结果写成 MCP 成功。

- **设计/计划正本**：
  [`2026-10-30-qq-outbound-actions-and-media-rulings.md`](2026-10-30-qq-outbound-actions-and-media-rulings.md)

### D-032 Inbox 短正文内联与 compact 工具结果

- **日期**：2026-10-30
- **用户原话**：
  > 「不要定上限，默认就上512，为啥要改inbox包？」
  > 「InboxMosire-DSH还需要改吗？iibotDSH具体要改啥，模块边界要确认好」
  > 「记文档，开始改吧」

- **裁定**：
  1. `InboxMosire` core 不改；继续把 item `kind` 作为 opaque 分类字段。
  2. Bot 生成的 digest 使用 `kind=digest_complete` 表示短正文已完整内联，
     `kind=digest` 表示仍需 read。
  3. `messageBoard.inlineMaxChars` 默认 `512`；不采用 200 默认值。
  4. `InboxMosire-DSH` 只增加通用 profile 能力：`completeKinds` 和
     `compactViewAckItems`；不得写 QQ 业务名、不得读 `content`。
  5. `iiBotMosire-DSH` 只提供 profile 数据、短文案和声明，不接管 SSE、binding、
     cursor、claim 或业务工具调用。
  6. `qq_conversation_read(source)` / `qq_conversation_ack(source)` 只接受 source
     作为定位参数；ack 结果只保留 `more`；显式 ack 仍保留。
  7. Inbox 内联正文仍是不可信用户内容，不能被执行成指令；权威正文仍在 Bot 消息板。
  8. `content` 的存储瘦身不属于本轮上下文收益，单独处理。

- **设计正本**：
  [`2026-10-30-inbox-inline-and-compact-tool-results.md`](2026-10-30-inbox-inline-and-compact-tool-results.md)

### D-034 B 站通用视频阅读与 Inbox 会话 Adapter

- **日期**：2026-10-31
- **用户原话**：
  > 「看视频这块，，我建议搞成通用工具，就是不管从哪里获取到b站视频链接都能看」
  > 「评论区则是要涉及inbox，主要需要处理账号登录、私信消息检测等」
  > 「其他没啥问题，开始吧」

- **裁定**：
  1. `bili_video_read` 是通用只读工具：输入只依赖 B 站链接/BV/av/b23，不要求来自
     QQ、消息板或 Inbox；任何 Agent 都可以直接调用。
  2. 视频阅读不产生消息板记录、不触发 Inbox、不发送 B 站评论/私信。
  3. B 站账号登录、Cookie/WBI、私信轮询、评论回复通知轮询、评论/私信发送由
     `BilibiliAdapter` 负责；入站进入 iibot 消息板并由 Inbox wake。
  4. B 站统一 source 使用 `bili_dm/{accountId}/{peerMid}`、
     `bili_comment/{accountId}/{bvid}/{rootRpid}`、`bili_video/{accountId}/{bvid}`。
  5. 通用会话工具增加 `conversation_read/ack/send` 别名；现有 `qq_conversation_*`
     继续兼容。B 站外发不得绕过 `BotReplyService`、`OutboxSender` 和三态。
  6. B 站写入默认关闭；`UNKNOWN` 不自动重发；评论/私信正文和 Cookie 不进入日志。
  7. DSH 不镜像 B 站工具、不保存 Cookie、不实现评论/私信业务规则。

- **设计正本**：
  [`2026-10-31-bilibili-generic-video-and-conversation-adapter.md`](2026-10-31-bilibili-generic-video-and-conversation-adapter.md)

### D-035 视频站 Connect 独立模块、无头浏览器与配置/日志

- **日期**：2026-10-31
- **用户原话**：
  > 「首先我建议把视频网站的视频抽取、评论区查询和整合，以及视屏网站的交互全都集中到一个统一的connect包里面方便管理，然后做标准MCP工具，统一在APP做注册，而不是分散在各个模块」
  > 「此外还有易用性，允许选择分辨率吗？」
  > 「无头浏览器是是必定要兼容的」
  > 「这个设计可以；然后是无头浏览器，能上吗？」
  > 「没问题，记文档，告诉我你的开发阶段规划」
  > 「哦对了，配置项与易维护改进也必须提上日程，相关灵活配置与日志要做好」

- **裁定**：
  1. 新增 `iibot-video-connect` Maven 模块，集中视频站视频抽取、元数据、评论查询/
     整合、播放流、抽帧、浏览器和无头交互；`iibot-connect` 只保留外部消息连接，
     不引入 B 站/ffmpeg/Chromium 依赖；`iibot-bot` 不依赖视频模块；`iibot-app`
     同时依赖两侧并做组合根。
  2. MCP 工具统一由 `iibot-app` 的单一 `BotToolCatalog` 注册；`BotRuntime`/
     `BotMain` 不再各自 `extraTools.add(...)`，`iibot-bot` 不再直接注册 B 站工具。
     标准工具名为 `video_read/frames/comments/comment_reply/account_login_*`，
     保留 `bili_*` 兼容别名。
  3. 视频读取支持 `quality=auto/360p/480p/720p/1080p/best`、`strictQuality`，
     返回 requested/actual/available/fallback；精确抽帧支持 `time/times`
     （SS/MM:SS/HH:MM:SS，相对分P，最多 9，越界 `INVALID_TIMESTAMPS`）。
  4. 评论区按需查询顶层评论/楼中楼，默认不全量扫描；评论通知入 Inbox 是独立路径。
  5. 无头浏览器作为正式兼容的 `BrowserBackend`：Playwright Java + 系统 Chromium，
     支持 `embedded` 与 `remote-cdp`；`ffmpeg` 仍是公开视频抽帧默认路径；不向
     Agent 暴露任意 URL/JavaScript 导航器；服务器 headless、非 root、profile 0700、
     资源/并发/超时限制、CDP 只回环，`--no-sandbox` 不默认。
  6. 配置与日志必须与功能同步：新增严格 schema 的 `video` 配置、未知字段
     fail-closed、`--check-config`/`--print-effective-config` 覆盖新字段、
     明确启动/热更新矩阵；新增 `io.mosire.iibot.video(.bilibili/.browser)` 包级
     logger，只记录 ID/状态/计数/耗时/结果码，正文、Cookie、token、URL、HTML
     不得入日志；浏览器 stdout/stderr 有界脱敏。
  7. 交互范围已选 **B**（评论/私信/登录 + 点赞/投币/收藏/关注）；V6 才实现，
     仍默认关闭、单次显式、可审计，禁止自动循环。
  8. 实施按 V0 文档冻结 → V1 新模块抽取 → V2 app 单一工具目录 → V3 精确时间/
     分辨率/评论查询 → V4 Playwright embedded → V5 remote-cdp/服务器 →
     V6 交互写入（范围 B）→ V7 真实 B 站/服务器验收；每阶段独立门禁，
     不得跨失败阶段继续。V0/V1/V2/V3 已完成，下一步 V4 Playwright embedded。

- **设计正本**：
  [`2026-10-31-video-site-connect-and-browser-backend.md`](2026-10-31-video-site-connect-and-browser-backend.md)
- **实施计划**：
  [`../plans/2026-10-31-video-site-connect-and-browser-backend-plan.md`](../plans/2026-10-31-video-site-connect-and-browser-backend-plan.md)

## 三、当前验收判据

- 当前 reactor 为 `iibot-connect` / `iibot-video-connect` / `iibot-bot` / `iibot-app`
  4 模块；D-035 V1 已创建 `iibot-video-connect` 并完成 B 站领域迁移；
  V2 已收敛 app 单一 `BotToolCatalog`/tool packs；V3 已实现
  quality/time/comments 与 `video.*` 配置；
- QQ 业务工具继续使用 `qq_*`；平台无关收发使用 `conversation_*` 别名；
  B 站内容读取使用 `video_read`/`video_frames`/`video_comments`，并保留
  `bili_video_read`/`bili_video_frames` 别名；不出现 `bot_*` / `tf_*` 业务工具；
- 多账号消息按 accountId 隔离，出站按 thread.accountId 路由；
- Agent 只传 threadId 即可 read + ack，不传 revision/snapshot/itemId/内部 seq；
- 日志 INFO/DEBUG 可配置，正文/token/Authorization 零命中；
- 配置有 schema、严格错误定位、脱敏有效配置和单文件首次生成；
- docs gate 与 full gate 通过；真实外部联调未跑写 `NOT_RUN`。

## 四、当前未裁定

1. 多 consumer 并发隔离与 per-consumer Inbox 绑定；
2. 是否提供宿主/运维侧的 `qq_account_use` 动态默认账号工具；
3. `qq_inbox_binding_descriptor` 是否完全移出 Agent MCP，改由宿主专用 handoff；
4. 回复成功后是否自动 ack（当前第一版统一要求显式 ack）；
5. `video_*` 标准工具名与 `bili_*` 兼容别名的保留周期。

历史 D-001..D-024 的详细正文与所有旧原话见归档原稿。
