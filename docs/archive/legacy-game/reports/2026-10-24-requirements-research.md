# 需求调研与实现调查报告（2026-10-24）

> **状态**：调查完成（尚无生产代码/真实联调）
> **性质**：证据与建议，不是设计裁定；设计结论见 `specs/**`，待用户裁定见需求基线 §7。
> **实施后记（2026-10-24）**：后续按用户补充裁定落成 4 模块 `iibot-connect` / `iibot-connect-agent` / `iibot-taskflow` / `iibot-app`；本文中早期“iibot-qq / iibot-core / iibot-storage”等命名是调研时假设，以当前模块表与 STATUS 为准。
> **调查范围**：用户需求、ProjectMosire 三库、NapCat/OneBot 11、MCP 总控、旧 iBotMosire 参考实现、文档体系。

## 0. 一页结论

1. **项目应先做“事件驱动的回合状态机”，再做 QQ/LLM 细节。**
   因为最大风险不是“发不出 QQ 消息”，而是重复消息、冻结/推演半提交、玩家越权看秘密、重试导致世界重复变更。
2. **NapCat Adapter 建议首版采用「事件正向 WebSocket + HTTP Action」。**
   事件走 WS，`send_msg/get_login_info` 等动作走 HTTP；把传输策略封在 `iibot-qq` 内。不要假设根 WS 必然同时支持动作。
3. **ProjectMosire 三库按发布页口径使用 `agentlib-mosire` / `workspace-mosire` / `docs-mosire` 0.2.3。**
   用 AgentLib 的 LLM、AgentLoop、工具、事件存储与 MCP server；用 Workspace/Docs 做路径安全与文档/叙事存储；
   **不使用** AgentLib 的“决策包”来表示玩家回合决策（两者语义不同）。
4. **中央 Agent 必须“提案”，不能直接写世界。**
   模型输出先通过 schema/实体/版本/可见性校验，再由宿主写一个 `turn.committed` 事件。
5. **玩家可见性必须在 prompt 组装和工具结果两处前置过滤。**
   如果不可见事实根本没有进入玩家 Agent 的上下文，就不依赖模型“自觉保密”。
6. **外部 MCP 是有价值但高风险的增强项。**
   建议默认关闭、回环、独立 ToolRegistry、只读 + 受控回合推进；绝不暴露关机、任意 QQ 发送、改角色、伪造决策。
   0.2.3 的 `AgentToMcpServer` HTTP 入口没有逐请求 Bearer 鉴权，公网/多用户需另立认证代理设计。
7. **现有 `~/DevMosire/iBotMosire` 可参考协议处理和测试，但不应作为本项目的 Agent/进程架构。**
   它是“Agent 操作 QQ/服务器”的通用网关，不是“玩家在世界中行动、中央 Agent 推演”的回合制引擎。
8. **本轮只做了源码/文档核实，没有编译 Maven、没有连真实 NapCat、没有调用真实 LLM、没有 DSH MCP E2E。**
   所有运行能力必须标 `NOT_RUN`。

## 1. 调研方法与证据等级

| 等级 | 本轮来源 | 可以支持什么 |
|---|---|---|
| 用户原话 | 本次对话 | 需求与裁定 |
| 本机源码 | `~/ProjectMosire` 三库源码、`~/DevMosire/iBotMosire` 旧实现 | API/行为存在性、边界、已知坑 |
| 发布页 | `https://c1589190.github.io/ProjectMosire/` | 版本、坐标、发布事实 |
| 官方第三方文档 | NapCat 文档、MCP Java SDK 文档 | 协议支持、字段与用法 |
| 未实测 | 本项目的构建、真实 QQ、真模型、MCP E2E | **不能声称通过** |

## 2. ProjectMosire 三库调查

### 2.1 事实

- 上游 HEAD：`79b349fb17bfe8800cefbcd7a03ea130204e5bb5`；tag `v0.2.3`。
- 根 POM 版本 `0.2.3`（`~/ProjectMosire/pom.xml:9`）。
- Pages 当前版本 `0.2.3`，发布三库与父 POM。
- 依赖方向：`docs-mosire → workspace-mosire → agentlib-mosire`（三个模块 POM）。
- 三库能力锚点：
  - `AgentLoop`：`AgentLibMosire/src/main/java/io/mosire/agentlib/runtime/AgentLoop.java:70`、`:113`；
  - 工具唯一入口：`.../tool/ToolCallAuthorizer.java:20`；
  - 工具接口：`.../tool/AgentTool.java:22`；
  - 事件存储：`.../event/EventStore.java:11`、`.../event/SqliteEventStore.java:95`；
  - MCP server：`.../mcp/AgentToMcpServer.java:60`、`:523` 起；
  - Workspace 工具：`WorkspaceMosire/src/main/java/io/mosire/workspace/WorkspaceTools.java:61`；
  - Docs 工具：`DocsMosire/src/main/java/io/mosire/docs/DocsTools.java:47`。
- 上游接入文档明确提醒：权限命名空间要显式声明；`destructiveAllowed(true)`；工具调用必须经 authorizer；
  `ToolContext.identity().instanceId()` 必须等于挂载 user；`DocsApi.list` 只服务 LOG 纪律。

### 2.2 对本项目最有价值的复用

1. **LLM + AgentLoop**：不用自写 OpenAI SSE 解析、工具调用拼接、reasoning_content 回传。
2. **ToolRegistry + ToolCallAuthorizer**：中央/玩家/外部 MCP 三个工具面都走同一授权入口，适合“不出岔子”。
3. **SqliteEventStore**：可作为全局事件流底座；字段 `seq/type/agent/payload/correlationId` 与本项目需求契合。
4. **AgentToMcpServer**：可直接把独立 ToolRegistry 暴露为 HTTP MCP，但必须收窄工具面。
5. **Workspace + Docs**：世界设定、叙事日志、玩家可见笔记有安全的路径与文档 API；不必自写文件围栏。

### 2.3 不建议直接复用的部分

| 上游能力 | 为什么不直接用 |
|---|---|
| `io.mosire.agentlib.decision.DecisionPackages` | 它是“工具调用审批/裁决包”，并非玩家叙事决策；复用会让审核对象、生命周期和失败语义混乱 |
| `brain-mosire` / `main-mosire` | 完整 Agent 运行时/入口装配，非 Pages 三库发布范围；本项目需要自己的回合/世界/QQ/MCP 边界 |
| `DocsTools` 全量工具面 | 玩家 Agent 不应有写文档/挂载工具；只可作为内部/推演工具，并逐个审权限 |

### 2.4 风险与待验证

- **API 兼容**：三库 0.2.3 尚未在本项目 Maven 工程编译；P0 必须验证依赖解析、公开 API、Jackson 2.x 线与 MCP SDK 2.0.1。
- **AgentLoop 非线程安全**：每个 `ContextWindow` 必须串行；这决定我们的 mailbox 设计。
- **AgentToMcpServer 鉴权缺口**：固定 caller、无逐请求 token 解析；不能直接公网暴露。
- **MCP schema 严格性**：外部导入工具的 inputSchema 可能有顶层 `null`，AgentLib 的 `ToolDefs.of` 会抛 NPE；
  本项目自产 schema 要在测试中验证。
- **上游文档工具数量**：0.2.3 以源码为准（Workspace 4、Docs 8），设计不写死 3/7。
- **未验证**：SQLite EventStore 高并发、WAL、长事件 payload、重建性能。

## 3. NapCat / OneBot 11 调查

### 3.1 关键协议事实（官方文档）

- NapCat 支持 HTTP 服务端/客户端与 WS 服务端/客户端四种网络角色；WS 双向、推荐实时使用。
- 正向 WS 配置：`websocketServers`，字段 `host/port/token/messagePostFormat/reportSelfMessage/heartInterval`。
- 官方 WebSocket 文档分别列出事件 WS 根路径与 API WS `/api`，也有在 WS 连接上发送 action 的示例；
  不同版本/部署需实测根路径是否同时接受 action。
- HTTP 动作：`POST /<action>`，标准响应 `status/retcode/data/message/echo`。
- 消息事件：`post_type=message`、`message_type=private|group`、`user_id`、`message_id`、`message` 数组或 CQ 字符串。
- 发送动作：`send_private_msg` / `send_group_msg` / `send_msg`；响应 `data.message_id`。
- 认证支持 `Authorization: Bearer` 与 `access_token` 查询参数。
- `reportSelfMessage` 默认 false；建议保持 false 避免自身消息回环。

### 3.2 方案比较

| 方案 | 优点 | 缺点 | v1 建议 |
|---|---|---|---|
| 事件 WS + HTTP Action | 请求响应一一对应；无需 echo pending map；JDK 原生支持 | 需同时启用两个 NapCat adapter；URL/token 两套 | **首选** |
| 事件 WS + API WS `/api` | 全双工；若根 WS 支持动作可少开 HTTP | 要维护 echo map、超时、断线失败、两条连接的一致性 | 备选 |
| 单 WS 根同时收发 | 配置最简单 | 是否支持动作依赖 NapCat 实现；错误/超时关联复杂 | 只做可配置策略，不设为默认假设 |
| HTTP 客户端推事件 + HTTP Action | 完全无 WS | 实时性/连接管理差 | 不推荐 |

### 3.3 可靠性建议

- 去重键：`selfId + messageId + subType`（群加 `groupId`）。
- 同一会话串行，不同会话并发。
- 发送结果三态：`SENT` / `FAILED_BEFORE_SEND` / `UNKNOWN`；超时未知不自动重试，防重复轰炸。
- Adapter 层统一限速、分段、敏感内容检查。
- 不把 OneBot 类型泄漏到 core。

### 3.4 欠验证

- 真实 NapCat 版本、配置项、根 WS 是否能发动作；
- 私聊文本长度上限/速率；
- 图片/语音等非文本事件形状；
- 连接失败/心跳/重连行为；
- `messagePostFormat=array` 对 at/reply/face 的细节。

## 4. 中央 Agent 与回合设计调查

### 4.1 需求对架构的硬约束

- “自由交流” ≠ “自由写状态”：聊天只产生叙事和会话事件，不产生世界事实。
- “格式化提交决策” ≠ “让 LLM 自由解析自然语言并直接执行”：必须有固定格式、解析、预览、确认和截止时间。
- “中央 Agent 审核、推演各个玩家决策”必须看到**冻结批次**，否则玩家可在推演过程中反复改决策。
- “出信息”必须按玩家可见性分发，不能把所有人的私密后果广播。

### 4.2 推荐结构

- **玩家 Agent（每玩家一个串行会话）**：只含该玩家可见上下文，只读工具，负责自由对话。
- **中央推演 Agent（单飞）**：看全量世界和冻结决策，只能提交结构化提案。
- **TurnCoordinator**：唯一状态机入口，处理 open/lock/adjudicate/commit/publish。
- **Outbox**：QQ 外发三态与人工可观测重试。
- **Visibility 服务**：所有 prompt 与工具结果的前置过滤器。

### 4.3 为什么不让中央 Agent 直接写 SQLite/调用发 QQ 工具

- 模型输出非确定、可被玩家 prompt injection 影响；
- 直接写会绕过世界版本、实体引用、可见性和事务；
- 直接发 QQ 会绕过限速/脱敏/幂等/玩家可见性。
- 因此模型只能通过工具“提交提案”，宿主验证后一次性 `commit`。

## 5. 外部 MCP 调查

### 5.1 可用性

- AgentLib `AgentToMcpServer` 基于官方 MCP Java SDK 2.0.1，支持 stdio 与 JDK HTTP Streamable HTTP。
- 可把独立 ToolRegistry 暴露出去，并用 `include` 过滤；工具调用可走 `ToolCallAuthorizer`。
- 对外部控制面来说，这正好可作为 DSH 的 MCP 入口。

### 5.2 主要风险

- **鉴权**：server 启动时绑定固定 caller 身份；没有 HTTP 请求级 bearer → caller 解析。
- **工具面膨胀**：如果复用一个全量 registry，会把 QQ 发送/文件/Agent 控制工具暴露出去。
- **模型误操作**：外部 Agent 可能误判状态并重试；必须 `expectedRevision + idempotencyKey + reason`。
- **隐私**：事件流里有私聊/决策；外部观察者默认只应拿摘要和 hash。

### 5.3 推荐 v1

- 默认关闭；只绑 `127.0.0.1`。
- 独立 ToolRegistry + 独立 AgentToMcpServer + 精确 allow-list。
- 只读工具：`sys_status`、`turn_list/turn_get`、`event_poll`、`invariant_check`、`outbox_status`、`agent_health`。
- 受控写工具（Q4=B）：`turn_open`、`turn_lock`、`turn_retry_adjudication`、`turn_publish`、`turn_pause/resume`；全部带幂等键。
- 不提供 `decision_*`、`qq_send_*`、`world_fact_set`、`player_role_set`、`process_stop`。
- 远程/公网鉴权另立设计；不靠“隐藏 URL”当安全。

## 6. 旧 iBotMosire 参考实现调查

### 6.1 可参考内容

- `OneBotConnection`：echo 关联、pending future、断线 failAll、指数退避、心跳。
- `OneBotEvent`：message/message_sent、plainText、conversationKey。
- `BoundedMessageBuffer`、`SendRateLimiter`、`AccountRegistry`、`MockNapCatServer` 测试。
- 账号保留思想（client 账号不允许 Agent 连接），对本项目“角色不可提权”有参考价值。

### 6.2 不应继承内容

- 它的核心是 MCP 工具化 QQ + 通用外部 Agent 会话，不是回合制世界引擎。
- 进程拓扑与“Agent 可操作服务器/QQ”的权限模型会扩大攻击面。
- 其配置、JAR 与运行时数据不得复制进本仓。

## 7. 文档体系调查

采用 `SimulatorMosire` 2026-10-23 最新结构：

- 根 `AGENTS.md` 保持薄索引；
- `docs/AGENTS.md` 规定设计/维护分账；
- `docs/agents/` 放维护手册与唯一 STATUS；
- `docs/superpowers/` 放设计、计划、报告、历史 status；
- `.agents/skills/` 只放经过 pilot 的 active Skill；
- `tools/check-docs.py`、`skills-lint.py`、`gate.sh` 做机械检查；
- 当前 pre-Maven 阶段，检查器不要求 `pom.xml`/模块 AGENTS 存在。

## 8. 需求解释中的关键空白

1. **私聊还是群聊**：群聊会引入多人同场可见性、@ 触发、群级权限、发言顺序和广播，不是小功能。
2. **中央 Agent 自动提交还是推演签核**：直接决定“中央 Agent 权威”和人工操作位置。
3. **决策格式**：固定标签卡 vs JSON vs 自然语言，直接决定解析复杂度与玩家体验。
4. **外部 MCP 写权限**：只读与可推进是不同风险级别；公网还需认证设计。
5. **LLM provider**：tool calling、reasoning_content、JSON 稳定性、上下文窗口都会影响可行性。
6. **单/多世界**：影响 ID 分区、事件回放、MCP 工具参数。
7. **“三库”具体所指**：需确认是发布三件套还是 Brain/Main 也包含。

这些问题已写入 [`specs/2026-10-24-requirements-baseline.md`](../specs/2026-10-24-requirements-baseline.md) §7。

## 9. 建议的下一步

1. 用户回答 Q1–Q7，更新需求基线/专题设计。
2. 以 P0 建 Maven reactor 并验证三库可解析、可编译。
3. P1 先做无 QQ/无真 LLM 的纯状态机纵向切片：Fake QQ + Fake LLM + SQLite 事件回放。
4. P2 接 NapCat Adapter 与 MockNapCat；P3 接真实私聊小号。
5. P4 做玩家 Agent；P5 做中央推演；P6 做 MCP 控制面；P7 做真模型/长跑/崩溃恢复。

## 10. 本轮实际执行与证据

| 检查 | 结果 |
|---|---|
| 阅读 ProjectMosire 三库源码/POM/接入文档 | 已完成（只读） |
| 阅读 NapCat/OneBot 官方文档 | 已完成（网页读取） |
| 阅读旧 iBotMosire 关键适配器/测试 | 已完成（只读） |
| 建立文档体系草稿 | 已完成初稿 |
| 文档/Skill 机械检查 | **PASS**（仅结构）：`DOCS CHECK PASSED (phase=pre-maven modules=0 files=30 links=62)`；check-docs selftest 11/11；skills-lint active=0、selftest 6/6；`tools/gate.sh docs` PASS |
| Maven 构建 | **NOT_RUN**（无 pom） |
| 真实 NapCat | **NOT_RUN** |
| 真实 LLM | **NOT_RUN** |
| MCP E2E | **NOT_RUN** |

## 11. 外部来源

- ProjectMosire 发布页：<https://c1589190.github.io/ProjectMosire/>
- NapCat 网络基础：<https://doc.napneko.icu/onebot/network>
- NapCat WebUI/网络配置：<https://doc.napneko.icu/config/basic>
- NapCat WebSocket：<https://mintlify.wiki/NapNeko/NapCatQQ/api/network/websocket>
- NapCat HTTP API：<https://mintlify.wiki/NapNeko/NapCatQQ/api/network/http>
- NapCat OneBot 11 概览：<https://mintlify.wiki/NapNeko/NapCatQQ/api/onebot/overview>
- NapCat Message Actions：<https://mintlify.wiki/NapNeko/NapCatQQ/api/onebot/message-actions>
- NapCat Message Events：<https://mintlify.wiki/NapNeko/NapCatQQ/api/events/message-events>
- OneBot 11 仓库：<https://github.com/botuniverse/onebot-11>
- MCP Java SDK 文档：<https://java.sdk.modelcontextprotocol.io/latest/>
- MCP 2.0 迁移说明：<https://github.com/modelcontextprotocol/java-sdk/blob/main/MIGRATION-2.0.md>

> 外部网页是可变的；本报告记录的是 2026-10-24 读取到的内容。实现前若 NapCat 版本或 MCP SDK 版本有变化，必须重新核实。
