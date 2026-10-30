# 统一 QQ Agent 客户端与消息板目标架构

> **状态**：已实施 P1–P7（2026-10-28）；D-034 已把统一 source/会话面扩展至
> B 站私信与评论（2026-10-31，code-level）；真实外部联调待用户授权。
> D-035 V1/V2 已把视频站领域/登录/抽帧迁到独立 `iibot-video-connect`，并把
> MCP 工具注册统一到 app 的 `BotToolCatalog`；V3 已补 quality/time/comments 与
> `video.*` 配置；下一步 V4 Playwright embedded，见
> [`2026-10-31-video-site-connect-and-browser-backend.md`](2026-10-31-video-site-connect-and-browser-backend.md)。
> **本文取代**：无；细化并扩展 D-021 的 bot-first 方向。
> **用户原话**：
> > 「iibot模块不额外提供程序化的文游服务，只作为给各类Agent软件提供一个统一QQ消息客户端的功能，因此文游相关模块可以标弃用删除了」
>
> > 「那这玩意不应该做成现有iibot的上游实现吗，天然的MCP工具，为啥没接进去」
>
> > 「就是消息板这块怎么办，总不能让Agent自己维护一个额外的标记系统吧」
> **关联计划**：[2026-10-28 统一 QQ Agent 客户端改造计划](../plans/2026-10-28-unified-qq-agent-client-refactor-plan.md)

## 1. 目标与边界

### 1.1 产品目标

`iiBotMosire` 的默认产品形态是：

> 一个面向各类 Agent 软件的本地统一 QQ 消息客户端。

它负责：

1. 连接一个或多个 OneBot 11 / NapCat / LLBot 实例；
2. 把入站消息可靠写入统一消息板；
3. 维护账号、会话、未读位置、出站投递三态；
4. 通过一个统一 MCP 工具面向 Agent 提供账号、会话、历史、发送、媒体和好友申请能力；
5. 通过 Inbox 唤醒 Agent，但不把 Inbox 的内部同步细节暴露给模型。

### 1.2 非目标

- 不提供玩家、秘书、回合、行动、裁定、世界、推文、投诉、Ticket、ActionPool 等文游服务；
- 不在 iibot 内运行 LLM 或业务 Agent；
- 不提供任意 OneBot action、原始 JSON、任意 SQL 或 shell 执行口；
- 不把 Inbox 做成第二套消息板或 Agent 必须维护的标记系统；
- 不在本阶段实现公网远程访问、TLS 终止或多租户云服务。

## 2. 当前事实

- `iibot-app/.../BotRuntime.java:84` 的启动参数是单个 `MessagingAdapter`，`BotRuntime.java:133` 又把它绑定到单个 `OutboxSender`；当前进程无法在运行期切换 OneBot/QQ 账号配置。
- `ChatSourceKey.java:13` 已经包含 `accountId`，消息板的数据结构具备多账号基础；缺口在运行时注册、入站归类和出站路由。
- `iibot-app/.../reply/BotOutboxReplyPort.java:24` 只持有一个 `OutboxSender`，没有根据 `source.accountId()` 选择 Adapter。
- `iibot-connect-agent/.../AdapterRegistry.java` 已经提供多 Adapter 注册能力，但 `BotRuntime` 没有使用它；`ConnectAgentTools` 的 `qq_*` AgentTool 也没有进入 Bot MCP。
- `BotRuntime.java:145` 组装 `BotMcpTools`，`BotRuntime.java:153` 又直接加入完整 Inbox 工具集；当前 Agent 面混合了 `bot_*`、`inbox_*`、`tf_*` 等不同层级工具。
- `BotReplyTool.java:31` 要求 Agent 提供 `idempotencyKey`；`BotThreadAckTool.java:26-30` 要求 Agent 提供 `itemId`、`expectedRevision`、`snapshotToSeq`。这些是服务内部一致性令牌，不应由模型维护。
- 根 `pom.xml:19` 仍包含 `iibot-taskflow`；`iibot-app/pom.xml:17` 仍直接依赖它。默认运行虽然不启动文游，但默认构建和 shaded jar 仍携带旧代码。
- `iibot-connect-agent` 不是游戏模块，但当前是孤立模块：它是通用 QQ AgentTool 适配层，设计上应被组合根复用。
- 上游 `agentlib-mosire:0.2.4` 是当前源码主线最新版本，但尚未公开 tag/Pages 发布；干净 Maven 仓库无法解析。该问题属于计划 P0 的依赖前置门。

## 3. 核心模型

### 3.1 Account

一个可连接的 QQ 账号配置：

```text
accountId   稳定业务 ID，也是消息板和 thread 的账号分区键
alias       人类可读配置别名
adapter     onebot11 等 Adapter 类型
endpoint    事件 WS / HTTP Action 配置
enabled     是否启动
```

同一 `accountId` 同时最多只能有一个活动 Adapter。配置中的预期 `accountId` 必须与 Adapter 查询到的登录账号一致；不一致时该账号启动失败，不允许把消息写入错误账号。

### 3.2 Conversation

一个 QQ 会话：

```text
accountId + chatType(PRIVATE|GROUP) + targetId
```

现有 `threadId` 已经是该三元组的稳定、不透明编码，可以继续作为 Agent 可见的会话句柄。Agent 不需要知道它是如何编码的。

### 3.3 Consumer

Consumer 是读取消息板并调用 MCP 的 Agent 客户端身份，不是模型参数。

- 来自 MCP 连接/Token 绑定，例如 `default` 或 `dsh-main`；
- 用于隔离每个 Agent 的已读位置和 Inbox 唤醒；
- 第一版可以只有一个 `default` consumer，但数据结构必须从一开始带 `consumerId`；
- 模型不得通过工具参数伪造或切换 consumer。

### 3.4 受服务所有的状态

以下状态由 iibot 服务维护，不进入 Agent 工具参数或模型上下文：

- `localSeq`、`messageKey`、thread 游标；
- consumer 的 `readSeq`；
- Inbox item id、revision、snapshot 范围；
- 出站幂等键、投递状态、重试/UNKNOWN 标记；
- Adapter 连接状态、重连次数和内部错误类型。

Agent 只需要业务实体：`accountId`、`threadId/conversationId`、`messageId` 和工具返回的稳定结果。

## 4. 目标拓扑

```text
OneBot 11 / NapCat / LLBot（可多个）
        │ WS 事件 + HTTP Action
        ▼
iibot-connect
  协议中立 MessagingAdapter + Adapter 状态 + 三态回执
        │
        ▼
iibot-connect-agent
  通用 AgentTool：
  qq_account_list / qq_account_status / qq_account_login_info
  低层发送原语（不直接作为消息板主发送面）
        │
        ▼
iibot-bot
  消息板与统一语义：
  - 入站入库
  - account/thread/conversation 投影
  - consumer 读游标
  - 出站投递三态
  - Inbox 同步
  - 统一 QQ MCP 工具
        │
        ▼
iibot-app
  组合根：
  - 多 Adapter Registry
  - Bot MCP
  - Embedded Inbox
  - 配置和日志
        │
        ▼
外部 Agent（DSH / OpenCode / 其他 MCP 客户端）
```

`iibot-connect-agent` 是 `iibot-connect` 的上游工具适配层；`iibot-bot` 是消息板语义层。两者都必须汇入同一个 `BotMcpServer` 工具注册表，不允许再出现一条未接线的平行发送路径。

## 5. 数据所有权与存储

### 5.1 消息板

消息板是唯一业务事实源。保留并扩展当前 schema：

```text
chat_message               所有入站/出站消息，按 thread + localSeq 排序
chat_thread                thread 投影和最后消息位置
chat_message_segment       协议中立富消息段
consumer_thread_state      consumer 对每个 thread 的 readSeq 和 Inbox 映射
```

`consumer_thread_state` 建议至少包含：

```text
consumer_id
thread_id
read_seq
inbox_item_id
inbox_revision
updated_at
PRIMARY KEY (consumer_id, thread_id)
```

该表只用于服务内部同步，不作为 Agent 可查询的业务对象。

### 5.2 Inbox

Inbox 只负责唤醒和未读预览，不拥有完整正文，也不拥有最终已读状态：

- Bot 入库后为每个受影响的 `(consumer, thread)` 计算未读数并更新同一 active item；
- Agent 读取会话后，Bot 在消息板推进 `read_seq`，再更新/归档对应 Inbox item；
- Inbox item 的 revision、snapshot 范围、itemId 是 Bot 与 Inbox 的内部协调字段；
- Inbox 更新失败不能回滚消息入库，必须记录 WARN 并在下一次读取/启动恢复时对账；
- Inbox 中只放 bounded 摘要、未读数、`threadId` 和唤醒原因，不放 token；
- 纯文本短批次在 `inlineMaxChars` 内可以使用 `kind=digest_complete` 和完整
  bounded summary；权威正文仍在消息板，`content` 只在内部/claim 面使用。

### 5.3 出站

出站消息仍以 `ChatMessage(OUT, PENDING)` 作为“发送意图”：

```text
工具请求
  → 解析 thread.source.accountId
  → 创建 PENDING 出站 ChatMessage
  → 选择该 account 的 Adapter
  → MessagingAdapter.send
  → 回写 SENT / FAILED_BEFORE_SEND / UNKNOWN
```

- 不允许 MCP 工具直接调用 Adapter 绕过消息板；
- 不允许用另一个账号的 Adapter 发送某个 thread 的回复；
- `UNKNOWN` 不自动重发，仍由宿主/人工按三态语义处置；
- Agent 不需要提供幂等键，服务端生成；如需调用方重试，可接受可选 `clientRequestId`，但不是必填。

## 6. 目标运行序列

### 6.1 启动

```text
1. 加载并验证配置（账号、端口、日志、Inbox）
2. 应用日志级别和文件配置
3. 打开 bot.db，执行消息板 schema 迁移
4. 创建 AccountRegistry，注册全部启用的 Adapter
5. 启动 Embedded Inbox，建立 consumer 映射
6. 构建统一 MCP 工具目录并启动 BotMcpServer
7. 启动每个 Adapter；账号信息核对通过后标记 CONNECTED
8. 启动恢复：对账 Inbox item、consumer_thread_state 和 PENDING/UNKNOWN 出站
```

任何账号、Inbox 或 MCP 启动失败都要回滚已启动资源；不允许“部分账号可用但健康检查显示整体成功”。

### 6.2 入站

```text
Adapter 事件
  → 去重并解析为 InboundMessage
  → 按 adapter.accountId + session 生成 ChatSourceKey
  → 写入 chat_message
  → 更新 consumer unread 投影
  → 防抖生成/更新 Inbox 摘要
  → 唤醒宿主 Agent
```

### 6.3 Agent 读会话与显式语义 ack

用户已于 2026-10-28 选择：**读取只预览，处理完显式 ack**。

```text
Inbox wake（只含 threadId/摘要/未读数）
  → Agent 可调用 qq_conversation_read(source)（可选；Inbox 摘要足够时直接 ack）
  → 服务按入站消息窗口读取 readSeq 之后的未读消息
  → 返回 seq/messageId/发送者/text/必要媒体段和可信 more
  → 仅记录 deliveredSeq；不推进 readSeq，不归档 Inbox item

Agent 完成处理后：
  → 调用 qq_conversation_ack(source)
  → 服务把 readSeq 推进到该 consumer/thread 的 deliveredSeq
  → 更新/归档 Inbox item
```

补充规则：

- `qq_conversation_read(source)` 可以重复调用，未 ack 前返回同一批未读消息；read 不是 ack 的前置条件；
- `ack(source)` 在未 read 时直接归档当前 active Inbox item；已 read 时按 read 时固定的 delivered 批次归档；
- `ack(source)` 不再接受 seq、itemId、revision、snapshot 或任何不透明 hash；
- `read` 与 `ack` 之间到达的新消息属于下一次未读，不会被错误 ack；
- read/ack 结果使用 compact 形状；ack 返回 `more=true` 时，表示同一 active item
  仍有剩余未读；Agent 应在**同一回合**再次 `qq_conversation_read(source)` 继续
  处理，不得结束本轮等待新 wake；
- `kind=digest_complete` 的短文本 wake 已包含完整可读正文，Agent 可直接 ack，
  不需要先 read；
- 发送回复成功后的自动 ack 仍未实现；当前仍需显式 ack；
- 完整消息历史始终可由 `qq_conversation_history` 读取，不受未读状态影响。

### 6.4 Agent 发送

```text
qq_conversation_send(source, text,
                     replyToMessageId? | replyToSeq?, mentionSender?, mentionAll?)
  → 服务校验 source/thread 和账号
  → 如给 replyToSeq：在同一 thread 内解析平台 messageId 和入站发送者
  → 生成服务端幂等键并写入 PENDING 出站消息
  → 按 thread.source.accountId() 选择 Adapter
  → 发送并写回三态
```

## 7. Agent 可见 MCP 工具面

以下是目标工具族，具体名称在实施 P6 冻结；原则是只暴露业务动作，不暴露内部同步令牌。

### 7.1 账号/连接

```text
qq_account_list
qq_account_status
qq_account_login_info
qq_account_use          # 仅在配置允许的账号集合内切换当前会话默认账号
```

### 7.2 会话/消息

```text
qq_conversation_list
qq_conversation_read
qq_conversation_history
qq_conversation_send            # text/at/reply/image/sticker; mentionAll
qq_private_send
qq_message_get
qq_message_recall
qq_forward_read
qq_forward_media_get
qq_media_get
qq_media_upload
qq_audio_get
qq_group_files
qq_group_file_get
qq_group_file_upload
qq_user_note_get/list/upsert
qq_friend_request_list/decide
```

### 7.3 Inbox

Agent 面保留语义明确的 `inbox_rest` 和 `qq_conversation_ack`；不暴露：

- `inbox_view` 的 revision/snapshot 参数；
- `bot_thread_ack` / `expectedRevision` / `snapshotToSeq`；
- Inbox claim/renew/complete/release 作为主流程；
- `tf_inbox_binding_descriptor` 给普通模型；descriptor 应由宿主交接流程读取。

`qq_conversation_ack(source)` 只接受业务来源，没有 seq、内部同步令牌或 revision。
read/ack 的完整 compact 契约见
[`2026-10-30-inbox-inline-and-compact-tool-results.md`](2026-10-30-inbox-inline-and-compact-tool-results.md)。
通用 Inbox daemon 的完整工具面可以继续存在，但 iiBot 的产品 MCP 不能把 16 个
Inbox 工具和内部绑定工具全量倒入。

### 7.4 命名与权限

- 统一使用 `qq_*` 作为 QQ 客户端业务命名空间；Inbox 只保留内部/宿主工具；
- 所有工具统一走 AgentLib `ToolCallAuthorizer` 和同一个日志包装器；
- 工具必须声明 `ToolSpec`、角色、资源、敏感/破坏性和错误码；
- 任何工具不得让模型构造任意 Adapter 配置、token、URL 或文件路径。

## 8. 多账号与切换

### 8.1 配置层

账号在配置中按 alias 映射；每个账号有稳定的业务 `accountId`。禁止两个账号使用同一 alias 或同一 accountId。

### 8.2 运行期

- `AccountRegistry` 维护 `accountId -> Adapter`；
- 入站事件由对应 Adapter 注入，不允许事件自行覆盖路由账号；
- 出站按 `ChatSourceKey.accountId()` 查 Adapter；
- `qq_account_use` 只改变当前 consumer 的默认账号，不修改全局配置；
- 新建会话必须显式指定允许的账号 alias 或使用 consumer 的当前账号；
- 已存在会话的发送、读取始终由 thread 的 accountId 决定，不受当前默认账号切换影响；
- Adapter 配置的新增/修改/删除属于受保护的运维控制面，不作为普通 QQ 工具暴露给模型。

### 8.3 失败语义

- Adapter 不存在、账号禁用或登录账号不匹配：发送失败且不产生外部消息；
- 账号切换只改路由上下文，不重连其他账号；
- 某账号断线不影响其他账号，但 Tool 返回必须明确 `accountUnavailable`；
- 不得把消息从账号 A 的 thread 迁到账号 B。

## 9. 文游模块处置

按用户 2026-10-28 裁定：

- 删除 `iibot-taskflow` 模块；
- 删除 `iibot-app` 中文游/legacy app 代码、旧 MCP、事件、发布、控制、玩家和世界相关包；
- 删除 `simulation/` 旧文游运行区、旧 `config/legacy` 和旧文游示例配置；
- 删除旧文游 specs/plans/status 的当前入口，历史由 Git 保留；需要保留的通用 bot 设计文档重写；
- 根 POM 移除 `iibot-taskflow`，移除 workspace-mosire/docs-mosire；默认构建只编译 QQ 客户端相关模块；
- 2026-10-28 用户已裁定整合 `iibot-connect-agent`：`AdapterRegistry` 与账号 AgentTool 进入 iibot 正式运行时/MCP 层，原独立模块在迁移完成后删除。

## 10. 不变量与失败语义

1. 消息板是正文、历史、未读和发送状态的唯一事实源；Inbox 不是。
2. Agent 不提供 `expectedRevision`、`snapshotToSeq`、`itemId` 作为完成前置条件；服务内部可保留乐观锁。
3. `qq_conversation_read(source)` 只预览并记录服务端 deliveredSeq；`qq_conversation_ack(source)` 才推进 readSeq；ack 只返回 `more`，不接受 seq/revision/itemId。read.more 必须只由剩余入站未读决定，不能被 OUT 或 replay 分支误报为 false。
4. Agent 不提供必填 `idempotencyKey`；服务根据 consumer、工具调用和会话生成稳定幂等键。
5. 出站目标只由 thread 的 accountId + targetId 决定，模型不能伪造其他目标。
6. 发送超时/中断一律 `UNKNOWN`，禁止自动重发。
7. 账号不匹配、Inbox 未就绪、配置非法、端口冲突都必须 fail-closed。
8. 日志只记录 ID、状态、计数、错误码、耗时和参数 key，不记录正文、token、Authorization 或完整协议 JSON。
9. 低权重消息仍进入消息板和 Inbox 未读语义；权重只影响排序/唤醒策略。
10. 任何“构建通过/真实联调通过”的表述必须有当前源码和实际命令证据；未跑写 `NOT_RUN`。

## 11. 验收判据

### 11.1 结构与依赖

- 默认 Maven reactor 不含 `iibot-taskflow`，不依赖 workspace-mosire/docs-mosire；
- 默认 shaded jar 不含文游 class 和旧 `tf_*` MCP 工具；
- 干净的 Maven 仓库可按文档解析全部依赖并构建成功。

### 11.2 多账号

- 同时注册两个 Adapter，入站消息分别进入不同 account 的 thread；
- 回复账号 A 的 thread 经 A Adapter 发送，绝不使用 B；
- 禁用账号 B 后，A 仍可读取和发送，B 的工具调用返回稳定错误；
- 账号切换后，已有 thread 的目标不变，新建会话使用切换后的默认账号。

### 11.3 消息板与 Inbox

- 新消息创建/更新一个 active Inbox item，带未读数和摘要；
- 纯文本短批次可写完整 bounded summary 和 `kind=digest_complete`；
- `qq_conversation_read` 后内容可见但未读仍保留；`qq_conversation_ack(source)` 后游标推进，Inbox item 更新或归档；
- Agent 不传 revision/snapshot/itemId 也能完成读、ack、回复、继续对话；
- read 与 ack 之间到达的新消息保留下一次未读，不被错误消费；
- Inbox 同步失败时消息板仍可读，并在后续对账中修复。

### 11.4 工具面

- 所有对外工具统一走同一个 MCP、同一个权限器和同一个日志包装器；
- 不存在直接发送的 `qq_send_message` 绕过消息板的路径；
- schema 单测证明 Agent 不需要内部标记字段；
- 工具名、错误码、权限元数据有契约测试。

### 11.5 文档

- 根 README、AGENTS、STATUS、config README 与目标架构一致；
- 所有旧文游入口有历史标记或已删除；
- `tools/gate.sh docs` 和 full gate 通过。

## 12. 待裁定

1. 第一版是否允许一个 iibot 同时服务多个 consumer（建议 schema 支持多层，运行期先实现一个 default consumer）。
2. `qq_account_use` 是否开放给普通 Agent，还是只允许宿主/运维控制面切换账号。
3. ~~读取即已读还是显式 ack~~：2026-10-28 用户已选择 **读取只预览，处理完显式 ack**。
4. ~~connect-agent 独立还是合并~~：2026-10-28 用户已选择 **整合并删除独立模块**；`AdapterRegistry` 与账号工具进入 iibot 正式运行时/MCP 层。
5. 文游文档是物理删除还是移入 `docs/archive/legacy-game/` 后不参与门禁。
6. 发送回复成功后是否自动 ack：当前统一要求显式 `qq_conversation_ack(source)`；自动 ack 规则仍待后续裁定。

## 13. 修订记录

- 2026-10-28：根据用户“统一 QQ 消息客户端”“消息板不维护额外标记”“connect-agent 应该是上游实现”的方向新建。
- 2026-10-28：用户冻结 AgentLib 0.2.4；选择读取只预览、处理完显式 ack；选择整合并删除独立的 `iibot-connect-agent` 模块。
- 2026-10-30：补充 partial ack 同轮续处理语义。
- 2026-10-30：D-032 把 read/ack 收敛为 source-only compact 契约，`ack` 只返回
  `more`；短纯文本批次使用 `kind=digest_complete` 在 Inbox wake 内联。
- 2026-10-30：D-033 明确 DSH 只做宿主传输面；iibot MCP 自解释业务。read 增加
  `messageId` 并修正 `more`，send 增加 `replyToSeq`，DSH 不再需要镜像业务工具契约。
