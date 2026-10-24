# 理想 GM 控制工作流实施计划（2026-10-24）

> **状态**：计划有效；P0（v3 行动池/裁定包）与 P1（events.db 完整内容基础）已提交；P2（现有领域事件接入与 JSON 迁移）已完成并提交 `0fbceaa`；P3（控制初始化 + GM 私有文档）已提交 `615595d`；P4（统一工单/消息板）已提交 `5fb5d8c`；P5（ACTION_DRAFT→ActionPool→DecisionPackages、包/批次恢复）已提交 `06d9e1a`；P6（发布编排、回合 Markdown 投影、记录查询）已完成实现/测试/多轮阶段评审/full gate，提交 `d3a9c91`；P7 用户裁定跳过；下一步 P8 真实模拟。
> **性质**：计划不是设计；事件 schema、数据所有权、工具语义以
> [`../specs/2026-10-24-ideal-gm-control-workflow-and-events-db.md`](../specs/2026-10-24-ideal-gm-control-workflow-and-events-db.md) 为准。
> **当前事实**：以 [`../../agents/STATUS.md`](../../agents/STATUS.md) 为准。本文件里的测试数/工期是预测，不是实测。

## 0. 执行原则

1. 先做 `events.db` 单一事实源，再做工单/发布编排；不允许先做新功能再补两份存储。
2. 旧 JSON 只做迁移输入/恢复缓存，不做长期第二事实源。
3. 每个阶段独立门禁；未通过不得进入下一阶段。
4. 兼容旧 Turn/DecisionDraft 路径，但新行动一律走 ActionPool/DecisionPackages。
5. 真实 QQ/LLM 只在隔离测试账号、用户明确协同下运行；不发未授权消息。
6. 不将 API key/token/真实 QQ 标识写入事件、日志、文档或 Git。

## 1. 当前基线

| 项 | 事实 |
|---|---|
| 远程 HEAD | `16a2499` 共享 Skill + 投诉闭环 |
| 工作树 | v3 ActionPool/DecisionPackages/MCP 48 工具未提交 |
| 应用进程 | **已停止**（不监听 8765） |
| v3 子集测试 | taskflow 192、app 132，曾通过；**未跑最终 full gate** |
| 旧持久化 | `events.db` 仅审计摘要；`taskflow-state.json`、`posts.json`、`complaints.json`；v3 action/packages/batches 仍在内存 |
| 未完成 | JSON/DB 持久化、Markdown 回合投影、控制初始化、GM 私有文档、统一工单、发布编排、事件回放 |

## 2. 阶段与依赖

```text
P0 稳定 v3 工作树
  ↓
P1 events.db 完整内容基础
  ↓
P2 领域事件全量接入 + JSON 迁移
  ↓
P3 控制初始化 + GM 私有文档
  ↓
P4 统一工单/消息板
  ↓
P5 行动工单 → ActionPool → DecisionPackages
  ↓
P6 发布编排 + Markdown 回合投影 + 查询工具
  ↓
P7 事件回放/重建/迁移对账
  ↓
P8 真实 MCP/LLM/多玩家/发布回归与发布
```

## 3. 阶段明细

### P0 稳定 v3 工作树

**目标**：把 v3-A/A2 的行动池、裁定包、48 工具变成一个可验证基线。

**产出**

- 停服后跑 `tools/gate.sh full`；
- 修复 v3 工作树中 app/taskflow 测试的任何回归；
- 更新 STATUS（48 工具、测试数、未提交状态）；
- 形成一次独立提交（建议信息：`feat: 接入行动池与 AgentLib 裁定包`）。

**验收**

- `tools/gate.sh full` PASS；
- 48 工具 loopback MCP 测试通过；
- 没有新增未使用的死代码/临时脚本；
- 真实 QQ 不发送消息。

**停止条件**

- full gate 不通过或 action pool/package 测试不可复现 ⇒ 不进入 P1。

### P1 events.db 完整内容基础

**状态**：已完成实现/测试/阶段评审/full gate（398 tests）；提交 `a52626f`。

**目标**：建立完整事件内容 schema、追加端口、内容 hash、schema 版本和迁移框架。

**责任区**

- `iibot-app/src/main/java/io/mosire/iibot/app/event/**`
- 新增 `iibot-app/src/main/java/io/mosire/iibot/app/event/content/**`
- `iibot-app` 测试
- `iibot-taskflow/src/main/java/io/mosire/iibot/taskflow/event/**`（只新增内容端口/类型，不改旧事件语义）

**产出**

- `events` 元数据表 + `event_contents` 正文表；
- `EventContentStore`/`EventAppendPort`；
- `ContentType`、`Visibility`、`content_hash`；
- `event_version`/旧事件迁移标记；
- 严格 schema、大小上限、敏感字段校验；
- 事件内容写入重试/失败语义：DB 失败则领域操作不前进。

**验收**

- 新事件 round-trip：header + content 可完整读取；
- hash 不一致 fail-closed；
- 旧 `events` 只读兼容；
- DB 文件权限 600，内容不含 token/API key；
- 不破坏现有 48 工具/旧回合测试。

**停止条件**

- 无法保证事件写成功与领域回执原子/因果一致 ⇒ 不接业务写入。

### P2 领域事件全量接入 + JSON 迁移

**状态**：已完成实现/测试/最终阶段评审/full gate（413 tests）；DecisionPackage/Batch 事件推迟到 P5；Ticket/GM 文档在 P3/P4；提交 `0fbceaa`。

**目标**：让回合、行动、推文、投诉以及完整 Taskflow 快照写入 events.db；旧 JSON 安全迁移。

**责任区**

- `iibot-taskflow`：ActionPoolService、PostService、ComplaintService 的事件端口；
- `iibot-app`：AppBootstrap、afterMutation、旧 JSON 迁移器。

**产出**

- 事件类型补齐：ACTION_*、POST_*、COMPLAINT_*、TASKFLOW_SNAPSHOT；PACKAGE_*/BATCH_* 作为 P5 预留，TICKET_*/CONTROL_*/GM_DOC_* 作为后续阶段预留；
- 正文写入 `event_contents`，`payload_json` 只放索引/状态/摘要；
- 旧 `posts.json`、`complaints.json`、`taskflow-state.json` 转为迁移输入/可选缓存；
- 迁移工具：`legacy -> events.db`；迁移快照事件使用当前 `event_version`，并以 exact `migrated` + `reconciled` 两阶段标记登记完成；
- 对账报告：记录数、hash、seq、状态。

**验收**

- 新建/审批/发布一条推文可只从 events.db 重建；
- 新建/回应一条投诉可只从 events.db 重建；
- TODO(P5): 行动包/批次从 events.db 查询和重建（P2 不宣称）；
- TASKFLOW_SNAPSHOT 可读回当前完整 Taskflow 快照（P7 前仍以 taskflow-state.json 为恢复缓存）；
- 旧 JSON 删除/降级前，迁移对账 0 差异；
- 迁移失败不删除旧文件。

**停止条件**

- 旧数据无法对账 ⇒ 保留旧数据只读，停止删除动作，报告 blocker。

### P3 控制初始化 + GM 私有文档

**状态**：已完成实现/测试/阶段评审/full gate（429 tests）；提交 `615595d`。

**目标**：DSH/Hermes 通过 MCP 动态初始化世界、公开群、玩家 QQ、秘书绑定和 GM 私有文档。

**责任区**

- `iibot-taskflow`：ControlBootstrapService、GmPrivateDocs；
- `iibot-app`：ControlMcpServer、AppBootstrap。

**产出**

- MCP：
  `tf_control_initialize`、`tf_control_set_public_group`、
  `tf_control_register_player`、`tf_control_register_director`、
  `tf_control_bind_secretary`、`tf_control_load_world`、
  `tf_control_load_gm_doc`；
- GM 私有 Workspace/Docs：`dataDir/gm-private/{world,plot,settings,notes}`；
- GM 文档 MCP：`tf_gm_doc_list/get/write`；
- 动态创建 SecretarySession + QQ 映射 + workspace。

**验收**

- 新玩家 QQ 注册后，私聊可进入对应秘书；
- 秘书/玩家不可读 gm-private；
- 初始化幂等；非法 QQ/群/路径 fail-closed；
- 重启后控制状态可恢复/可重建。

**停止条件**

- 动态秘书创建会破坏旧配置装配或恢复语义 ⇒ 先加适配层，不绕过身份映射。

### P4 统一工单/消息板

**状态**：已完成实现/测试/阶段评审/full gate（446 tests）；提交 `5fb5d8c`。

**目标**：把设定质询、行动草稿、投诉、GM 备注统一到 Ticket 模型。

**责任区**

- `iibot-taskflow/src/main/java/io/mosire/iibot/taskflow/ticket/**`
- `iibot-app/src/main/java/io/mosire/iibot/app/mcp/**`
- 秘书 Agent 工具

**产出**

- Ticket 类型/状态：SETTING_QUERY、ACTION_DRAFT、COMPLAINT、GM_NOTE；OPEN/CLAIMED/RESOLVED/CLOSED；
- 秘书工具：submit/list/read；
- GM MCP：board/list/get/claim/reply/batch_reply/close；
- 按 ticket 归属过滤；批量回复；不要求 GM 立即处理；
- 工单正文/回复全部写入 events.db。

**验收**

- 秘书可在无回合、冻结、OPEN、ADJUDICATING 任意状态提交工单；
- A 玩家的工单对 B 秘书不可见；
- GM 可批量回复/claim；重复操作幂等；
- TODO(P5): 行动工单升级到 ActionPool；P4 只存储/展示 ACTION_DRAFT，设定/投诉/GM_NOTE 只回复，不进入包。

**停止条件**

- 工单状态与 ActionPool/DecisionPackage 出现双状态真相 ⇒ 停工修状态所有权。

### P5 行动工单 → ActionPool → DecisionPackages

**状态**：已完成实现/测试/五轮阶段评审/full gate（470 tests）；提交 `06d9e1a`。

**目标**：把现有 v3 行动池/裁定包嵌入工单流程，并取消每回合一条硬限制。

**责任区**

- `ActionPoolService`、`ActionPackageService`、TicketService、SecretarySession。

**产出**

- ACTION_DRAFT ticket 接受/claim 后生成或关联 PlayerAction；
- GM 选择工单生成 Package；包内 item 指向 action/ticket；
- 包 PROPOSED 可 append/replace/remove；首次 itemDecided 冻结；
- 保留旧 DecisionDraft 兼容路径，但默认新流程走 ActionPool；
- 删除/禁用 `DECISION_ALREADY_CONFIRMED` 旧硬限制作为新玩家路径。

**验收**

- 同一玩家一个包内可有多条行动；
- 包 commit 产生 ActionBatch；同回合/跨回合可多批次；DecisionPackage/Batch 完整事件与恢复适配在本阶段补齐；
- GM 不处理全部工单也能开下一回合；
- 未批准行动不执行；
- 旧数据仍可读取。

**停止条件**

- 旧 Turn/DecisionDraft 与新包同时修改同一行动 ⇒ 必须选定唯一 owner。

### P6 发布编排 + Markdown 回合投影 + 查询工具

**状态**：已完成实现/测试/多轮阶段评审/full gate（504 tests）；提交 `d3a9c91`。

**目标**：GM 选择已 commit 批次出推文，生成标准 Markdown 记录；状态从 events.db 查询。

**责任区**

- `iibot-app/src/main/java/io/mosire/iibot/app/publication/**`
- `iibot-app/src/main/java/io/mosire/iibot/app/records/**`
- `iibot-app/src/main/java/io/mosire/iibot/app/mcp/**`

**产出**

- PublicationOrchestrator：ActionBatch → LLM 推演 → Public/PrivatePostDraft；
- GM 审核/发布；Public→群、Private→玩家；
- 秘书只能读取已发布可见推文；
- `TurnRecordProjector`：`records/turns/<turnNo>-<turnId>/*.md`；
- 查询 MCP：`tf_record_turn_list/get`、`tf_record_query`；
- 推文标准文档：每篇 post 一个 Markdown，元数据/正文/hash 从 events.db 投影。

**验收**

- 同一回合多个批次结果都能投影；
- 公共/私密正文不混淆，秘书看不到他人私密；
- 投影幂等、可重建，失败不回滚事件；
- GM 审核前不发送 QQ；
- Markdown 与 events.db 可对账。

**停止条件**

- 投影/发布时出现“DB 与 Markdown 两份正文不一致” ⇒ 只允许 DB canonical，停止手工写 Markdown。

### P7 事件回放/重建/迁移对账

**状态**：用户 2026-10-24 明确裁定跳过；P6 完成后直接进入 P8 真实模拟。保留本节作为后续可选项。

**目标**：从 events.db 重建 current status、回合、行动池、包、批次、推文、投诉和工单；移除旧 JSON canonical 依赖。

**产出**

- EventProjector / RebuildService；
- 恢复校验：seq、content hash、schema、visibility；
- 旧 snapshot/JSON 只读归档；
- 删除“仅靠 taskflow-state.json 恢复”的默认路径（保留可配置降级）。

**验收**

- 空缓存 + events.db 可重建当前领域；
- 损坏/缺内容 fail-closed；
- 旧数据库迁移后对账通过；
- 真实重启 smoke。

**停止条件**

- 事件回放不能覆盖所有领域 ⇒ 不宣称 events.db 单一事实源完成。

### P8 真实回归与发布

**目标**：真实 MCP/LLM/QQ 多玩家验证并推送。

**验收**

- DSH/Hermes 真实 MCP：初始化→工单→行动包→裁决→推文；
- 至少 2 名玩家、多个工单、多个包/批次；
- 公共/私密推文分发正确；
- 重启后 events.db 恢复；
- 全量门禁、文档、STATUS、Git 提交/推送；
- 未跑项标 `NOT_RUN`。

## 4. 工作量估算

| 范围 | 预估 |
|---|---|
| P0 | 0.5–1 天，少量回归修复 |
| P1 | 2–3 天，schema/内容端口/迁移基础 |
| P2 | 3–5 天，跨领域事件接入与迁移 |
| P3 | 2–3 天，控制初始化/GM 文档 |
| P4 | 3–5 天，Ticket 领域 + 秘书/GM 工具 |
| P5 | 2–4 天，行动工单→包/批次 |
| P6 | 3–5 天，发布编排 + Markdown 投影 |
| P7 | 3–5 天，回放/重建/对账 |
| P8 | 2–4 天，真实联调与发布 |

预计新增/修改：

- 20–30 个生产文件；
- 20+ 个 MCP 工具；
- 6–8 个秘书/玩家工具；
- 50–100 个测试；
- 2 次 schema/数据迁移。

## 5. 测试与门禁

每个阶段：

```bash
tools/gate.sh docs
tools/gate.sh focused
tools/gate.sh full
```

真实外部：

```text
真实 DSH/Hermes MCP
真实 LLM
真实 NapCat/QQ 隔离账号
重启/迁移/回放
```

不在普通 JUnit 门禁中跑真实 QQ/LLM；测试用 FakeLLM/FakeAdapter + loopback MCP。

## 6. 风险与缓解

| 风险 | 缓解 |
|---|---|
| events.db 变超大/正文膨胀 | event_contents 分表、内容上限、归档/压缩策略 |
| 旧 JSON 迁移不一致 | 只读保留、hash 对账、迁移报告 |
| AgentLib DecisionPackages 重启难恢复 | P5 做包持久化/批次事务事件；P7 做重建 |
| 工单/行动/包三套状态冲突 | Ticket→Action→Package 单向所有权 |
| 私密内容泄露 | visibility 在事件写入和查询双侧校验 |
| 外部控制方远程接入 | SSH 隧道或 TLS 代理；不做公网裸 MCP |
| 多批次推文重复/UNKNOWN | 单 post/批次幂等键；UNKNOWN 不重发；events 记录 |
| 旧 Turn 与新包并存 | 旧路径只兼容，新路径唯一 owner |

## 7. 停止条件与回滚

- P1 未完成事件内容原子性 ⇒ 不开始 P2；
- P2 迁移对账失败 ⇒ 不删除旧 JSON；
- P3 动态秘书创建破坏恢复 ⇒ 只加适配层；
- P4/P5 状态所有权冲突 ⇒ 停止新功能，先定唯一 owner；
- P6 投影与 DB 不一致 ⇒ 只允许 DB canonical；
- P7 回放不完整 ⇒ 保留 `taskflow-state.json` 降级路径；
- 无真实凭据/用户协同 ⇒ P8 保持 `WAITING_USER/NOT_RUN`。

## 8. 第一条可执行命令

当前先做 P0：

```bash
# 停服已确认后
tools/gate.sh full
```

P0 通过后再开始 P1：新增 `event_contents` schema 与 `EventContentStore`，不要先写工单或发布编排。
