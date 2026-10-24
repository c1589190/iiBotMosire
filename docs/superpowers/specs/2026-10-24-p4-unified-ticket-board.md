# P4：统一工单/消息板

> **状态**：有效（P4 实施设计；依据 [`2026-10-24-ideal-gm-control-workflow-and-events-db.md`](2026-10-24-ideal-gm-control-workflow-and-events-db.md) §5.3 与 [`../plans/2026-10-24-ideal-workflow-implementation-plan.md`](../plans/2026-10-24-ideal-workflow-implementation-plan.md) P4）
> **日期**：2026-10-24
> **用户原话**：「首先是加载控制方……秘书需要根据自己的理解回答，实在遇到需要确认的点，再通过统一的工单体系询问“GM”……玩家需要和秘书协商，确定行动草稿，然后把行动草稿作为特殊工单发送给‘GM’……遇到申述，也走工单」（摘自 `2026-10-24-user-requirements-source.md` 对应需求原话）。
> **范围**：工单核心、秘书 submit/list/read、GM board/list/get/claim/reply/batch_reply/close/note；旧 Complaint 兼容路径在完整 Ticket 装配中隐藏。行动工单升级到 ActionPool 留 P5。

## 1. 目标

- 用统一 Ticket 模型承载 `SETTING_QUERY`、`ACTION_DRAFT`、`COMPLAINT`、`GM_NOTE`；
- 秘书可随时提交/查询自己所属玩家的工单，不依赖回合状态；
- GM 可在消息板累积、claim、单条/批量回复、关闭；
- A 玩家的工单对 B 秘书不可见；
- 工单正文/回复完整写入 events.db，JSON 旧投诉仅保留兼容；
- 完整 Ticket 装配下，旧秘书/GM complaint 工具不再作为主路径暴露；
- ACTION_DRAFT 仅存储/展示，转 ActionPool 在 P5。

## 2. Ticket 模型

```text
Ticket(
  ticketId, type, playerId?, authorActorId, title, body,
  status, revision, createdAt, updatedAt,
  claimedBy?, replies: List<TicketReply>, closedBy?, closeReason?
)
TicketReply(replyId, byActorId, body, at)
```

- `TicketType`：SETTING_QUERY / ACTION_DRAFT / COMPLAINT / GM_NOTE；
- `TicketStatus`：OPEN → CLAIMED → RESOLVED；OPEN/CLAIMED/RESOLVED → CLOSED；
- 秘书提交只接受 PLAYER actor，playerId 永远取 actorId；GM_NOTE 只能 GM 创建；
- reply 将 OPEN/CLAIMED 置为 RESOLVED；close 可从 OPEN/CLAIMED/RESOLVED 进入 CLOSED；
- 每次成功写操作 revision+1；幂等键重复返回首次成功回执；失败不落快照；
- TicketSnapshot 保存列表与幂等回执；TicketStore.save(snapshot,event) 与 P2 事件源模式一致。

## 3. 事件与 stores

- 事件类型：`TICKET_CREATED`、`TICKET_CLAIMED`、`TICKET_REPLIED`、`TICKET_BATCH_REPLIED`、`TICKET_CLOSED`、`TICKET_SNAPSHOT`；
- content = 完整 TicketSnapshot；summary 只放 ticketId/type/status/playerId/revision/count/idempotencyKeyHash；batch 额外放 ticketIds（仍不含正文）；
- visibility 固定 `GM`（整份快照跨玩家，不能进入秘书事件查询面；秘书查询走领域服务过滤）；
- App `EventSourcedTicketStore` 从 latest `TICKET_` content 重建快照；事件写成功后才替换服务内存；
- 正文/回复不进入 payload_json；不存 token/密钥/模型 reasoning。

## 4. 工具面

秘书 Agent（与 Complaint 旧工具二选一；TicketService 注入时启用）：

```text
secretary_ticket_submit
secretary_ticket_list
secretary_ticket_read
```

GM MCP（完整 Ticket 装配）：

```text
tf_ticket_board
tf_ticket_list
tf_ticket_get
tf_ticket_claim
tf_ticket_reply
tf_ticket_batch_reply
tf_ticket_close
tf_ticket_note_create
```

- board/list 默认只返回摘要与计数；get 返回正文/回复；
- claim/reply/batch_reply/close 需要 expectedRevision；batchReply 在一份新快照里原子应用所有回复；
- GM 工具都 roles=supervisor、strict schema、ToolCallAuthorizer；
- 完整 Ticket 装配隐藏 `tf_complaint_*` 与秘书 `secretary_complaint_*`，避免主路径双状态；旧 ComplaintService 仅保留兼容/旧数据，不再作为新工单来源。

## 5. 权限与可见性

- 秘书只能 list/read 自己的 playerId；不接受 playerId 参数；
- GM 工具不暴露玩家 QQ；
- Ticket 事件 visibility=GM，秘书侧只能通过领域查询获得自己的工单；
- GM_NOTE playerId=null，仅 GM 可见/处理；
- 不存在玩家/越权/不存在统一返回稳定错误。

## 6. 验收

- 任意回合状态/无回合秘书可提交 SETTING_QUERY、ACTION_DRAFT、COMPLAINT；
- B 秘书无法读 A 的工单；
- GM board/list/get/claim/reply/batch_reply/close/note 可用；
- 重复 claim/reply/close/batch 幂等；batch 中任一 revision 冲突 => 整批零变更；
- 工单正文/回复仅从 events.db 可重建；
- 完整 MCP 工具面从 55 变为 60（-3 complaint +8 ticket）；旧 complaint 工具在完整装配隐藏；
- 48/55 旧兼容部分装配不回归；
- full gate 与阶段评审通过。

## 7. 非目标 / P5

- ACTION_DRAFT 转 ActionPool/DecisionPackage 不在 P4（P5）；
- 不做工单优先级/附件/外部通知；
- 旧 ComplaintService 的 GmAide 只读工具与 ComplaintDistributor 继续兼容旧投诉，不用于新 COMPLAINT ticket；秘书通过 ticket read 转告玩家；完整外部 MCP 不暴露旧 complaint 写面；
- 不做真实 DSH/QQ/LLM 联调。
