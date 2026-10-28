# 设计裁定索引（项目设计）

> **本文属于项目设计，不属于项目维护。**
> 只做代码/架构/数据/接口/工具面/提示词/可见性/公式等设计裁定的索引，不复制正文。
> 维护裁定在 [`../agents/MAINTENANCE_RULINGS.md`](../agents/MAINTENANCE_RULINGS.md)；
> 当前状态只在 [`../agents/STATUS.md`](../agents/STATUS.md)。

## 一、最高权威：用户需求原话

- **正本**：[`specs/2026-10-24-user-requirements-source.md`](specs/2026-10-24-user-requirements-source.md)
- **规则**：用户给定设计 = 最终版本；不得以“实现方便/更优设计”静默改口径。新裁定必须由用户明示，再在原稿追加 D-xxx。
- **查法**：先按 D 编号；同主题看专题 spec 的文件头与取代关系；机制性描述回代码/实际运行核。

## 二、D-xxx 索引

| 编号 | 主题 | 当前状态 | 正本 |
|---|---|---|---|
| `D-001` | QQ 接入与可替换 Adapter；首期 NapCat | 有效 | 需求原稿 |
| `D-002` | QQ 用户角色 = 玩家 / 推演，角色由服务端配置决定 | legacy 文游；bot core 改为通用 QQ 用户 + 备注/标签 | 需求原稿 |
| `D-003` | 固定提示词 + 玩家/推演设定载入 | legacy 文游；由外部主 Agent 的文游 Skill 维护 | 需求原稿 |
| `D-004` | 自由对话 + 回合循环 + 格式化决策 | legacy 文游；由外部主 Agent 的文游 Skill 维护 | 需求原稿 |
| `D-005` | 中央 Agent 审核/推演全部玩家决策并出信息 | legacy 文游；由外部主 Agent 的文游 Skill 维护 | 需求原稿 |
| `D-006` | Java + Maven | 有效 | 需求原稿 |
| `D-007` | 优先复用 ProjectMosire 三库 | 原话有效；三库解释待 Q7 | 需求原稿 |
| `D-008` | 可选对外 MCP 供外部 Agent 总控事件流 | 方向有效；权限待 Q4 | 需求原稿 |
| `D-009` | 文档体系先行，采用 SimulatorMosire 最新格式 | 有效（维护落点 M-001） | 需求原稿 |
| `D-010` | 私聊 + 公共群 | 有效；bot core 允许群聊入板，唤醒/回复策略见 D-021 | 需求原稿 §五 |
| `D-011` | 每玩家一个秘书子 Agent；秘书与 GM 交流，GM 提供审查过滤后的信息 | 被 D-021 取代（bot core / 默认运行；旧文保留历史） | 需求原稿 §五 |
| `D-012` | 自然语言决策 + 秘书工具结构化 + 玩家私聊硬命令确认 | 被 D-021 取代（宿主硬确认退出 bot core；可由文游 Skill 自建） | 需求原稿 §五 |
| `D-013` | 外部 MCP 全游戏事件流控制 | legacy 文游控制面；bot core 外部 MCP 改为消息板/恢复/回复面 | 需求原稿 §五 |
| `D-014` | 秘书会话 cache、切换与只读 QQ 历史 | 被 D-021 取代（秘书 cache 退出默认运行；QQ 历史能力由消息板/平台工具重做） | 需求原稿；[专题](specs/2026-10-24-secretary-conversation-cache-and-qq-history.md) |
| `D-015` | 玩家行动与信息获取必须轻松顺滑；建立推演文档系统 | 旧文游历史；bot core 不再适用，文游 Skill 另行设计 | 需求原稿；[专题](specs/2026-10-24-player-experience-interaction-document-system.md) |
| `D-016` | 秘书 system prompt 保持最小；详细规则放 AGENTS.md/Skill | 被 D-021 取代（秘书提示词退出默认运行） | 需求原稿 §5.5；[运行时架构](specs/2026-10-24-player-experience-runtime-architecture.md) |
| `D-017` | connectlib 平台级 QQ 能力 + 受控自主入团 | 平台 QQ 能力保留；自主入团文游语义退出 bot core | 需求原稿 §5.6；[计划](plans/2026-10-24-mcp-external-authoring-and-qq-chat-access-plan.md) |
| `D-018` | 工单板 wait 长轮询，第一条新信息到达前保持调用 | legacy 文游工单；bot core 不再使用 | 需求原稿 §5.7；[计划](plans/2026-10-24-mcp-external-authoring-and-qq-chat-access-plan.md) |
| `D-019` | 所有外发逻辑消息共享 100–200ms 最小间隔 | 已实现代码与测试；运行中旧 jar 未替换 | 需求原稿 §5.8；[计划](plans/2026-10-24-mcp-external-authoring-and-qq-chat-access-plan.md) |
| `D-020` | 玩家秘书与 GM 推演秘书默认 128 轮 / 5 分钟 / 单响应 128 工具（2026-10-25 修订，原 8） | legacy 文游历史；bot core 不再适用 | 需求原稿 §5.9（含 2026-10-25 修订）；[历史状态](status/2026-10-24-d020-agent-budget.md) |
| `D-021` | Bot-first 核心；外部主 Agent 全量可见；Inbox 摘要/恢复；用户备注标签；文游降为 Skill | 方向继续有效；旧模块处置由 D-025 决定 | [需求原稿 §5.10](specs/2026-10-24-user-requirements-source.md)；[架构](specs/2026-10-26-bot-first-main-agent-architecture.md)；[计划](plans/2026-10-26-bot-first-main-agent-implementation-plan.md) |
| `D-022` | Bot 为默认启动模式；`config/` 多文件合并；Inbox embedded 参数化 | 默认 bot、多文件与 Inbox 参数接入均已实现；真实启动/DSH 绑定 smoke 待跑 | [需求原稿 §5.11](specs/2026-10-24-user-requirements-source.md) |
| `D-023` | 正文文本约束只在 Skill/AGENTS.md；程序不得清洗、静默替换或 fallback | 有效；程序守卫与 BotReplyTextNormalizer 已删除 | [需求原稿 §5.12](specs/2026-10-24-user-requirements-source.md) |
| `D-024` | Inbox = 一来源一个 QQ 式未读桶；预览最后 n 条、未读计数、可变追加、snapshot ack | 未读桶语义有效；面向 Agent 的手动 snapshot ack 将由 D-026 服务端游标替代 | [需求原稿 §5.13](specs/2026-10-24-user-requirements-source.md) |
| `D-025` | iibot 只做统一 QQ Agent 客户端；文游模块退出默认产品并删除 | 用户方向已确认；待实施 | [需求原稿 §5.14](specs/2026-10-24-user-requirements-source.md) |
| `D-026` | connect-agent 进入统一上游工具层；多账号路由；消息板服务端维护内部标记；Agent 不维护 revision/snapshot | 已确认读取预览+显式 ack、connect-agent 整合删除；实施待进行 | [需求原稿 §5.14](specs/2026-10-24-user-requirements-source.md)；[目标架构](specs/2026-10-28-unified-qq-agent-client-architecture.md) |
| `D-027` | debug/info 日志与易用配置系统为默认运维能力 | 规划已落文档；待实施 | [需求原稿 §5.15](specs/2026-10-24-user-requirements-source.md)；[日志](specs/2026-10-28-logging-levels-and-diagnostics.md)；[配置](specs/2026-10-28-configuration-system.md) |
| `D-028` | 依赖必须最新且干净仓库可复现；文档与当前目标一致 | 用户固定 AgentLib 0.2.4；上游公开 0.2.4 的发布落差为 P7 风险 | [需求原稿 §5.16](specs/2026-10-24-user-requirements-source.md)；[计划](plans/2026-10-28-unified-qq-agent-client-refactor-plan.md) |

## 三、专题设计索引

| 主题 | 当前设计入口 | 说明 |
|---|---|---|
| 需求基线与范围 | [`specs/2026-10-24-requirements-baseline.md`](specs/2026-10-24-requirements-baseline.md) | 功能/非功能需求、建议 v1 范围、Q1–Q7 待裁定 |
| 外部接入 Lib / Agent 扩展层 | [`specs/2026-10-24-connect-and-agent-extension-design.md`](specs/2026-10-24-connect-and-agent-extension-design.md) | connect 独立 Lib、connect-agent 工具/权限扩展层与验收判据 |
| v2 送达/工作区/权限推文/多模型 | [`specs/2026-10-24-v2-agent-workspaces-permissions-and-models.md`](specs/2026-10-24-v2-agent-workspaces-permissions-and-models.md) | 四项 v2 需求的目标运行序列、权限矩阵与验收判据 |
| 共享 Skill / 投诉闭环 | [`specs/2026-10-24-shared-skills-and-complaints.md`](specs/2026-10-24-shared-skills-and-complaints.md) | 全局公共 Skill 区、投诉状态机、GM 审核回应与验收判据 |
| v3 裁定包/行动池/回合记录 | [`specs/2026-10-24-decision-packages-action-pool-and-turn-records.md`](specs/2026-10-24-decision-packages-action-pool-and-turn-records.md) | AgentLib DecisionPackages、自然语言行动池、多批次与回合记录 |
| 理想 GM 控制工作流 / events.db | [`specs/2026-10-24-ideal-gm-control-workflow-and-events-db.md`](specs/2026-10-24-ideal-gm-control-workflow-and-events-db.md) | DSH/Hermes 控制初始化、GM 私有文档、统一工单、events.db 单一事实源 |
| P2 领域事件接入 | [`specs/2026-10-24-p2-domain-event-integration.md`](specs/2026-10-24-p2-domain-event-integration.md) | Post/Complaint/ActionPool 事件源、Taskflow 快照、旧 JSON 两阶段迁移；DecisionPackage/Batch 推迟 P5 |
| P3 控制初始化 / GM 私有文档 | [`specs/2026-10-24-p3-control-initialization-gm-private-docs.md`](specs/2026-10-24-p3-control-initialization-gm-private-docs.md) | 动态玩家/推演/秘书绑定、公开群、世界装载、动态路由、GM 私有文档 |
| P4 统一工单 / 消息板 | [`specs/2026-10-24-p4-unified-ticket-board.md`](specs/2026-10-24-p4-unified-ticket-board.md) | 统一 Ticket 模型、秘书 submit/list/read、GM board/claim/reply/batch/close/note、events.db 快照 |
| P5 行动工单 / 包批次 | [`specs/2026-10-24-p5-action-ticket-package-integration.md`](specs/2026-10-24-p5-action-ticket-package-integration.md) | ACTION_DRAFT ticket→ActionPool；包/批次命令事件、恢复、投影 reconcile |
| P6 发布编排 / 回合记录 | [`specs/2026-10-24-p6-publication-and-turn-records.md`](specs/2026-10-24-p6-publication-and-turn-records.md) | COMMITTED batch→GM 审核发布、Public/Private post、records Markdown 投影与查询 |
| P8-A 零玩家/开场推文 | [`specs/2026-10-24-p8-preplayer-opening-publication.md`](specs/2026-10-24-p8-preplayer-opening-publication.md) | 允许 players=[] 启动；GM 独立 PUBLIC 开场推文；records/openings |
| 秘书会话 cache / NapCat 历史 | [`specs/2026-10-24-secretary-conversation-cache-and-qq-history.md`](specs/2026-10-24-secretary-conversation-cache-and-qq-history.md) | App cache 水合/切换/读取，玩家与 GM 工具，NapCat 只读历史 |
| 玩家体验 / 交互 / 推演文档系统 | [`specs/2026-10-24-player-experience-interaction-document-system.md`](specs/2026-10-24-player-experience-interaction-document-system.md) | D-015：玩家可见文风、秘书确认与恢复、信息卡、推文模板、审稿门、自动化边界 |
| 玩家体验运行时 | [`specs/2026-10-24-player-experience-runtime-architecture.md`](specs/2026-10-24-player-experience-runtime-architecture.md)；[实现快照](status/2026-10-24-player-experience-runtime-implementation.md) | 短确认码、ACTION_DRAFT 确认、结构化裁定、NO_OP、开场门禁；D-023 已删除程序侧输出守卫（历史实现快照不再代表当前代码） |
| 首轮体验问题全量登记 | [`reports/2026-10-24-first-live-run-player-experience-audit.md`](reports/2026-10-24-first-live-run-player-experience-audit.md) | 真实 QQ/DSH/events.db 证据；工程问题 + 玩家体验问题统一编号 |
| 目标运行与模块边界 | [`specs/2026-10-24-target-runtime-design.md`](specs/2026-10-24-target-runtime-design.md) | 旧文游目标运行；默认运行已被 D-021 bot-first 取代，只作历史 |
| Bot-first 消息板与外部主 Agent | [`specs/2026-10-26-bot-first-main-agent-architecture.md`](specs/2026-10-26-bot-first-main-agent-architecture.md)；[计划](plans/2026-10-26-bot-first-main-agent-implementation-plan.md) | 通用 QQ 消息板、来源大框、用户备注、Inbox 摘要恢复、外部主 Agent 全量可见 |
| 统一 QQ Agent 客户端目标架构 | [`specs/2026-10-28-unified-qq-agent-client-architecture.md`](specs/2026-10-28-unified-qq-agent-client-architecture.md) | 多账号 Adapter Registry、消息板游标、统一 MCP 工具面、Agent 不维护内部标记 |
| 日志分级与诊断 | [`specs/2026-10-28-logging-levels-and-diagnostics.md`](specs/2026-10-28-logging-levels-and-diagnostics.md) | INFO/DEBUG 分级、结构化事件、关联 ID、配置化轮转、脱敏验收 |
| 配置文件系统 | [`specs/2026-10-28-configuration-system.md`](specs/2026-10-28-configuration-system.md) | 多账号 schema、严格校验、来源追踪、有效配置脱敏、首次单文件生成、迁移 |
| 统一 QQ Agent 客户端改造计划 | [`plans/2026-10-28-unified-qq-agent-client-refactor-plan.md`](plans/2026-10-28-unified-qq-agent-client-refactor-plan.md) | P0–P7：依赖前置、文游删除、日志、配置、账号路由、消息板游标、统一 MCP、收口 |
| 对外 MCP 总控面 | [`specs/2026-10-24-external-mcp-control-plane.md`](specs/2026-10-24-external-mcp-control-plane.md) | 工具面、安全、幂等、回环限制与已知鉴权缺口 |
| 三库上游契约 | [`../upstream/project-mosire-three-libs-0.2.3.md`](../upstream/project-mosire-three-libs-0.2.3.md) | 坐标、能力、边界、踩坑、未验证清单 |
| NapCat/OneBot 11 契约 | [`../upstream/napcat-onebot11-contract.md`](../upstream/napcat-onebot11-contract.md) | 事件/动作/鉴权/传输策略/可靠性 |
| 本轮实现调查 | [`reports/2026-10-24-requirements-research.md`](reports/2026-10-24-requirements-research.md) | 事实、方案比较、风险、建议 |
| 首个纵向切片证据 | [`status/2026-10-24-first-vertical-slice.md`](status/2026-10-24-first-vertical-slice.md) | P0–P6 实现与 175 测试历史快照；不是当前状态入口 |
| 共享 Skill / 投诉证据 | [`status/2026-10-24-shared-skills-and-complaints.md`](status/2026-10-24-shared-skills-and-complaints.md) | 共享 Skill 与投诉真实闭环历史快照 |
| 理想工作流实施计划 | [`plans/2026-10-24-ideal-workflow-implementation-plan.md`](plans/2026-10-24-ideal-workflow-implementation-plan.md) | P0–P8 阶段、验收、停止条件与工作量估算 |

## 四、当前未裁定

2026-10-26 起，旧的秘书结构与文游 Q1/Q3/Q4/Q7 不再决定 bot core 默认运行；它们保留为 legacy 文游历史。

当前 bot-first 待裁定：

1. 根 `AGENTS.md` 红线 2 是否改为“单一受信任外部主 Agent 全量可见”；
2. `iibot-taskflow` / 旧 `iibot-app` 已由 D-025 裁定退出默认产品；删除顺序、当前 dirty worktree 处理和 `iibot-connect-agent` 最终去留见改造计划。
3. Inbox 摘要粒度：一来源一个来源大框，还是每条消息一个 item；
4. 群聊是否默认全部唤醒，还是全部入摘要但仅私聊/@Bot/高优先级唤醒；
5. 原始消息保留期限与非文本消息解析范围。

见 [`specs/2026-10-26-bot-first-main-agent-architecture.md`](specs/2026-10-26-bot-first-main-agent-architecture.md) §15。

## 五、设计与计划的边界

- `specs/**`：设计与契约，能作为实现依据；未获用户确认的标 `提议`。
- `plans/**`：按设计拆出的执行计划；不是设计本身，计划里的数字是预测。
- `reports/**`：调查/证据，不等于设计裁定。
- `status/**`：历史快照，不构成设计权威。
- 维护流程去 [`../agents/`](../agents/AGENTS.md)，不要写进设计原稿。
