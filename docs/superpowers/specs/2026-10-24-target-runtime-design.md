# 目标运行设计与模块边界（v1，已实现切片）

> **状态**：有效（P0–P6 的 Fake/Mock 纵向切片已实现；真实 E2E 见 STATUS；Q5/Q6/Q8/Q9 仍待定）
> **日期**：2026-10-24
> **本文取代**：无
> **用户原话**：见 [`2026-10-24-user-requirements-source.md`](2026-10-24-user-requirements-source.md)
> **上游契约**：[三库](../../upstream/project-mosire-three-libs-0.2.3.md)、[NapCat/OneBot 11](../../upstream/napcat-onebot11-contract.md)

> 本文描述目标运行序列与不变量。P0–P6 的 Fake/Mock 切片已按此实现；真实 NapCat、真实 LLM、
> 真实 DSH MCP 与事件回放恢复仍未验证。当前事实以 [`../../agents/STATUS.md`](../../agents/STATUS.md) 为准。

## 1. 目标拓扑

```text
玩家 QQ ──私聊──► Bot ──► 秘书 Agent(每玩家一个) ──问询/收 brief──► GM/中央推演 Agent
                    │                                                │
                    └──────────── 玩家硬确认决策 ◄──────────────────┘
推演 QQ ──私聊──► Bot ──► 中央 GM/推演 Agent
公共 QQ 群 ◄──公开信息/公告── Bot
外部 Agent（DSH）──MCP（本地回环 + Bearer）──► 全游戏事件流控制面

┌─────────────────────────────────────────────────────────────────────┐
│ iibot-app（组合根 / 唯一进程）                                       │
│                                                                     │
│ iibot-connect      MessagingAdapter + NapCat/OneBot 11              │
│      │ 归一化 InboundMessage / OutboundMessage                      │
│      ▼                                                             │
│ iibot-connect-agent  connect 能力 → AgentLib AgentTool/权限          │
│      ▼                                                             │
│ iibot-taskflow     领域状态机 / GM brief / 秘书 / 中央推演 / 控制工具  │
│      ├────────────► SQLite 事件审计（AgentLib EventStore）           │
│      ├────────────► Workspace/Docs 世界文档与审计日志                 │
│      ├────────────► Outbox → iibot-connect 发送                      │
│      └────────────► app/mcp 认证代理 → AgentToMcpServer             │
└─────────────────────────────────────────────────────────────────────┘
```

**单进程**是 v1 默认：便于原子状态机、串行化中央推演与统一审计。秘书与 GM 是**逻辑子 Agent**，
不是独立进程或独立 QQ 账号；外部 Agent 通过 MCP 控制游戏事件流，不直接操作 SQLite 或 QQ 连接。

## 2. 模块边界与依赖

```text
iibot-connect ──► (Jackson + JDK HTTP/WS + SLF4J)
      ▲
iibot-connect-agent ──► iibot-connect + agentlib-mosire
      ▲
iibot-taskflow ──► connect/connect-agent + agentlib/workspace/docs
      ▲
iibot-app ──► taskflow + connect-agent + 认证 MCP 传输 + 可运行入口
```

| 模块 | 拥有 | 禁止 |
|---|---|---|
| `iibot-connect` | 统一消息/连接 SPI、NapCat/OneBot 11、重连、心跳、鉴权、去重、限速、分段、发送三态 | 世界规则、LLM 提示词、Taskflow 类型 |
| `iibot-connect-agent` | connect 能力 → `AgentTool`；ToolSpec/角色/资源/敏感与破坏性/脱敏/错误码 | 世界/回合/决策领域逻辑 |
| `iibot-taskflow` | 领域状态机、秘书/GM、brief、自然语言决策草案、玩家硬确认、中央推演、事件、外部控制工具 | OneBot/NapCat 类型、直接 QQ 发送 |
| `iibot-app` | 组合根、严格配置、SQLite 审计、Workspace/Docs、Outbox、Mailbox、Bearer MCP 代理、Main | 领域规则的第二套实现、伪造权限 |

AgentLib 三库只在 `iibot-connect-agent` / `iibot-taskflow` / `iibot-app` 中作为实现细节；`iibot-connect` 不 import
`io.mosire.agentlib.*` 或 Taskflow 类型，以避免协议/运行时类型污染接入契约。

## 3. 核心数据所有权与值对象

### 3.1 角色与会话主体

```text
QqRole = PLAYER | DIRECTOR
Actor = PLAYER(playerId) | DIRECTOR | SECRETARY(playerId) | GM | SUPERVISOR(external-mcp)
AccountBinding = qqUserId -> QqRole + actorId + displayName
```

- 玩家/推演角色映射只来自配置或本地运维命令；
- 秘书是系统为每个玩家派生的逻辑 Agent，不是 QQ 角色，也不能被玩家注册/替换；
- 每个玩家私聊只映射到自己的秘书；推演私聊映射到中央 GM/推演 Agent；
- 公共群是发布通道；群内普通消息在 Q8 未定前不进入 Agent；
- QQ 事件里的 `sender`、昵称、群名片等不改变角色；
- 未登记 QQ 用户没有 actorId，不能构建 Agent 上下文；
- 外部 MCP 调用者固定为 `SUPERVISOR`，不能伪装成玩家/推演/秘书。

### 3.2 世界事实与可见性

```text
Visibility = PUBLIC
           | PLAYERS(Set<ActorId>)
           | DIRECTOR_ONLY
           // 扩展时只允许新增，不放宽既有语义

WorldFact {
  factId, worldVersion, kind, title, content,
  visibility, tags, source, createdAt
}
WorldBible {
  worldId, version, corePromptVersion, corePromptHash,
  facts[], publicSummary, createdAt
}
```

- 世界事实不可原地改；每次改动产生新版本；
- 每条事实有稳定 `factId`，LLM 必须引用已有 factId，不能凭空创造持久事实；
- 可见性在进入任意模型上下文前由宿主求值；不把“请保密”当安全边界；
- 推演可见全部；玩家可见 PUBLIC + 自己命中的 `PLAYERS`。

### 3.3 设定提案

```text
SettingProposal {
  proposalId, authorActorId, baseWorldVersion,
  targetKind, proposedText, structuredFacts[],
  status = PENDING | APPROVED | REJECTED,
  reviewerActorId, reviewNote, decidedAt
}
```

- 玩家提案必须由推演批准；推演提案可由推演显式确认；
- 批准后生成新 `WorldVersion`，旧回合仍绑定旧版本；
- 提案、审核与版本切换均写事件。

### 3.4 决策草案、硬确认与决策

```text
DecisionDraft {
  draftId, turnId, playerId, secretarySessionId,
  intent, actions[], resources, target, contingency,
  rawConversationRefs[], revision,
  status = DRAFT | AWAITING_CONFIRM | CONFIRMED | SUPERSEDED | EXPIRED | WITHDRAWN,
  previewHash, expiresAt
}
Decision {
  decisionId, draftId, turnId, playerId,
  decisionVersion, contentHash,
  confirmedByQqId, confirmedAt, confirmationMethod = PRIVATE_COMMAND
}
```

- 玩家用**自然语言**与秘书讨论；秘书可用 `decision_draft` 工具生成/更新结构化草案；
- 秘书可以自主调用 draft 工具，但**不能**把草案变成有效决策；
- 秘书把 `draftId + 字段预览 + 回合号 + 过期时间` 回显给玩家，并进入 `AWAITING_CONFIRM`；
- 玩家必须在**同一私聊**发送固定硬命令 `/确认 <draftId>`；宿主校验 draftId、玩家身份、回合、previewHash 后
  生成 `Decision` 并写事件；
- 自然语言“同意/提交/可以”不是硬确认；群聊、其他玩家、外部 MCP、GM 都不能代替；
- 一个玩家一个回合至多一个有效 `Decision`；确认新 draft 会让旧的待确认 draft `SUPERSEDED`；
- 决策是**叙事意图**，不是可执行工具调用；中央 GM/推演 Agent 不能把自由文本变成宿主命令；
- 冻结时只收集 `CONFIRMED` 决策，并记录内容哈希。

### 3.5 秘书信息 brief（GM 审查/过滤产物）

```text
InfoRequest {
  requestId, playerId, secretaryActorId, turnId,
  question, requestedAt, status
}
InfoBrief {
  briefId, playerId, turnId, sourceFactIds[],
  content, visibility, issuedByGmActor, issuedAt, expiresAt,
  filterPolicyVersion, contentHash
}
SecretaryKnowledge {
  playerId, briefs[], revokedBriefIds[], lastUpdatedSeq
}
```

- 秘书不读取完整 `WorldBible`；只能使用当前玩家可见的 `InfoBrief` 和本人会话；
- 秘书可调用 `request_brief` 向 GM 提信息请求；GM 的“信息过滤模式”只装载**该玩家可见事实 + 玩家问题 + 公共信息**，
  因此 GM 在该模式下也无从泄露不可见秘密；
- GM 发布 brief 时宿主再次校验：`sourceFactIds` 必须是该玩家可见事实；不可见 fact 不得进入 brief；
- brief 有版本与 hash；撤销/过期后从下一次上下文组装起不再注入；
- GM 的全量推演模式与信息过滤模式使用不同 prompt、不同工具面和不同上下文，禁止混用。

### 3.6 回合

```text
Turn {
  turnId, turnNo, worldVersion, promptVersion,
  state, revision, openedAt, closesAt,
  decisions[], lockedSnapshotHash,
  adjudicationStatus, committedEventSeq
}
```

状态机：

```text
SETUP ──open──► OPEN ──lock──► LOCKED ──start──► ADJUDICATING
                  │                                 │
                  │                                 ├── valid → COMMITTED
                  │                                 └── invalid/error → FAILED_RETRYABLE
                  │
                  └── pause ──► PAUSED ──resume──► OPEN/LOCKED

COMMITTED ──publish──► PUBLISHED ──next──► OPEN(next turn)
ANY non-terminal ──cancel──► CANCELLED
```

约束：

- 只有 `OPEN` 接受决策；
- 只有 `LOCKED` 可启动推演；
- 一个回合只能有一个成功 `COMMITTED`；
- `COMMITTED` 之后世界版本只增不减；
- 任意转移带 `expectedRevision`；CAS 失败返回冲突，不自动覆盖；
- 自动截止由宿主调度器触发，但真正冻结必须由同一个命令处理器完成。

### 3.7 事件

事件存储为权威事实源。每个领域事件至少包含：

```json
{
  "schemaVersion": 1,
  "eventId": "uuid",
  "turnId": "turn-0007",
  "worldVersion": 3,
  "actor": "player:10001",
  "idempotencyKey": "...",
  "correlationId": "...",
  "causationId": "...",
  "occurredAt": "..."
}
```

首批事件类型：

| 事件 | 含义 |
|---|---|
| `account.binding.loaded` | 本地配置角色映射装载 |
| `qq.message.received` | 去重后接收的入站消息 |
| `qq.message.rejected` | 未注册/超限/非文本等拒绝 |
| `conversation.message.appended` | 玩家/推演会话消息追加 |
| `llm.call.completed` | LLM 调用元数据（脱敏） |
| `setting.proposal.created` / `setting.proposal.decided` | 设定提案与审核 |
| `world.version.activated` | 新世界版本生效 |
| `secretary.info.requested` / `gm.info.brief.issued` | 秘书向 GM 请求信息、GM 发布过滤 brief |
| `secretary.message.appended` | 秘书与玩家的私聊上下文消息 |
| `decision.draft.created` / `decision.draft.previewed` | 秘书生成/更新决策草案 |
| `decision.confirmed` / `decision.superseded` | 玩家硬确认/旧草案被替代 |
| `turn.opened` / `turn.locked` | 回合开始/冻结 |
| `decision.submitted` / `decision.withdrawn` | 硬确认后进入回合的决策变更 |
| `turn.adjudication.started` | 中央 Agent 启动 |
| `turn.adjudication.proposal` | 模型提交的结构化提案（可用于审计） |
| `turn.adjudication.rejected` | 提案非法/失败，记录原因 |
| `turn.committed` | 合法提案生效的唯一世界变更事件 |
| `turn.published` | 结果已进入出站 outbox |
| `qq.outbound.requested` / `sent` / `failed` / `unknown` | 外发状态 |
| `control.requested` | 外部 MCP 写请求与结果 |
| `system.error` | 需要运维关注的错误 |

> 事件 payload 只放必要信息；完整 QQ token、API key、原始鉴权头绝不落事件。

## 4. 目标运行序列

### 4.1 启动与恢复

1. 读取配置：QQ 绑定、数据目录、LLM 路由、回合时长、MCP 开关。
2. 打开事件存储；按 seq 重放领域事件，重建世界投影、回合投影、会话摘要、outbox 投影与幂等索引。
3. 校验不变量：
   - 至多一个非终态回合；
   - `COMMITTED` 事件之前必须有 `turn.locked`；
   - 世界版本单调；
   - 每个回合至多一个 committed 结果；
   - outbox 中 `UNKNOWN` 不自动重发。
4. 启动 QQ Adapter；恢复连接与心跳。
5. 恢复调度器：若当前回合 `OPEN` 且已过截止，则立即转入 `LOCKED`；若 `ADJUDICATING` 但无进程内任务，则标 `FAILED_RETRYABLE` 等待重试。
6. 启动 MCP 控制面（若启用）；只读工具先可用，写工具继续受状态机限制。

**失败语义**：事件流无法解析/发现未知 schema 时 fail-closed，不启动 QQ/Agent；只读诊断可通过本地 CLI/日志。

### 4.2 QQ 入站

1. NapCat Adapter 接收事件并归一化为 `InboundMessage`。
2. Adapter 使用 `self_id + message_id + sub_type` 生成去重键；群消息另加 `group_id`。
3. 若消息不是纯文本（首期）→ 记录拒绝/提示，交 outbox 回复；不进入 LLM。
4. 查询角色绑定：
   - 未绑定 → 安全拒绝模板，不泄露世界信息；
   - `PLAYER` / `DIRECTOR` → 构建 actor。
5. 持久化或内存校验去重键（由投影持有）；重复事件直接忽略，不产生第二次回复。
6. 命令解析器优先于 LLM：
   - `/帮助`、`/状态`、`/世界`、`/决策`、`/确认`、`/取消`、`/设定`、`/回合...`；
   - 非命令文本进入当前 actor 的会话 Actor。
7. 同一 actor 的消息投递到单线程/串行 mailbox；不同 actor 可并行。

### 4.3 玩家自由对话

1. 读取 actor、当前回合、会话历史摘要、可见世界投影。
2. 组装 prompt：
   - 固定玩家 Agent 规则；
   - 世界核心提示版本 + 公开摘要；
   - 只注入该玩家的可见事实；
   - 该玩家会话最近消息 + 旧消息摘要；
   - 当前回合状态与决策草稿（仅该玩家自己的）。
3. 注册只读工具：`world_lookup`、`turn_status`、`my_decision_status`（实现可按需拆分）。
4. 用 AgentLib `AgentLoop.runTurn` 执行工具/对话循环；工具调用必须经 `ToolCallAuthorizer`。
5. 写 `conversation.message.appended`、`llm.call.completed`；回复进入 outbox。
6. 不允许玩家 Agent 调用写世界/写设定/发 QQ/推进回合的工具。

### 4.4 决策提交

1. 玩家发 `/决策`：进入 `EDITING`，Bot 返回固定决策卡模板和当前回合/截止时间。
2. 玩家逐行填写或粘贴固定标签内容；Bot 只缓存，不立即提交。
3. 玩家发 `/确认`：
   - 校验当前回合 `OPEN`、玩家角色、草稿属于该 QQ 用户；
   - 解析标签/长度/动作数量；失败返回字段级错误；
   - 生成 `Decision` 与 `contentHash`，用 `expectedRevision` 提交 `decision.submitted`；
   - 同时返回预览和提交编号。
4. 重复确认命中幂等键 → 返回已提交回执，不重复记决策。
5. 截止后：`/确认` 被拒绝并记录原因；不因中央 Agent 还没开始就偷偷接收。

### 4.5 冻结回合

1. 触发来源：推演 `/回合截止`，或调度器到达 `closesAt`，或外部 MCP `turn_lock`（若启用）。
2. 校验 `OPEN` + `expectedRevision`。
3. 生成不可变 `TurnSnapshot`：
   - `turnId`、`turnNo`、世界版本、核心提示版本/hash；
   - 全部 `SUBMITTED` 决策（含 hash）；
   - 随机种子（若本回合需要随机判定）；
   - 冻结时间。
4. 追加 `turn.locked` 事件，转入 `LOCKED`；此后任何决策写入被拒绝。

### 4.6 中央 Agent 推演

1. `TurnCoordinator` 以单飞锁把 `LOCKED → ADJUDICATING` 事件写出。
2. 组装中央上下文：
   - 固定推演 Agent 规则；
   - 完整世界版本（公开 + 私密 + 推演专用）；
   - 全部决策与玩法规则；
   - 上一回合结果/未结线索；
   - 明确的输出 schema 和“不可越权”边界。
3. 注册只读世界工具 + 唯一的提案工具 `submit_adjudication`。
4. 中央 Agent 通过 `AgentLoop` 运行；`submit_adjudication` 只把结构化提案放入本回合 staging，不直接改世界、不发 QQ。
5. 宿主做确定性校验：
   - schema 与大小；
   - 引用实体均存在且属于当前世界版本；
   - 每个玩家最多一条结果；不可给未登记 actor 发私密结果；
   - 变更指令白名单；不能改角色/权限/提示词；
   - 公开/私密可见性合法；
   - 提案 worldVersion 与回合一致。
6. 非法 → 写 `turn.adjudication.rejected`；最多重试 N 次，仍失败则 `FAILED_RETRYABLE`，等待推演/运维重试。
7. 合法 → 写**一个** `turn.committed` 事件，包含：
   - `AdjudicationResult`（公开叙事、每玩家私密结果、世界事实增量、后续线索）；
   - 提案 hash、模型/路由元数据、耗时、token 用量；
   - 新的 `worldVersion`。
8. 提交成功后才允许发布；失败零世界变更。

### 4.7 发布结果

1. `turn.committed` 投影生成发布计划：
   - 公共公告（按配置进入私聊/群聊）；
   - 每个玩家的私密结果；
   - 推演审计摘要。
2. 发布通过 outbox 逐条发送；每条有稳定 `outboundId` 和幂等键。
3. 发送成功写 `qq.outbound.sent`；明确本地失败写 `failed`；超时/连接中断写 `unknown`，默认不自动重发。
4. 全部必需消息达到终态后写 `turn.published`，然后允许开下一回合。

### 4.8 下一回合

- 推演显式 `/回合开始`；或配置允许自动开下一回合。
- 新回合继承最新已提交世界版本与新的提示版本。
- 上回合未发送/`UNKNOWN` 的出站不得阻塞世界状态推进，但必须在健康检查中可见，需人工处置。

## 5. 提示词与上下文组装

### 5.1 分层

```text
[固定核心规则] + [角色规则] + [世界设定/可见事实] + [回合状态]
+ [会话历史/摘要] + [本轮输入] + [输出契约]
```

- **固定核心规则**：只由本地部署文件/配置提供，有版本与 hash；聊天不可修改。
- **角色规则**：玩家 Agent 与中央推演 Agent 不共用同一权限描述，避免玩家获得推演规则细节。
- **世界设定**：从当前 `WorldVersion` 投影生成；按 actor 过滤。
- **输出契约**：玩家 Agent 只输出叙事；中央 Agent 必须通过 `submit_adjudication` 工具提交结构化结果。
- 不把宿主错误堆栈、内部路径、token、其他玩家私聊内容放入 prompt。

### 5.2 上下文窗口与压缩

- AgentLib `ContextWindow` 非线程安全，一个会话串行使用。
- 每个玩家独立窗口；中央 Agent 每次推演使用隔离窗口，避免上个回合的秘密污染下一回合。
- 历史超过 token 预算时生成摘要事件；摘要必须只覆盖该玩家可见内容。
- 摘要生成也走 LLM 路由，记录调用元数据；失败时回退到最近 N 条可见消息，不得注入不可见事实。

### 5.3 输出可信边界

- 玩家 Agent 的自然语言不能成为世界事实，除非通过设定提案/决策/推演提交。
- 中央 Agent 的叙事可以输出新名称，但结构化世界变更必须使用已知实体或显式 `create_fact` 提案；
- 所有持久事实都要有来源 actor 与事件 seq。

## 6. 工具面

### 6.1 玩家 Agent（只读）

| 工具 | 用途 |
|---|---|
| `world_lookup` | 查询当前 actor 可见事实/实体，带分页与实体 ID |
| `turn_status` | 当前回合、截止、决策状态 |
| `my_decision_status` | 自己的草稿/有效决策与 hash |
| `session_note_read`（可选） | 读取自己可见的会话摘要 |

不得有：`doc_create`、`workspace_mount`、QQ 发送、回合写操作、设定批准、任意文件/网络工具。

### 6.2 中央推演 Agent

| 工具 | 用途 |
|---|---|
| `world_lookup_full` | 只读完整当前世界版本/实体，带版本校验 |
| `turn_snapshot_read` | 读取当前冻结回合快照（决策、hash、种子） |
| `submit_adjudication` | 提交结构化推演提案；只 staging，不提交世界 |
| `random_check`（可选） | 记录种子的确定性随机判定 |

中央 Agent 没有 QQ 发送、文件写、数据库、shell、MCP 控制面工具。

### 6.3 三库工具复用

- `DocsTools.all()` / `WorkspaceTools.all()` 可作为内部管理工具/推演侧只读文档入口；
- 不把它们的写工具直接暴露给玩家 Agent；
- 所有工具执行统一经过 `ToolCallAuthorizer`；不绕过资源/角色/破坏性/敏感判定；
- `AgentTool.jsonSchema()` 必须可被 MCP SDK 校验；外部 MCP 导入工具需注意 schema 顶层 `null` 的已知边界。

## 7. 外部 MCP 控制面

详见 [`2026-10-24-external-mcp-control-plane.md`](2026-10-24-external-mcp-control-plane.md)。当前实现摘要：

- 独立 ToolRegistry + 内部 `AgentToMcpServer` + 外部 Bearer 认证代理；
- 默认关闭、仅回环、精确路径、请求体上限；
- 5 个只读 + 14 个全游戏事件流写工具，写工具统一带幂等键/期望 revision/原因；
- 不暴露玩家决策提交/确认、任意 QQ 发送、停机、改角色、密钥或 shell；
- 长时间 SSE/断线重连/Last-Event-ID 重放尚未验证。

## 8. 时间、随机与并发

- 宿主注入 `Clock`；单测用 FakeClock，领域逻辑不直接 `Instant.now()`。
- 自动截止由调度器触发，但状态转移始终经过命令处理器与 expectedRevision。
- 随机规则必须记录 `randomSeed` 与算法版本；中央 Agent 不得自造随机结果。
- 同一 actor mailbox 串行；同一回合中央推演单飞；事件 append 由存储串行化。
- 不允许两个线程同时修改同一个 `ContextWindow`。

## 9. 安全与不变量

1. 角色、可见性、工具权限不可由模型输出扩大。
2. 玩家 Agent 上下文里不出现其不可见事实；工具结果同样过滤。
3. 中央 Agent 只提交提案；宿主校验后才提交世界。
4. 每个对外写操作有幂等键与操作者事件。
5. 外发超时进入 `UNKNOWN`，不自动重复发送。
6. 不提供远程关机、任意 shell、任意文件、任意 QQ 发送 MCP 工具。
7. 配置/密钥/完整 QQ 事件中的敏感字段在日志与事件里脱敏。
8. 事件重放必须从不变量校验开始；坏事件 fail-closed。

## 10. 文件与包建议

```text
iibot-connect/src/main/java/io/mosire/iibot/connect/{core,napcat}
iibot-connect-agent/src/main/java/io/mosire/iibot/connect/agent
iibot-taskflow/src/main/java/io/mosire/iibot/taskflow/{domain,agent,control,event}
iibot-app/src/main/java/io/mosire/iibot/app/{config,world,event,delivery,dispatch,runtime,mcp}
```



- 包名与 groupId 待 P0 确认，建议 `io.mosire.iibot` 或 `com.cna.iibot`；不要与上游 `io.mosire.agentlib` 混淆。
- 公开 API 使用中文 Javadoc，解释“为什么”与失败语义。

## 11. 验收判据

### 11.1 功能

- [ ] 两个配置玩家可各自私聊，Bot 只回各自可见信息。
- [ ] 开放回合内 `submit` → 预览 → `/确认` → 冻结 → 推演 → 发布闭环可复现。
- [ ] 推演可新建/批准设定，世界版本递增，旧回合仍可追溯。
- [ ] 中央 Agent 的非法提案不会产生世界变更或 outbox。
- [ ] 外部 MCP 可观察事件游标，可在正确状态推进，错误状态/过期 revision 被拒绝。

### 11.2 失败与负向

- [ ] 未登记用户、自称推演、伪造 MCP 指令均无法提权。
- [ ] 过期决策、重复决策、冻结后决策均拒绝且不改变快照。
- [ ] 重复 QQ 消息 ID、重复 `/确认`、重复 MCP idempotencyKey 均只生效一次。
- [ ] LLM 超时/非法 JSON/未知实体/过期 worldVersion 均进入可重试失败态。
- [ ] crash point 在事件写后、世界投影前、发送前、发送后未知位置均有恢复语义。
- [ ] 事件 replay 产生同一投影哈希。

### 11.3 证据

- 单元/契约测试 counts、Surefire XML；
- FakeNapCat 消息帧捕获；
- FakeLlm 脚本化输出；
- 真实 NapCat / 真模型 E2E 分开记录；
- 事件库 dump 与投影 hash；
- MCP 客户端请求/响应记录；
- 长跑摘要与日志路径。

## 12. 当前未定项与实现边界

- Q5：真实 LLM provider/model/路由与 tool calling 能力；
- Q6：单世界/多世界；
- Q8：公共群是否只做公告，还是允许群内玩家交流/决策；
- Q9：外部 MCP 是否扩展到进程启停/角色配置/密钥管理等非游戏运维权（当前不提供，建议保持不提供）；
- 真实 NapCat/LLM/DSH 连接未验；
- 领域事件回放/重启恢复未实现（SQLite 事件库当前只做跨重启审计读取）；
- 上下文压缩/长期会话摘要未实现。

> 以上边界以 [`../../agents/STATUS.md`](../../agents/STATUS.md) 为准；未落地部分不得说成完成。
