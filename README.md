# iiBotMosire

面向 QQ 的**回合制世界观推演 Bot**：QQ 账号经可替换 Adapter（首期 NapCat / OneBot 11）接入；
配置中的 QQ 用户被指定为“玩家”或“推演”；每名玩家拥有自己的**秘书 Agent**，在私聊中与玩家交流、
经 GM 过滤信息并形成结构化决策草案；玩家必须用私聊硬命令确认后，决策才进入回合；回合冻结后由中央
GM/推演 Agent 审核与推演，输出公开及玩家私密信息。

> **当前阶段**：v2 纵向切片已实现，261 个自动化测试通过；真实 NapCat + 真实 LLM + 本地 MCP 单人回合已验证。
> 多模型路由、秘书独立工作区、GM 推文审核/发布、GM 专用推演秘书已落地；真实 DSH 外部会话与真实 v2 多玩家回归尚未验证。当前事实以 [`docs/agents/STATUS.md`](docs/agents/STATUS.md) 为准。

## 已实现能力

- **可替换接入 Lib** `iibot-connect`：OneBot 11 事件 WS + HTTP Action、鉴权、重连、心跳、去重、限速、文本分段、三态发送回执。
- **Agent 工具扩展层** `iibot-connect-agent`：依赖 connect + AgentLib，提供 `qq_*` AgentTool、`ToolSpec`/角色/资源权限与统一授权；含通用 `qq_get_login_info`（AccountInfoProvider）。
- **交互-推演任务流** `iibot-taskflow`：
  - 玩家/推演运行期设定提案与推演审核（批准后成为新世界事实）；
  - 玩家私聊秘书 Agent；
  - GM brief 审查与可见性过滤；
  - 自然语言对话 → `DecisionDraft` → 私聊 `/确认 <draftId>` 硬确认；
  - 回合状态机、冻结快照、中央推演提案校验与提交；
  - 事件审计、幂等、revision CAS、拒绝零落盘。
- **即时送达 ACK**：已登记玩家/推演私信在进入 LLM 前先收到独立幂等状态提示，正式回复随后异步生成。
- **秘书独立工作区**：`dataDir/secretaries/<playerId>/{public,player,gm,notes}`，基于 Workspace/Docs；秘书只读允许资料并可记笔记，GM 可列出/读取/下发资料并直接与秘书对话。
- **推文权限与 GM 最终发布**：秘书只能创建/读取自己可见的草稿；GM/DIRECTOR/SUPERVISOR 审核，只有 GM 侧 `tf_post_publish` 才进入 outbox 公共群/私聊发送；`posts.json` 原子持久化。
- **多模型路由**：`llm` 默认路由 + `llmRoutes` 覆盖 + `agentRoutes(secretary/adjudicator/gmAide)`；另有只读 GM 推演秘书 `GmAideAgent`，可读世界/回合/brief/事件/所有秘书工作区/所有推文，只返回 MCP/GM 会话。
- **组合根** `iibot-app`：严格配置、世界事实装载、Workspace/Docs 世界文档、SQLite 事件审计、**完整状态快照的原子持久化与重启恢复**、入站回复与回合结果 outbox、逐用户 mailbox、自动冻结调度、可运行 Main。
- **外部 MCP 控制面**：28 个全游戏事件流控制工具（含 `tf_run_adjudication_agent`）；默认关闭、只绑回环、Bearer 鉴权、独立 ToolRegistry、经 `ToolCallAuthorizer` 执行。
- **可运行包**：`java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar --config <path>`。

## 快速开始

```bash
./mvnw -B -ntp package

# 1. 复制配置与世界事实样例并改成你的 QQ/群/模型配置
cp iibot-app/config/iibot.example.json iibot-app/config/iibot.json
cp iibot-app/config/world-facts.example.json iibot-app/config/world-facts.json
# 编辑 iibot-app/config/iibot.json：players/directors/publicGroupId/napcat/llm

# 2. 密钥只放环境变量
export IIBOT_NAPCAT_ACCESS_TOKEN='...'
export IIBOT_LLM_API_KEY='...'
export IIBOT_CONTROL_TOKEN='...'   # 仅 control.enabled=true 时需要

# 3. 启动
java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar \
  --config iibot-app/config/iibot.json
```

MCP 控制面示例（外部 Agent 侧）：

```text
URL:  http://127.0.0.1:8765/mcp/control
Header: Authorization: Bearer <IIBOT_CONTROL_TOKEN>
```

仅允许回环地址；不要把该端口直接暴露公网。远程/多用户 TLS 与认证代理尚未实现。

## 文档入口

| 需要 | 入口 |
|---|---|
| 常驻红线、模块与文档路由 | [`AGENTS.md`](AGENTS.md) |
| 当前状态、未验证项、下一步 | [`docs/agents/STATUS.md`](docs/agents/STATUS.md) |
| 文档系统规则 | [`docs/AGENTS.md`](docs/AGENTS.md) |
| 项目设计索引 | [`docs/superpowers/AGENTS.md`](docs/superpowers/AGENTS.md) |
| 用户需求原话与 D-xxx 拆分 | [`docs/superpowers/specs/2026-10-24-user-requirements-source.md`](docs/superpowers/specs/2026-10-24-user-requirements-source.md) |
| 需求基线与待裁定 | [`docs/superpowers/specs/2026-10-24-requirements-baseline.md`](docs/superpowers/specs/2026-10-24-requirements-baseline.md) |
| 接入 Lib / Agent 扩展层设计 | [`docs/superpowers/specs/2026-10-24-connect-and-agent-extension-design.md`](docs/superpowers/specs/2026-10-24-connect-and-agent-extension-design.md) |
| 目标运行与模块设计 | [`docs/superpowers/specs/2026-10-24-target-runtime-design.md`](docs/superpowers/specs/2026-10-24-target-runtime-design.md) |
| 外部 MCP 控制面 | [`docs/superpowers/specs/2026-10-24-external-mcp-control-plane.md`](docs/superpowers/specs/2026-10-24-external-mcp-control-plane.md) |
| 需求/实现调查 | [`docs/superpowers/reports/2026-10-24-requirements-research.md`](docs/superpowers/reports/2026-10-24-requirements-research.md) |
| 首个纵向切片证据 | [`docs/superpowers/status/2026-10-24-first-vertical-slice.md`](docs/superpowers/status/2026-10-24-first-vertical-slice.md) |
| 文档/Skill/构建门禁 | [`tools/gate.sh`](tools/gate.sh) |

## 模块

```text
iibot-connect        独立外部接入 Lib（不依赖 AgentLib/业务域）
      ▲
iibot-connect-agent  connect → AgentLib AgentTool/权限扩展层
      ▲
iibot-taskflow       领域、秘书/GM Agent、回合与外部控制工具
      ▲
iibot-app            组合根、配置、SQLite 审计、Docs、Outbox、MCP 传输、Main
```

- 技术栈：Java 21、Maven Wrapper 3.9.16、AgentLib/Workspace/Docs `0.2.3`、Jackson、SQLite、官方 MCP Java SDK 2.0.1。
- 当前 261 个自动化测试以进程内/本地 Fake 为主；真实 NapCat + LLM + 本地 MCP 单人回合另有独立证据。

## 已知边界

- **逐事件回放尚未实现**：当前重启恢复依赖 `dataDir/taskflow-state.json` 完整状态快照，并用 SQLite 事件库的 seq 做一致性锚点；聊天会话上下文（ContextWindow）不在领域快照内，重启不恢复闲聊历史。
- 真实 NapCat 根事件 WS + HTTP Action 的版本差异、真实速率/分段阈值未实测。
- 真实 LLM 的 tool calling、reasoning_content、超时与模型输出稳定度未实测。
- 公共群目前是公开广播边界，群内玩家对话/群内确认未接入。
- 外部 MCP 是本地单 supervisor 控制面，非多租户；长时间 SSE/断线重连/Last-Event-ID 重放未验证。
- 当前没有 Git 元数据；未提交、未推送。

任何“构建通过/联调成功”的表述都必须先有实际命令与证据，见 [`docs/agents/VERIFICATION.md`](docs/agents/VERIFICATION.md)。
