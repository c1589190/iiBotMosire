# 秘书会话 Cache 与 NapCat 聊天记录查看器

> **状态**：有效；2026-10-24 第一切片已通过 full gate（549 tests），真实 NapCat 历史读取仍为 `NOT_RUN`
> **用户原话**：
> 「1 我认为直接做一个对接Napcat的通用聊天记录查看器最方便
> 2 允许单个秘书位置切换不同id的cache即可
> 3 允许当前秘书方便阅读同秘书之前的Cache即可
> 4 GM自然能用查看秘书上下文的工具
> 5 迎刃而解」
> **相关设计**：[目标运行设计](2026-10-24-target-runtime-design.md)、[v2 秘书工作区](2026-10-24-v2-agent-workspaces-permissions-and-models.md)、[外部 MCP 控制面](2026-10-24-external-mcp-control-plane.md)

## 1. 目标与非目标

### 1.1 目标

1. 每个玩家秘书拥有一个可持久化的 `ContextWindow` 会话 cache；app 重启后可以恢复，不能静默变成空上下文。
2. 同一个玩家秘书槽位可以在多个 cacheId 之间切换；旧 cache 不删除。
3. 秘书 Agent 可以列出并阅读同一玩家以前的 cache 对话；不能读取其他玩家的 cache。
4. GM/外部 supervisor 可以只读查看任意已注册玩家的秘书 cache，并可以显式切换某个玩家当前 active cache。
5. 提供一个只读的 NapCat 聊天记录查看器，支持群聊和私聊的最新页/游标翻页；它用于人工恢复和证据查看，不冒充 LLM 上下文。
6. 玩家对话窗口与 GM 调试窗口分离，GM 消息不得进入玩家后续上下文。

### 1.2 非目标

- 不以 NapCat 历史替代 App 侧 raw `LlmMessage` cache。NapCat 只保存 QQ 可见文本，没有 tool_call、tool_result、host reminder 和 system prompt 组合。
- 不提供任意 QQ 私聊读取。`tf_qq_history_read` 只允许已注册玩家/推演 QQ 与当前公共群。
- 不在此切片实现自动从 NapCat 导入 cache；先把只读查看器和 App cache 稳定下来。
- 不修改运行中的 app、`simulation/dist` jar、DSH/Hermes 进程或公共群。
- 不实现跨进程并发 cache 写入；当前部署是单 JVM。

## 2. 现状事实

- `SecretarySession` 只持有内存窗口：`SecretarySession.java:73`，构造为 `new ContextWindow("secretary:" + playerId)`：`SecretarySession.java:508`。
- `chat()` 与 `gmChat()` 共用这个窗口：`SecretarySession.java:795`、`:825`。
- `AgentRunner` 使用 `ContextWindow` 执行工具/回复回填：`AgentRunner.java:109`；窗口支持 `unsavedTail()` / `markSaved()`，但当前没有宿主持久化。
- `AppBootstrap` 在启动时通过 `sessionFactory` 新建 Session：`AppBootstrap.java:296`。
- 当前 STATUS 已明确记录：Secretary 的 `ContextWindow` 聊天历史不恢复。
- 本机 NapCat 的 `get_friend_msg_history` 对非好友会使用 `KCHATTYPETEMPC2CFROMGROUP`；但接口只按 `user_id` 定位，不能指定来源群。NapCat 历史还受本地消息库和短 `message_seq` LRU 限制。

## 3. 数据所有权与命名

### 3.1 App 会话 cache

新增 taskflow 端口 `SecretaryCacheStore`：

```text
cacheId      由宿主生成，形如 cache-<uuid>
playerId     cache 的唯一 owner
messages     LlmMessage[]（含 user/assistant/tool；system prompt 不进入窗口）
active      每玩家唯一 activeCacheId
source       APP（App 上下文）或 NAPCAT_IMPORT（后续预留）
```

约束：

1. 一个 cache 只能属于一个 playerId；秘书槽位切换时必须校验 owner。
2. 切换 cache 不改变 actor/player 身份、权限和工具面。
3. activeCacheId 持久化失败时不得切换内存窗口。
4. cache 丢失/损坏时，Session 返回 `CONTEXT_LOST`，不得假装空历史。
5. 旧 cache 只归档，不因切换/重置删除。

### 3.2 NapCat 历史

`ChatHistoryProvider` 是 `iibot-connect` 的只读端口：

```text
query: chatType(private|group), targetId, cursor?, limit?, direction(older|newer)
page:  messages[], nextCursor?, exhausted
message: messageId, senderId, senderName, text, sentAt
```

它是外部 QQ 缓存视图，不是领域事实源，也不直接进入 `ContextWindow`。

## 4. 目标运行序列

### 4.1 Session 创建

1. `SecretaryCacheStore.ensureDefaultCache(playerId)` 返回 active cache。
2. 加载该 cache 的 `LlmMessage[]`。
3. 构造 `new ContextWindow(cacheId, messages)`。
4. 若历史非空，`firstPlayerTurn=false`；不重复注入首轮 `AGENTS.md` 提醒。
5. cache 加载失败：Session 标记 `CONTEXT_LOST`，保留可诊断状态；后续玩家消息不得被静默写入空 cache。

### 4.2 玩家聊天

1. `chat()` 在玩家窗口运行 Agent。
2. runner 把本轮 user、assistant、tool result 写入窗口。
3. 宿主取 `window.unsavedTail()`，逐条 `append` 到当前 active cache。
4. 成功后才 `window.markSaved()`。
5. 写入失败：标记 `CACHE_WRITE_FAILED`/`CONTEXT_LOST`，日志只记录类型，不记录正文或工具结果。

### 4.3 cache 切换

1. 校验 cache 属于当前 playerId。
2. 先保存当前窗口未落库尾部。
3. 加载目标 cache 消息。
4. 持久化 activeCacheId。
5. 替换内存窗口；`firstPlayerTurn` 按目标历史是否为空重算。
6. 任何一步失败保持旧窗口和旧 activeCacheId。

切换、聊天、GM 调试聊天都由 Session 的监视器串行化；不得在一轮聊天中途替换窗口。

### 4.4 秘书读取旧 cache

玩家工具：

```text
secretary_cache_list
secretary_cache_read(cacheId?, limit?)
```

- playerId 只从 `ToolContext.config` 获取；schema 不提供 playerId；
- 只返回当前玩家 cache；
- `read` 默认读 active cache，只渲染 user/assistant 文本；
- 不返回 tool_call/tool_result 的原始参数、system prompt 或其他玩家内容；
- 最近 N 条，默认 20、上限 50，单次结果最多 16 KiB。

### 4.5 GM cache 工具

```text
tf_gm_secretary_cache_list(playerId)
tf_gm_secretary_cache_create(playerId, title)
tf_gm_secretary_cache_read(playerId, cacheId?, limit?)
tf_gm_secretary_cache_switch(playerId, cacheId, reason)
```

- 只由外部 supervisor 的 MCP allowlist 暴露；
- read 默认只读玩家可见对话；
- create/switch 不发送 QQ、不写世界；
- 所有 target playerId/cacheId 必须存在且归属一致，不一致 fail-closed；
- 返回不包含物理路径、token 或完整原始事件。

### 4.6 GM 与玩家窗口隔离

- `chat()` 使用玩家 cache window。
- `gmChat()` 使用独立的临时 GM window，可把玩家窗口当前消息作为只读快照种子，但 GM 消息不回写玩家 cache。
- GM 要查看历史，使用 `tf_gm_secretary_cache_read`，而不是污染玩家上下文。

### 4.7 NapCat 查看器

MCP 工具：

```text
tf_qq_history_read(chatType, targetId, cursor?, count?, direction?)
```

- `chatType=private` 只允许已注册玩家/推演 QQ；
- `chatType=group` 只允许当前公共群（后续可扩展白名单）；
- `count` 1–100，默认 20；
- `direction=older` 默认：从最新/游标向过去；
- 结果只包含文本消息；图片/合并转发等非文本段可跳过并在结果中计数；
- 空页/retcode 1200 视为历史到底，不当作系统成功或无限重试；
- 超时、HTTP/JSON 错误返回稳定错误码，不记录 token/完整 URL；
- 不自动写入 App cache。

## 5. 失败语义

| 情况 | 行为 |
|---|---|
| cache 索引损坏 | `CONTEXT_LOST` / `CACHE_UNAVAILABLE`，不静默空窗口 |
| cacheId 不存在 | `CACHE_NOT_FOUND` |
| cache 属于其他玩家 | `CACHE_OWNER_MISMATCH` |
| 切换时保存失败 | 不切换，保留旧 activeCacheId |
| cache 写入失败 | 不 `markSaved`，标记错误；不假装成功 |
| NapCat 历史 1200/空页 | `exhausted=true`，不重试 |
| NapCat 超时/非法响应 | `HISTORY_TIMEOUT` / `HISTORY_INVALID_RESPONSE` |
| 未注册 QQ/非公共群 | `TARGET_NOT_ALLOWED` |

## 6. 验收判据

### 6.1 正向

- [ ] Fake cache store 可创建两个 cache；切换后 Session 只看到目标 cache 的旧消息。
- [ ] 重启/新 Session 从同一 cache 恢复历史，能正确回答上一轮内容。
- [ ] 秘书 `secretary_cache_read` 能读取自己旧 cache；不能读取其他玩家 cache。
- [ ] GM `tf_gm_secretary_cache_read` 能读取指定玩家 cache；不发送 QQ。
- [ ] `tf_gm_secretary_cache_switch` 只改变 activeCacheId，不改变玩家身份/工具权限。
- [ ] `tf_qq_history_read` 对 private/group 返回稳定消息结构、游标和 exhausted。
- [ ] GM 对话不会出现在玩家窗口/cache。

### 6.2 负向

- [ ] 不存在/他人 cache 的 switch/read 必须失败且零状态变化。
- [ ] cache 索引损坏时不得创建空 cache 并继续。
- [ ] 未注册 QQ 的 private history 查询失败，不返回任何消息。
- [ ] 非当前公共群的 group history 查询失败。
- [ ] NapCat retcode 1200 不触发重试循环。
- [ ] 失败结果不包含物理路径、token、完整 QQ 原始 JSON。

## 7. 实施切片

1. 本 spec + `SecretaryCacheStore` 端口与内存 Fake 测试。
2. Session hydration、cache tools、cache switch/read。
3. `GmSecretaryCacheMcpTools` 与 ControlMcpServer 接入。
4. `ChatHistoryProvider` + `NapCatAdapter` 实现 + `tf_qq_history_read`。
5. 单测/focused/full gate；真实 NapCat 查询逐项标 `NOT_RUN`，等待用户明确目标/授权。

## 8. 未定项

- cache 的自动摘要/压缩：本切片不做；先保留 raw。
- cache 跨进程并发写：本切片不做，单 JVM 串行。
- NapCat 历史自动导入 cache：后续单独设计，不能在未授权时自动读取真实私聊。
- 私聊临时会话的来源群歧义：NapCat 只按 user_id；真实测试时记录限制。
