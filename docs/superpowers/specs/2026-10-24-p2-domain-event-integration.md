# P2：领域事件接入与旧 JSON 迁移

> **状态**：有效方向（P2 实施设计；依据 [`2026-10-24-ideal-gm-control-workflow-and-events-db.md`](2026-10-24-ideal-gm-control-workflow-and-events-db.md) 与 [`../plans/2026-10-24-ideal-workflow-implementation-plan.md`](../plans/2026-10-24-ideal-workflow-implementation-plan.md)）
> **日期**：2026-10-24
> **用户原话**：见理想工作流设计文件头（“统一到 events.db，把内容记全”）。
> **范围**：只接当前已存在的 Post/Complaint/ActionPool/Turn 快照；DecisionPackage/Batch 推迟到 P5，Ticket/GM 文档在 P3/P4 落地后复用同一端口，不在本阶段虚构领域对象。

## 1. 目标

让 P1 的 `EventContentStore` 从“内容基础”变成现有领域写操作的 canonical 事件来源：

- Post、Complaint、ActionPool 使用 events.db 作为持久化 store；
- `taskflow-state.json` 降级为恢复缓存，完整状态另写 `TASKFLOW_SNAPSHOT` 事件；
- 旧 `posts.json`、`complaints.json` 只作为迁移输入，迁移成功前不删除；
- DecisionPackage/Batch 事件接入推迟到 P5；P2 不声称包/批次可持久化或重启恢复；
- 旧 `SqliteEventReadPort` 的 `taskflow.event` 兼容读取保持不变。

## 2. 事件端口与内容

### 2.1 DomainEvent

`iibot-taskflow/event` 新增：

```text
DomainEvent(
  occurredAt, eventType, actorRole, actorId, correlationId, turnId,
  visibility, Object content, Map<String,Object> summary
)
DomainEventJournal.append(DomainEvent) -> long seq
```

- `content` 序列化为 `event_contents.content_json`，保存完整领域快照/正文；
- `summary` 序列化为 `events.payload_json`，只放 ID、状态、revision、visibility、计数、idempotencyKey 等索引；
- `agent = role:id`，不存 QQ 凭据/系统提示词/模型 reasoning；
- 可见性在写侧固定；内容脱敏、大小上限、hash 由 P1 `EventContentStore` 负责。

### 2.2 事件类型

```text
POST_DRAFTED
POST_APPROVED
POST_REJECTED
POST_PUBLISHED
POST_DELIVERY_CHANGED
POST_LEGACY_MIGRATED

COMPLAINT_SUBMITTED
COMPLAINT_RESPONDED
COMPLAINT_DELIVERY_CHANGED
COMPLAINT_LEGACY_MIGRATED

ACTION_SUBMITTED
ACTION_WITHDRAWN
ACTION_SELECTED
ACTION_RELEASED
ACTION_COMMITTED

-- PACKAGE_* / BATCH_*：P5 预留，本阶段不写

TASKFLOW_SNAPSHOT
TASKFLOW_SNAPSHOT_MIGRATED

CONTROL_*
GM_DOC_*
SECRETARY_MESSAGE
GM_MESSAGE
```

`CONTROL_*`、`GM_DOC_*`、`SECRETARY_MESSAGE`、`GM_MESSAGE` 先保留常量；实际写入分别在 P3/P4/P6。

## 3. 事件源存储

### 3.1 Post / Complaint / ActionPool

三个 store 增加一个**向后兼容默认方法**：

```text
save(snapshot, DomainEvent event) -> default save(snapshot)
```

事件源实现只用 `DomainEventJournal.append(event)` 持久化，不另写 JSON：

- 事件 content = 完整的新 `PostSnapshot` / `ComplaintSnapshot` / `ActionPoolSnapshot`；
- `load()` 读取对应前缀的最新完整事件并反序列化；
- 事件追加成功后才替换服务内存状态；
- 追加失败 => 服务保持旧状态并返回既有 STORE_UNAVAILABLE/等价错误；
- 事件源 store 仍可单独从 events.db 重建，不依赖 JSON 文件。

这样 PostService/ComplaintService/ActionPoolService 的现有状态机、幂等回执和权限校验不变。

### 3.2 Taskflow 状态

- `TaskflowEngine` 每次成功状态提交后，除现有 `taskflow.event` 审计事件外，再写一条 `TASKFLOW_SNAPSHOT`；
- content = 完整 `TaskflowStateSnapshot`；
- summary = seq/eventCount/worldVersion/turnId/turnNo/state；
- 原有 `taskflow-state.json` 继续原子写入，作为恢复缓存；
- 恢复 seq 校验只看 `type='taskflow.event'` 的最大 seq，不能让快照事件污染兼容锚点；
- P7 才改为优先从 events.db 重建；P2 不宣称已移除快照缓存依赖。

### 3.3 DecisionPackage / Batch：推迟到 P5

AgentLib 0.2.3 只提供进程内 `DecisionPackages`，没有 live restore；而包状态、ActionPool
状态和批次 executor 是多处写，P2 无法给出“事件失败时零推进”的事务语义。因此 P2 **不接入**
`PACKAGE_*` / `BATCH_*` 业务事件，只保留事件类型常量作为 P5 预留：

- P5 在引入 DecisionPackage 持久化/恢复适配与批次 outbox 语义后，再写完整包/批次事件；
- 在 P5 前不得声称包/批次可从 events.db 重建或重启恢复；
- 当前 `ActionPackageService` 保持 P0 的内存实现。

这是对 P2 计划“包/批次全量接入”的收窄，不改变最终目标；原因是上游缺 restore/事务
边界，继续做会把“只读投影可重建”伪装成“live 状态已持久化”。

## 4. 迁移与对账

新增 app `LegacyEventMigrator`，在打开 events.db 后、构造 TaskflowEngine 和领域服务前运行：

1. `posts.json` / `complaints.json` / `taskflow-state.json` 分别先查 exact 完成标记：
   `LEGACY_POST_MIGRATION_RECONCILED` / `LEGACY_COMPLAINT_MIGRATION_RECONCILED` /
   `TASKFLOW_BASELINE_RECONCILED`；
2. 完成标记存在 ⇒ 迁移阶段跳过，不拿持续变化的旧文件再做永久 equals；
3. 无完成标记、旧 JSON 存在且对应普通事件前缀已存在 ⇒
   `[LEGACY_EVENT_MIGRATION_STATE_AMBIGUOUS]` fail-closed；
4. 无完成标记、旧 JSON 存在、无普通事件 ⇒ 追加 `*_LEGACY_MIGRATED` 完整快照事件，
   重读 exact 迁移事件并与旧文件 equals；通过后追加 exact reconciled 标记；
5. reconciled content 只含 `migrationEventType` / `migrationEventSeq` / `sourceFile` /
   `recordCount` / `contentHash`（目标 `StoredEvent.contentHash`）等索引元数据，不复制正文；
6. 迁移事件成功但 reconciled 标记失败/进程崩溃 ⇒ 下次启动因“无完成标记 + 普通事件存在”
   fail-closed，旧文件保留；
7. 旧文件不删除、不改名、不继续写；迁移失败或对账不一致不得进入 bootstrap。

`taskflow-state.json` 是持续更新的恢复缓存：fresh bootstrap 写完首份
`TASKFLOW_SNAPSHOT` 后追加 `TASKFLOW_BASELINE_RECONCILED`（sourceFile=fresh-bootstrap）；
recovery 时由迁移器建立基线，之后 `ensureRecorded` 只追加/追平普通快照事件，不再要求旧
JSON 永久等于迁移事件。

## 5. 验收

- 新建/审批/发布推文后，删除/移走 posts.json，仅 events.db 可重建相同 `PostSnapshot`；
- 投诉提交/回应同理；
- 行动提交/选择/撤回/提交后，仅 events.db 可重建相同 `ActionPoolSnapshot`；
- `TASKFLOW_SNAPSHOT` 可用于读回当前完整 Taskflow 状态（P7 才切换 live 恢复）；
- 旧 posts/complaints/taskflow-state 迁移对账 0 差异；
- 迁移完成标记存在时可重启，普通事件存在但无完成标记时持续 fail-closed；
- 事件 store 写失败时对应 Post/Complaint/ActionPool 状态不前进；
- 运行期 Taskflow 快照事件失败不向已提交命令伪造失败；启动期 `ensureRecorded` 修复 stale；
- 48 MCP 工具、现有回合/投递/重启测试不回归；
- 全量 gate 与阶段评审通过。

## 6. 负向用例

- 事件库损坏/最新内容 hash 不一致 ⇒ fail-closed，不回退旧 JSON 覆盖；
- 迁移后与旧快照不一致 ⇒ 启动失败，旧 JSON 保留；
- Post/Complaint/ActionPool 事件追加失败 ⇒ 内存状态不变；
- 无事件但旧 JSON 存在 ⇒ 必须迁移，不能静默当空；
- 有事件但旧 JSON 更新 ⇒ events.db 优先，旧 JSON 只读不覆盖；
- 非授权角色仍不能写 Post/Complaint/ActionPool 包；
- 公开/私密 visibility 在事件写入时固定，查询侧仍按既有权限过滤。

## 7. 停止条件

- 任一领域事件含正文但 summary 也含用户可控正文 ⇒ 停止，修 summary 形状；
- Post/Complaint/ActionPool 事件写失败仍推进领域状态 ⇒ 停止接入该领域；
- 迁移完成标记缺失/对账不一致 ⇒ 不删除旧 JSON，不宣称 P2 完成；
- package/batch 事件接入推迟到 P5 ⇒ P2 不得声称包/批次已持久化或可重启恢复。


## 8. 修订记录

| 日期 | 修订 | 原因 |
|---|---|---|
| 2026-10-24 | P2 收窄：DecisionPackage/Batch 业务事件推迟 P5；迁移增加 exact reconciled 完成标记；Taskflow 运行期快照事件 best-effort + 启动 ensure | 评审发现 AgentLib 无 restore/事务边界，以及迁移完成标记对持续更新 JSON 的语义冲突；避免用“只读投影可重建”冒充 live 恢复 |
