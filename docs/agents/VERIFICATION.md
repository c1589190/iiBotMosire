# 验证维护手册

> **本文件属于项目维护。** 只记录当前统一 QQ Agent 客户端的验证入口、证据规则与已知边界。
> 最新实际门禁摘要见 [`STATUS.md`](STATUS.md) §3；没跑的命令必须写 `NOT_RUN`。

## 一、门禁入口

```bash
tools/gate.sh pre
tools/gate.sh docs
tools/gate.sh selftest
tools/gate.sh focused <module> [args...]
tools/gate.sh full
```

- `docs`：文档相对链接、目录结构、STATUS 字段与 Skill 登记。
- `selftest`：`check-docs.py` / `skills-lint.py` 的自测；不跑 Maven。
- `pre`：docs + Maven/Java 环境自检。
- `focused`：`./mvnw -B -ntp -pl <module> -am <args...>`，只能写 `FOCUSED TEST PASSED`。
- `full`：`./mvnw -B -ntp clean verify`，全 reactor 测试、打包与 shade。

当前 `verify` 未接入 SpotBugs/Checkstyle 静态门禁；`full` 只代表构建与测试通过。

## 二、当前基线

- 2026-10-31 D-035 V2 基线：app 已引入单一 `BotToolCatalog`/`BotToolContext` 与
  `QqToolPack`/`MediaToolPack`/`InboxToolPack`/`VideoSiteToolPack`；`BotRuntime` 只构造
  context 并调用 catalog，`BotMain` 只传 `withVideoAccounts`；旧 extraTools 入口保留。
  `tools/gate.sh full` PASS，**306 tests / 0 failures / 0 errors / 0 skipped**
  （connect 73、video 14、bot 127、app 92）；docs PASS（modules=4 files=50 links=162）。
  工具名、参数、返回形状未改；真实 B 站/浏览器外部联调 `NOT_RUN`。
- 2026-10-31 D-035 V1 基线：`iibot-video-connect` 第 4 模块已创建并完成 B 站领域/
  登录/抽帧迁移；`tools/gate.sh full` PASS，**304 tests / 0 failures / 0 errors /
  0 skipped**（connect 73、video 14、bot 127、app 90）；`tools/gate.sh docs`
  PASS（modules=4 files=50 links=162）。工具名、参数、返回形状未改。
  模块边界：`iibot-connect` 无 B 站 main 类；`iibot-video-connect` 不依赖 AgentLib/MCP；
  `iibot-bot` 不依赖 video；`iibot-app` 依赖 video 并承载 MCP 工具。
  真实 B 站登录/私信/评论发送、Playwright/B 站页面、服务器部署 `NOT_RUN`。
- 2026-10-30 D-033 第一阶段基线：`tools/gate.sh full` PASS，
  **282 tests / 0 failures / 0 errors / 0 skipped**（connect 73、bot 124、app 85）；
  `tools/gate.sh docs` PASS（modules=3 files=46 links=132）。本机 loopback/fake
  验证，真实 QQ/LLBot/DSH 联调仍为 `NOT_RUN`。
- 2026-10-31 D-033 DSH 侧基线：`InboxMosire-DSH` 0.7.0 `npm test` PASS，
  **150 tests / 147 pass / 3 live-skipped / 0 fail**；`iiBotMosire-DSH`
  wake-profile 2/2、persona 24/24 PASS。真实 DSH reload/rebind 与 generic
  view-ack live wake 仍为 `NOT_RUN`。
- 2026-10-31 D-034 基线：`tools/gate.sh full` PASS，**304 tests / 0 failures / 0 errors /
  0 skipped**（connect 86、bot 127、app 91）；`tools/gate.sh docs` PASS
  （modules=3 files=47 links=142）。B 站匿名 `bili_video_read` 公开元数据/热评
  smoke PASS；登录态字幕、真实私信/评论轮询与发送 `NOT_RUN`。
- 2026-10-31 D-035 V0 基线（docs-only，已被 V1 接续）：`tools/gate.sh docs` PASS
  （modules=3 files=49 links=159）；当时代码/配置未改，full 沿用 D-034 的 304 tests。
  snap Chromium 156 已安装，headless `about:blank` PASS；B 站页面/Playwright/
  服务器验收 `NOT_RUN`。
- 依赖：Jackson 2.22.4、SLF4J 2.0.20、JUnit 6.1.3、Java-WebSocket 1.6.0；
  `iibot-app` shaded jar 构建成功。
- Inbox 依赖刷新后 `./mvnw -B -ntp clean verify` PASS：37 tests / 0 failures / 0 errors。
- 干净空 Maven 仓库：`PUBLICATION_GAP` / `NOT_RUN`。公开 AgentLib 0.2.4 尚未发布，
  空仓全量下载也在 300 秒超时；不得用本机 `~/.m2` 冒充公开可复现。
- 真实 QQ/NapCat/DSH/OpenCode/MCP/Inbox 外部闭环、日志真实轮转与崩溃恢复：
  `NOT_RUN`。

## 三、证据规则

- PASS = 命令实际执行 + 退出码符合预期 + Surefire 汇总/XML 与关键输出核对。
- 管道取 `${PIPESTATUS[0]}`，不得拿 `tail` 的退出码。
- 关键证据保留 source SHA、命令、时间、测试数、报告路径与 run 边界。
- 旧报告、Mock、进程内回环测试不得混写成真实外部验收。
- 外部副作用测试（QQ 发送、好友审批、MCP/Inbox 外部连接）必须使用隔离实例或 fake；
  真实操作需用户当次授权。

## 四、当前测试分层

| 层 | 目的 | 当前状态 |
|---|---|---|
| 领域单测 | 消息板、thread、备注、好友申请、来源权重、consumer 游标 | 已有 |
| 契约测试 | OneBot 事件/动作 JSON、MCP schema、SQLite 迁移与重开 | 已有 |
| 应用集成 | BotRuntime + fake Adapter + embedded loopback Inbox/MCP | 已有 |
| B 站公开读取 | 元数据/热评/字幕/短链 | 元数据+热评 smoke PASS；ffmpeg 3 帧看图 smoke PASS；字幕/短链 `NOT_RUN` |
| B 站账号会话 | Cookie 登录、WBI、私信轮询、评论通知、私信/评论发送 | fake HTTP 单测 PASS；真实 `NOT_RUN` |
| 视频站模块/工具目录（D-035） | `iibot-video-connect`、app `BotToolCatalog`、quality/time、评论查询 | V1 模块已创建（14 tests）；V2 catalog/tool packs 已有单测（app 92 tests）；quality/time、评论查询 `NOT_RUN` |
| 无头浏览器（D-035） | Playwright embedded/remote-cdp、headless Linux、codec/超时/清理 | snap Chromium 156 headless `about:blank` PASS；Playwright/B 站页面/remote-cdp `NOT_RUN` |
| 真实 NapCat E2E | 真实私聊/群聊入站与发送 | `NOT_RUN` |
| 真实 DSH/OpenCode wake | 真实 wake/ack 闭环 | `NOT_RUN` |
| 长跑/崩溃恢复 | 多账号、日志轮转、进程崩溃与重启 | `NOT_RUN` |

## 五、当前负向判据

1. 重复 QQ `message_id` ⇒ 只入库/通知一次。
2. 入站 accountId 与绑定账号不一致 ⇒ `inbound_account_mismatch` fail-closed。
3. 出站 thread 的 accountId 无对应 Adapter ⇒ 不得改投其他账号。
4. 发送超时/`UNKNOWN` ⇒ 不得自动重试。
5. `qq_conversation_read` 不得推进 `readSeq`；重复读取返回同一 delivered 批次。
6. 未显式 ack 前新到消息不得被上一批 ack 吃掉。
7. ack 必须先成功同步 Inbox，再推进 consumer `readSeq`。
8. 多账号下 recent/history/friend request/user note 未指定 `accountId` ⇒ `ACCOUNT_REQUIRED`。
9. 重复 `idempotencyKey` ⇒ 不重复发送；省略时服务端生成并回传。
10. 日志/MCP 结果不得出现 raw token、Authorization、完整协议 JSON 或聊天正文。
11. 非回环监听、错误 Bearer、未知工具/参数 ⇒ fail-closed。
12. 配置 JSON 错误必须给来源文件与 JSON pointer；`--print-effective-config` 必须脱敏。
13. B 站 Cookie 文件非 0600、登录态 UID 与 accountId 后缀不一致、评论 root/parent
    缺失或跨 thread 时必须 fail-closed。
14. B 站私信/评论发送超时或响应不可解析 ⇒ `UNKNOWN`，不得自动重发。
15. 通用 source 的 `bili_dm/...`、`bili_comment/...` 不能落回 QQ private/group 分支。
16. D-035 `video_read` 请求不可用清晰度：`strictQuality=true` ⇒ `QUALITY_UNAVAILABLE`；
    `false` ⇒ 回退并返回 requested/actual/available/fallback，不得静默伪装成请求值。
17. D-035 `time/times` 超出分P时长 ⇒ `INVALID_TIMESTAMPS`，不得截断或抽错帧；
    `time/times` 与 `count` 同时出现 ⇒ 参数错误。
18. D-035 `browser.enabled=false` 不得启动浏览器；浏览器只能访问 B 站允许域名，
    禁止 `file:`/`data:`/`javascript:` 与 Agent 任意 URL/JS；失败必须有稳定错误码。
19. D-035 视频/浏览器日志、异常和 MCP 文本不得出现正文、Cookie、token、签名 URL
    或 HTML；浏览器 stdout/stderr 必须有界脱敏。
13. read 窗口只按入站消息计数；OUT 不能占用窗口或造成 `more=false` 假阴性。
14. replay 已投递批次时，若批次之后仍有未读入站，`more` 必须为 true。
15. `replyToSeq` 只能解析同一 thread 内消息；与 `replyToMessageId` 同时出现必须拒绝；
    mention 目标必须是同 thread 入站消息。

## 六、未验证表达

- “第三方文档说可以” ⇒ `文档结论（未实测）`。
- “本机源码存在 API” ⇒ `源码核实（未编译）`。
- “Mock/回环通过” ≠ “真实 NapCat/DSH/OpenCode 通过”。
- “构建通过” ≠ “长跑稳定”或“真实外部副作用已验证”。
