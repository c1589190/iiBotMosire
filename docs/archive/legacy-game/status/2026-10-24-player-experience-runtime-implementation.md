# 玩家体验运行时：实现与验证快照

> **状态**：历史快照（2026-10-24）。**当前状态入口**：[`../../agents/STATUS.md`](../../agents/STATUS.md)。
> **设计依据**：[`../specs/2026-10-24-player-experience-runtime-architecture.md`](../specs/2026-10-24-player-experience-runtime-architecture.md)
> **实施计划**：[`../plans/2026-10-24-player-experience-runtime-plan.md`](../plans/2026-10-24-player-experience-runtime-plan.md)

## 一、实现范围

### A. 玩家可见文本边界

- 新增 `PlayerFacingTextGuard`：
  - 清理 Markdown 标题、代码围栏、加粗、反引号、表格分隔；
  - 删除后台字段、UUID、绝对路径、状态枚举、系统标签；
  - 保留唯一机器交互 `/确认 <短确认码>`；
  - 提供 `remainingBackstageLeaks` 供发布前 fail-closed。
- `SecretarySession.recordAndRender` 对玩家私聊输出调用 guard；GM 调试输出不走玩家清理。
- `OutboxSender` 在最终外发边界对所有私聊与公共群文本再次清理。
- `PublicationOrchestrator` 存储发布草稿前清理公共/私密正文，并拒绝剩余后台字段。
- `LlmActionBatchExecutor` 清理结构化推演结果后再持久化。

### B. 秘书 system prompt、AGENTS.md 与信息推送

- `AgentPrompts.secretary` / `secretaryGm` 保持精确最小 system prompt：
  `You are a helpful software engineer assistant.`（D-016）。
- `SecretaryKnowledge` 扩展为 profile、briefs、public/player-visible facts、人类可读回合摘要，仅用于首轮 user-level 上下文注入，不进入 system prompt。
- `TaskflowEngine.knowledgeForSecretary` 填充扩展字段，并把回合状态转成人类可读描述。
- `SecretaryWorkspaceStore.DEFAULT_AGENTS_MD` 写入完整玩家体验规则：
  - 纯文本、无 Markdown、无 ID/路径/状态；
  - 信息直接转述，不让玩家读文件；
  - 报名最低三项、缺失项暂定、不重复确认；
  - 正式行动只一次 `/确认 <短确认码>`；
  - 工具失败/隐私/不伪造回执。
- `SecretarySession.firstTurnReminder` 要求先读取共享 Skill 和 `AGENTS.md`，再注入可见事实；不再把详细规则放进 system prompt。
- 现有 simulation 运行态 9 个 `secretaries/player-*/AGENTS.md` 已同步为同一规则模板。
- 共享 Skill `simulation/campaign/palatinia/07-secretary-skill.md` 已补玩家可见输出、信息获取、唯一确认和工具回执规则，并同步到运行态共享 Skill 文件。

### C. 短确认码

- 新增 `ConfirmationCodes`：
  - 8 位 Crockford Base32；
  - 决策草稿与 ACTION_DRAFT 工单类型隔离；
  - 稳定派生，大小写不敏感。
- `TaskflowEngine.findDraftByConfirmationCode` 按玩家+短码查找。
- `/确认` 兼容完整 draftId 与短码。
- 确认成功回执移除 draftId/decisionId/contentHash/turnId。
- 重复确认返回友好“已经确认过”，不产生第二条 Decision。

### D. ACTION_DRAFT 工单确认

- `TicketStatus` 新增 `CONFIRMED`。
- `TicketService.confirmActionDraft`：
  - 仅作者本人；
  - 仅 ACTION_DRAFT；
  - OPEN/CLAIMED → CONFIRMED；
  - revision CAS、幂等、事件 `TICKET_CONFIRMED`。
- `TicketActionPromotionService.promote` 只接受 `CONFIRMED`；
  未确认返回 `ACTION_DRAFT_NOT_CONFIRMED`。
- `TicketService.attachAction` 只接受 `CONFIRMED`。
- `SecretaryTicketTools.submit` 的 ACTION_DRAFT 回执给模型附带短确认码与一次确认指令。
- GM Ticket board 的 `openClaimedRecent` 包含 CONFIRMED。
- `tf_ticket_promote_action` schema 接受可选 `reason`，修复旧 schema 冲突。

### E. 结构化裁定与 NO_OP

- `ActionBatchStatus` 新增 `NO_OP`、`NEEDS_CLARIFICATION`。
- 新增 `AdjudicationOutput`：
  - 严格 JSON：`publicSummary/privateOutcomes/worldChanged/noOpReason`；
  - 拒绝未知字段、code fence、推理链、批次外 playerId；
  - `worldChanged=false` 必须给 noOpReason。
- `LlmActionBatchExecutor`：
  - 模型必须输出严格 JSON；
  - 合法且 worldChanged=true → COMMITTED，保存规范化 JSON；
  - no-op → NO_OP；
  - Markdown/CoT/空输出/越权 player → REJECTED；
  - 不再将模型 reasoning 原文写入 batch.result。
- `PublicationOrchestrator.generate` 拒绝可解析为 NO_OP 的批次。
- `PublicationOrchestrator.createOpening` 只在新创建的 opening 上要求零玩家 + 无活动回合；相同幂等键重放仍返回既有记录。

### F. 自动化边界

- `simulation/scripts/board-autopilot.py` 重写为只读 watchdog：
  - 默认只采样工单/待审帖，写 status/events；
  - 删除日推生成、campaign clock 推进、自动回复工单、自动确认行动；
  - `--publish-existing` 只能人工显式启动，且只处理已存在的待审帖；
  - `--once` 单次采样模式。
- 实测 `--once`：post 总数发送前/后均为 85，未创建新内容。
- `simulation/README.md`、`simulation/dsh/GM_SYSTEM_PROMPT.md`、工程 Skill 增加玩家体验 Gate。

### G. 推演文档系统

创建 `simulation/player-experience/` 10 份文档 + README：

- 体验总纲；
- 玩家可见文风；
- 秘书行为；
- 确认与恢复；
- 信息卡；
- 发布模板；
- 审稿清单；
- 自动化边界；
- 正反例；
- Playtest 验收。

## 二、验证结果

| 验证 | 命令 | 结果 |
|---|---|---|
| 全仓测试 | `./mvnw -o test` | **BUILD SUCCESS**；33 + 21 + 248 + 266 = **568 tests / 0 failures / 0 errors / 0 skipped** |
| 完整门禁 | `tools/gate.sh full`（`clean verify`） | **PASS**；33 + 21 + 248 + 266 = **568 tests / 0 / 0 / 0**；shaded jar 成功 |
| 打包 | `./mvnw -o -DskipTests package` | **BUILD SUCCESS**；`iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar` 已生成，22 MB |
| jar help | `java -jar ... --help` | rc=0 |
| 模拟区部署 | `simulation/scripts/00-build.sh`；`sha256sum -c` | 成功；`simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar` sha256=`ba7c617039a57370e89de32b547be6de8772e6af1e700a6406e78207c7099cc6`；app 已停止、8765 空闲 |
| watchdog py_compile | `python3 -m py_compile simulation/scripts/board-autopilot.py` | PASS |
| watchdog 只读采样 | `python3 simulation/scripts/board-autopilot.py --once` | rc=0；post 数 85→85，无新内容 |
| docs check | `python3 tools/check-docs.py` | PASS |
| skills lint | `python3 tools/skills-lint.py` | PASS |

证据日志：

- `.work` 外临时：`/tmp/pe-final-full-test-after-d016.log`、`/tmp/pe-final-package.log`（本机证据；不随仓库提交）
- `/tmp/pe-final-gate-full-d016.log`
- `/tmp/pe-final-jar-help.log`
- `/tmp/pe-mutant-{a,b,c,d,e,f-d016}.log`

## 三、变异/负向验证

最后统一执行，全部“改坏 → 测试当场红 → 还原 → md5 一致”：

| 变异 | 预期失败测试 | 结果 |
|---|---|---|
| `PlayerFacingTextGuard.normalize` 直接返回原文 | `PlayerFacingTextGuardTest` | 4 failures；md5 还原一致 |
| 移除 ACTION_DRAFT 未确认晋升门 | `TicketActionPromotionServiceTest.unconfirmedActionDraftCannotBePromoted` | 1 failure；md5 还原一致 |
| NO_OP 分支改为 COMMITTED | `LlmActionBatchExecutorTest.structuredNoOpBecomesNoOpAndNeverCommitted` | 1 failure；md5 还原一致 |
| 移除 opening 玩家/活动回合门 | `PublicationOpeningTest.openingIsRejectedOncePlayersOrActiveTurnExist` | 1 failure；md5 还原一致 |
| 禁用短确认码查找 | `ConfirmationFlowTest.decisionShortCodeConfirmsOnceAndReplyHasNoInternalIds` | 1 failure；md5 还原一致 |
| 给最小 system prompt 追加长篇规则（违反 D-016） | `SecretaryPromptTest` + `SecretarySessionWorkspaceTest` | 2 failures；md5 还原一致 |

## 四、明确未完成

1. **已部署但未启动**：`simulation/dist` 已替换为新 jar；app 已停止，等待用户重新初始化。真实 playtest 尚未开始。
2. **未真实 playtest**：未用真实 QQ 玩家执行 5 人验收。
3. **未实现 canonical RegistrationApplication/CharacterSheet**：仍沿用现有报名/角色流程，只是去掉了阻塞式交互；领域模型留作下一责任区。
4. **未实现直接 GM 私聊通知工具、秘书 status/reset、原生事件推送**。
5. **当前 live simulation runtime 仍是被首轮事故污染的状态**，不能作为可靠性验收环境。
6. **D-014 cache/history 仍未部署/未真实 NapCat 工具验证**。
7. 代码主提交已 push；随后状态/部署文档更新单独提交。

## 五、建议下一步

1. 停当前 app 前先保留当前 runtime 作为证据；
2. 用新 jar 在干净 runtime 做 5 人 playtest，按 `simulation/player-experience/09-playtest-and-acceptance.md` 验收；
3. 通过后再补 canonical 报名/角色与直接玩家通知；
4. 真实多玩家前再次跑 `tools/gate.sh full`。
