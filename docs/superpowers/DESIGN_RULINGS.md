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
| `D-002` | QQ 用户角色 = 玩家 / 推演，角色由服务端配置决定 | 有效 | 需求原稿 |
| `D-003` | 固定提示词 + 玩家/推演设定载入 | 有效 | 需求原稿 |
| `D-004` | 自由对话 + 回合循环 + 格式化决策 | 有效；格式待 Q3 | 需求原稿 |
| `D-005` | 中央 Agent 审核/推演全部玩家决策并出信息 | 有效；签核模式待 Q2 | 需求原稿 |
| `D-006` | Java + Maven | 有效 | 需求原稿 |
| `D-007` | 优先复用 ProjectMosire 三库 | 原话有效；三库解释待 Q7 | 需求原稿 |
| `D-008` | 可选对外 MCP 供外部 Agent 总控事件流 | 方向有效；权限待 Q4 | 需求原稿 |
| `D-009` | 文档体系先行，采用 SimulatorMosire 最新格式 | 有效（维护落点 M-001） | 需求原稿 |
| `D-010` | 私聊 + 公共群 | 有效；群内边界待 Q8 | 需求原稿 §五 |
| `D-011` | 每玩家一个秘书子 Agent；秘书与 GM 交流，GM 提供审查过滤后的信息 | 有效；协议待细化 | 需求原稿 §五 |
| `D-012` | 自然语言决策 + 秘书工具结构化 + 玩家私聊硬命令确认 | 有效；命令/过期规则待设计 | 需求原稿 §五 |
| `D-013` | 外部 MCP 全游戏事件流控制 | 有效；认证与非游戏运维权界待 Q9 | 需求原稿 §五 |

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
| 目标运行与模块边界 | [`specs/2026-10-24-target-runtime-design.md`](specs/2026-10-24-target-runtime-design.md) | 事件流、回合状态机、提示词/可见性、失败语义、验收判据 |
| 对外 MCP 总控面 | [`specs/2026-10-24-external-mcp-control-plane.md`](specs/2026-10-24-external-mcp-control-plane.md) | 工具面、安全、幂等、回环限制与已知鉴权缺口 |
| 三库上游契约 | [`../upstream/project-mosire-three-libs-0.2.3.md`](../upstream/project-mosire-three-libs-0.2.3.md) | 坐标、能力、边界、踩坑、未验证清单 |
| NapCat/OneBot 11 契约 | [`../upstream/napcat-onebot11-contract.md`](../upstream/napcat-onebot11-contract.md) | 事件/动作/鉴权/传输策略/可靠性 |
| 本轮实现调查 | [`reports/2026-10-24-requirements-research.md`](reports/2026-10-24-requirements-research.md) | 事实、方案比较、风险、建议 |
| 首个纵向切片证据 | [`status/2026-10-24-first-vertical-slice.md`](status/2026-10-24-first-vertical-slice.md) | P0–P6 实现与 175 测试历史快照；不是当前状态入口 |
| 共享 Skill / 投诉证据 | [`status/2026-10-24-shared-skills-and-complaints.md`](status/2026-10-24-shared-skills-and-complaints.md) | 共享 Skill 与投诉真实闭环历史快照 |
| 理想工作流实施计划 | [`plans/2026-10-24-ideal-workflow-implementation-plan.md`](plans/2026-10-24-ideal-workflow-implementation-plan.md) | P0–P8 阶段、验收、停止条件与工作量估算 |

## 四、当前未裁定

已确认 Q1/Q3/Q4/Q7 与秘书结构；剩余 Q5（LLM 路由）、Q6（单/多世界）、Q8（公共群交互边界）、Q9（MCP 非游戏运维权界）
见 [`specs/2026-10-24-requirements-baseline.md`](specs/2026-10-24-requirements-baseline.md) §7。

已实现默认：单世界、公共群仅公开广播边界、MCP 不提供非游戏运维操作。它们仍是待用户确认的默认选择，不因代码存在而自动升级为裁定。

## 五、设计与计划的边界

- `specs/**`：设计与契约，能作为实现依据；未获用户确认的标 `提议`。
- `plans/**`：按设计拆出的执行计划；不是设计本身，计划里的数字是预测。
- `reports/**`：调查/证据，不等于设计裁定。
- `status/**`：历史快照，不构成设计权威。
- 维护流程去 [`../agents/`](../agents/AGENTS.md)，不要写进设计原稿。
