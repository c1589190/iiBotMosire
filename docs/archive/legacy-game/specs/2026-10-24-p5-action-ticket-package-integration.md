# P5：ACTION_DRAFT 工单 → ActionPool → DecisionPackages

> **状态**：有效（P5 实施设计；依据 ideal workflow §3.4/§5.4 与 implementation plan P5）
> **日期**：2026-10-24
> **用户原话**：「玩家需要和秘书协商，确定行动草稿，然后把行动草稿作为特殊工单发送给‘GM’……GM 维护待裁定选区……」。完整原话见 `2026-10-24-user-requirements-source.md`。
> **范围**：ACTION_DRAFT ticket 晋升到 ActionPool；GM 从行动建包；包/批次事件与恢复；默认秘书工具切到工单/行动池，旧 DecisionDraft 仅兼容。

## 1. 目标

- ACTION_DRAFT ticket 由 GM 显式晋升为 `PlayerAction`，ticket 记录 `relatedActionId`；
- 同一玩家可有多条行动，不再受旧每回合一条硬限制；
- GM 可用现有 ActionPackageService 建包、追加/替换/删除、逐条/整包裁决、commit；
- 包 commit 产生 ActionBatch，同一回合可多个批次，GM 自由决定下一回合；
- 未批准条目零执行；
- PackageCommand/BatchCompleted 事件与包/批次内存状态是 canonical；ActionPool SELECTED/COMMITTED 是可修复投影，投影写失败不反向否决包/批次成功；启动恢复强制 reconcile。
- 包/批次完整事件进入 events.db，并可从命令日志恢复 live package 状态；
- 旧 Turn/DecisionDraft 代码保留兼容，但完整 Ticket 装配下的秘书默认不再暴露旧 draft/直接 action 工具。

## 2. Ticket 晋升

- Ticket 新增 `relatedActionId`（nullable），仅 ACTION_DRAFT 可关联。
- `TicketService.attachAction(gm, ticketId, expectedRevision, actionId, idempotencyKey)`：
  * 只接受 GM 侧；ticket 必须 ACTION_DRAFT；
  * OPEN/CLAIMED → CLAIMED，记录 `claimedBy` 与 `relatedActionId`；
  * 已关联同一 action 时幂等；关联不同 action => CONFLICT；
  * 事件 `TICKET_ACTION_LINKED`，content=完整 TicketSnapshot，summary 不含正文。
- `ActionPoolService.submitFromTicket(playerId, ticketId, text, idempotencyKey)`：
  * 宿主/GM 晋升专用，不暴露给秘书模型；
  * source=`ticket:<ticketId>`，同一 ticket 重复晋升返回同一 PlayerAction；
  * 不再检查回合状态。
- app `TicketActionPromotionService` 编排 ActionPool 写入 + Ticket attach；若 attach 失败，返回稳定 partial/可重试错误，重试通过 source 找已有 action 后完成 attach。
- `tf_action_pool_ignore` 必须在 ActionPackageService 同一 canonical 监视器内完成 owner 检查与 ActionPool withdraw，禁止先查后撤的 TOCTOU。
- MCP `tf_ticket_promote_action(ticketId, expectedRevision, idempotencyKey)`：
  * supervisor；返回 actionId/status/ticket revision；
  * 不返回行动正文。

## 3. 包/批次事件与恢复

AgentLib `DecisionPackages` 无 restore API，因此采用**命令日志重放**：

- 每次成功包操作追加 `PACKAGE_COMMAND` 事件，content=`PackageCommand`：
  * commandType: CREATE/APPEND/REPLACE_ITEM/REMOVE_ITEM/DECIDE/DECIDE_ITEM/COMMIT；
  * packageId/actor/title/proposerKey/items/index/approvedIndexes/approve/note/deadlineEpochMs/idempotency；
  * COMMIT 额外存 CommitReceipt 与 ActionBatch。
- 另写 `BATCH_COMPLETED` 事件（COMMITTED/REJECTED/CONFLICTED 首次产生批次均写一次；重复 commit/恢复不重复），content=完整 ActionBatch；summary 不含行动正文。
- 启动时 `ActionPackageRecoveryService` 按 seq 读取 `PACKAGE_COMMAND`，在 fresh `DecisionPackages` 上重放：
  * CREATE 用事件中 items/proposerKey；未来 deadline 精确保留，已过期才用 now+1h 重放后 expireNow；
  * APPEND/REPLACE/REMOVE/DECIDE/DECIDE_ITEM 调对应 AgentLib API；
  * COMMIT 用回放提交口返回事件中 CommitReceipt，不再次执行 LLM/executor；
  * 恢复 titles/createIdempotency/batches；
  * 原 deadline 已过且仍无终局时调用 `expireNow`，避免复活过期包。
- 命令事件 STARTED append 失败 => 领域内存不推进；APPLIED/BATCH_COMPLETED 写失败发生在包内存变更之后，服务 fail-stop，明确不宣称跨 ActionPool/package/event 原子事务；真实进程崩溃恢复以事件为准。
- 重复 commit 直接返回已持久化 receipt，不新增命令事件、不重跑 executor；恢复批次按 batchId 去重。
- executor 异常/返回 null/非 COMMITTED 时，live 与恢复都必须保留同一 rejected/conflicted batch。
- 恢复命令事件分页读取，超过上限 fail-closed，不得静默截断。
- Batch/command 事件正文完整、summary 索引化；不存密钥/模型 reasoning。
- P5 测试覆盖 replay 重建包/批次、未批准零执行、多批次。

## 4. 默认秘书工具

完整 Ticket+ActionPool 装配下：

- 不再暴露 `secretary_action_submit/list/withdraw`（行动统一走 ACTION_DRAFT ticket）；
- 不再暴露旧 `DecisionDraftCreateTool/UpdateTool`；`RequestBrief` 等非冲突工具保留；
- 秘书 `secretary_ticket_submit` 支持 ACTION_DRAFT；
- 旧 DecisionDraft/Turn 路径在未注入 TicketService 的兼容装配中保留。

## 5. 验收

- ACTION_DRAFT ticket promote 后 ActionPool 有 QUEUED action 且 ticket relatedActionId 一致；
- 同一 ticket 重复 promote 不重复 action；同玩家多 ticket 可产生多个 action；
- GM package 可选多条 action，PROPOSED 编辑规则不变；
- 未批准条目零执行；包 commit 产生批次；
- 同一回合两个包 commit 得到两个 batch；重复 commit 不新增事件/不重复批次；
- ActionPool 投影失败后包/批次仍成功，pending 状态可被 reconcile 修复；
- events.db 命令日志重启可重建 packages/batches（含已 COMMIT 批次回执，不重跑 executor）；
- 旧 DecisionDraft/48/55/60 兼容装配不回归；
- full gate 与阶段评审通过。

## 6. 非目标 / 风险

- 不实现工单优先级/附件；
- 不做真实外部 LLM/QQ 联调；
- package 命令日志采用进程内 fail-stop + 启动重放；极端“执行后命令事件写失败”语义必须显式报告，不伪装成功；
- 旧 ComplaintService/GmAide 只读兼容路径继续存在。
