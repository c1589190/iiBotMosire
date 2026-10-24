# 玩家体验运行时架构设计

> **状态**：有效（2026-10-24，用户要求「列新的开发架构设计文档、开发计划，自己一口气实现」）。
> **实现状态**：本轮代码已实现并通过 568 tests；未部署、未真实 playtest。证据：[`../status/2026-10-24-player-experience-runtime-implementation.md`](../status/2026-10-24-player-experience-runtime-implementation.md)。
> **本文取代**：无；细化 D-015，并保持 D-012 的一次硬确认。
> **事实底稿**：[`../reports/2026-10-24-first-live-run-player-experience-audit.md`](../reports/2026-10-24-first-live-run-player-experience-audit.md)
> **体验规则设计**：[`2026-10-24-player-experience-interaction-document-system.md`](2026-10-24-player-experience-interaction-document-system.md)
>
> **用户原话**：
> 「实际推演流程中也有大量真人推演不会犯的错误……反复确认（死磕条项目，打击玩家游玩积极性）、推文重点不明确、秘书Agent Markdown残留……必须用额外的推演文档系统来解决」
> 「把这些所有问题先都记录下来，然后你出解决方案，要求是让玩家的行动与信息获取变得轻松顺滑」
> 「列新的开发架构设计文档、开发计划，自己一口气实现」

## 一、目标

让玩家的两条主路径变短：

1. **行动路径**：自然语言意图 → 一份可读草案 → 一次短确认 → 回执；
2. **信息路径**：任何“我现在在哪/知道什么/能做什么”的问题 → 直接答复 + 2–3 个下一步选项。

玩家不需要知道 draftId、ticketId、postId、revision、MCP、workspace、文件名或版本号。

## 二、非目标

- 不取消 D-012 的玩家硬确认；
- 不绕过 PostService 的审核/发布状态机；
- 不引入 Web UI、外部网关或新 Maven 模块；
- 不在当前被首轮事故污染的 simulation runtime 上做验收；
- 不把文档风格规则全部硬编码为代码；代码只负责硬边界和结构。

## 三、运行时组件

```text
QQ 私聊入站
  -> InteractionTaskflowService
       -> PlayerCommandRouter
            -> /确认 <短确认码> -> ConfirmationResolver
            -> 普通文本 -> SecretarySession
       -> SecretarySession
            -> SecretaryPromptAssembler
                 - 玩家身份/显示名
                 - 玩家可见世界事实
                 - 当前 brief
                 - 玩家体验文档规则摘要
            -> AgentRunner + tools
            -> PlayerFacingTextGuard（输出净化/校验）
  -> OutboxSender
       -> PlayerFacingTextGuard（最后一道外发闸门）

GM/MCP 发布
  -> PublicationOrchestrator
       -> 结构化 ActionBatch.result（禁止 CoT）
       -> PlayerFacingTextGuard（正文净化）
       -> PostService 待审草稿
  -> GM tf_post_decide / tf_post_publish
  -> PostDistributor
       -> PlayerFacingTextGuard（第二次外发闸门）
```

### 3.1 `PlayerFacingTextGuard`

位置：`iibot-taskflow/src/main/java/io/mosire/iibot/taskflow/agent/PlayerFacingTextGuard.java`。

职责：

- 去除 Markdown 标题、代码围栏、加粗标记、反引号、表格分隔；
- 删除或拒绝后台字段：draftId/decisionId/ticketId/postId/revision/contentHash/turnId/MCP/schema/workspace/文件路径/系统状态枚举；
- 合并重复空行，限制极端长度；
- 保留唯一允许的机器交互：`/确认 <短确认码>`；
- 提供 `normalizeForChat`、`normalizeForPost`、`containsBackstageLeak`；
- 在秘书输出和 QQ 外发两个边界调用。

### 3.2 最小 system prompt + AGENTS.md / Skill 规则

D-016 裁定：

- `AgentPrompts.secretary` 与 `AgentPrompts.secretaryGm` 必须返回精确的最小 system prompt：
  `You are a helpful software engineer assistant.`；
- 不把玩家可见文风、确认规则、信息卡模板、隐私规则或模型行为约束注入 system prompt；
- 这些规则写入：
  - `SecretaryWorkspaceStore.DEFAULT_AGENTS_MD`；
  - 每个玩家的 `secretaries/<playerId>/AGENTS.md`；
  - 共享 Skill（例如 `simulation/campaign/palatinia/07-secretary-skill.md` 同步到共享 Skill 区）。
- `SecretarySession.firstTurnReminder`：
  - 要求秘书先读取共享 Skill 和当前工作区 `AGENTS.md`；
  - 可注入经过可见性过滤的 player profile、visible facts、briefs；
  - 不重复长篇规则，不要求玩家读文件；
  - 玩家只收到转述后的信息卡/结果卡。

输出仍由 `PlayerFacingTextGuard` 做最后硬边界，避免模型即使未遵守 AGENTS.md 也把后台字段/Markdown 发出去。

### 3.3 `ConfirmationResolver` 与短确认码

位置：

- `iibot-taskflow/src/main/java/io/mosire/iibot/taskflow/ConfirmationCodes.java`；
- `TaskflowEngine` 新增按短码查找当前玩家草稿；
- `TicketService` 支持 ACTION_DRAFT 工单确认；
- `InteractionTaskflowService` 统一解析。

规则：

- 短码由稳定实体 ID + 类型派生，固定 8 个 Crockford Base32 字符；
- 玩家只需发送 `/确认 ABC12345`；仍兼容旧完整 draftId；
- 短码只对本人、当前回合/当前工单有效；
- 确认后状态为 `CONFIRMED`，不可重复确认；
- 草案更新或工单修订后旧短码失效；
- ACTION_DRAFT 只有 `CONFIRMED` 才允许 `ticket_promote_action`。

### 3.4 结构化裁定

`LlmActionBatchExecutor` 的模型输出改为严格 JSON：

```json
{
  "publicSummary": "可公开的世界级结果",
  "privateOutcomes": {"player-1": "该玩家可见结果"},
  "worldChanged": true,
  "noOpReason": ""
}
```

- JSON 之外的文本、Markdown code fence、未知字段、批次外 player key 一律拒绝；
- `worldChanged=false` 或 `noOpReason` 非空 → `ActionBatchStatus.NO_OP`；
- 禁止把模型 reasoning 原文写入 `batch.result`；
- `PublicationOrchestrator` 只接受 `COMMITTED`，并拒绝可解析为 NO_OP 的批次；
- `ActionBatchStatus` 增加 `NO_OP` 与 `NEEDS_CLARIFICATION`。

### 3.5 开场/自动化门

`PublicationOrchestrator.createOpening()`：

- 仅在 `engine.playerCount()==0` 且无活动回合时允许；
- 有玩家/有回合时返回稳定失败 `OPENING_NOT_ALLOWED`。

`simulation/scripts/board-autopilot.py`：

- 删除 `publish_daily_tick()`、自动推进 campaign clock、自动审批发布；
- 改为只读监控：仅发现工单/待审帖并写状态，不创建/批准/发布任何内容；
- 保留显式 `--publish-existing` 模式，但只处理已经存在的待审帖，需人工启动。

### 3.6 报名/角色暂不新增独立领域模块

本轮不引入新的 canonical `RegistrationApplication`/`CharacterSheet` 持久化模块，避免在同一批改动中扩大事件快照 schema。先通过：

- 秘书 prompt 的“三项即可开始”；
- 暂定值规则；
- 世界事实直接注入玩家可见知识；
- 现有 PRIVATE post 审核流程；

完成体验闭环。canonical 报名/角色留作下一责任区，不在本轮伪装为已实现。

## 四、错误码与玩家文案

新增稳定领域错误码（玩家文案与日志分离）：

- `CONFIRMATION_CODE_INVALID`
- `CONFIRMATION_CODE_EXPIRED`
- `CONFIRMATION_AMBIGUOUS`
- `ACTION_DRAFT_NOT_CONFIRMED`
- `OPENING_NOT_ALLOWED`
- `BATCH_NO_OP`
- `PLAYER_TEXT_UNSAFE`

玩家只看到自然语言，例如：

> 这次确认码无效或已过期。让秘书重新发你一份行动，再确认一次即可。

不得看到枚举名、异常类、工具名或内部 ID。

## 五、验收判据

1. 秘书 system prompt 精确保持为 `You are a helpful software engineer assistant.`；详细规则在 `AGENTS.md` 与共享 Skill；
2. 玩家私聊输出中不再出现后台 ID、状态枚举、文件路径、Markdown 标题/代码块；
3. `/确认 <短码>` 可确认决策草稿和 ACTION_DRAFT 工单；
4. 未确认 ACTION_DRAFT 晋升必须失败；
5. 同一草案第二次确认返回幂等成功或明确“已确认”，不生成第二条行动；
6. `worldChanged=false` 的批次状态为 `NO_OP`，发布编排拒绝；
7. 有玩家/活动回合时 `createOpening` 失败；
8. 自动监控脚本在一次无变化轮询中不产生任何 post、不推进 clock；
9. 项目 `./mvnw test` 全绿；最后统一做变异/负向验证。

## 六、本轮代码改动边界

- `iibot-taskflow`：输出守卫、SecretaryKnowledge 扩展、确认码、Ticket 状态、ActionBatch 状态、确认解析；
- `iibot-app`：Secretary 首轮规则加载、InteractionTaskflowService、LlmActionBatchExecutor、PublicationOrchestrator、Outbox/PostDistributor 外发守卫、MCP schema；
- `simulation/scripts`：autopilot 降级为只读监控；
- 文档：体验文档包初稿与开发计划；
- 不改 `simulation/dist` 正在运行的 jar，不向真实 QQ 外发。
