# MCP 外部发帖与 QQ 会话全量查看实现计划

> **状态**：已实现代码与自动化测试；未部署 simulation jar、未做真实 NapCat/QQ smoke
> **日期**：2026-10-24
> **关联需求**：D-013 外部 MCP 全功能总控、D-014 QQ 历史查看器、D-017 平台级 QQ 能力与受控自主入团
> **前置现状**：app/MCP 当前未运行；runtime 仍是旧状态；fresh-init 与世界事实 seed 修复另批处理
> **工作树提示**：当前已有其他会话的 campaign 未提交改动；本计划不覆盖、不合并、不提交那批文件
> **D-017 追加**：2026-10-24 追加平台级 QQ 能力与公屏自主入团设计，见第 12 节；第 1.1 节和第 11 节中与其冲突的旧边界以第 12 节为准

## 1. 用户要求

用户原话（逐字）：

> 「此外还有一个功能性要求，就是MCP端，控制方Agent不但不强制要求和app内核对Agent对账才能发推文，而且要有能力查看所有qq私信、任意qq群聊（默认只看设置的推演群，但是事实上应该啥都能看）的消息」

拆成两条可验收能力：

### FR-1 外部控制方直接发帖

外部 MCP 控制方 Agent（DSH/Hermes）可以：

1. 自行撰写推文正文，不依赖 app 内 `adjudicator` LLM、`ActionBatch` 或 `PublicationOrchestrator`；
2. 不依赖 app 内 Agent 先做内容对账/生成/审核；
3. 通过 MCP 明确选择“只建草稿”或“直接发布”；
4. 公开推文仍发到当前设置的推演群；私密推文仍发到已注册玩家的 QQ；
5. 仍保留幂等、审计、正文守卫、送达三态和防重复外发。

### FR-2 查看 Bot 能访问的全部 QQ 会话

外部 MCP 控制方 Agent 可以：

1. 列出 Bot 当前可访问的最近会话、加入的群、好友（好友列表为慢路径）；
2. 默认查看当前设置的推演群；
3. 显式查询任意一个 Bot 可访问的 QQ 群历史；
4. 显式查询任意一个 Bot 可访问的 QQ 私聊历史，包括非好友临时会话；
5. 用稳定游标分页，不把 OneBot 原始 JSON、token、路径或数据库内容暴露给控制方。

### 1.1 必须写清的现实边界

“所有 QQ 私信”不能理解为平台级窃听。本系统只实现：

- Bot 自己参与的私聊；
- Bot 已加入的群；
- NapCat/QQ 当前允许该 Bot 账号访问的历史。

不实现：

- 两个其他 QQ 用户之间的私聊；
- Bot 未加入的群；
- 绕过 QQ/NapCat 权限或从非官方数据库直接抓取；
- 把 QQ 历史自动导入玩家秘书上下文。

## 2. 当前实现事实

### 2.1 发帖

- `tf_publication_opening` 只能零玩家、无活动回合时创建 PUBLIC 草稿，仍要求
  `tf_post_decide` + `tf_post_publish`。
- `tf_publication_generate` 只接受 COMMITTED `ActionBatch`，且调用 app 内
  `adjudicatorLlm` 生成正文。
- 现有 `PostService.createPublicationDraft` 已经支持 GM 侧创建 PUBLIC/PRIVATE
  `PENDING_APPROVAL` 草稿，但 MCP 没有“自由正文创作”入口。
- 因而不存在一条路径能让外部控制方 Agent 独立撰写并发布一条普通公告/私聊，
  这就是“被要求和 app 内 Agent 对账”的实际来源。

### 2.2 QQ 历史

- `tf_qq_history_read` 当前只允许：
  - 已注册玩家/推演的私聊 QQ；
  - 当前公共群。
- `NapCatAdapter.history` 本身已按任意 `user_id` / `group_id` 调用
  `get_friend_msg_history` / `get_group_msg_history`，限制只在 MCP 工具层。
- 当前没有“列出群/最近会话/好友”的 MCP 工具，控制方无法发现目标 ID。
- 本机只读探测结果：
  - `get_recent_contact {"count":100}` 正常，返回 17 条，包含群、好友私聊和
    `chatType=100` 的群临时私聊；
  - `get_group_list` 正常，返回 14 个群；
  - 任意群历史和任意私聊历史动作可调用；
  - `get_friend_list` 与 `get_friends_with_category` 在本机超过 40 秒未返回，
    不能作为同步主路径；好友列表必须是显式慢查询，并有独立超时/缓存。

## 3. 设计原则

1. **外部控制方拥有内容主权**：正文由 MCP 调用方提供，app 内 LLM 不参与。
2. **不绕过宿主不变量**：仍走 `PostService`、幂等键、正文守卫、状态审计和送达账本。
3. **默认收敛，显式放开**：默认目标仍是当前推演群；查看其他会话必须显式给 scope/target。
4. **只读查看不进入玩家上下文**：QQ 历史是外部证据视图，不自动写秘书 cache。
5. **不可逆动作显式命名**：直接发布用单独 mode，不把“创建草稿”和“真实外发”混成一个
   默认行为。
6. **失败 fail-closed**：未知目标、超时、非法历史、MCP 未授权都不返回半截内容，
   不自动重试真实外发。

## 4. 发帖实现设计

### 4.1 新 MCP 工具：`tf_post_compose`

用途：由外部控制方直接撰写一条推文草稿，或明确要求创建后立即发布。

参数：

```json
{
  "mode": "DRAFT | PUBLISH_NOW",
  "visibility": "PUBLIC | PRIVATE",
  "playerId": "PRIVATE 时必填",
  "title": "内部标题",
  "body": "玩家可见正文",
  "idempotencyKey": "调用方稳定键"
}
```

规则：

- `PUBLIC`：
  - 作者固定 `system:publication`；
  - 实际目标永远是当前 `publicGroupId`，不允许 MCP 任意指定群；
- `PRIVATE`：
  - `playerId` 必须已注册且角色为 PLAYER；
  - 实际目标由该玩家当前 QQ 映射决定；
- `title`/`body` 长度沿用 `PostService` 上限；
- 正文先过 `PlayerFacingTextGuard`，返回 `guardApplied`；
- `mode=DRAFT`：只创建 `PENDING_APPROVAL`，不触发 QQ；
- `mode=PUBLISH_NOW`：在同一 MCP 调用内完成草稿→批准→发布；
- 不调用 `PublicationOrchestrator`、不查 `ActionBatch`、不调用任何 LLM；
- `afterMutation` 在 MCP 响应后触发现有 `PostDistributor` 外发一次。

返回：

```json
{
  "postId": "...",
  "revision": 3,
  "status": "PENDING_APPROVAL | PUBLISHED",
  "visibility": "PUBLIC | PRIVATE",
  "deliveryState": null,
  "guardApplied": false
}
```

### 4.2 幂等与恢复

`PUBLISH_NOW` 内部使用确定性三段键：

```text
external-post:<idempotencyKey>:create
external-post:<idempotencyKey>:approve
external-post:<idempotencyKey>:publish
```

实现步骤：

1. `createPublicationDraft`；
2. 读取返回 `postId` 与 `revision=1`；
3. `approve(expectedRevision=1)`；
4. `publish(expectedRevision=2)`；
5. 返回最终 post 状态。

崩溃窗口：

- 草稿创建后崩溃：重试命中 create 幂等回执，继续 approve/publish；
- approve 后崩溃：重试先命中 create/approve 回执，再继续 publish；
- publish 后崩溃：重试命中 publish 幂等回执，不重复发 QQ；
- 真实发送由现有 `PostDistributor` 与 delivery ledger 保证 at-most-once 尝试。

不宣称跨三个事件的单事务原子性；以可重试收敛和送达账本作为恢复边界。

### 4.3 代码落点

新增：

```text
iibot-app/src/main/java/io/mosire/iibot/app/publication/ExternalPostService.java
iibot-app/src/main/java/io/mosire/iibot/app/mcp/GmPostComposeTool.java
```

修改：

```text
iibot-app/src/main/java/io/mosire/iibot/app/mcp/GmPostMcpTools.java
iibot-app/src/main/java/io/mosire/iibot/app/mcp/ControlMcpServer.java
iibot-app/src/main/java/io/mosire/iibot/app/runtime/AppBootstrap.java
```

说明：

- `ExternalPostService` 持有 `PostService`、已注册玩家查询函数、`PlayerFacingTextGuard`；
- MCP 工具只做参数校验和稳定错误映射；
- 新工具复用 `PostStore.RESOURCE_NAMESPACE` 的写权限；
- 加入 `GmPostMcpTools.NAMES` 与 `ControlMcpServer` 的 allowlist/resource scope；
- 工具总数以实际 `tools/list` 为准，不在文档写死。

### 4.4 与现有发布路径的关系

| 路径 | 正文来源 | 依赖批次 | 依赖 app LLM | 用途 |
|---|---|---:|---:|---|
| `tf_publication_generate` | app 内 adjudicator | 是 | 是 | 行动结果生成 |
| `tf_publication_opening` | 外部控制方 | 否 | 否 | 旧零玩家开场 |
| **`tf_post_compose`** | **外部控制方** | **否** | **否** | **任意公告/私聊/直接发布** |

`tf_post_compose` 不删除旧两个工具，也不改变行动裁定路径。

## 5. QQ 会话查看实现设计

### 5.1 新协议中立模型

在 `iibot-connect/src/main/java/io/mosire/iibot/connect/core/` 新增：

```java
public record ChatConversation(
        SessionType chatType,
        String targetId,
        String displayName,
        String remark,
        Instant lastMessageAt,
        String lastMessageId,
        boolean configuredPublicGroup) { }
```

新增只读端口：

```java
public interface ChatDirectoryProvider {
    CompletionStage<List<ChatConversation>> recentConversations(int count);
    CompletionStage<List<ChatConversation>> joinedGroups();
    CompletionStage<List<ChatConversation>> friends(boolean noCache);
}
```

`NapCatAdapter` 同时实现 `ChatDirectoryProvider`：

- `get_recent_contact`：
  - `targetId <- peerUin`；
  - 类型优先读 `lastestMsg.message_type`；
  - 无 `lastestMsg` 时，`chatType=2` 视为 GROUP，`1/99/100` 视为 PRIVATE；
  - 展示名取 `remark`，否则 `peerName`；
  - 时间取 `msgTime`，ID 取 `msgId`；
- `get_group_list`：
  - `targetId <- group_id`；
  - 展示名取 `group_remark`，否则 `group_name`；
- `get_friend_list`：
  - `targetId <- user_id`；
  - 展示名取 `remark`，否则 `nickname`；
  - 允许 `no_cache=true`；
  - 该动作慢，必须有独立超时和缓存；超时不得拖垮 recent/groups 查询。

### 5.2 目录服务

新增：

```text
iibot-app/src/main/java/io/mosire/iibot/app/mcp/...（工具）
iibot-app/src/main/java/io/mosire/iibot/app/history/ChatDirectoryService.java
```

职责：

- 合并 recent / groups / friends；
- 按 `(chatType, targetId)` 去重；
- 标记当前 `publicGroupId` 为 configured；
- 对慢好友列表做 single-flight + TTL 缓存；
- 对 `all` 查询允许返回 `partial=true` + `warnings`，不能因好友慢查询丢失 recent/groups；
- 不返回最后一条消息正文，只返回定位元数据。

建议缓存：

| scope | TTL | 说明 |
|---|---:|---|
| configured | 无 | 直接取当前群配置 |
| recent | 15s | 最近会话短缓存 |
| groups | 30s | 群列表短缓存 |
| friends | 5min | 慢查询，single-flight |

### 5.3 新 MCP 工具：`tf_qq_chat_list`

用途：让控制方发现全部可查看会话，而不用预先知道 QQ/群号。

参数：

```json
{
  "scope": "configured | recent | groups | friends | all",
  "count": 50
}
```

默认行为：

- 缺省 `scope=configured`：只返回当前设置的推演群；
- `scope=recent`：最近会话；
- `scope=groups`：全部已加入群；
- `scope=friends`：好友列表；慢查询，可返回 `DIRECTORY_TIMEOUT`；
- `scope=all`：合并 recent + groups + friends；慢源失败时返回 partial 结果和 warning。

返回每条：

```json
{
  "chatType": "private | group",
  "targetId": "...",
  "displayName": "...",
  "remark": "...",
  "lastMessageAt": "...",
  "lastMessageId": "...",
  "configuredPublicGroup": true
}
```

### 5.4 改造 `tf_qq_history_read`

新参数：

```json
{
  "chatType": "private | group | auto（可选）",
  "targetId": "可选；省略时默认当前推演群",
  "cursor": "可选",
  "count": 20,
  "direction": "older | newer"
}
```

规则：

1. `chatType`/`targetId` 都省略：查询当前配置的推演群；
2. 只给 `targetId`：通过 `ChatDirectoryService` 解析唯一 chatType；
   - 无法解析或同 ID 同时出现在私聊/群：返回 `CHAT_TYPE_REQUIRED`；
3. 任意私聊 QQ / 任意群号均可查询，不再校验注册身份或是否为当前公共群；
4. 目标不存在、Bot 未加入群或 NapCat 拒绝：`TARGET_UNAVAILABLE`；
5. 继续使用现有恢复/空页语义：
   - retcode 1200 / 空页 → `exhausted=true`；
   - 超时 → `HISTORY_TIMEOUT`；
   - 非法响应 → `HISTORY_INVALID_RESPONSE`；
6. 只返回文本消息；图片/语音/文件/合并转发不下载、不解析；
7. 增加 `nonTextSkipped` 计数，明确本次分页里跳过了多少非文本段；
8. 结果继续限制单条 4,000 字符、总结果 32 KiB；
9. 不返回 OneBot 原始 JSON、token、完整 URL、NapCat 本地路径。

### 5.5 `ChatHistoryProvider` / `ChatHistoryPage` 扩展

修改：

```text
iibot-connect/.../core/ChatHistoryPage.java
iibot-connect/.../core/NapCatAdapter.java
iibot-app/.../mcp/GmChatHistoryTool.java
```

`ChatHistoryPage` 增加 `nonTextSkipped` 计数；保留兼容构造器，避免破坏既有测试和调用点。

### 5.6 代码装配落点

修改：

```text
iibot-app/.../runtime/BootstrapOptions.java
iibot-app/.../runtime/AppBootstrap.java
iibot-app/.../mcp/ControlMcpServer.java
```

装配规则：

- 若 adapter 实现 `ChatDirectoryProvider`，注入 `ControlMcpServer`；
- 若未注入，`tf_qq_chat_list` 不注册；
- `tf_qq_history_read` 仍可在只有 `ChatHistoryProvider` 时注册；
- 所有新工具只加入 supervisor allowlist；
- 资源 scope 使用 `qq-history/read` 与 `qq-directory/read`，不开放给秘书/玩家。

## 6. 权限、审计与隐私

1. 只有 authenticated supervisor 可调用新工具；
2. MCP 继续只绑回环地址，Bearer token 从 env 读取；
3. 工具返回和日志不得包含 token、API key、完整 HTTP URL、数据库路径；
4. 历史正文不写应用日志；日志只记 `chatType/targetId/count/resultCode`；
5. 不自动把 NapCat 历史写进秘书 cache 或世界事实；
6. 不提供玩家/秘书调用入口；
7. 读取全部会话是用户明确要求的能力扩展，必须用 D-017 记录并删掉旧 D-014
   “不提供任意 QQ 私聊读取”的非目标；
8. 真实外发仍按 M-004 在控制方 Agent/真人 GM 对话层确认；本计划不增加 app 内
   “必须经过内部 Agent”的硬门，但也不把 M-004 自动解释为已取消。若用户要取消
   M-004，需要单独裁定。

## 7. 失败语义

| 场景 | 错误码/行为 |
|---|---|
| 未授权调用 | `ResourceDeniedException`，不执行 |
| PRIVATE 未给 playerId | `INVALID_ARGUMENTS` |
| playerId 不存在/非玩家 | `TARGET_NOT_FOUND` / `TARGET_NOT_PLAYER` |
| PUBLIC/PRIVATE 互斥字段错误 | `INVALID_ARGUMENTS` |
| 正文为空/超长 | `INVALID_ARGUMENTS` |
| 正文被守卫修改 | 正常返回 `guardApplied=true`，按清洗后正文入库 |
| 草稿创建后 approve 失败 | 返回失败；同幂等键重试，不发送 |
| publish 后进程崩溃 | 重试命中幂等回执；PostDistributor 不重复尝试 |
| 私聊目标无 QQ 映射 | 不发，`UNKNOWN_PLAYER` 进入 FAILED |
| 群未加入/私聊不可达 | `TARGET_UNAVAILABLE` |
| 历史分页空/1200 | `exhausted=true`，空列表 |
| 好友列表慢 | `DIRECTORY_TIMEOUT` 或 partial + warning |
| 历史超时 | `HISTORY_TIMEOUT` |
| 历史返回非文本 | 跳过并计入 `nonTextSkipped` |

## 8. 测试计划

### 8.1 发帖

- `ExternalPostServiceTest`
  - PUBLIC DRAFT：只创建 PENDING_APPROVAL；
  - PUBLIC PUBLISH_NOW：最终 PUBLISHED、deliveryState=PENDING；
  - PRIVATE 到已注册玩家：作者和映射正确；
  - PRIVATE 未注册/非玩家：零状态变化；
  - 正文守卫：Markdown/ID/路径被清洗，`guardApplied=true`；
  - 同一幂等键重试：不重复创建 post、不重复 approve/publish；
  - 注入会抛异常的 fake LLM，证明发帖路径不调用 LLM；
  - 不依赖 ActionBatch / PackageService。
- `GmPostComposeToolTest`
  - schema、参数校验、supervisor 权限、错误码、返回字段；
- `ControlMcpServer` 装配测试
  - 新工具在 allowlist 中；
  - 未装配 `PostService` 时不注册；
  - 工具总数以 `tools/list` 断言更新。

### 8.2 QQ 历史

- `NapCatAdapterChatDirectoryTest`
  - `get_recent_contact` 解析群/好友/临时私聊；
  - `get_group_list` 解析；
  - `get_friend_list` 超时映射；
  - 任意 `user_id` / `group_id` 历史请求 payload 正确；
  - retcode 1200 空页；
  - 非文本段计数。
- `GmChatHistoryToolTest`
  - 省略参数默认查当前群；
  - 任意未注册私聊 QQ 可查；
  - 任意非当前群可查；
  - 仅 targetId 自动解析；
  - 不明类型返回 `CHAT_TYPE_REQUIRED`；
  - 结果不含 token/原始 JSON；
  - 结果大小上限。
- `ChatDirectoryServiceTest`
  - recent/groups/friends 合并去重；
  - configured 标记；
  - all 的 partial/warnings；
  - friends 缓存/single-flight；
- `ControlMcpServer` 工具名单/权限测试。

### 8.3 门禁与真实探测

- `tools/gate.sh full`；
- `tools/gate.sh docs`（若当前工作树的其他 campaign 改动阻塞，记录为外部阻塞，不伪称通过）；
- 真实 NapCat 只读 smoke：
  - `get_recent_contact`、`get_group_list`；
  - 选一个当前推演群查询 1 页；
  - 选一个明确授权的私聊目标查询 1 页；
  - 不在日志/终端打印消息正文，只记录条数、游标和错误码；
- 真实外发 smoke（需用户明确给目标与内容）：
  - DRAFT 模式不发送；
  - PUBLISH_NOW 发送一条明确测试文本；
  - 核对公共群/私聊收件和 deliveryState。

## 9. 实施阶段

### Phase 0：口径确认（0）

- 确认 D-017 措辞；
- 确认 `PUBLISH_NOW` 是需要的模式；
- 确认“任意私信”只指 Bot 可访问会话；
- 确认是否保留 M-004 真人确认；
- 确认本计划暂不向未注册 QQ 任意发私信，只读取任意会话。

### Phase 1：契约与测试先行（0.5 天）

- 新增 core records/interfaces；
- 先写 Fake provider/service 测试；
- 更新 MCP schema 测试；
- 不接真实 NapCat 写操作。

### Phase 2：QQ 目录与历史（1 天）

- `NapCatAdapter` 新增目录动作；
- `ChatDirectoryService`；
- `tf_qq_chat_list`；
- `tf_qq_history_read` 去掉白名单并支持默认群/任意目标；
- 非文本计数和错误映射。

### Phase 3：外部自由发帖（1 天）

- `ExternalPostService`；
- `tf_post_compose`；
- 幂等恢复和正文守卫；
- MCP 注册、allowlist、resource scope。

### Phase 4：装配与文档（0.5 天）

- `BootstrapOptions` / `AppBootstrap` / `ControlMcpServer` 装配；
- 更新 D-017、D-014、target runtime design、external MCP spec、README、GM prompts；
- 删除冲突的“不提供任意 QQ 私聊读取/任意群”旧条款。

### Phase 5：验证（0.5–1 天）

- focused tests；
- full gate；
- docs gate；
- 构建 shaded jar；
- 真实 NapCat 只读 smoke；
- 真实外发 smoke 等用户确认目标后执行。

## 10. 验收判据

### 发帖

- [ ] `tf_post_compose(mode=PUBLISH_NOW, visibility=PUBLIC)` 不依赖任何行动批次或 app 内 LLM，
      能创建并发布到当前推演群；
- [ ] `mode=DRAFT` 不发送，只返回 `PENDING_APPROVAL`；
- [ ] `PUBLISH_NOW` 重试不重复发送；
- [ ] 未注册私聊目标 fail-closed；
- [ ] 正文守卫和长度限制生效；
- [ ] 结果与审计可查，但日志不含正文/token。

### QQ 历史

- [ ] `tf_qq_chat_list(scope=configured)` 只返回当前推演群；
- [ ] `scope=recent/all` 能发现当前账号最近会话和全部已加入群；
- [ ] 任意已加入群可查询历史；
- [ ] 任意 Bot 可访问的私聊（含非好友临时会话）可查询历史；
- [ ] 默认不传目标时查当前推演群；
- [ ] 分页、空页、超时、1200、非文本计数语义稳定；
- [ ] 结果不泄露 token/原始 JSON/路径；
- [ ] 玩家/秘书工具面没有新增历史读取入口；
- [ ] 不自动写 cache/世界事实。

## 11. 风险与待确认

1. **“所有私信”边界**：只能是 Bot 参与的私聊；无法读取其他用户之间的私信。
2. **好友列表慢**：本机 `get_friend_list` / `get_friends_with_category` 40 秒未返回；
   计划将 recent + groups 作为主路径，friends 作为显式慢路径，并加缓存/超时。
3. **PUBLISH_NOW 安全**：单调用即真实外发。保留 M-004 的真人确认是默认假设；
   若用户要求连真人确认也取消，需要单独明确授权。
4. **任意 QQ 私信发送**：本计划只放开“读取”到任意可达会话；发帖仍限当前推演群/
   已注册玩家。若还要向未注册 QQ 直接发私信，需要另建 target 模型和投递路由。
5. **工具数变化**：新增 `tf_post_compose`、`tf_qq_chat_list`，`tf_qq_history_read`
    schema 变化；完整工具数以 `tools/list` 为准。
6. **工作树冲突**：当前已有其他会话未提交的 campaign 改动；实施与提交时必须先隔离。

## 12. D-017 追加：平台级 QQ 能力与受控自主入团

> 本节是 2026-10-24 对用户追加要求的裁定与实施补充；与前文章节冲突时以本节为准。

### 12.1 裁定结论

可以实现，但采用“**connectlib 全局能力 + app/MCP 权限收口**”，不做单个无类型 raw god tool：

1. `iibot-connect` 提供 `QqPlatformCapability` 作为平台级 typed capability：
   - 列出最近会话、已加入群、好友（慢路径）；
   - 读取任意 Bot 可访问私聊/群历史；
   - 查询群成员；
   - 按消息 ID 取消息；
   - 发送私聊（可带来源群，走临时会话）和群消息。
2. `QqPlatformCapability` 是**内部能力接口**，不是对 Agent 暴露的 raw OneBot executor：
   - 不接受任意 action 名；
   - 不接受任意 JSON；
   - 不允许直连数据库、shell、文件系统；
   - 发送动作仍走 `MessagingAdapter` 的分段、限速、三态回执和幂等。
3. app/MCP 层负责权限与游戏语义：
   - 外部 supervisor 才能访问平台工具；
   - 读取、发送、入团是不同权限；
   - 目标范围、速率、玩家上限、群 allowlist 可配置；
   - 所有真实发送有幂等键和审计。
4. “平台量级”仍然只覆盖 Bot 账号可访问的 QQ 会话；不突破平台权限。
5. 公屏自主入团是受控例外：外部控制方 Agent 在策略允许范围内可以自主注册并发送报名表，
   不要求 app 内 Agent 审核或逐次真人确认；用“来源消息 opt-in + 群 allowlist + 配额 +
   单 QQ 幂等 + delivery ledger + 审计”替代逐次确认。该例外不适用于任意公告或任意群发。

### 12.2 Connectlib 新能力接口

建议新增：

```java
public interface QqPlatformCapability {
    CompletionStage<List<ChatConversation>> recentConversations(int count);
    CompletionStage<List<ChatConversation>> joinedGroups();
    CompletionStage<List<ChatConversation>> friends(boolean noCache);
    CompletionStage<List<GroupMember>> groupMembers(String groupId);
    CompletionStage<ChatHistoryPage> history(ChatHistoryQuery query);
    CompletionStage<ChatMessageDetail> message(String messageId);
    CompletionStage<SendReceipt> sendPrivate(PrivateSendRequest request);
    CompletionStage<SendReceipt> sendGroup(GroupSendRequest request);
}
```

新增记录：

```text
ChatConversation(chatType, targetId, displayName, remark, lastMessageAt, lastMessageId)
GroupMember(groupId, qqId, displayName, card, role)
ChatMessageDetail(messageId, chatType, targetId, senderId, senderName, text, sentAt)
PrivateSendRequest(targetQqId, temporaryGroupId?, texts, idempotencyKey)
GroupSendRequest(groupId, texts, idempotencyKey)
```

实现落点：

```text
iibot-connect/.../core/QqPlatformCapability.java
iibot-connect/.../core/ChatConversation.java
iibot-connect/.../core/GroupMember.java
iibot-connect/.../core/ChatMessageDetail.java
iibot-connect/.../core/PrivateSendRequest.java
iibot-connect/.../core/GroupSendRequest.java
iibot-connect/.../napcat/NapCatPlatformCapability.java
```

`NapCatPlatformCapability` 复用 `NapCatAdapter` 的动作 HTTP 和错误映射，不复制一套协议栈。

### 12.3 MCP 工具分层

默认启用的读取/入团工具：

```text
tf_qq_chat_list
tf_qq_history_read
tf_qq_group_members
tf_qq_message_get
tf_player_onboard_from_group
```

默认关闭的任意发送工具：

```text
tf_qq_send_private
tf_qq_send_group
```

规则：

- 任意发送工具必须显式设置 `platform.allowArbitrarySend=true` 且授予
  `qq-platform:send` 权限后才注册/放行；
- `tf_player_onboard_from_group` 是受控组合工具：内部可以发送报名表，但不开放任意目标群发；
- 读取工具默认 `scope=configured`，显式 `recent/groups/friends/all` 才扩展；
- 任何工具都不接受 raw OneBot action 或任意 JSON。

建议权限矩阵：

| 能力 | 默认 | 权限 |
|---|---|---|
| 当前推演群历史 | 开 | `qq:history:read` |
| 最近会话/全部群历史 | 开 | `qq:history:read` |
| 群成员 | 开 | `qq:member:read` |
| 任意私聊发送 | 关 | `qq:send:private` + 显式配置 |
| 任意群发送 | 关 | `qq:send:group` + 显式配置 |
| 公屏玩家自主入团 | 开（仅允许群） | `onboarding:register` + group allowlist |

### 12.4 自主入团工具 `tf_player_onboard_from_group`

参数：

```json
{
  "sourceGroupId": "...",
  "sourceMessageId": "...",
  "qqId": "...",
  "displayName": "...",
  "registrationForm": "可选；省略用默认报名卡",
  "idempotencyKey": "..."
}
```

执行顺序：

1. 校验调用者是 supervisor，且 `onboarding.enabled=true`；
2. 校验 `sourceGroupId` 在 `onboarding.allowedGroupIds` 中；
   - 默认只允许当前 `publicGroupId`；
   - 显式配置 allowlist 或 `*` 才扩展到平台量级；
3. 调用 `QqPlatformCapability.message(sourceMessageId)`：
   - 消息必须存在；
   - 必须属于 `sourceGroupId`；
   - 发送者必须是 `qqId`；
   - 默认要求文本含报名/加入意图关键词（可配置）；
4. 校验该 QQ 未注册为其他 playerId，且未超过 `onboarding.maxPlayers`；
5. 生成稳定 playerId：`player-` + `sha256("qq:" + qqId)` 前 16 位；
   - 也允许调用方显式提供 playerId，但必须通过注册校验；
6. `ControlBootstrapService.registerPlayer`；
7. `ControlBootstrapService.bindSecretary`；
8. 发送报名表：
   - 以 private post / 受控 direct delivery 持久化；
   - `temporaryGroupId=sourceGroupId`，走群临时会话；
   - 使用 delivery ledger，key 为 `onboarding:<playerId>:registration-card:v1`；
   - 正文过 `PlayerFacingTextGuard`；
9. 返回：

```json
{
  "playerId": "...",
  "qqId": "...",
  "secretaryBound": true,
  "registrationCard": {
    "deliveryState": "SENT | FAILED | UNKNOWN",
    "messageIds": ["..."]
  }
}
```

入团本身是幂等的：同一 QQ 重复调用返回既有 playerId 和既有报名卡投递状态；
`UNKNOWN` 不自动重发，需要人工显式重试。

本工具是用户明确要求的自主入团例外：只要来源消息 opt-in、群 allowlist、配额和 QQ 去重全部通过，
外部控制方 Agent 可以直接完成注册与报名卡发送，不要求每名玩家再由真人 GM 点一次确认。
任意公告/任意私聊/任意群发仍不享有该例外。

### 12.5 报名表的持久化选择

不直接在 MCP 工具里裸调 `OutboxSender` 后返回。建议：

- 优先把报名表做成一条 `PRIVATE` 的持久化 post，作者为该新 playerId；
- 为 post 扩展可选 `deliveryTemporaryGroupId`，使 `PostDistributor` 能把来源群传给
  `OutboxMessage.privateTemporaryMessage`；
- 复用现有 `PUBLISHED → deliveryState` 和 `PostDistributor`；
- 若不想改 `Post` 模型，则新增最小 `OnboardingCardDelivery` 事件/记录，但必须有
  持久化状态和 delivery ledger，不能只做进程内发送。

### 12.6 权限实施落点

新增配置：

```text
control.platform.readAnyChats = true        # 默认 true
control.platform.allowArbitrarySend = false # 默认 false
control.onboarding.enabled = true
control.onboarding.allowedGroupIds = ["<current-public-group>"] # 或显式列表 / ["*"]
control.onboarding.requireOptIn = true
control.onboarding.maxPlayers = 64
control.onboarding.maxCardsPerMinute = 10
```

落点：

```text
iibot-app/.../config/AppConfig.java
iibot-app/.../mcp/ControlMcpConfig.java
iibot-app/.../mcp/ControlMcpServer.java
iibot-app/.../control/PlayerOnboardingService.java
iibot-app/.../mcp/PlayerOnboardingTool.java
```

权限检查顺序必须是：

```text
bearer token
→ supervisor role
→ tool allowlist
→ operation permission
→ target/group policy
→ quota/rate limit
→ source-message verification
→ idempotency
→ execute
→ audit
```

### 12.7 新增测试

- `QqPlatformCapabilityTest` / `NapCatPlatformCapabilityTest`
  - recent/groups/members/message/history 解析；
  - temp private send 带 `temporaryGroupId`；
  - 超时、1200、非法响应映射；
  - 不暴露 raw JSON。
- `PlayerOnboardingServiceTest`
  - 合法来源消息 + 群 allowlist：注册、绑定、报名卡发送；
  - 来源群不在 allowlist：零状态变化；
  - 消息 ID 不存在/发送者不匹配：零状态变化；
  - 无报名意图：拒绝；
  - 重复 QQ：幂等；
  - 达到 maxPlayers：拒绝；
  - 报名卡失败：玩家注册保留，状态明确，不自动群发重试；
  - UNKNOWN 保持 UNKNOWN，不自动重发。
- `PlayerOnboardingToolTest`
  - supervisor 权限、参数 schema、错误码、返回字段。
- `ControlMcpServerTest`
  - 默认 only onboarding tool exposed，arbitrary send tools absent；
  - 显式开启 allowArbitrarySend 后才注册发送工具；
  - tool allowlist/resource scope 正确。
- 全量 `tools/gate.sh full`。

### 12.8 验收判据（D-017）

- [ ] Connectlib 提供 typed 平台能力，不提供 raw action。
- [ ] 读取任意 Bot 可访问私聊/群历史；但无法读取未加入群/他人私聊。
- [ ] 默认只允许当前推演群入团；显式 allowlist/`*` 后可扩展到平台范围。
- [ ] 群消息中的公屏玩家经来源证据校验后可被自主注册并绑定秘书。
- [ ] 合法入团不要求 app 内 Agent 审核或逐次真人确认，但所有策略检查均通过。
- [ ] 报名表可通过群临时私聊发送，且有持久化 delivery 状态。
- [ ] 重复调用不重复注册、不重复发报名表；UNKNOWN 不自动重发。
- [ ] 任意发送工具默认关闭，开启需显式配置和权限。
- [ ] 玩家/秘书工具面不能访问平台能力。
- [ ] 日志和返回值不含 token、原始 JSON、正文详情。

## 13. 实施结果（2026-10-24）

已落地：

- `iibot-connect`：
  - `QqPlatformCapability` typed 平台能力；
  - `ChatConversation` / `GroupMember` / `ChatMessageDetail`；
  - `NapCatAdapter` 实现最近会话、群列表、好友慢路径、群成员、get_msg、
    临时私聊发送和群发送；
  - `ChatHistoryPage.nonTextSkipped`。
- `iibot-app`：
  - `tf_qq_chat_list`：默认 configured，显式 recent/groups/friends/all；
  - `tf_qq_history_read`：默认当前推演群，任意 Bot 可访问目标，不再有注册/当前群白名单；
  - `tf_qq_group_members`、`tf_qq_message_get`；
  - `tf_post_compose`：外部撰写 DRAFT / PUBLISH_NOW，不依赖 ActionBatch/内部 LLM；
  - `tf_player_onboard_from_group`：来源群消息校验、注册、绑定秘书、发送报名卡；
  - `tf_qq_send_private` / `tf_qq_send_group`：仅 `allowArbitrarySend=true` 时注册；
  - `control.platform` 策略：onboarding allowlist、opt-in、人数上限、任意发送开关；
  - OutboxSender 支持显式来源群的临时私聊路径。
- 默认模拟 MCP 工具数 77；显式开启任意发送后 79。以 `tools/list` 为准。

验证：

- `tools/gate.sh full` **PASS**；
- 全仓 **604 tests / 0 failures / 0 errors / 0 skipped**
  （connect 36、connect-agent 21、taskflow 251、app 296）；
- shaded jar 成功：`iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar`
  sha256 `36be71e69743e89e6c94285613409c8ada55a53a27c2c8777bcb9b3dd66374e8`；
- 真实 NapCat/QQ 读取与外发 smoke **NOT_RUN**；
- simulation/dist jar **未替换**，因为旧 app 仍在运行；未停止、未重启、未发布任何真实 QQ 消息。

## 14. D-018 追加：工单板长轮询

> 2026-10-24 追加。用户要求：给 Agent 看的告示板增加等待模式；在第一条信息来临前，
> 工具调用不结束。

已实现：

- `TicketService` 增加单调 `changeVersion`、原子 `stateView()`、`awaitChange(version, timeout)`；
- 只有成功 save/commit 才推进版本并唤醒等待者；存储失败不唤醒；
- `tf_ticket_board` 增加：
  - `wait`：boolean，默认 false；
  - `waitSeconds`：1..1800，默认 300。
- 语义：
  - 已有 OPEN/CLAIMED/CONFIRMED 工单：立即返回，`alreadyPending=true`；
  - 否则阻塞到下一次成功工单变更/新工单到达；
  - 超时返回 `waitTimedOut=true` 与当前板面；
  - 等待中断返回 `WAIT_INTERRUPTED`，不伪装成成功。
- 长轮询返回仍只包含计数/摘要，不返回工单正文。
- 工具名与工具数不变（77 / allowArbitrarySend 时 79）。

测试：

- `TicketServiceChangeWaitTest`：新工单唤醒、版本推进、回复推进版本、超时；
- `GmTicketBoardToolWaitTest`：真实阻塞后新工单唤醒、已有工单立即返回、1 秒超时。

未验证：

- 真实 DSH MCP client 的请求超时是否大于 300 秒；若客户端先超时，需要降低
  `waitSeconds` 或调整 DSH MCP client 超时；
- app 关闭/重启时的进行中长轮询取消语义尚未做真实进程级 smoke。
