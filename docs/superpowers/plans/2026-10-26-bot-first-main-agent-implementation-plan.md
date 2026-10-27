# Bot-first 消息板与外部主 Agent 实施计划

> **状态**：实施中（2026-10-26；P0–P6 运行时/配置 CLI 已完成，默认入口切换与 P7 未开始）
> **依据**：[`../specs/2026-10-26-bot-first-main-agent-architecture.md`](../specs/2026-10-26-bot-first-main-agent-architecture.md)
> **当前分支**：`refactor/bot-first-main-agent`
> **当前源码基线**：`31f502b`（main 同步）；工作树包含 bot-first 文档、P0 依赖升级与 P1 新模块
> **用户约束**：
> > 「Agentlib、Inbox包都更新了，你应该先规划新的架构实现，然后先更新依赖再写代码」
> **停止条件**：涉及旧模块物理删除、根红线重写、真实 QQ 外发或真实 DSH 长跑时，先停下来请用户确认。

## 0. 计划原则

1. **依赖先行**：先完成 AgentLib 0.2.4 + Inbox summary/content/15 工具升级并通过 focused gate，再写 bot core。
2. **新旧隔离**：新增 `iibot-bot` / `iibot-bot-app`，先不删除旧 `iibot-taskflow` / `iibot-app`；旧路径保持可编译以便回滚。
3. **消息原文单一真相**：Inbox 只放摘要和恢复引用，正文永远在 `bot.db`。
4. **先纵向切片后扩面**：先打通“一条私聊入站 → 入库 → Inbox → 读摘要 → 恢复 → 回复”，再接群聊、备注、重启恢复。
5. **每阶段独立验收**：未跑的命令写 `NOT_RUN`，不以旧 627 tests 或旧工具数冒充新结果。

## 1. 阶段 P0：依赖升级与基线重建

### 目标

把现有工程从 AgentLib `0.2.3` 升到 `0.2.4`，把 Inbox 客户端从旧 11/`title` 语义升到 15/`summary/content` 语义；
不写 bot core 业务代码。

### 文件所有权

- `pom.xml`
- `iibot-app/pom.xml`（仅在需要显式版本时）
- `iibot-app/src/main/java/io/mosire/iibot/app/inbox/*`
- `iibot-app/src/test/java/io/mosire/iibot/app/inbox/*`
- `iibot-app/src/test/java/io/mosire/iibot/app/runtime/AppBootstrapInboxMcpTest.java`
- `.work/` 下的新证据文件

### 步骤

1. 记录当前基线：
   - `git rev-parse HEAD`
   - AgentLib jar commit/版本
   - InboxMosire HEAD、实际安装 jar sha256
   - 当前 full gate 数字只作升级前参照，不写成升级后结果。
2. 根 POM：
   - `agentlib.version` 从 `0.2.3` 改为 `0.2.4`；
   - `inbox.version` 暂保持 `0.1.0-SNAPSHOT`；
   - 不新增本阶段不需要的依赖。
3. 重新安装 InboxMosire（如本机 artifact 与源码 HEAD 不一致）：
   - 在 `/home/cna/InboxMosire` 执行其标准安装命令；
   - 记录 `967f5c4` 及 jar hash。
4. 把 `InboxNotificationBridge` 的 `title/payload` 改为 `summary/content`：
   - ticket summary 仍只含类型/状态安全摘要；
   - publication-review summary 仍只含 visibility；
   - content 只放引用字段，不放业务正文。
5. 更新 `InboxHostRuntime`：
   - 注释/工具面从 11 个 `inbox_*` 改为 15 个；
   - 确认 `items:read` / `items:manage` 在 program token scopes；
   - 不把 raw token、content 正文写入日志。
6. 更新测试：
   - 工具名断言增加 `inbox_view/update/append/delete`；
   - bridge 断言改为 `summary/content`；
   - Inbox host 断言 15 个工具 + descriptor。
7. 运行：

```bash
tools/gate.sh focused iibot-app test
tools/gate.sh docs
```

### 验收

- Maven 解析 `agentlib-mosire:0.2.4`，无 0.2.3 混入；
- Inbox client `all()` 实际返回 15 个工具；
- `summary/content` round-trip 测试通过；
- app focused gate PASS，日志落 `.work/bot-p0-deps-*.log`；
- 不修改 bot core，不启动真实 app，不发送真实 QQ。

### 停止条件

- Inbox 新 artifact 与源码不一致；
- AgentLib 0.2.4 与现有 API 出现非预期破坏且无法在 P0 内小修；
- `summary/content` 契约需要 DSH 侧同步修改但本地 DSH 版本不匹配。

## 2. 阶段 P1：`iibot-bot` 模块与通用模型

### 目标

建立不含任何文游语义的 bot 核心模块，先只做模型/端口/内存实现，不接 NapCat。

### 新增模块

```text
iibot-bot/
  AGENTS.md
  pom.xml
  src/main/java/io/mosire/iibot/bot/
    account/
    message/
    thread/
    note/
    summary/
    inbox/
    reply/
    mcp/
    config/
```

### 最小类型

```text
BotAccount
ChatSourceKey
ChatMessage
ChatThread
ChatDirection
UserNote
MessageSummary
InboxDigest
BotStatus
```

### 端口

```text
MessageBoardStore
UserNoteStore
InboxDigestPort
OutboxPort
BotClock
```

### 内存实现

- `InMemoryMessageBoardStore`
- `InMemoryUserNoteStore`
- `InMemoryInboxDigestPort`

### 验收

- 模块不依赖 `iibot-taskflow`；
- 源码中不出现 `Player` / `Director` / `Secretary` / `Turn` / `Ticket` / `ActionPool`；
- 同来源消息分组、`localSeq` 排序、持久去重键的单测通过；
- 用户备注按 `(accountId, qqId)` 共享的单测通过；
- 不运行真实 adapter。

## 3. 阶段 P2：Adapter 入站事件补齐

### 目标

让 `iibot-connect` 能提供 bot core 所需的全部文本/非文本占位与发送者元数据，同时保持协议细节不泄漏到 bot core。

### 文件所有权

- `iibot-connect/src/main/java/io/mosire/iibot/connect/core/InboundMessage.java` 或新增 `InboundEnvelope`
- `iibot-connect/src/main/java/io/mosire/iibot/connect/napcat/OneBot11Codec.java`
- `iibot-connect/src/main/java/io/mosire/iibot/connect/napcat/NapCatAdapter.java`
- connect 测试

### 步骤

1. 增加 `senderName`（card 优先、nickname 兜底）。
2. 增加 `nonTextSummary`：图片、文件、语音、表情等只计数/标类型。
3. 增加 `replyToMessageId` 与 `mentionedSelf`（能解析则填，不能则空）。
4. 无文本但有非文本段的消息不丢弃；空消息仍丢弃并记录稳定原因。
5. 保持 `dedupeKey` 形状兼容；群聊去重键包含 groupId。
6. 确认群聊消息仍通过 adapter 回调，不在 connect 层按 @ 过滤。

### 验收

- 私聊文本、群聊文本、纯图片占位、混合消息、无文本消息各有用例；
- 缺字段/自身消息/重复消息的负向用例；
- connect focused gate PASS；
- 不改变发送协议。

## 4. 阶段 P3：SQLite 消息板与用户备注

### 目标

实现 bot-first 的持久化核心：原始消息、来源 thread、用户备注。

### 数据文件

```text
<dataDir>/bot.db
```

建议 schema：

```text
chat_message(
  message_key TEXT PRIMARY KEY,
  thread_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  chat_type TEXT NOT NULL,
  target_id TEXT NOT NULL,
  direction TEXT NOT NULL,
  sender_id TEXT NOT NULL,
  sender_name TEXT NOT NULL,
  message_id TEXT NOT NULL,
  local_seq INTEGER NOT NULL UNIQUE,
  sent_at TEXT,
  received_at TEXT NOT NULL,
  text TEXT,
  non_text_summary TEXT,
  reply_to_message_id TEXT,
  delivery_state TEXT,
  idempotency_key TEXT
)

chat_thread(
  thread_id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  chat_type TEXT NOT NULL,
  target_id TEXT NOT NULL,
  display_name TEXT,
  last_seq INTEGER NOT NULL,
  last_message_at TEXT,
  message_count INTEGER NOT NULL
)

user_note(
  account_id TEXT NOT NULL,
  qq_id TEXT NOT NULL,
  display_name TEXT,
  tags_json TEXT NOT NULL,
  note TEXT NOT NULL,
  priority INTEGER NOT NULL,
  revision INTEGER NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(account_id, qq_id)
)
```

### 验收

- 同一唯一键重复插入只落一条；
- `localSeq` 由数据库事务分配，不依赖时间；
- 群消息 sender 不同但 thread 相同；
- outbound 的 `SENT/FAILED/UNKNOWN` 可持久读取；
- 用户备注跨 thread 读取一致；
- 关闭/重启后数据可恢复；
- 不接触旧 `events.db` schema。

## 5. 阶段 P4：摘要与 Inbox 大框

### 目标

按来源 thread 维护一个 Inbox digest，实现“全部消息有摘要、按来源合成大框、主 Agent 可选择恢复”。

### 步骤

1. 实现确定性 `MessageSummaryBuilder`：
   - 头部 = 来源类型 + sender + tags/note + priority；
   - 正文 = 压平/截断文本 + 非文本占位；
   - 单条 summary 上限常量；
   - 无 LLM、无网络。
2. 实现 `InboxDigestService`：
   - `dedupeKey = qq-thread:<threadId>:active`；
   - 新消息到达时在 thread 锁内构造 bounded digest；
   - 已存在 available item 用 `inbox_update` 更新 summary/content；
   - revision conflict 时读取最新并重试一次，仍冲突则创建新批次 item；
   - 归档/删除后清理 active mapping，下一条消息重新建 item。
3. digest content 只放：
   - threadId、chatType、targetId、fromSeq/toSeq；
   - 每条的 messageId/seq/sender/summary/tags/note；
   - 不含全量正文，不含 token，不含原始 OneBot JSON。
4. 摘要 item priority 取 digest 内最高 `UserNote.priority`；无备注为 0。
5. 对同一 thread 做防抖/合并窗口（建议默认 1–3 秒），避免每条消息各唤醒一次；
   所有消息仍在 digest 中，不丢摘要。

### 验收

- 同一来源连续 5 条消息只产生一个 active digest，content 中有 5 条摘要；
- 不同来源产生不同 digest；
- `inbox_list` 只见 summary，`inbox_view` 见 content；
- 新消息推进 revision；旧 revision archive 返回 conflict；
- Inbox update 失败时消息仍在 board，后续可重试；
- 测试不依赖真实 DSH。

## 6. 阶段 P5：Bot MCP 与回复链路

### 目标

让外部主 Agent 通过 MCP 读取 thread、恢复正文、更新用户备注、按来源回复。

### 工具

```text
bot_status
bot_thread_list
bot_thread_read
bot_message_get
bot_reply
bot_thread_ack
bot_user_note_get
bot_user_note_list
bot_user_note_upsert
inbox_* (15)
bot_inbox_binding_descriptor
```

### 传输

- 优先抽取现有 `ControlMcpServer` 的 Bearer/回环/代理/资源范围骨架为通用 `McpHttpGateway`；
- bot app 使用新网关，不把旧 taskflow 的 88 工具面带进来；
- 内部 MCP 仍绑 `127.0.0.1:0`，外部只绑回环；
- token 只从环境变量/现有 SecretProvider 获取。

### `bot_reply`

1. 用 `threadId` 查 thread，解析 `chatType/targetId`；
2. 校验 text 长度和非空；
3. 用 idempotencyKey claim delivery ledger；
4. 构造 private/group outbound；
5. 发送并等待三态；
6. 把 outbound message 写回 board；
7. 返回 deliveryState、messageIds、errorCode，不回显 token。

### 验收

- 非法 threadId ⇒ `THREAD_NOT_FOUND`；
- 空文本/超长文本 ⇒ 拒绝且不发送；
- 同 idempotencyKey 重放 ⇒ 不重复发送；
- SENT ⇒ board 有 OUT 消息；
- UNKNOWN ⇒ board 记录 UNKNOWN，且不自动重试；
- 工具面不出现任何旧 `tf_*` 文游工具；
- Fake adapter 端到端私聊/群聊回复通过。

## 7. 阶段 P6：Bot 组合根与 Inbox 唤醒

### 目标

形成可运行的 bot-first app：无玩家/无世界/无回合也能启动。

### 配置

新增 bot 配置，建议最小形状：

```json
{
  "dataDir": "...",
  "account": {"accountId": "...", "displayName": "..."},
  "napcat": {"...": "..."},
  "dispatcher": {"maxConcurrentThreads": 32, "maxPendingPerThread": 1000},
  "outbox": {"sendTimeoutSeconds": 15, "minSendIntervalMillis": 150},
  "control": {"enabled": true, "host": "127.0.0.1", "port": 8765, "...": "..."},
  "inbox": {"enabled": true, "channels": ["qq"], "...": "..."},
  "summary": {"debounceMillis": 2000, "maxMessagesPerDigest": 100}
}
```

- 不要求 `players`、`directors`、`world`、`publicGroupId`、`agentRoutes`；
- 旧模拟配置不能直接让 bot app 启动，需单独 bot 配置样例；
- fail-closed：control 未配置 token、inbox 配置不完整、dataDir 不可写都拒绝启动。

### 生命周期

1. 打开 `bot.db`；
2. 启动 adapter；
3. 启动 Inbox embedded；
4. 签发 program/wake token + descriptor；
5. 注册 bot MCP 工具和 15 个 inbox 工具；
6. 启动 digest bridge；
7. 关闭时先停 adapter，再停 bridge/inbox，不丢已接收消息。

### 验收

- 无文游配置启动成功；
- `tools/list` 只有 bot 工具 + inbox 工具；
- 一条 Fake 私聊 → 入板 → digest → MCP view → thread read → reply 全链路；
- 同样流程覆盖一个 Fake 群聊，且回复目标为群；
- 重启后消息、thread、备注可恢复；
- 不发送真实 QQ，不启真实 LLM。

## 8. 阶段 P7：默认入口切换与 legacy 隔离

### 目标

让 bot-first app 成为默认入口，旧文游 app 不进入默认构建/启动。

### 方案（推荐）

1. 新增 `iibot-bot-app` 为可运行组合根；
2. 根 POM 默认模块加入 `iibot-bot` / `iibot-bot-app`；
3. 新增 Maven profile `legacy-game` 编译 `iibot-taskflow` / `iibot-app`；
4. `iibot-bot-app` 的 shaded jar 改名为 `iibot-app-0.1.0-SNAPSHOT.jar`（或保留独立 artifact，用户裁定）；
5. `simulation/scripts/02-start.sh` 切到 bot app 配置；
6. 旧 `iibot-app` / `iibot-taskflow` 标记 `LEGACY`，保留回滚路径。

### 验收

- 默认 `./mvnw clean verify` 不依赖旧 taskflow；
- 默认 jar `--help` 不显示玩家/推演/回合命令；
- 旧 profile 仍可编译（若保留）；
- docs gate 通过；
- 未执行真实 QQ/DSH 长跑前，不宣称生产切换完成。

## 9. 阶段 P8：Skill、模拟区与真实验收

### 目标

把文游变成外部主 Agent 的 Skill，而不是宿主模块。

### 步骤

1. 归档旧 secretary / turn / ticket / player-experience Skill 文档；
2. 新 Skill 只依赖：
   - `inbox_*` 读取摘要；
   - `bot_thread_read` 恢复正文；
   - `bot_reply` 回复；
   - `bot_user_note_*` 管理标签/备注；
3. Skill 自行定义文游的角色/回合/确认约定；宿主不内置这些语义；
4. 用隔离 Fake adapter/真实回环 Inbox 做 smoke；
5. 真实 NapCat/DSH 测试前，按 M-004 停下请求用户提供群/账号/外发授权。

### 验收

- 旧 Skill 不再被默认加载；
- 新 Skill 能在无文游 Java 代码的情况下完成一次“摘要 → 恢复 → 回复”闭环；
- 真实外发 `NOT_RUN` 除非用户明确授权；
- 不将测试数字写成生产长跑结论。

## 10. 里程碑与提交边界

建议提交顺序：

1. `chore(deps): 升级 AgentLib 0.2.4 与 Inbox summary/content 工具面`
2. `feat(bot): 新增通用消息板与用户备注模型`
3. `feat(connect): 补齐发送者/非文本/回复元数据`
4. `feat(bot): SQLite 消息板与 thread 恢复`
5. `feat(inbox): 按来源生成摘要大框`
6. `feat(bot): 外部主 Agent MCP 读恢复与回复`
7. `feat(bot-app): 独立 bot-first 组合根`
8. `refactor: 默认入口切换到 bot app，legacy 游戏 app 隔离`
9. `docs(skills): 归档旧文游 Skill，新增 bot-first 文游 Skill`

不允许 `git add -A`；每个提交只包含一个责任区；提交前跑对应 focused gate。

## 11. 测试与证据

每阶段至少：

```bash
tools/gate.sh focused <module> test
tools/gate.sh docs
```

最终候选：

```bash
tools/gate.sh full
```

证据记录：

- 源码基线 commit；
- 依赖版本/artifact hash；
- 命令、rc、测试数；
- Inbox 工具实际数量；
- 消息板 schema/迁移版本；
- 未验证项（真实 NapCat、真实 DSH、长跑、图片解析）明确写 `NOT_RUN`。

## 12. 停止条件 / 需要用户裁定

1. 旧 `iibot-taskflow` / `iibot-app` 是保留 legacy profile 还是物理删除；
2. 根 `AGENTS.md` 红线 2 是否按新架构改为“单一受信任主 Agent 全量可见”；
3. Inbox 摘要粒度采用“一来源一个大框”还是“每条消息一个 item”；
4. 群消息是否默认全部唤醒，还是全部入摘要但只对私聊/@Bot/高优先级唤醒；
5. 原始消息保留期限；
6. 非文本消息是否只做占位，还是需要下载/解析；
7. 默认可运行 artifact 是继续叫 `iibot-app`，还是新名 `iibot-bot-app`。

## 13. 执行记录

### 2026-10-26：P0 依赖升级完成

- 根 POM `agentlib.version`：`0.2.3` → `0.2.4`。
- Inbox 通知桥从 `title/payload` 迁移到 `summary/content`。
- Inbox 测试工具面从 11 个客户端工具更新为 15 个：新增
  `inbox_view/update/append/delete`。
- 命令：`tools/gate.sh focused iibot-app test`
- 结果：**PASS**；app 312 tests / 0 failures / 0 errors / 0 skipped；
  日志 `.work/bot-p0-focused-final.log`（含“inbox_* 恰为 15 个”断言）。
- 未验证：真实 Inbox/DSH live 绑定、真实 QQ 外发。

### 2026-10-26：P1 `iibot-bot` 通用核心完成

- 新增 `iibot-bot` 模块，依赖 `iibot-connect + agentlib-mosire`，不依赖 `iibot-taskflow`。
- 已落地：
  - `ChatSourceKey` / `ChatType` / `ChatMessage` / `ChatThread` / `DeliveryState`；
  - `MessageBoardStore` + `InMemoryMessageBoardStore`；
  - `UserNote` / `UserNoteDraft` / `UserNoteStore` + 内存实现；
  - `MessageSummary` / `MessageSummaryBuilder` / `InboxDigest`。
- 命令：`tools/gate.sh focused iibot-bot test`
- 结果：**PASS**；iibot-bot 15 tests / 0 failures / 0 errors / 0 skipped；
  日志 `.work/bot-p1-focused-3.log`。
- 边界：当前只有内存实现，尚未接 SQLite、Inbox、NapCat 或 MCP。

### 2026-10-26：P0+P1 全仓门禁

- 命令：`tools/gate.sh full`
- 结果：**PASS**；全仓 **642 tests / 0 failures / 0 errors / 0 skipped**
  （connect 38、connect-agent 21、bot 15、taskflow 256、app 312）；
  shaded legacy app jar 构建成功。
- 日志：`.work/bot-p0p1-full.log`。
- 说明：这证明升级与新增模块没有破坏现有 legacy 工程；不证明 bot-first 消息板/Inbox/MCP
  已完成。

### 2026-10-26：P2 连接层入站元数据完成

- `InboundMessage` 增加 `senderName`、`nonTextSummary`、`replyToMessageId`、
  `mentionedSelf`；保留旧 9 参构造器兼容。
- `OneBot11Codec` 支持：
  - `sender.card` / `sender.nickname` 回退；
  - CQ/数组消息的非文本类型计数与占位；
  - 纯图片/文件/表情消息不再因无文本被丢弃；
  - `reply` 段消息 ID 与 `at` 段 @Bot 检测。
- 命令：`tools/gate.sh focused iibot-connect test`
- 结果：**PASS**；connect 40 tests / 0 failures / 0 errors；日志 `.work/bot-p2-connect.log`。

### 2026-10-26：P3 SQLite 消息板与用户备注完成

- 新增 `SqliteMessageBoardStore`：
  - messageKey 持久去重；
  - SQLite 自增 `local_seq`；
  - thread 投影与分页读取；
  - outbound `SENT/FAILED_BEFORE_SEND/UNKNOWN` 更新。
- 新增 `SqliteUserNoteStore`：
  - accountId + qqId 唯一；
  - tags/note/priority/revision 持久化；
  - 乐观 revision 冲突。
- 根 POM 增加 `sqlite-jdbc 3.53.4.0` dependencyManagement。
- 命令：`tools/gate.sh focused iibot-bot test`
- 结果：**PASS**；bot 18 tests / 0 failures / 0 errors；日志 `.work/bot-p3-focused.log`。
- 全仓命令：`tools/gate.sh full`；结果 **PASS**，647 tests / 0 failures / 0 errors
  （connect 40、connect-agent 21、bot 18、taskflow 256、app 312）；日志 `.work/bot-p2p3-full.log`。
- 边界：SQLite 尚未接入 app 组合根；Inbox digest、MCP、adapter→board 桥仍未实现。

### 2026-10-26：P4 Inbox 来源大框核心与真实 embedded 适配

- 新增 `InboxDigestService`：
  - 从消息板按 `lastSeq` 读取未处理消息；
  - OUT 消息只推进游标，不产生自我唤醒；
  - 同 thread 多条 IN 消息合并为一个 digest；
  - 复用 active item 时 `update`，冲突/不可用时回退新建；
  - 支持 `markHandled` 归档并清游标。
- 新增 `InboxDigestPort` / `InboxDigestItem` / `InboxDigestState` /
  `InboxDigestStateStore` 与内存、SQLite 游标实现。
- 新增 `InboxClientDigestPort`：
  - 把 digest 序列化为 Inbox `summary/content`；
  - `contentRef=message-board:<threadId>`；
  - 映射 `push/view/update/archive` 与 409 conflict。
- 命令：
  - `tools/gate.sh focused iibot-bot test` → **PASS**，bot 22 tests；
    日志 `.work/bot-p4-core.log`
  - `tools/gate.sh focused iibot-app test` → **PASS**，app 313 tests；
    日志 `.work/bot-p4-app.log`
  - `tools/gate.sh full` → **PASS**，全仓 **652 tests / 0 failures / 0 errors**
    （connect 40、connect-agent 21、bot 22、taskflow 256、app 313）；
    日志 `.work/bot-p4-full.log`
- 已验证真实 embedded Inbox：
  `push → list/view → update revision → archive` 全链路通过，
  content 是 JSON digest，不是正文数据库。
- 未完成：按 thread 的自动防抖定时器、bot app 组合根接线、Bot MCP 读恢复/回复。

### 2026-10-26：P4 防抖调度 + P5 Bot MCP 核心

- 新增 `InboxDigestScheduler`：同 thread 多次 enqueue 合并为一次 debounce flush，
  不同 thread 独立；后台异常通过 `lastError`/error handler 隔离。
- 新增 Bot MCP 工具：
  - `bot_thread_list`
  - `bot_thread_read`
  - `bot_message_get`
  - `bot_reply`
  - `bot_thread_ack`
  - `bot_user_note_get/list/upsert`
- 新增 `BotReplyService` / `BotReplyPort`：
  - 按 thread 反解目标；
  - 先写入 PENDING 出站消息；
  - 发送后回写 SENT/FAILED_BEFORE_SEND/UNKNOWN；
  - 同幂等键不重复发送。
- 新增 app 适配 `BotOutboxReplyPort`，复用现有 OutboxSender 的正文守卫、节奏、
  三态回执和 UNKNOWN 不重发语义。
- 验证：
  - `tools/gate.sh focused iibot-bot test` → **PASS**，bot 26 tests；
    日志 `.work/bot-p5-core.log`
  - `tools/gate.sh focused iibot-app test` → **PASS**，app 314 tests；
    日志 `.work/bot-p5-app.log`
  - `tools/gate.sh full` → **PASS**，全仓 **657 tests / 0 failures / 0 errors**
    （connect 40、connect-agent 21、bot 26、taskflow 256、app 314）；
    日志 `.work/bot-p5-full.log`
- 未完成：实际 Bot MCP HTTP 传输、bot app 组合根、NapCat 入站→SQLite→Inbox 的装配、
  真实 DSH live wake。

### 2026-10-26：P6 运行时核心、MCP 传输与端到端 fake 验证

- 新增 `BotInboxRuntime`：通用 embedded Inbox、program/wake token、15 工具 +
  descriptor、digest port；不依赖 Ticket/Post/玩家/回合。
- 新增 `BotMcpServer`：回环 Bearer MCP、精确路径、GET/POST/DELETE 透明代理、
  streamable HTTP 流式响应、工具白名单由注册表决定。
- 新增 `BotIngestionService`：InboundMessage → thread/message → digest scheduler。
- 新增 `BotRuntime` / `BotRuntimeOptions`：
  - SQLite 消息板、用户备注、digest 游标；
  - BotInboxRuntime + InboxDigestScheduler；
  - BotMcpTools + Inbox tools；
  - OutboxSender → BotOutboxReplyPort；
  - adapter 启动与全生命周期关闭。
- 新增 `BotRuntimeE2ETest`：Fake adapter → 消息入库 → Inbox digest →
  真实 loopback MCP → `bot_thread_list/read/reply`，无文游服务。
- 命令与结果：
  - `tools/gate.sh focused iibot-bot test` → **PASS**，bot 29 tests；
    日志 `.work/bot-p6-mcp.log`
  - `tools/gate.sh focused iibot-app test` → **PASS**，app 316 tests；
    日志 `.work/bot-p6-runtime.log`
  - `tools/gate.sh full` → **PASS**，全仓 **662 tests / 0 failures / 0 errors**
    （connect 40、connect-agent 21、bot 29、taskflow 256、app 316）；
    日志 `.work/bot-p6-full.log`
- 未完成：真实 bot.json CLI、默认入口切换、真实 NapCat/DSH live、旧模块 legacy profile。

### 2026-10-26：P6 bot 配置与 CLI

- 新增 `BotConfig` / `BotConfigLoader`：
  - 只接受 bot-first 字段：dataDir/accountId/napcat/control/inbox/summary/outbox；
  - 严格拒绝未知字段（`players`/`world`/`llm` 等旧文游字段）；
  - 相对路径按配置文件目录解析。
- 新增 `BotMain`：
  - `--config <bot.json>`；bot 为默认模式，不需要 `--mode bot`；
  - 从环境变量解析 NapCat/control token；
  - 构造 NapCatAdapter + BotRuntime；
  - 不要求玩家、世界或 LLM 配置。
- `Main` 默认 bot；legacy 必须显式 `--mode legacy`。
- 新增 `iibot-app/config/bot.example.json`。
- 命令与结果：
  - `tools/gate.sh focused iibot-app test` → **PASS**，app 318 tests；
    日志 `.work/bot-p6-config.log`
  - `tools/gate.sh full` → **PASS**，全仓 **664 tests / 0 failures / 0 errors**
    （connect 40、connect-agent 21、bot 29、taskflow 256、app 318）；
    日志 `.work/sim2-final-full.log`
- 未完成：默认入口切换、simulation 启动脚本改为 bot 配置、真实 DSH live wake。

### 2026-10-26：P7 simulation2 独立 bot 运行区已准备

- 新增 `simulation2/`：
  - `scripts/00-build.sh` / `01-prepare.sh` / `00-check.sh` / `02-start.sh` /
    `03-stop.sh` / `04-status.sh` / `05-wait-mcp.sh` / `06-mcp.sh` /
    `07-inbox-descriptor.sh`；
  - 生成 bot-only `config/bot.sim.local.json`；
  - 部署 `dist/iibot-app-0.1.0-SNAPSHOT.jar`；
  - DSH 引导 `dsh/HANDOFF_PROMPT.md`。
- `simulation2/scripts/00-check.sh` 已 PASS：
  - jar/config 存在；
  - config 无 legacy 字段；
  - NapCat 3000/3001 listening；
  - control port 8765 free。
- jar sha256：`41dd13faffe8bf991c0df0a2d4ec2e379fdd6202d98a0abdc818b014b84a0418`。
- **未启动 bot**（`NOT_RUN`）：用户将在 simulation2 自行启动养一晚；
  启动本身会连接真实 NapCat，后续真实外发仍需用户明确授权。
- 默认入口已切为 bot；`simulation2` 继续作为独立运行区。

### 2026-10-27：view-ack 生命周期与冲突收口

- Inbox/DSH 侧已切到 `wakeMode=view-ack`，program MCP 挂载成功；
  Inbox 提供 `ITEM_STATUS_CONFLICT` / `ITEM_REVISION_CONFLICT` + details。
- `InboxDigestService`：
  - ack 成功后保存 cursor-only，不再清空 thread 游标；
  - 使用 Inbox item content 的 `toSeq` 校正在本地 state 滞后的游标；
  - active item 非 available 或第二次 update 冲突时 fail-closed，不再创建
    第二个 available digest；
  - ack 校验 threadId/itemId 归属，避免清错游标。
- `InboxClientDigestPort` / `bot_thread_ack`：
  - 保留 Inbox 稳定冲突 code 和脱敏 details；
  - 网络/鉴权/not-found 不再压成 `INBOX_CONFLICT`。
- 命令与结果：
  - `iibot-bot` focused → **PASS**，31 tests；
  - `iibot-app` focused → **PASS**，321 tests；
  - `tools/gate.sh docs` → **PASS**（modules=5 files=511 links=201）；
  - `tools/gate.sh full` → **PASS**，全仓 **669 tests / 0 failures / 0 errors**
    （connect 40、connect-agent 21、bot 31、taskflow 256、app 321）；
    日志 `.work/view-ack-full-2.log`。
- target jar sha256：`aac759b7c7306d8514bf5ff8cd71ea292a4e2407144dfde7fbb864eda9fb492a`。
- 运行中 bot 仍是旧 jar；新 jar 尚未部署，需停 bot 窗口部署并重新绑定。
- 未完成：真实 view-ack live smoke、图片媒体读取、debounce/分段耗时指标。


### 2026-10-27：OneBot 11 富消息、历史、媒体、好友申请与上下文

- `iibot-connect`：
  - `OutboundSegment`/rich `OutboundMessage`、reply/at 段、长文本富消息切分；
  - 入站 `MessageSegment`、历史段保留、`message_seq` 游标；
  - `get_forward_msg` 的 `content` 数组解析；
  - `MediaCapability` + 受控图片下载；
  - `RecentQqProvider`（LLBot `get_recent_contact` 404 时按
    group/friend + latest history 组合）；
  - `FriendRequestCapability`（`set_friend_add_request`，只处理入站申请）。
- `iibot-bot`：
  - SQLite `chat_message_segment`、历史/引用/forward 工具；
  - Inbox `context`、`inbox_digest_audit`；
  - 好友申请 store/service/tools；
  - `bot_reply` 的 `mentionSender` 宿主解析。
- `iibot-app`：
  - `BotMediaStore`、`bot_media_get`、Bot MCP `ToolAssetResolver`；
  - BotRuntime 装配 media/friend/audit/recent/history capability。
- 命令与结果：
  - `tools/gate.sh docs` → **PASS**（modules=5 files=512 links=201）；
  - `tools/gate.sh full` → **PASS**，全仓 **691 tests / 0 failures / 0 errors**
    （connect 45、connect-agent 21、bot 45、taskflow 256、app 324）；
    日志 `.work/rich-full.log`。
- target jar sha256：`ab06e4f4605bd940856c9516eaddabde64b1c85b0548af7522a8c8d958d6ded2`。
- 运行中 `simulation2` bot 仍是旧 jar；未部署、未做真实 LLBot live smoke。
- 未完成：真实图片 ImageContent/好友申请/嵌套 forward 样本、长跑；
  主动加好友不实现（标准 OneBot 11 / LLBot 无 action）。


### 2026-10-27：view-ack 重放 / 归档竞态修复

- 根因确认：
  - 实际运行旧 jar 的 `markHandled` 在 archive 后 `states.clear`，导致旧范围重放；
  - active item 原地 update 导致 view 后 revision 漂移，ack CAS 追不上。
- 修复：
  - active item 冻结，ack 后 `flushThread` 发布下一版；
  - `bot_thread_ack` 增加 `snapshotToSeq`；
  - `archiveAndCursor` 保留 `cursorOnly`；
  - 启动 `flushAll` + `reconcileActive` 修复旧 state/item seq 空洞；
  - 保持 claimed fail-closed，等待 lease 过期，不引入 claim 绕过。
- 门禁：
  - `tools/gate.sh full` **PASS**，全仓 **697 tests**（connect 46、
    connect-agent 21、bot 49、taskflow 256、app 325）；
  - `tools/gate.sh docs` **PASS**；
  - `simulation2/dist` 已部署，sha256
    `8cbd348cd3d2f81254029187daf559f06781ad7054c2341680c752bbc2614162`；
  - 启动 smoke：33 tools、7 available item、claimed=0；同一 item 6 秒
    view revision/content 不变。
- 未完成：重新 bind DSH、真实回复/好友审批、长跑。


### 2026-10-27：正文一致性、OUT 富段、旧图片回退

- 正文：`BotReplyTextNormalizer` 在落库前执行，app 注入
  `PlayerFacingTextGuard`；本地文本与实际发送文本对齐。
- 富段：`BotReplyService` 写入 `REPLY/MENTION/TEXT` 的
  `chat_message_segment`。
- 旧图片：`MediaCapability.imageSegmentsForMessage` + `get_msg` fallback；
  `bot_media_get` 对无本地 image segment 的旧消息按 messageId 回退。
- 历史修复：对 72 条 OUT 做一次 OneBot 对账，修复 10 条 guard 导致的不一致；
  再次 dry-run 为 0 changes。
- 门禁：`tools/gate.sh full` PASS，全仓 **697 tests**（connect 46、
  connect-agent 21、bot 49、taskflow 256、app 325），日志
  `.work/consistency-full.log`；jar sha256
  `8cbd348cd3d2f81254029187daf559f06781ad7054c2341680c752bbc2614162`。
- 引用/@ 真实外发 NOT_RUN；等待明确目标。


### 2026-10-27：来源权重路由

- 新增 `weight` 域：`SourceWeightPolicy`、`MessageWeightDecision`、
  `SourceWeightStore`、SQLite/InMemory 实现、`SourceWeightService`。
- 计分：`sourceWeight + max(signalWeight) + sum(keywordWeight)`；阈值过滤，
  `priority` 映射 Inbox 排序。
- 首次来源 `FIRST_CONTACT`；已有 thread 迁移为已引导。
- 工具：`bot_source_weight_get/list/set`；设置来源权重可调整 active item priority。
- 新 Inbox lib 自动获得 `inbox_rest`；生产工具面 37。
- 门禁：`tools/gate.sh full` PASS，全仓 **705 tests**（connect 46、
  connect-agent 21、bot 55、taskflow 256、app 327），日志
  `.work/weight-full2.log`；jar sha256
  `0d25749e0f790e40195408fe4c7d8398bc07b86671ae95f0a1ccff6bb6ffbf74`。
- 运行态 7 个 thread 已迁移；群 sourceWeight 0→500→0 的 active item
  priority 0→500→0 已实测。引用/@ 真实外发 NOT_RUN。


## 14. 预测风险

- Inbox `update/append/delete` 与 active digest 的 revision 竞态；
- 群消息线程串行化导致吞吐下降；
- 一批群消息摘要过大，需要分页/裁剪；
- 旧 `events.db` 与 `bot.db` 并存造成误解；
- 旧 app 测试数量大，默认 reactor 切换后基线变化；
- DSH binding 每次 app 重启随机端口变化，必须重签 descriptor；
- 所有真实外发仍可能 UNKNOWN，必须保留人工处置路径。
