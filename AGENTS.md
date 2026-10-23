# AGENTS.md —— iiBotMosire 常驻索引

> **更新时间**：2026-10-24（首个可运行纵向切片）
> **本文件只做三件事**：项目简介、常驻红线、文档/模块路由。
> **规则正文不在本文件**：项目设计看 `docs/superpowers/`，项目维护看 `docs/agents/`。
> **禁止用 `@` 导入长文**；按需打开链接。

## 0. 冲突顺序（从上到下）

1. 用户当次明确指令（逐字优先）。
2. 用户设计裁定：需求原话与当前有效专题设计。
   - 原话正本：`docs/superpowers/specs/2026-10-24-user-requirements-source.md`
   - 索引：`docs/superpowers/DESIGN_RULINGS.md`
3. 项目维护裁定：`docs/agents/MAINTENANCE_RULINGS.md`、`docs/AGENTS.md`。
4. 当前有效目标运行设计 / 契约：`docs/superpowers/specs/**`。
5. 本文件（常驻路由与红线）。
6. `docs/agents/STATUS.md`（只表示当前事实，不覆盖设计/维护规则）。
7. 旧文档、历史 status、调查报告与外部资料（只作证据）。

## 1. 项目简介

**iiBotMosire** 是一个 Java 21 + Maven 的 QQ 回合制世界观推演 Bot：

- QQ 账号通过可替换 Adapter 接入，首期实现 NapCat / OneBot 11；
- QQ 用户在配置中被指定为 **玩家（PLAYER）** 或 **推演（DIRECTOR）**；
- 玩家在私聊中与自己的秘书 Agent 自然语言交流，秘书形成结构化草案，玩家必须用私聊硬命令确认；
- 中央 Agent 在回合冻结后审核、推演所有玩家决策，生成公开/私密结果；
- 所有领域操作进入可审计事件流（引擎回放恢复尚未实现）；外部 Agent 可通过受控 MCP 观察/推进游戏事件流；
- Agent 底座优先使用相邻 `~/ProjectMosire` 的 `agentlib-mosire`、`workspace-mosire`、`docs-mosire`。

> 当前已有 Maven 多模块实现与 185 个通过测试；**真实 NapCat/LLM/DSH MCP 联调尚未运行**。
> 当前实现边界以 [`docs/agents/STATUS.md`](docs/agents/STATUS.md) 为准，不能把 Mock 通过当成真实联调通过。

## 2. 常驻红线

1. **身份只由配置决定。** QQ 消息、玩家发言、外部 MCP 参数都不能自行提升为“推演”、系统或管理员。
2. **可见性在进入模型前过滤。** 玩家 Agent 的上下文和工具结果都不得包含该玩家不可见的事实；不能只靠提示词要求“保密”。
3. **LLM 不直接改世界。** 中央 Agent 只能提交结构化推演提案；由宿主校验、冻结快照并原子提交事件。非法/越权/过期提案零落盘。
4. **所有状态变化可审计、目标可重放。** 领域变更必须进入带版本事件流；当前实现的事件库只做审计读取，尚未完成引擎回放恢复（见 STATUS）。外部副作用（QQ 发送）保持明确 at-least-once / 不确定态语义。
5. **回合推进有状态机。** 决策提交、冻结、推演、提交、发布必须校验当前状态、版本与幂等键；禁止绕过 `TurnCoordinator` 直接改状态。
6. **Adapter 隔离协议。** 领域层不得出现 OneBot 字段、NapCat URL、`echo` 或 CQ Code；协议细节只在 `iibot-connect`。
7. **外部 MCP 默认最小权限。** 默认关闭、Bearer 鉴权、只绑回环；外部 supervisor 拥有全游戏事件流控制工具，但不得关机、改玩家角色/密钥、伪造玩家硬确认或绕过状态机直接写库。
8. **密钥零泄露。** token、API key、完整 QQ 凭据不进入日志、事件 payload、异常消息或模型上下文。
9. **没跑过的不写“通过”。** 构建、测试、真实模型、NapCat、MCP 与长跑证据必须分层表述。
10. **用户协同门。** 需要用户提供状态、操作或真实外部确认时，先停下说明事实与单轮请求，等待用户回应；不得用自测消息冒充秘书回复，不得代替玩家硬确认或连续自发外发。详见 `docs/agents/MAINTENANCE_RULINGS.md` M-004。

## 3. Maven 模块（P0 已落地）

> 根 `pom.xml` 为 `io.mosire.iibot:iibot-parent:0.1.0-SNAPSHOT`，当前 **4 个 `iibot-*` 模块**。
> 依赖方向：`iibot-connect` 不依赖 AgentLib；`iibot-connect-agent` 依赖 `iibot-connect + agentlib-mosire`；
> `iibot-taskflow` 依赖前两者与三库；`iibot-app` 是组合根。

| 模块 | 职责 | 允许依赖 |
|---|---|---|
| `iibot-connect` | 独立外部接入 Lib：统一消息/连接 SPI、NapCat/OneBot 11、重连/心跳/限速/去重/发送回执、契约测试工具；不依赖 AgentLib 或业务域 | Jackson、SLF4J |
| `iibot-connect-agent` | 扩展层：把 connect 能力映射成 AgentLib `AgentTool`，提供 `ToolSpec`/角色/敏感与破坏性/错误/脱敏/权限元数据；不实现世界或回合 | `iibot-connect` + `agentlib-mosire` |
| `iibot-taskflow` | 交互-推演任务流：玩家私聊秘书、GM 信息过滤 brief、自然语言决策草案、玩家硬确认、回合冻结、中央推演、外部控制工具；不出现 OneBot 类型 | connect/connect-agent + 三库 |
| `iibot-app` | 组合根：配置、连接、Agent/Taskflow、SQLite 审计、Workspace/Docs、Outbox、认证 MCP 传输、生命周期与可运行入口 | 上述全部 |

`iibot-connect` 是其他模块的接入边界；后续可增加 OneBot 12/Discord 等实现而不改任务流。

## 4. 文档路由

| 需要找什么 | 入口 |
|---|---|
| 当前状态 / 目标 / 下一步 | [`docs/agents/STATUS.md`](docs/agents/STATUS.md) |
| 文档系统规则 | [`docs/AGENTS.md`](docs/AGENTS.md) |
| 项目维护裁定 | [`docs/agents/MAINTENANCE_RULINGS.md`](docs/agents/MAINTENANCE_RULINGS.md) |
| 工作流 / 验证 / 环境 / Skill | [`docs/agents/WORKFLOW.md`](docs/agents/WORKFLOW.md)、[`VERIFICATION.md`](docs/agents/VERIFICATION.md)、[`ENVIRONMENT.md`](docs/agents/ENVIRONMENT.md)、[`SKILLS.md`](docs/agents/SKILLS.md) |
| 项目设计索引 | [`docs/superpowers/AGENTS.md`](docs/superpowers/AGENTS.md) |
| 设计裁定索引 | [`docs/superpowers/DESIGN_RULINGS.md`](docs/superpowers/DESIGN_RULINGS.md) |
| 用户需求原话 | [`docs/superpowers/specs/2026-10-24-user-requirements-source.md`](docs/superpowers/specs/2026-10-24-user-requirements-source.md) |
| 本轮调查 | [`docs/superpowers/reports/2026-10-24-requirements-research.md`](docs/superpowers/reports/2026-10-24-requirements-research.md) |
| 实施计划与进度 | [`docs/superpowers/plans/2026-10-24-next-implementation-plan.md`](docs/superpowers/plans/2026-10-24-next-implementation-plan.md) |
| 工具与门禁 | [`tools/AGENTS.md`](tools/AGENTS.md) |

## 5. 工作树与环境

- 未来所有 git worktree 统一放 `<仓库根>/worktree/<name>/`；具体规则见 [`docs/agents/ENVIRONMENT.md`](docs/agents/ENVIRONMENT.md)。
- JDK 21；仓库内 Maven Wrapper 3.9.16（`./mvnw`）。文档/Skill 检查用 Python 3。
- 门禁入口：`tools/gate.sh docs|selftest|pre|focused|full`。
- 本机参考仓：`~/ProjectMosire`（三库上游，当前发布 0.2.3）、`~/DevMosire`（旧工程实践参考；不继承架构/不直接复制 GPL 代码）。
- 不在同一个 checkout 并发跑 Maven；不覆盖正在运行的 jar；不擅自 commit/push。

## 6. 目录级规则

- 每个实际存在的 Maven 模块在落地时必须有 `AGENTS.md`，只写本模块职责、边界、入口、验证与文档指针。
- 目录级 `AGENTS.md` 不复制根红线、不另立用户裁定、不把历史状态写成当前事实。
- 当前顶层文档/Skill 结构由 [`docs/AGENTS.md`](docs/AGENTS.md) 规定。
