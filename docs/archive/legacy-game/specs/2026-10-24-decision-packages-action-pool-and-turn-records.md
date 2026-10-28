# v3：裁定包、自然语言行动池与回合记录

> **状态**：有效方向（实施中）
> **日期**：2026-10-24
> **用户原话（逐字）**：
> 「1 这个狭隘了，可以废了 2 看gm，玩家行动本身是自然语言文本 3 在包确认之前，可以 ——这意味着你甚至要翻出来agentlib维护的裁定包机制，以及，所有推文都应该写成标准文档在一回合内落盘，方便查看、修改等，可以额外放在一个外部的全数据记录文档集中」
> 「内部全数据集不需要搞md文件，可以做成简单的json方便格式化读取写入，其他没意见，直接做」

## 1. 目标

1. 取消“每回合每玩家只能一条行动”的硬限制。
2. 玩家/秘书可随时提交自然语言行动到待裁定行动池，不要求当前回合 OPEN。
3. GM 维护待裁定选区：选择行动、建包、追加/替换/删除，直到首次裁决前。
4. 使用 AgentLib `DecisionPackages` 的裁定包状态机，而不是继续扩展旧 `DecisionDraft` 单条模型。
5. 同一回合允许一个或多个推演批次；GM 决定批次组成与何时开下一回合。
6. 每回合结果、推文、行动、裁定包落成标准 Markdown 文档；内部全数据集落成单个结构化 JSON，便于格式化读写。

## 2. 行动池

### 2.1 模型

`PlayerAction`：

```text
actionId, playerId, text(自然语言正文), status,
packageId, createdAt, updatedAt, source, idempotencyKey
```

`ActionStatus`：

```text
QUEUED → SELECTED → COMMITTED
QUEUED → WITHDRAWN
SELECTED → QUEUED（包释放/取消）
```

- `text` 是自然语言，不强制拆成 actions 数组；
- 玩家/秘书提交时不能指定其他 playerId；
- 提交不依赖回合状态；
- 包确认前可追加/替换/删除；首次 itemDecided 后内容冻结（AgentLib 的 `PACKAGE_CONTENT_FROZEN`）。

### 2.2 秘书工具

- `secretary_action_submit`：text/idempotencyKey；无 playerId 参数；随时可提交。
- `secretary_action_list`：只看自己的行动摘要。
- `secretary_action_withdraw`：只能撤回未被选中的 QUEUED 行动。

### 2.3 GM/MCP 工具

- `tf_action_pool_list`：GM 查看全部 QUEUED/SELECTED 行动摘要；
- `tf_action_pool_get`：查看正文；
- `tf_action_pool_ignore`：GM 可选择忽略/撤回一条；
- GM 建包/追加/修改/裁决/commit 使用裁定包工具（见 §3）。

## 3. AgentLib 裁定包

### 3.1 复用组件

直接依赖 AgentLib：

```text
DecisionPackages
DecisionPackage
PackageItem
Decision
PackageState
DecisionPackageTools
DecisionTurnScope
DecisionSubmissionPort
DecisionCommitPort
```

- 包项 `toolName = "player_action"`；
- `args` 存 `{actionId, playerId, text, source, submittedAt}`；
- `argsDigest`/`summary` 由 AgentLib 生成，列表默认不返回正文；
- 包内容只能在 `PROPOSED` 状态修改；首次 `itemDecided` 后冻结；
- 整包裁决 `decide` 支持 `approvedIndexes`；未列出=驳回；
- 逐条裁决 `itemDecided` 不产生第二个包；
- `commit` 只执行一次已批子集。

### 3.2 GM/MCP 工具

- `tf_package_create`：title + selected actionIds + deadlineSeconds；建包（PROPOSED）。
- `tf_package_list`：列出待裁定/已裁决未执行包摘要。
- `tf_package_get`：默认摘要；`full=true` 才看正文并写读取审计。
- `tf_package_append`：追加 actionIds；仅 PROPOSED。
- `tf_package_replace_item` / `tf_package_remove_item`：仅在首次 itemDecided 前。
- `tf_package_decide`：整包批准子集/全驳。
- `tf_package_decide_item`：逐条 approve/reject。
- `tf_package_commit`：执行已批子集，进入一个 AdjudicationBatch。
- 工具全部 roles=supervisor，经 ToolCallAuthorizer；无 SYSTEM/`*`。

### 3.3 执行语义

- commit 时把批准的行动按包内 index 顺序交给宿主 `ActionBatchExecutor`；
- 一个包 = 一个推演批次；同一回合可提交多个包 → 多个批次；
- 批次结果由中央推演 Agent 生成，写入 `AdjudicationBatch`；
- GM 可决定何时发布批次、何时开下一回合；
- 旧 Turn/DecisionDraft 路径保留兼容，但新行动默认走行动池/裁定包；
- 不再由引擎硬拒绝“已冻结不能提交行动”；行动提交永远允许，是否裁决由 GM 决定。

## 4. 回合记录

### 4.1 标准文档

每回合目录：

```text
dataDir/records/turns/<turnNo>-<turnId>/
  index.md
  public-narrative.md
  players/<playerId>.md
  actions/<actionId>.md
  packages/<packageId>.md
  batches/<batchId>.md
  posts/<postId>-<visibility>.md
```

- 推文正文写成 Markdown；
- `index.md` 记录 turnId、worldVersion、时间、参与者、proposal/batch/package hash；
- 文档为可读、可编辑的标准 Markdown；写入走 workspace/docs；
- 回合结束（发布/批次发布）时生成；同一回合重放幂等。

### 4.2 内部全数据集 JSON

```text
dataDir/records/internal/all-data.json
```

- 结构化 JSON：turns、actions、packages、batches、posts、complaints、modelRoutes、hashes、statuses；
- 严格 schemaVersion、未知字段拒绝、原子保存、权限 600；
- 支持格式化读取/写入/恢复；
- 不存 API key/token/QQ 正文；
- Markdown 与 JSON 是同一事实的两面：JSON 是结构化主索引，Markdown 是标准可读文档；需要明确 canonical 字段。

## 5. 验收判据

- [ ] 秘书可在回合外提交自然语言行动；
- [ ] GM 可建包、追加/替换/删除，首次 itemDecided 后内容冻结；
- [ ] 同一玩家可在包内有多条行动；不出现“一回合一条”硬限制；
- [ ] GM 可整包/逐条裁决，未批准条目零执行；
- [ ] 一个包 commit 产生一个批次；同一回合可多批次；
- [ ] GM 可自由决定下一回合，不受行动池阻塞；
- [ ] 每回合生成标准 Markdown 推文/结果文档；
- [ ] 内部 all-data.json 可严格 round-trip，重启可恢复行动池/包/批次/推文元数据；
- [ ] 权限/幂等/UNKNOWN 语义保持；
- [ ] 全量测试、真实 MCP/LLM 回归、提交推送。
