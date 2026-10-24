# 理想 GM 控制工作流与 events.db 单一事实源设计

> **状态**：有效方向（设计/计划阶段；v3 实现仍在工作树）
> **日期**：2026-10-24
> **本文取代**：无；与 v3 行动池/裁定包设计兼容并取代其“内部 all-data.json”持久化方案
> **用户原话（逐字）**：
> 「首先是加载控制方，控制方Agent可以搭载在DSH、Hermes等外部Agent上，负责与真人GM对接、梳理世界观剧情，在【一个指定的存放GM专用剧情文档】的地方存剧情文档、梳理设定
> 然后控制方接入iiBot的MCP，初始化推演群（在哪里发公开推文）、各个玩家（秘书Agent和谁对接）的qq信息
> 以上为初始化流程，初始化完之后，（我觉得可以把回合制算成可选）理论上秘书Agent已经拿到了玩家所需要的资料，但是玩家必然会对秘书就设定发出质询，秘书需要根据自己的理解回答，实在遇到需要确认的点，再通过统一的工单体系询问“GM”
> 控制方Agent需要加入一个消息板，首先需要加载前者提供的工单，处理完一个之后，消息板上也许累积了多个工单，允许控制方Agent统一回复，遇到不确定的可以询问用户；
> 玩家需要和秘书协商，确定行动草稿，然后把行动草稿作为特殊工单发送给“GM”，也就是MCP上那个消息板，方便控制Agent处理
> 然后设定确认完，各个玩家都发送行动了，或者只有部分——没关系，开始出推文，推文推送到公开推演群和各个玩家（看需求，最好公开推文和私密推文的内容不重合、私密推文有发送必要，不过这就是推文协作skill的内容了），允许秘书Agent自由查看能查看的推文范围并作解读；
> 然后重复上述流程，遇到申述，也走工单
> 推演辅助Agent？我觉得暂时不用管它」
> 「我认为应该统一到events.db，把内容记全，剩下的事只有筛选提取了」

## 1. 目标与非目标

### 目标

1. 外部控制方 Agent（DSH/Hermes）能通过 MCP 完成控制面初始化：公开推演群、玩家 QQ、秘书绑定、世界/剧情资料装载。
2. GM 拥有独立私有剧情文档区；秘书与玩家不可见。
3. 秘书与玩家自由对话；秘书遇到需确认事项时提交统一工单。
4. 玩家行动草稿作为特殊工单进入 GM 消息板。
5. GM 消息板支持跨回合累积、单条/批量查看与回复；不确定项由控制方 Agent 询问真人 GM。
6. 行动工单进入待裁定选区，可由 AgentLib `DecisionPackages` 组包、增删、逐条/整包裁决、commit。
7. 不再依赖回合制硬限制；一个回合可有多个推演批次，GM 决定何时开下一回合。
8. 公共/私密推文由 GM 最终发布；秘书只能读取被允许的已发布推文。
9. `events.db` 成为唯一 canonical 领域事实库；完整领域内容入库，剩余工作是从事件流筛选/提取/投影。
10. 标准 Markdown 回合记录是 events.db 的投影，不是第二事实源。
11. 投诉、设定质询、行动草稿统一走工单体系，允许分开类型和权限。

### 非目标

- 不把 DSH/Hermes 自身的私有记忆、模型内部 reasoning 或 API key 写入 iiBot events.db。
- 不实现多世界/多租户；当前仍是单世界。
- 不把推演辅助 Agent 接入主链路；其保留为可选只读工具。
- 不在本设计阶段实现运行时模型热切换。
- 不将旧 `posts.json`、`complaints.json`、`taskflow-state.json` 长期保留为第二事实源。

## 2. 现状事实

### 2.1 模块与已有积木

| 模块 | 现状 |
|---|---|
| `iibot-connect` | NapCat/OneBot 11 事件 WS + HTTP Action、ACK/outbox 基础 |
| `iibot-connect-agent` | AgentLib `AgentTool` 权限扩展 |
| `iibot-taskflow` | 秘书、brief、旧 DecisionDraft、设定、回合、裁定包/行动池（v3 工作树） |
| `iibot-app` | 配置、SQLite 审计、Workspace/Docs、MCP、outbox、分发器（v3 工作树 48 工具） |

### 2.2 关键现状缺口

| 缺口 | 代码/事实 |
|---|---|
| 控制方初始化是静态配置 | `publicGroupId`、players、directors 在 `AppBootstrap` 装配；秘书会话按启动配置一次性创建 |
| 没有 GM 私有文档区 | 现有 world docs、shared-skills、每玩家 workspace 都不是 GM-only |
| 没有统一工单/消息板 | 现有 complaints / action pool / brief 分散 |
| 旧回合只允许每玩家一条行动 | `TaskflowEngine.java:787` 返回 `DECISION_ALREADY_CONFIRMED` |
| 旧回合一次只有一个裁定 | `TaskflowEngine.java:575/927/1001` 的 open→lock→adjudicate→publish 状态机 |
| events.db 只存审计摘要 | `AgentLibEventSink.java:26-90` 将 payload 作为脱敏 JSON 存储；`events` 表没有完整正文列；TURN_PUBLISHED 事件只有 hash |
| 内容分散在多处 | `taskflow-state.json`、`posts.json`、`complaints.json`、workspace/docs 文件；v3 action pool/packages/batches 仍为进程内 |
| 没有标准 Markdown 回合投影 | 尚无 `records/turns/...` |
| 公开/私密推文无重合校验 | `PostService` 只做 visibility/状态；内容去重/重合策略需 Skill + GM 审核 |
| 远程 Hermes 不可直连 | MCP 只绑回环；远程需 SSH 隧道/认证代理，当前没有 TLS |

### 2.3 v3 已实现但未提交的部分

工作树已有：

- `ActionPoolService`：自然语言行动随时提交、撤回、SELECTED/COMMITTED；
- `ActionPackageService`：直接使用 AgentLib `DecisionPackages`，支持包编辑/逐条裁决/commit；
- `LlmActionBatchExecutor`：一个包 commit 产生一个推演批次；
- MCP 48 工具（v3-A2 测试通过，未 full gate、未推送）。

## 3. 目标运行序列

### 3.1 初始化

```text
外部控制方 Agent（DSH/Hermes）通过 Bearer MCP 连接
→ tf_control_initialize
→ tf_control_set_public_group
→ tf_control_register_player（QQ → playerId → 创建 SecretarySession → 绑定 workspace）
→ tf_control_register_director（可选）
→ tf_control_load_world / tf_control_load_gm_docs
→ 返回初始化摘要 + revision + 工单板/公开群状态
```

初始化必须幂等；同一 QQ 不能重复绑定；未知/非法 QQ/群/路径 fail-closed。

### 3.2 秘书问答与设定工单

```text
玩家私聊秘书
→ ACK
→ 秘书从可见资料/共享 Skill/GM 下发资料回答
→ 遇到需确认问题
→ secretary_ticket_submit(type=SETTING_QUERY)
→ GM 消息板
```

### 3.3 GM 消息板

```text
控制方 Agent → tf_ticket_board
→ 读取 OPEN/CLAIMED 工单
→ 单条/批量 claim
→ 回复/合并回复/要求补充
→ 写入 reply + RESOLVED
→ 不确定时在控制方 Agent 与真人 GM 的对话中询问
```

GM 不必立刻处理所有工单；消息板允许累积、批处理、按优先级筛选。

### 3.4 行动工单与裁定包

```text
玩家与秘书协商
→ secretary_action_submit / ACTION_DRAFT 工单
→ GM 消息板看到行动工单
→ GM 选择若干行动工单
→ tf_package_create
→ PROPOSED：可 append/replace/remove
→ 首次 decide_item 后内容冻结
→ tf_package_decide / tf_package_decide_item
→ tf_package_commit
→ ActionBatch（一个批次）
```

同一回合/跨回合可创建多个包；GM 自由决定包组成、批次数量与下一回合。

### 3.5 推文与回合投影

```text
ActionBatch + 世界事实
→ 中央推演 LLM
→ PublicPostDraft / PrivatePostDraft
→ GM 审核
→ PostService 发布（公共群 / 玩家私聊）
→ events.db 写入完整 POST_* 事件
→ 投影生成 records/turns/<turnNo>-<turnId>/*.md
→ 秘书读取可见已发布推文
```

公开与私密推文的内容分工/重合检查是发布 Skill + GM 审核的职责，宿主只做可见性和强制审核。

### 3.6 循环

```text
玩家继续对话/提交设定质询/行动工单/投诉
→ GM 消息板持续累积
→ GM 自由决定下一批裁定/推文/下一回合
```

## 4. events.db 单一事实源

### 4.1 原则

- `events.db` 是唯一 canonical 领域事实库；
- `taskflow-state.json` 是可重建/可校验的恢复缓存，不是事实源；
- `posts.json`、`complaints.json`、action-pool JSON 迁移后删除或降级为缓存；
- Markdown 记录是投影，可由 events 重建；
- 所有领域写操作必须先/同时落完整事件，再对外产生副作用（ACK/outbox/API 回执按既有三态语义）。

### 4.2 推荐 schema

```sql
events(
  seq            INTEGER PRIMARY KEY AUTOINCREMENT,
  ts             TEXT NOT NULL,
  type           TEXT NOT NULL,
  agent          TEXT NOT NULL,
  correlation_id TEXT NOT NULL,
  turn_id        TEXT,
  event_version  INTEGER NOT NULL,
  visibility     TEXT NOT NULL,
  content_hash   TEXT NOT NULL,
  payload_json   TEXT NOT NULL
);

event_contents(
  seq            INTEGER PRIMARY KEY REFERENCES events(seq),
  content_type   TEXT NOT NULL,     -- JSON / MARKDOWN / TEXT
  content_json   TEXT,
  content_text   TEXT
);
```

- 大正文进 `event_contents`，`events.payload_json` 保留索引/元数据；
- `content_hash` 计算正文/结构化内容；
- 敏感数据不入 DB；可见性 `PUBLIC/GM/PLAYER/SECRETARY` 由事件写入时固定；
- 迁移旧事件：`event_version=0, content_missing=true`，只读保留。

### 4.3 完整事件类型（至少）

```text
CONTROL_INITIALIZED
PUBLIC_GROUP_SET
PLAYER_BOUND
DIRECTOR_BOUND
GM_DOC_CREATED/UPDATED
TICKET_CREATED/CLAIMED/REPLIED/RESOLVED/CLOSED
ACTION_SUBMITTED/WITHDRAWN/SELECTED/COMMITTED
PACKAGE_PROPOSED/APPENDED/ITEM_REPLACED/ITEM_REMOVED/
  ITEM_DECIDED/DECIDED/COMMITTED
BATCH_CREATED/COMPLETED
TURN_OPENED/LOCKED/ADJUDICATED/PUBLISHED
POST_DRAFTED/APPROVED/REJECTED/PUBLISHED
COMPLAINT_SUBMITTED/RESPONDED
SECRETARY_MESSAGE/ GM_MESSAGE
```

行动/推文/工单/包/批次必须写完整正文事件；不能只写 hash。

### 4.4 投影

```text
events.db
→ query by turn_id/type/visibility
→ records/turns/<turnNo>-<turnId>/*.md
→ 内部查询 API / MCP 只读工具
```

投影失败不回滚已提交事件；但必须可重试且幂等。

## 5. 新接口 / 工具面

### 5.1 控制初始化 MCP

```text
tf_control_initialize
tf_control_set_public_group
tf_control_register_player
tf_control_register_director
tf_control_bind_secretary
tf_control_load_world
tf_control_load_gm_doc
```

### 5.2 GM 私有文档

```text
dataDir/gm-private/{world,plot,settings,notes}/
```

- 仅 GM/DIRECTOR/SUPERVISOR 可读写；
- MCP：`tf_gm_doc_list/get/write`；
- 秘书/玩家上下文永远不出现。

### 5.3 工单

类型：

```text
SETTING_QUERY
ACTION_DRAFT
COMPLAINT
GM_NOTE
```

秘书工具：

```text
secretary_ticket_submit
secretary_ticket_list
secretary_ticket_read
```

GM/MCP：

```text
tf_ticket_board
tf_ticket_list
tf_ticket_get
tf_ticket_claim
tf_ticket_reply
tf_ticket_batch_reply
tf_ticket_close
```

### 5.4 行动池/裁定包/批次

保留 v3 工具面，并补：

```text
tf_action_pool_ignore（正式 GM API）
tf_package_*（9 个）
tf_batch_list/get
```

### 5.5 记录查询

```text
tf_record_turn_list
tf_record_turn_get
tf_record_query
tf_record_rebuild
```

## 6. 权限与不变量

1. 控制方初始化只能由 SUPERVISOR/GM 执行；玩家不能改公开群/绑定身份。
2. 秘书不能读 GM 私有文档或其他玩家私密数据。
3. 工单只允许本人/对应秘书/授权 GM 可见。
4. 行动提交不依赖回合；是否裁决由 GM 决定。
5. 包编辑只能在 `PROPOSED`；首次 `itemDecided` 后冻结。
6. 未批准条目零执行；commit 仅一次。
7. 只有 GM 侧可发布推文/公开叙事；秘书只提交草稿/工单。
8. 公开/私密推文正文先入 events.db，再投影 Markdown，再由 GM 最终发布到 QQ。
9. events.db 不存 token/API key/模型 reasoning；正文按 visibility 隔离。
10. 任何外部发送 UNKNOWN 不自动重发；events 记录投递状态。
11. 旧 JSON 与新 DB 迁移前，禁止双重写不同事实；迁移期双写只能短暂且必须对账。

## 7. 对现有代码的改动规模

| 模块 | 改动 |
|---|---|
| `iibot-connect` | 基本不动；新增投递相关性/内容 hash 可能需要小改 |
| `iibot-connect-agent` | 不动或仅补审计关联 |
| `iibot-taskflow` | 新增控制初始化、GM 文档、工单、事件内容端口；改 ActionPool/DecisionPackage 事件写入；旧 Turn/Decision 兼容 |
| `iibot-app` | 大改：events.db schema/content store、JSON 迁移、控制初始化装配、GM 文档、工单 MCP、记录投影、afterMutation 编排 |

预计：**8–12 个新领域服务/工具组，20–30 个新/改文件，新增 20+ MCP 工具，新增/迁移测试 50–100 个。**

## 8. 验收判据

### 基础

- [ ] 外部 MCP 初始化公开群/玩家/秘书/世界资料，幂等且 fail-closed；
- [ ] GM 私有文档不可被秘书/玩家读取；
- [ ] 秘书可随时提交设定质询/行动/投诉工单；
- [ ] GM 消息板可累积、查看、批量回复，不要求立即处理；
- [ ] 行动工单可进入 ActionPool，GM 组包后可在 PROPOSED 修改；
- [ ] 首次 itemDecided 后编辑失败，未批准行动零执行；
- [ ] 一个包 commit 产生一个批次，同一回合可多批次；
- [ ] GM 可自由开下一回合，不受行动池阻塞；
- [ ] 所有推文/叙事/工单/包/批次完整内容可从 events.db 查询；
- [ ] `records/turns/**/*.md` 是可重建投影；
- [ ] 重启后可从 events.db 重建/校验当前领域状态；
- [ ] 公开/私密推文按 visibility 过滤，秘书看不到未发布/他人私密；
- [ ] 真实 MCP/LLM 联调与全量测试通过。

### 负向

- [ ] 未授权 QQ 不能绑定/初始化群；
- [ ] 秘书伪造 playerId 提交工单/行动被拒；
- [ ] 非 GM 不能裁决/commit/发布；
- [ ] 包冻结后 append/replace/remove 返回稳定错误；
- [ ] events.db 写入失败时领域状态不得前进；
- [ ] 内容 hash 不一致、schema 不兼容 fail-closed；
- [ ] 投影失败不回滚事件，但重试幂等；
- [ ] 旧 JSON 迁移不一致时停止写入并报告，不静默双写。

## 9. 未定项 / 待确认

- 聊天正文是否全部入库：默认 **玩家↔秘书真实聊天正文入库**，不存 system prompt/模型 reasoning；
- 工单批量回复的交互细节（单条 reply 还是 batch transaction）；
- 公开/私密推文内容重合检测是硬校验还是 Skill 检查；
- 旧 JSON 数据迁移的保留窗口；
- 远程 Hermes 的 TLS/SSH 隧道方案。

## 10. 停止条件

- events.db schema/content 写入未通过时，不继续做工单/发布编排；
- 迁移无法对账时保留旧快照只读，不删除旧 JSON；
- 包状态无法从 DB 重建前，不宣称“重启恢复通过”；
- 真实外部发送未获用户明确协同授权时，保持 `WAITING_USER/NOT_RUN`。
