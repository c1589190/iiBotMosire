# iiBotMosire

面向 QQ 的 **bot-first** 消息接入与外部主 Agent 桥：QQ 账号经可替换 Adapter（当前 LLBot，后续 NapCat，统一 OneBot 11）接入；
`iibot-bot` 把私聊/群聊消息写入来源消息板，按来源生成 bounded 摘要并投递到 Inbox，供一个受信任的外部主 Agent
读取摘要、恢复正文和回复。纯文游语义正在退出默认核心，改为外部 Agent Skill；旧文游模块在迁移期保留为 legacy。

> **当前阶段**：bot-first 重构与依赖升级进行中。AgentLib 已升到 `0.2.4`，Inbox 已切到 `summary/content` 与 15 工具面；
> 新 `iibot-bot` 通用核心已开始落地。当前事实以 [`docs/agents/STATUS.md`](docs/agents/STATUS.md) 为准。

## 已实现能力

- **可替换接入 Lib** `iibot-connect`：OneBot 11 事件 WS + HTTP Action、鉴权、重连、心跳、去重、限速、文本分段、三态发送回执。
- **Agent 工具扩展层** `iibot-connect-agent`：依赖 connect + AgentLib，提供 `qq_*` AgentTool、`ToolSpec`/角色/资源权限与统一授权；含通用 `qq_get_login_info`（AccountInfoProvider）。
- **Bot-first 核心** `iibot-bot`：通用 QQ 来源/thread、消息板端口、用户备注/标签、bounded 摘要与 Inbox digest 模型；不依赖旧 taskflow。
- **OneBot 11 富消息**：text/at/reply 出站段、入站图片/转发元数据、引用链与合并转发树。
- **历史与上下文**：`bot_thread_history`、`bot_qq_recent`、`bot_qq_history`；Inbox `inbox_view.content.context` 默认带最近 6 条 IN/OUT 摘要。
- **图片阅读**：`bot_media_get` 按需取图，通过 MCP `ImageContent` 交给支持识图的模型；不暴露 raw URL。
- **入站好友申请审批**：`bot_friend_request_list/decide`；标准 OneBot 11/LLBot 不支持主动发起好友申请，因此不提供主动加好友。
- **legacy 交互-推演任务流** `iibot-taskflow`：
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
- **legacy 组合根** `iibot-app`：旧文游配置、世界事实装载、Workspace/Docs、SQLite 审计、快照恢复、Outbox、MCP 传输与 Main；迁移期保留回滚。
- **外部 MCP 控制面**：旧文游工具面仍保留在 legacy app；bot-first 新工具面将收敛为消息板/恢复/回复/用户备注。
- **全局共享 Skill 区**：`dataDir/shared-skills/{public,skills}`，所有秘书只读，GM 通过 MCP 写入；无需逐玩家复制公共文档。
- **投诉闭环**：秘书可随时提交玩家投诉，GM 通过 MCP 列表/查看/回应；回应可私聊发回玩家，`complaints.json` 原子持久化。
- **重启不重复投递**：回合结果使用 `delivery-ledger.json` 逐消息持久化声明；重启恢复不会重发已投递的公共/私聊结果，UNKNOWN 不自动重试。
- **可运行包**：`java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar`；默认 bot 模式，无 `--mode`。

## 快速开始

Bot 是默认运行模式，不需要 `--mode bot`。

```bash
./mvnw -B -ntp package

# 1. 第一次启动：没有 config/bot/*.json 时自动生成模板并退出（exit 2）
java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar

# 2. 编辑生成的配置
#    config/bot/00-base.json            accountId / dataDir
#    config/bot/10-napcat.json          WS/HTTP 地址和 env 名称
#    config/bot/20-control-inbox.json   MCP 端口/path 和 Inbox id
#    config/bot/30-policy.json          通知策略

# 3. 密钥只放环境变量
export IIBOT_NAPCAT_ACCESS_TOKEN='...'
export IIBOT_CONTROL_TOKEN='...'

# 4. 再次启动；默认读取 config/bot/ 中全部 *.json
java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar
```

首次生成只发生在默认 `config/bot/` 完全没有任何 `*.json` 时；已有配置绝不覆盖。
显式传 `--config` 时不会自动生成，缺失路径按配置错误处理。

显式指定多个配置源：

```bash
java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar \
  --config config/bot/00-base.json \
  --config config/bot/10-napcat.json
```

legacy 文游必须显式选择：

```bash
java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar \
  --mode legacy --config iibot-app/config/iibot.json
```

`config/README.md` 记录多文件合并、目录顺序和相对路径规则。

Bot 程序 MCP 示例（外部 Agent 侧）：

```text
URL:  http://127.0.0.1:8765/mcp/bot
Header: Authorization: Bearer <IIBOT_CONTROL_TOKEN>
```

legacy 控制面路径仍是 `/mcp/control`。

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
iibot-bot            bot-first：消息板/thread/用户备注/摘要/Inbox 模型
      ▲
iibot-taskflow       legacy：旧文游领域、秘书/GM、回合与控制工具
      ▲
iibot-app            legacy：旧文游组合根与可运行 Main
```

- 技术栈：Java 21、Maven Wrapper 3.9.16、AgentLib/Workspace/Docs `0.2.4`、Jackson、SQLite、官方 MCP Java SDK 2.0.1。
- 当前数字以 [`docs/agents/STATUS.md`](docs/agents/STATUS.md) 为准；旧 345/627 等数字是历史基线，不冒充 bot-first 全量验收。

## 已知边界

- **逐事件回放尚未实现**：当前重启恢复依赖 `dataDir/taskflow-state.json` 完整状态快照，并用 SQLite 事件库的 seq 做一致性锚点；聊天会话上下文（ContextWindow）不在领域快照内，重启不恢复闲聊历史。
- 真实 NapCat 根事件 WS + HTTP Action 的版本差异、真实速率/分段阈值未实测。
- 真实 LLM 的 tool calling、reasoning_content、超时与模型输出稳定度未实测。
- 公共群目前是公开广播边界，群内玩家对话/群内确认未接入。
- 外部 MCP 是本地单 supervisor 控制面，非多租户；长时间 SSE/断线重连/Last-Event-ID 重放未验证。
- 当前没有 Git 元数据；未提交、未推送。

任何“构建通过/联调成功”的表述都必须先有实际命令与证据，见 [`docs/agents/VERIFICATION.md`](docs/agents/VERIFICATION.md)。
