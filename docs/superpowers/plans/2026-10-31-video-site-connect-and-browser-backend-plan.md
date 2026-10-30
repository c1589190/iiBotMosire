# 视频站 Connect 与无头浏览器实施计划

> **状态**：V1/V2 已完成（2026-10-31）；V3 待开始
> **设计**：[`../specs/2026-10-31-video-site-connect-and-browser-backend.md`](../specs/2026-10-31-video-site-connect-and-browser-backend.md)
> **当前基线**：`7d24f9b`；**306 tests**（connect 73 / video 14 / bot 127 / app 92）；真实 B 站登录/私信/评论联调 `NOT_RUN`；Playwright 未接入。
> **用户要求**：视频站能力集中到 connect；MCP 统一在 app 注册；分辨率/精确时间；
> 无头浏览器必须兼容；配置项、易维护性和日志同步做好。

## 0. 执行规则

- 每个阶段独立可编译、可验收、可回滚；阶段结束更新 `STATUS.md` 并提交。
- 不跨过失败门禁进入下一阶段；不能用 fake 测试冒充真实 B 站/浏览器验收。
- 同一 checkout 串行 Maven；不覆盖运行中 jar。
- 新模块必须有 `AGENTS.md`，并进入 docs gate 的 Maven 模块检查。
- 任何真实 B 站写操作仍受 M-004 和用户当次授权约束。
- 真实浏览器、服务器部署未跑前统一写 `NOT_RUN`。

## V0 文档冻结

**目标**：把模块边界、配置、日志、浏览器安全条件和阶段顺序固定下来。

产物：

- D-035 设计文档；
- 本实施计划；
- `DESIGN_RULINGS.md`、需求原稿、`STATUS.md`、根/项目 `AGENTS.md` 链接更新。

验收：

- `tools/gate.sh docs` PASS；
- 代码/配置无改动；
- 用户确认的边界逐字可追溯。

## V1 `iibot-video-connect` 模块抽取（行为不变）

**目标**：新增第 4 个 Maven 模块，把视频领域从 `iibot-connect` 和 `iibot-app` 搬出；
先不改工具行为和外部 API。

工作项：

1. 根 `pom.xml` 增加 `iibot-video-connect`；
2. 新模块 `AGENTS.md`；
3. 迁移 B 站领域类：
   - `BilibiliHttpClient`、`BilibiliModels`、`BilibiliTarget`、
     `BilibiliTargetResolver`、`BilibiliCredential`、`BilibiliLoginClient`；
   - `BilibiliAdapter`、`BilibiliAdapterConfig`（依赖 connect core 的 MessagingAdapter）；
   - `BiliLoginService`、凭据原子落盘；
4. 迁移视频帧：
   - V1 实际保留旧名 `BiliFrameExtractor`、`FfmpegBiliFrameExtractor`；
     计划中的 `VideoFrameExtractor` 命名统一留到后续阶段；
5. `iibot-connect` 删除 B 站 main 类；V1 未保留 deprecated 转发类；
6. `iibot-app` 依赖新模块，MCP 工具暂时保持现有行为；
7. `BotConfig.Bilibili` 保持在 app 配置层，connect 只接收 `BilibiliAdapterConfig`。

验收：

- `iibot-connect` 依赖树无 B 站/ffmpeg/Playwright；
- `iibot-video-connect` 不依赖 AgentLib/MCP；
- 现有 B 站、QQ、Inbox 全量测试 PASS；
- 工具名和返回形状不变；
- `tools/gate.sh full` + docs PASS。

**V1 实际结果（2026-10-31，提交 `84fd1a8`）**：

- 新增 `iibot-video-connect`，迁移 8 个 B 站类、4 个 B 站测试、`BiliLoginService`
  与 `BiliFrameExtractor`/ffmpeg 实现；新增共享 `VideoAccountRef`。
- `iibot-connect` 不再包含 B 站 main 类；`iibot-bot` 未新增 video 依赖；
  `iibot-app` 新增 `iibot-video-connect` 依赖并更新 MCP 工具 import/类型。
- `tools/gate.sh full` PASS：**304 tests / 0 failures / 0 errors / 0 skipped**
  （connect 73、video 14、bot 127、app 90）；docs PASS（modules=4 files=50 links=162）。
- 工具名、参数、返回形状未改；真实 B 站/浏览器验收仍 `NOT_RUN`。

回滚：只回滚根 POM、模块目录和 import；不改数据库/配置。

## V2 App 单一 MCP 工具目录（行为不变）

**目标**：所有 MCP 工具注册集中到 `iibot-app`，`BotRuntime`/`BotMain` 不再各自注册。

工作项：

1. 新增 `BotToolContext` 与 `BotToolCatalog`；
2. `BotRuntime` 构造 board、mediaStore、replies、digest、capabilities、
   accountRegistry 等运行时服务并交给 catalog；
3. 新增 tool packs：
   - `QqToolPack`（调用现有 `BotMcpTools` 工厂）；
   - `MediaToolPack`；
   - `InboxToolPack`；
   - `VideoSiteToolPack`；
4. `BotRuntime` 移除 `BiliVideoFramesTool`、`BotMediaUploadTool` 等硬编码注册；
5. `BotMain` 移除 `extraTools`；
6. 工具名/别名 snapshot 测试集中到 app catalog。

验收：

- `BotRuntime` 无 `bilibili`/`video`/media/Inbox 工具 import；`BotMain` 不再构造
  `extraTools` 或直接 import B 站 MCP 工具（仍保留 B 站 Adapter 组合根创建）；
- 工具清单与旧版一致（或只多兼容别名）；
- MCP E2E、权限、重复工具名、启动失败回滚 PASS；
- 无行为变化，full gate PASS。

**V2 实际结果（2026-10-31，提交 `7d24f9b`）**：

- 新增 `io.mosire.iibot.app.mcp.BotToolCatalog`、`BotToolContext` 与
  `app.mcp.toolpack.{QqToolPack,MediaToolPack,InboxToolPack,VideoSiteToolPack}`。
- `BotToolCatalog` 提供 `standard()`（默认运行时仅匿名分帧）、
  `withVideoAccounts(...)`（生产路径保留 `bili_video_read` + 登录工具）和
  `withExtraTools(...)`（旧入口兼容）；工具顺序与关闭语义保持。
- `BotRuntime` 只构造 context 并调用 catalog；`BotMain` 只传
  `BotToolCatalog.withVideoAccounts(...)`。工具名、参数、返回形状未改。
- `tools/gate.sh full` PASS：**306 tests / 0 failures / 0 errors / 0 skipped**
  （connect 73、video 14、bot 127、app 92）；真实 B 站/浏览器验收仍 `NOT_RUN`。

## V3 精确时间、分辨率和评论查询

**目标**：补齐视频易用性，不改消息/Inbox 语义。

工作项：

1. `VideoQuality`/`VideoStreamInfo`：
   - `quality` 枚举和 `maxHeight` 兼容；
   - 返回 requested/actual/available/fallback；
   - `strictQuality` 行为；
2. 抽帧 `time`/`times`：
   - `SS`、`MM:SS`、`HH:MM:SS`；
   - 相对 page 起点；
   - 与 `count` 互斥，最多 9；
   - 越界 `INVALID_TIMESTAMPS`；
3. 评论查询：
   - 顶层/楼中楼分页；
   - 不透明 cursor；
   - `allowFullScan` 默认 false；
   - `video_comments` MCP 工具；
4. 兼容工具名：
   - `video_read/frames/comments` + `bili_*` alias；
5. 配置：
   - `video.defaultQuality/maxHeight/defaultFrameCount/maxFrameCount/comments.*`；
   - `--check-config` / `--print-effective-config` 覆盖。

验收：

- fake B 站 HTTP 单测覆盖 quality fallback/strict、精确时间、越界、游标；
- 真实公开视频 smoke：指定时间帧和分辨率；
- full/docs PASS。

## V4 Playwright embedded 浏览器 backend

**目标**：正式接入无头浏览器，先本地 embedded 跑通，不改变 ffmpeg 默认路径。

工作项：

1. `iibot-video-connect` 增加 `video/browser` SPI：
   - `BrowserBackend`、`BrowserSession`、`BrowserProfile`、
     `BrowserFrameRequest/Result`、`BrowserGuard`、`BrowserPolicy`；
2. 增加 Playwright Java 实现，Chromium 走系统路径；
3. 配置 `video.browser.*` + `enabled=false` 默认；
4. 懒加载：工具首次调用 browser 才启动；启动失败不拖垮 app；
5. B 站页面专用导航/等待/seek/截图脚本；
6. 日志事件、超时/kill/profile 清理；
7. 本地 fake 页面测试。

验收：

- `browser.enabled=false` 不启动进程；
- headless Chromium 启动、fake 页面截图非空白；
- 真实 B 站公开视频浏览器抽帧有非空白或明确 codec 错误；
- 超时、崩溃、并发、profile 权限、日志脱敏负向测试 PASS；
- 依赖只出现在 `iibot-video-connect`（或独立 browser 模块）。

## V5 remote-cdp 与服务器部署

**目标**：服务器可运行、可隔离、可恢复。

工作项：

1. `BrowserBackend` 增加 `remote-cdp`；
2. Docker/部署文档：Chromium + ffmpeg + Java；
3. Chromium sidecar 或同容器非 root 运行；
4. CDP 只绑回环/Unix socket；
5. CPU/内存/并发/超时限制，浏览器崩溃自动重启；
6. health check：browser 可用性、版本、冷却；
7. 服务器 smoke：真实 B 站页面、3 并发、优雅关闭。

验收：

- Linux 无 Xvfb；
- `remote-cdp` 连接、断线重连、崩溃恢复；
- 资源限制下不拖垮 Java；
- 真实服务器部署证据；未跑写 `NOT_RUN`。

## V6 交互写入（范围 B）

**前置**：已确认范围 B（评论/私信/登录 + 点赞/投币/收藏/关注）；仍默认关闭、单次显式、禁止自动循环。

工作项：

1. `VideoCommentWriter` + 顶层评论/回复；
2. 写入默认关闭、账号级配额、冷却、`UNKNOWN` 不重发；
3. 所有写入经 app 出站意图/消息板/审计；
4. 评论查询与通知路径不混淆；
5. B 选项的额外交互按每个动作单独 capability，拒绝自动循环。

验收：

- 未启用写入时 fail-closed；
- 重放/幂等、冷却、配额、UNKNOWN、审计 PASS；
- 真实写入仅在使用独立测试账号和用户当次授权后验收。

## V7 真实 B 站与服务器验收

工作项：

- 真实扫码登录；
- 私信/评论通知入站；
- 公开/登录态视频阅读、字幕、quality、精确时间抽帧；
- ffmpeg + browser 双 backend；
- 长视频、CDN 限速、Cookie 过期、风控、浏览器 codec；
- 服务器长跑、日志轮转、崩溃恢复。

验收：

- 真实证据保留；未跑 `NOT_RUN`；
- 全量门禁 PASS；
- `STATUS.md`/`VERIFICATION.md` 更新。

## 配置与日志的跨阶段要求

每个阶段都必须同步做：

- 新配置进入 JSON schema，严格未知字段；
- `--check-config` 错误带 file + JSON pointer；
- `--print-effective-config` 脱敏；
- 新包 logger 可配置级别；
- ops 事件只记录 ID/状态/计数/耗时，不记录正文/Cookie/URL token；
- 浏览器 stdout/stderr 有界脱敏；
- 配置热更新矩阵见设计第 8.1 节。

## 主要风险

1. **模块迁移造成 import/测试大改**：V1 先行为不变，保留兼容包装。
2. **BotRuntime 依赖面过大**：V2 用 `BotToolContext` 收窄，不做全量重写。
3. **Playwright 依赖体积/服务器镜像**：浏览器只在新模块；必要时拆 Playwright 实现子模块。
4. **Chromium H.264 解码缺失**：ffmpeg 保持默认；浏览器失败返回稳定 codec 错误。
5. **B 站风控/登录态**：真实账号验收单独进行，不用匿名结果冒充。
6. **评论扫描量过大**：默认只按需查询，`allowFullScan=false`。
7. **写入副作用**：默认关闭、用户授权、UNKNOWN 不重发。

## 停止条件

- 设计与用户原话/红线冲突：停止上报；
- 真实账号/浏览器/服务器需要用户提供：`WAITING_USER`；
- 任一阶段 full/docs/负向验收失败：不进入下一阶段；
- 无法达到验收判据：`BLOCKED`，不以“写进风险”替代。
