# 玩家体验运行时实施计划

> **状态**：本轮实现完成（2026-10-24）；测试、打包与变异验证见 [`../status/2026-10-24-player-experience-runtime-implementation.md`](../status/2026-10-24-player-experience-runtime-implementation.md)。未部署、未真实 playtest。
> **依据**：[`../specs/2026-10-24-player-experience-runtime-architecture.md`](../specs/2026-10-24-player-experience-runtime-architecture.md)
> **纪律**：一个责任区一次实现；测试最后统一跑；变异/负向验证最后统一做。

## 一、工作分解

### 责任区 A：玩家可见文本边界

1. 新增 `PlayerFacingTextGuard`，实现 Markdown 清理、后台词检测、路径/状态过滤。
2. 秘书 `recordAndRender` 接入输出清理。
3. `OutboxSender` 私聊回复接入最后一道外发清理。
4. `PostDistributor` 公共/私密推文正文接入外发清理。
5. 单元测试覆盖 Markdown、ID、路径、正常世界文本、确认码行不被破坏。

### 责任区 B：秘书 system prompt、AGENTS.md 与信息推送

1. **D-016**：`AgentPrompts.secretary` / `secretaryGm` 保持精确最小 system prompt。
2. 扩展 `SecretaryKnowledge`：玩家 profile、玩家可见 facts、回合摘要；仅用于首轮 user-level 上下文注入。
3. 将全部玩家体验规则写入 `SecretaryWorkspaceStore.DEFAULT_AGENTS_MD` 与共享 Skill。
4. 首轮提醒秘书读取共享 Skill + 工作区 `AGENTS.md`，并注入已过滤事实；禁止要求玩家读文件。
5. 同步现有运行时 9 个秘书 `AGENTS.md`。
6. 测试最小 system prompt、AGENTS.md 规则存在、首轮注入可见事实与文件读取指令。

### 责任区 C：短确认码

1. 新增 `ConfirmationCodes`（稳定、短、可读）。
2. `TaskflowEngine` 增加按玩家+短码查找草稿。
3. `/确认` 接受短码；友好回执不含 draftId/decisionId/contentHash/turnId。
4. 保留完整 draftId 兼容。
5. 测试：短码成功、旧回执无内部字段、第二次确认幂等/明确已确认、他人短码不可用。

### 责任区 D：ACTION_DRAFT 工单确认

1. `TicketStatus` 增加 `CONFIRMED`。
2. `TicketService.confirmActionDraft`：仅本人、ACTION_DRAFT、OPEN/CLAIMED，revision CAS、幂等、事件。
3. `TicketActionPromotionService.promote` 只接受 CONFIRMED。
4. `SecretaryTicketTools.submit` 回执给模型附短确认码（内部结果）。
5. `InteractionTaskflowService` 统一短码解析：先 DecisionDraft，再 ACTION_DRAFT ticket。
6. 更新既有 action promotion / MCP 测试，新增负向测试“未确认不得晋升”。

### 责任区 E：结构化裁定与发布门

1. `ActionBatchStatus` 增加 `NO_OP`、`NEEDS_CLARIFICATION`。
2. 新增严格 `AdjudicationOutput` JSON 解析/校验。
3. `LlmActionBatchExecutor`：JSON-only 输出；拒绝 CoT/空输出/Markdown；NO_OP 状态。
4. `PublicationOrchestrator`：拒绝 NO_OP/可解析 no-op；`createOpening` 只允许零玩家且无活动回合。
5. 测试：合法 JSON -> COMMITTED；no-op -> NO_OP；markdown/CoT -> REJECTED；publication 拒绝 no-op；opening 门禁。

### 责任区 F：自动化脚本降级

1. `board-autopilot.py` 删除日推、自动批准、自动回复、clock 推进。
2. 默认只读监控，输出 `pendingTickets/pendingPosts` 快照。
3. 显式 `--publish-existing` 才允许对已存在待审帖执行批准发布；不生成新内容。
4. 更新脚本注释和 README 警告。
5. shell/python 语法检查。

### 责任区 G：体验文档包初稿

1. 在 `simulation/player-experience/` 建立体验总纲、玩家文案、秘书行为、确认恢复、信息卡、发布模板、审稿清单、自动化边界、正反例、验收 10 份文档。
2. 秘书/GM 提示词引用它们的逻辑路径。
3. 本轮不要求运行时热加载文档；先形成可审稿内容资产。

## 二、验证顺序

1. 编译：`./mvnw -q -o -DskipTests compile`
2. 责任区 focused tests；
3. 全量测试：`./mvnw -o test`（不 package，避免覆盖正在运行 jar）；
4. 脚本：`python3 -m py_compile simulation/scripts/board-autopilot.py`
5. 文档：`python3 tools/check-docs.py`（既有 Skill 未登记失败单列）
6. 最后统一做变异/负向验证，并恢复源文件、比 md5。

## 三、禁止

- 不自动向真实 QQ 发送任何消息；
- 不启动/停止当前 app；
- 不覆盖 `simulation/dist`；
- 不 commit/push；
- 不把未跑项写成通过。


## 四、执行结果（2026-10-24）

- A–G 责任区的代码/文档改动已完成；
- 全仓测试 568 / 0 / 0 / 0，打包成功；
- 5 个关键护栏完成“改坏 → 红 → 还原 → md5 一致”；
- 只读 watchdog `--once` 实测 post 数不变；
- 未部署、未真实 playtest、未 commit。
