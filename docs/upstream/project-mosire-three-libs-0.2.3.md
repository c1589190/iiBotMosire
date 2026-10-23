# ProjectMosire 三库消费契约（0.2.3，调研稿）

> **状态**：调查结论（源码/发布页核实，未在本项目编译）
> **日期**：2026-10-24
> **上游**：`~/ProjectMosire`，HEAD `79b349fb`，release tag `v0.2.3`
> **发布页**：<https://c1589190.github.io/ProjectMosire/>
> **发布坐标**：`io.mosire:agentlib-mosire:0.2.3`、`workspace-mosire:0.2.3`、`docs-mosire:0.2.3`

## 1. 结论

本项目 v1 采用发布页实际发布的**三库**：

```text
docs-mosire ──→ workspace-mosire ──→ agentlib-mosire
```

- `agentlib-mosire`：LLM 客户端/路由、AgentLoop、工具接口与注册表、权限/审批、SQLite 事件存储、MCP server/client、子进程。
- `workspace-mosire`：虚拟挂载、路径解析、能力/资源权限、4 个 `workspace_*`/`mount_*` 工具。
- `docs-mosire`：LOG/FILE 文档存储、DocsApi、可见性、8 个 `doc_*` 工具。

不采用 `brain-mosire` / `main-mosire` 作为 v1 依赖：它们是完整 Agent 运行时/入口装配，且不随 Pages 三库发布；
本项目需要自己定义回合、世界、QQ 与 MCP 控制面。若后续发现三库缺能力，必须另立设计裁定，不能静默扩大依赖。

## 2. Maven 坐标与仓库

```xml
<repositories>
  <repository>
    <id>mosire-pages</id>
    <url>https://c1589190.github.io/ProjectMosire/</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>

<dependency>
  <groupId>io.mosire</groupId>
  <artifactId>agentlib-mosire</artifactId>
  <version>0.2.3</version>
</dependency>
<dependency>
  <groupId>io.mosire</groupId>
  <artifactId>workspace-mosire</artifactId>
  <version>0.2.3</version>
</dependency>
<dependency>
  <groupId>io.mosire</groupId>
  <artifactId>docs-mosire</artifactId>
  <version>0.2.3</version>
</dependency>
```

- 父 POM 在 Pages 同仓 `io.mosire:mosire-parent:0.2.3`。
- 上游 POM 版本：`~/ProjectMosire/pom.xml:9`；MCP SDK 2.0.1、Jackson 2.22.2、SQLite JDBC 3.53.4.0 见同文件 `:57`、`:59`、`:63`。
- 使用固定发布版，不使用 `0.2.3-SNAPSHOT` 作为可复现依赖。
- **未验证**：本项目尚未创建 Maven 工程，未执行依赖解析/编译。上表是发布页与上游源码核实结果。

## 3. 本仓会优先复用的能力

### 3.1 AgentLoop（通用循环骨架）

- `AgentLoop.runTurn(List<LlmMessage>, List<AgentTool>)`：一次用户回合的「组请求 → LLM → 工具分发 → 终止判定」循环，
  见 `~/ProjectMosire/AgentLibMosire/src/main/java/io/mosire/agentlib/runtime/AgentLoop.java:113`。
- 可插拔 `StopPolicy`、`TurnListener`、`ToolCallDispatcher`；骨架不落库、不发事件、不压缩、不做权限。
- 终止回调 exactly-once，宿主必须在 `onFinished` / `onStop` / `onToolCallLimit` / `onLlmError` 中完成事件/历史落地，
  见 `AgentLoop.java:42`。
- **线程约束**：不做同步、窗口非线程安全、同一实例必须串行，见 `AgentLoop.java:67`。
- 对本项目：每个玩家会话一个串行 mailbox；中央推演单飞；不能把同一 `ContextWindow` 同时交给两个线程。

### 3.2 LLM 客户端

- `LlmClient.chat(LlmRequest)` 同步返回 `LlmResponse`；OpenAI 兼容流式实现为 `OpenAICompatibleLlmClient`。
- `LlmRequest` 包含 messages/tools/sampling；`LlmResponse` 包含 assistant message、模型、token、reasoning 元数据。
- `LlmRouteLoader` / `LlmRouteAssembler` 可从 AgentLib 配置读取命名路由并构造 client。
- 本项目的玩家 Agent 与中央 Agent可使用不同 route；必须记录实际模型与耗时到脱敏事件。
- 需要单独验证 reasoning_content 回传、tool calling、超时与错误分类。

### 3.3 工具、权限与唯一执行入口

- `AgentTool` 是工具唯一接口：`name/description/jsonSchema/spec/roles/resources/execute`，
  见 `~/ProjectMosire/AgentLibMosire/src/main/java/io/mosire/agentlib/tool/AgentTool.java:22`。
- `ToolRegistry` 支持注册、按 source 注册/注销、动态变更监听；见 `ToolRegistry.java:56` 起。
- `ToolCallAuthorizer` 是工具调用唯一入口：工具存在性 → 硬拒 → 资源判定 → 命令闸/审批 → 执行，
  见 `ToolCallAuthorizer.java:20` 起。
- **强制**：内部 AGUI/玩家工具调用、MCP 工具调用都经同一个 authorizer；不得直接 `tool.execute(context)`。
- 资源面需要工具声明 `ResourceManifest`，调用者权限面需提供对应 `ResourceScope`；未声明/未表态默认 fail-closed。
- 角色维 `AgentTool.roles()` 空集 = 不构成约束；调用者侧空集 = fail-closed，两者语义相反。

### 3.4 Workspace 工作区

- `DefaultWorkspaceRegistry.create(catalog)`、`mount`、`resolve`、`resolveForIo`、`list`、`grant` 等。
- 装配时必须显式声明 `workspaces` / `docs` / `docs-edit` 等资源命名空间；不表态 = 很多操作 fail-closed。
- `WorkspaceTools.all(registry)` 实现 4 个工具；`DocsTools.all(registry)` 实现 8 个工具（上游文档曾把数量写作 3/7，已由 2026-10-17 对接须知更正）。
- 本项目可用 Workspace/Docs 保存世界设定、叙事日志、每玩家可见笔记；但**回合状态/决策/世界事实仍以事件流权威**，
  文件系统不是事务边界。

### 3.5 Docs 文档

- `DocsApi` 提供 append/void/find/read/query/list/readFile/createFile/editFile 等；逐次 IO 经 Workspace `resolveForIo` 与软链拒绝。
- `DocVisibility` 的可见性由 owner/tags/affiliations/GM 判定；GM 为 SYSTEM 或 SYSTEM token。
- 不能用 Docs 的可见性替代本项目的世界事实过滤：玩家 Agent 的过滤必须在 prompt 组装前完成，Docs 读写只是存储实现。
- `DocsTools.all()` 的写工具（`doc_append`/`doc_create`/`doc_edit`）是破坏性操作，需显式 `destructiveAllowed(true)`；
  不直接暴露给玩家 Agent。

### 3.6 事件存储

- `EventStore`：append/query/queryLatestByCorrelation/bySeq/count/close，
  见 `~/ProjectMosire/AgentLibMosire/src/main/java/io/mosire/agentlib/event/EventStore.java:11`。
- `Event`/`EventWrite` 字段：type、agent、payload、correlationId，存储补 seq/ts；
  见同一目录 `Event.java:12`、`EventWrite.java:6`。
- `SqliteEventStore.open(Path)` 使用 SQLite WAL 与 busy timeout，见 `SqliteEventStore.java:95`、`:216`。
- **边界**：`EventStore` 不提供跨域事务、唯一幂等键、快照或投影注册；本项目需要自己的投影/幂等层与“单 writer”串行化。
- **不可假设**：`append` 外副作用与发送 QQ 消息之间没有原子协议；外发必须走 outbox + 未确定态。

### 3.7 MCP server

- `AgentToMcpServer.startHttp(...)` 可把 `ToolRegistry` 暴露为 Streamable HTTP MCP server；
  需要先建好 MCP SDK server/provider，再注册 context。
- 暴露面可用 `include` 过滤；工具调用经 authorizer；`ToolSpec.noExport()` 永不外发。
- **安全缺口**：HTTP 入口的调用者身份由建链时固定，未提供逐请求 Bearer token 解析；
  因此本项目外部 MCP 只能默认关闭/回环，并另立鉴权方案才能公网或多用户化。
- `McpToolSource` / `McpSourceBridge` 可将外部 MCP server 工具映射进本地 ToolRegistry，但本项目 v1 不需要把外部 MCP 客户端能力交给玩家 Agent。

## 4. 三库的已踩坑与消费约束

来源：`~/ProjectMosire/接入指南-三个库给消费方.md`、`对接须知-三个库-2026-10-17.md`、本仓实测前的研究。

1. **权限面显式表态**：`workspaces` / `docs` / `docs-edit` 不声明就可能全部 fail-closed。
2. **破坏性写工具需显式放行**：`destructiveAllowed(true)`，否则 `doc_create/doc_edit` 返回 `PERMISSION_DENIED`。
3. **资源 scope 必填**：否则 `RESOURCE_DENIED` 或 “工作区不可见”。
4. **调用者身份必须等于挂载 user**：`ToolContext.identity().instanceId()` 与挂载 user 不一致会 `WORKSPACE_NOT_FOUND`。
5. **`DocsApi.list` 只服务 LOG 纪律**；FILE 直接子项列举要用 `WorkspaceRegistry.list`。
6. **0.2.0 源级不兼容**：`AgentTool` 新增 `roles()` 与既有消费方同名方法冲突；本项目从 0.2.3 全新接入，不继承该旧签名。
7. **Spring/PF4J/MCP SDK transitive 依赖**：注意 classpath 与 Maven enforcer；不要混用 Jackson 3 线。
8. **上游文档数字可能滞后**：工具数量以 `WorkspaceTools.all()` / `DocsTools.all()` 的源码为准；本仓以 0.2.3 源码为准。

## 5. 推荐消费方式

| 需求 | 用法 | 理由 |
|---|---|---|
| LLM 调用 | AgentLib `LlmClient` + route assembler | 不复制 OpenAI 协议/流式解析 |
| Agent 循环 | `AgentLoop` + 自定义 composer/dispatcher/listener | 复用循环骨架，保留宿主最终控制 |
| 工具 | `AgentTool` + `ToolRegistry` + `ToolCallAuthorizer` | 统一权限与调用入口 |
| 事件 | `SqliteEventStore` 作底层 append/query，上面加事件信封/投影 | 复用持久化，但领域语义归本仓 |
| 世界/文档文件 | Workspace + Docs | 复用路径安全与可见性基础 |
| 外部控制 MCP | AgentLib `AgentToMcpServer` + 独立 registry/authorizer | 复用 MCP SDK 适配，但工具面由本仓收窄 |
| 决策包（`io.mosire.agentlib.decision`） | **v1 不使用** | 它是“工具调用审批/裁决包”协议，不是叙事回合决策；强行复用会混淆两种决策语义 |

## 6. 待验证清单

- [ ] 新建 Maven 工程可从 Pages 解析全部三库 0.2.3。
- [ ] JDK 21 编译 + AgentLib `AgentLoop`/工具/事件的最小 smoke。
- [ ] MCP Streamable HTTP server 与 DSH/标准 MCP client 握手。
- [ ] Docs/Workspace 权限装配的最小成功与 fail-closed 用例。
- [ ] 各上游库在本项目的 actual transitive dependency tree 与许可证清单。

> 这些全部是 `NOT_RUN`；本文件不能作为构建成功证据。
