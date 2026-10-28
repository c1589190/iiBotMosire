# 统一 QQ Agent 客户端改造计划

> **状态**：提议（2026-10-28）
> **设计依据**：
> - [统一 QQ Agent 客户端与消息板目标架构](../specs/2026-10-28-unified-qq-agent-client-architecture.md)
> - [日志分级与诊断](../specs/2026-10-28-logging-levels-and-diagnostics.md)
> - [配置文件系统](../specs/2026-10-28-configuration-system.md)
> - [用户需求原稿 D-025..D-028](../specs/2026-10-24-user-requirements-source.md)
>
> **当前执行状态**：P0–P7b 已完成并合并；只剩真实外部验收（需用户授权）与 AgentLib 0.2.4 公开发布跟进。
> **禁止事项**：未经用户当次指令不启动真实 QQ、不挂载 Bot MCP、不执行真实外发、不 commit/push。

## 1. 目标

把 iiBotMosire 收敛成一个面向 Agent 的统一 QQ 消息客户端：

```text
OneBot/NapCat/LLBot（可多个）
  → iibot-connect（协议适配）
  → iibot-connect-agent（通用 QQ AgentTool）
  → iibot-bot（消息板/读游标/发送三态/统一 MCP 语义）
  → iibot-app（多账号运行时、Embedded Inbox、配置、日志）
  → 外部 Agent（DSH/OpenCode/其他 MCP 客户端）
```

完成标准：

1. 默认构建和默认 jar 不再包含文游模块、文游 MCP 工具和旧控制面；
2. 同一 iibot 进程可管理多个 OneBot/QQ 账号，消息和出站按账号路由；
3. `iibot-connect-agent` 的通用 QQ 工具进入统一 MCP 工具目录，不再有平行发送路径；
4. Agent 不需要维护 `expectedRevision`、`snapshotToSeq`、`itemId` 或必填 `idempotencyKey`；消息板服务端维护这些状态；
5. 日志具备稳定 INFO/DEBUG、结构化运维事件、关联 ID、脱敏和可配置轮转；
6. 配置文件系统严格、可解释、可检查、可脱敏输出，支持多账号；
7. 文档与实现同步，旧文游文档从当前入口移除；
8. 所有验收有当前源码的真实命令和证据；未跑项写 `NOT_RUN`。

## 2. 先决条件与停止条件

### 2.1 开工前

- 当前工作树 dirty（D-023/D-024 等未提交改动）。用户已要求在**新分支**上按责任区
  分批编码、提交并推送保存状态；正式合并前必须确认全量门禁无恙。
  - 先创建 `refactor/unified-qq-agent-client` 分支；
  - 先将既有 bot-first/D-023/D-024 运行时代码作为独立 checkpoint 提交（不与后续 P1 混提）；
  - 每完成一个责任区：跑对应测试 → 更新文档/STATUS → 只 stage 本责任区文件 → commit → push；
  - 不使用 `git add -A`，不把其他责任区的 dirty 文件混入提交。
- 用户已裁定**继续使用 `agentlib-mosire:0.2.4`**，不降级。
  - 上游 0.2.4 尚未公开 tag/Pages 发布，因此干净 Maven 仓库可复现性仍是已知发布前置问题；
  - 本地开发/验证可继续使用已安装的 0.2.4 源码构件；
  - P7 最终门禁必须记录该版本的实际来源；若上游仍未发布，干净仓库验证只能标
    `BLOCKED` / `NOT_RUN`，不得用本机 `~/.m2` 冒充可公开复现。
- 任何真实 QQ/DSH/OneBot 连接、外部发送必须按 M-004 等用户当次明确指令；本计划只要求本地 fake/sqlite/临时目录测试。
- 同一 checkout 不并发跑 Maven；不覆盖运行中的 jar；不删除运行目录中的 token/descriptor 证据。

### 2.2 立即停止条件

- 需要删除/改写用户未确认的当前设计裁定；
- 需要真实 QQ 外发或真实账号登录；
- 需要把 AgentLib 从用户已冻结的 0.2.4 降级；
- 旧模块删除会影响当前未提交的用户改动；
- 发现需要改变 Inbox 公共契约或 DSH/OpenCode 外部契约，而本计划未覆盖。

## 3. 阶段总览

| 阶段 | 责任区 | 结果 |
|---|---|---|
| P0 | 设计与依赖前置 | 文档有效、AgentLib 版本策略确定、工作树策略确定 |
| P1 | 文游清理与模块收缩 | 默认 reactor 只剩 QQ 客户端模块 |
| P2 | 日志基础设施 | 分级、结构化、脱敏、配置化、诊断命令基础 |
| P3 | 配置系统 v2 | 多账号 schema、严格校验、有效配置输出、模板 |
| P4 | 多账号 Adapter 与路由 | AccountRegistry、入站归属、出站按账号选择 |
| P5 | 消息板游标与 Inbox 自管理 | consumer 游标、自动已读、Inbox 自动同步 |
| P6 | 统一 MCP 工具面 | connect-agent 整合删除、账号工具接入、命名/权限/日志统一、旧标记退出 |
| P7 | 依赖与文档收口 | 依赖版本、文档重写、全量门禁与证据 |

阶段必须串行执行。P1 先删旧代码；P2/P3 可以先各自写测试但不得并发改同一文件。

## 3.1 分支与提交策略

用户 2026-10-28 裁定：

> 「按责任区分批编码、提交并推送以保存状态，然后进入下一责任区，可以开一个新分支，确认一切无恙之后再合并」

执行规则：

- 分支名：`refactor/unified-qq-agent-client`；
- 从开始改造前已验证的 baseline 派生；
- 每个责任区一个或少量 focused commits，提交信息写清责任区、改动、验证命令和未验证项；
- 每次提交后 push 到 origin，保存远端状态；
- 下一个责任区开始前确认工作树只含本责任区预期变更；
- 全部 P0–P7 完成、`tools/gate.sh docs` 与 full gate 通过、用户确认后，才允许合并回原分支；
- 未经用户当次确认不得合并、不得强制推送、不得改写已推送历史。
- 既有 dirty 的 D-023/D-024/ops logging 改动先做一次独立 baseline checkpoint；
  后续 P0 文档、P1 删除等分别提交，不能靠 `git add -A` 混在一起。

## 4. P0：文档裁定与依赖前置

### 4.1 交付

- 新增/更新：
  - `docs/superpowers/specs/2026-10-28-unified-qq-agent-client-architecture.md`
  - `docs/superpowers/specs/2026-10-28-logging-levels-and-diagnostics.md`
  - `docs/superpowers/specs/2026-10-28-configuration-system.md`
  - 本计划
  - 需求原稿 D-025..D-028
  - `docs/superpowers/DESIGN_RULINGS.md` 索引
  - `docs/agents/STATUS.md` 当前规划入口
- 依赖决策记录：
  - 用户裁定继续固定 `agentlib-mosire:0.2.4`；不降级到 0.2.3；
  - 上游源码主线 0.2.4 与公开 Pages 0.2.3 的发布落差作为 P7 可复现性风险单独记录；
  - Jackson 2.22.2 → 2.22.4；
  - SLF4J 2.0.19 → 2.0.20；
  - Java-WebSocket 1.5.7 → 1.6.0（test）；
  - SpotBugs annotations 4.10.4 → 4.10.5；
  - JUnit 5.11.4 → 6.1.3（大版本，单独迁移）；
  - Maven plugins 见 P7；
  - Inbox 需要引入 Jackson BOM，当前 `jackson-dataformat-yaml` 为 2.18.4，存在版本混杂。

### 4.2 验收

- 文档相对链接有效，`tools/gate.sh docs` PASS；
- `DESIGN_RULINGS.md` 有 D-025..D-028 和三个专题入口；
- AgentLib 版本记录为固定 0.2.4，并记录公开仓库发布落差；
- 未修改生产代码、未启动外部连接。

### 4.3 停止条件

- 需要删除用户仍要求保留的非文游通用能力；`iibot-connect-agent` 已裁定整合后删除，其能力必须迁入正式模块而非直接丢弃。
- 无法在不改变用户已冻结 0.2.4 决定的情况下继续。

## 5. P1：删除文游与 legacy 代码

### 5.1 目标

让默认构建只包含 QQ 客户端能力；旧文游由 Git 历史保留，不进入当前 classpath。

### 5.2 改动

1. 根 `pom.xml`：
   - 删除 `iibot-taskflow` module；
   - 删除 `workspace-mosire`、`docs-mosire` 的 dependencyManagement（若不再有其他消费者）；
   - P1 暂保留 `iibot-connect-agent` 作为待整合源码；P6 完成迁移后删除该 module 与 POM 引用。
   - 最终目标模块为 `iibot-connect`、`iibot-bot`、`iibot-app`（`connect-agent` 整合完成后消失）。
2. 删除 `iibot-taskflow/` 整个目录。
3. 删除 `iibot-app` 的 legacy 代码：
   - `app/action`、`app/cache`、`app/chat`（仅 legacy）、`app/complaint`、`app/control`、`app/decision`、`app/dispatch`、`app/event`、`app/mcp`、`app/publication`、`app/records`、`app/runtime`、`app/ticket`、`app/world`；
   - legacy 配置类 `AppConfig`、`AppConfigLoader`、`JsonConfigBundle` 中仅 legacy 需要的部分按实际引用保留/迁移；
   - `Main` 删除 `--mode legacy` 分支，只保留 bot 入口。
   - 保留 bot 路径实际需要的 app 类（初步静态闭包约 19 个：bot/config/subset inbox/delivery/media/reply）。
4. 删除旧运行目录/配置：
   - `simulation/` 文游运行区；
   - `config/legacy/`；
   - `iibot-app/config/iibot.example.json`；
   - `iibot-app/config/world-facts.example.json`；
   - 旧文游 examples / scripts / status 文档。
5. 把仍有效的通用能力（如有）迁入 bot 相关包，不允许在 app 中保留无引用旧类。
6. 更新 `iibot-app/pom.xml`：移除 taskflow 依赖、旧 LLM/workspace/docs 依赖；只保留 bot runtime 必需依赖。

### 5.3 测试

- `./mvnw -B -ntp clean verify`（或先 focused 逐模块编译）；
- 全仓无 `io.mosire.iibot.taskflow` 生产 import；
- 默认 jar 中无 taskflow class；
- `Main` schema/help 中无 legacy 模式；
- docs gate 不引用已删除旧文档。

### 5.4 验收与停止

- 默认 reactor 不包含 `iibot-taskflow`；
- 默认 jar 不含文游 MCP 工具；
- 删除前必须确认未被当前未提交用户改动覆盖；
- 任何需要保留的旧数据迁移必须在删除前写清方案，否则停。

## 6. P2：日志基础设施

### 6.1 目标

先让后续大规模重构可观测，再改路由和消息板。

### 6.2 改动

1. 分离技术日志与运维事件：
   - `iibot.log`：DEBUG/INFO/WARN/ERROR；
   - `iibot-ops.log`：稳定 `event=...` INFO/WARN/ERROR；
   - 旧的 `OpsLog.debug` 逐步改为普通技术 logger 或明确保留为 debug-only。
2. 增加日志配置模型：
   - `logging.directory`、`console`、`file`、`rootLevel`、`opsLevel`、`levels`、`maxFileSize`、`maxHistory`；
   - 早期 fallback：`IIBOT_LOG_DIR`、`IIBOT_LOG_LEVEL`、`IIBOT_OPS_LOG_LEVEL`。
3. 建立 `traceId`/`callId`/`bootId`：
   - 入站消息、MCP 工具调用、出站发送、启动恢复能够串联。
4. 补齐 P0–P5 所需事件：
   - config_loaded/config_invalid；
   - account_registered/adapter_connected/adapter_unavailable；
   - inbound_accepted/duplicate；
   - read_cursor_advanced/inbox_sync；
   - mcp_tool_call；
   - outbound_intent_created/outbound_send_finished；
   - shutdown_component_failed。
5. 建立字段白名单/参数 key 日志包装器，禁止正文、token、Authorization、完整协议 JSON。
6. 增加日志测试：
   - 捕获 appender；
   - DEBUG 开关；
   - 轮转大小/保留数；
   - 正文和 token 零命中；
   - 日志实现异常不影响业务。

### 6.3 验收

- 默认 INFO 能看到启动、账号、端口、工具数、入站/发送里程碑；
- DEBUG 能看到配置合并、游标推进、路由选择和去重细节；
- `logs/iibot.log`、`logs/iibot-ops.log` 权限 0600；
- 日志中测试正文和 token 0 命中；
- 测试只在临时目录运行，不启动真实 QQ。

## 7. P3：配置系统 v2

### 7.1 目标

用一份可检查、可解释、多账号的配置取代当前单账号 + 分散示例 + legacy 配置。

### 7.2 改动

1. 新增 `config/schema/bot-config.schema.json` 与完整示例：
   - `config/examples/bot.full.json`
   - `config/examples/bot.multi-account.json`
2. 新增 `BotConfigV2`（名称可实现时再定）：
   - `accounts` alias 映射；
   - `messageBoard`（summary/outbox/weighting）；
   - `logging`；
   - `control`/`inbox`。
3. 升级 `JsonConfigBundle`：
   - 记录每个 JSON pointer 的来源文件和覆盖链；
   - 错误输出 file + pointer + expected + actual type + hint；
   - 保留对象深合并、数组替换和同文件重复 key 拒绝。
4. 升级 `BotConfigBootstrap`：
   - 空目录生成单一 `config/bot/bot.json`，0600，rc=2；
   - 生成后提示最小修改项与缺失 env；
   - 不覆盖已有文件，不启动网络。
5. 新增 CLI：
   - `--check-config`
   - `--print-effective-config`
   - `--log-level`
6. 增加脱敏有效配置输出：
   - secret env 只显示 `SET/MISSING`；
   - 不输出 raw token、Authorization 或 secret 值。
7. 更新 `.gitignore` 与 config README：
   - 忽略本地 `config/bot/*.json`；
   - 保留 README、schema、examples；
   - 提供旧配置手工迁移表和可选迁移命令。
8. `logging` 块与 P2 的日志应用器对接。

### 7.3 验收

- 首次启动生成单一配置并退出；
- 合法配置 `--check-config` rc=0，不启动 QQ/Inbox/MCP；
- 非法字段/类型/URI/端口/secret env 均给出可定位错误；
- 多文件合并顺序和来源追踪有测试；
- 多账号配置有稳定 alias/accountId；
- 有效配置输出脱敏测试通过；
- README 从零可以启动。

## 8. P4：多账号 Adapter Registry 与路由

### 8.1 目标

让一个 iibot 进程能注册多个 Adapter，每个账号独立连接；入站、读取和发送都按账号路由。

### 8.2 改动

1. 把 `AdapterRegistry` 从孤儿模块提升为运行时共享组件（可迁移到 `iibot-connect` core 或稳定 package）。
2. `BotRuntime` 从单个 `MessagingAdapter` 改为 `AccountRegistry`：
   - 每个账号启动一个 Adapter；
   - 账号启动状态独立；
   - 关闭时统一逆序回收。
3. `BotMain` 按 `accounts` 配置创建 Adapter：
   - 账号 `accountId` 必须与登录信息核对；
   - 一个账号失败不伪装成整体成功；
   - 不自动把消息发送给默认账号替代失败账号。
4. 入站：
   - `BotIngestionService` 用 Adapter 绑定账号和事件 selfId 生成 `ChatSourceKey`；
   - 禁止事件 JSON 自行覆盖账号。
5. 出站：
   - `BotOutboxReplyPort` 不再持有一个 `OutboxSender`；
   - 根据 `ChatSourceKey.accountId()` 获取对应 sender/adapter；
   - 无账号、账号禁用、登录不匹配时 fail-closed。
6. 账号控制工具：
   - 接入 `iibot-connect-agent` 的 `qq_account_list`、`qq_account_status`、`qq_account_login_info`；
   - `qq_account_use` 仅切换 consumer 默认账号，不改变已有 thread 的归属；
   - 配置新增/修改/删除属于受保护运维面，不暴露普通模型工具。
7. 测试：
   - 两个 fake adapter 的入站隔离；
   - 跨账号回复路由；
   - 账号禁用/断线；
   - 账号切换不改变已有 thread；
   - 单账号失败不影响另一账号的读取。

### 8.3 验收

- 默认启动一个账号时行为与现版本兼容；
- 两个账号同时在线时，消息不串号、出站不串号；
- 任何路由失败都产生稳定的 `account_unavailable`/`adapter_missing` 错误，不产生外部消息；
- 账号登录信息与配置不一致时 fail-closed；
- 不能通过工具参数伪造任意 accountId。

## 9. P5：消息板游标与 Inbox 自管理

### 9.1 目标

Agent 不再维护同步标记；消息板服务端拥有读游标、未读状态和 Inbox 同步。

### 9.2 改动

1. 新增/迁移 `consumer_thread_state`：
   - `consumer_id`、`thread_id`、`read_seq`、`inbox_item_id`、`inbox_revision`、`updated_at`；
   - 唯一键 `(consumer_id, thread_id)`；
   - schema 迁移可重复执行，旧库安全升级。
2. 新增 consumer 解析：
   - 第一版从 MCP token/config 映射到 `default` consumer；
   - 数据结构从一开始带 consumerId，后续多 consumer 无需重建。
3. 入站后计算每个 consumer 的未读；
4. 新增 `qq_conversation_read`：
   - 读取 readSeq 之后、deliveredSeq 之前的未读消息；
   - 只在服务端记录 deliveredSeq，不推进 readSeq、不归档 Inbox；
   - 未 ack 前重复读取返回同一批消息；
   - 返回消息，不要求 Agent 回传任何位置/seq/revision/itemId。
5. 新增 `qq_conversation_ack(threadId)`：
   - 只接受 threadId，不接受 revision/snapshot/itemId/localSeq；
   - 服务把 `readSeq` 推进到当前 `deliveredSeq`；
   - read 与 ack 之间到达的新消息保留为下一次未读；
   - 更新 Inbox 未读数或归档 active item。
6. 发送成功后的语义：
   - 可以按固定规则自动 ack 当前已投递批次；
   - 无回复的会话必须由 Agent 显式 `qq_conversation_ack`；
   - 自动 ack 规则必须写入工具契约并测试。
7. 删除/降级 Agent 面旧工具：
   - `bot_thread_ack` 退出主 Agent MCP；
   - `expectedRevision` / `snapshotToSeq` / `itemId` 不再作为 Agent 必填参数；
   - 旧工具可在内部迁移期保留，但不得影响主流程 schema。
8. 恢复与对账：
   - 启动扫描 PENDING/UNKNOWN 出站；
   - 扫描 consumer_thread_state 与 Inbox item 不一致；
   - 只修正可证明的状态，冲突 fail-closed 并记 ERROR；
   - 不自动重发 UNKNOWN。
9. 测试：
   - read 后不 ack、重复 read、ack 后游标推进；
   - read 与 ack 之间到达新消息时不被错误消费；
   - Inbox item 更新失败但消息板可读；
   - 并发读+新消息；
   - consumer 隔离（至少 fake 双 consumer）；
   - 无 revision/snapshot 的 Agent schema 契约测试。

### 9.3 验收

- `qq_conversation_read` 后 Inbox 未读保留；`qq_conversation_ack(threadId)` 后未读数下降或 item 归档；
- Agent 不需要传任何内部 revision/seq/itemId；
- read 与 ack 之间到达的新消息不丢、不重复、可再读；
- Inbox 同步失败可恢复，不导致正文丢失；
- 消息板仍可独立提供历史查询；
- 旧 `bot_thread_ack` 不再出现在默认工具列表。

## 10. P6：统一 MCP 工具面与 connect-agent 接线

### 10.1 目标

把通用 QQ 工具和消息板工具接入同一个 MCP catalog，结束平行模块和混合命名。

### 10.2 改动

1. 确定最终工具命名（默认建议 `qq_*`）：
   - account：list/status/login_info/use；
   - conversation：list/read/history/send；
   - message/media/forward/note/friend；
   - Inbox 仅保留 `inbox_rest` 或宿主控制面工具。
2. 在 `BotRuntime` 注册：
   - `BotMcpTools` 产生的消息板 AgentTool；
   - `ConnectAgentTools` 产生的账号/连接 AgentTool；
   - 不允许把全部 Inbox 16 工具无差别暴露。
3. 处理 `qq_send_message`：
   - 不进入默认主 Agent MCP，也不保留独立模块的公开直接发送路径；
   - 对外发送统一走 `qq_conversation_send`，由消息板写 PENDING 后再按 accountId 调用 Adapter；
   - 低层发送实现可迁移为消息板内部原语，或随独立模块删除；
   - 如未来开放低层发送，必须使用独立 admin scope、消息板登记和明确 UNKNOWN 警告。
4. 所有工具：
   - 统一 `ToolSpec`、roles、resources、sensitive/destructive 元数据；
   - 统一 `BotToolCallLoggingTool` 包装；
   - 统一错误码、参数 schema、脱敏和审计；
   - 统一 MCP `tools/list` 顺序或按域稳定排序。
5. descriptor：
   - `tf_inbox_binding_descriptor` 从普通 Agent 工具移到宿主交接/控制面；
   - 绑定流程仍由外部 Agent 按用户授权执行，不把 raw token 暴露给模型。
6. schema/契约测试：
   - 不存在 `expectedRevision/snapshotToSeq` 必填字段；
   - 不存在直接发消息绕过消息板的路径；
   - 所有工具都有非空描述、合法 JSON schema、唯一名称；
   - 账号切换与 thread 路由有端到端 fake 测试。
7. `iibot-connect-agent` 整合（用户 2026-10-28 已裁定）：
   - `AdapterRegistry` 迁入 `iibot-connect` 的稳定通用包；
   - `qq_account_list`、`qq_account_status`、`qq_account_login_info` 等 AgentTool 迁入
     `iibot-bot` 的统一 QQ MCP 工具包；
   - `qq_send_message` 不保留为公开直接发送工具；其能力由
     `qq_conversation_send` + 消息板 + Adapter 路由承接；
   - 删除 `iibot-connect-agent/` 目录、根 POM module 和所有依赖声明；
   - README/AGENTS/模块图同步为 `connect / bot / app` 三模块。

### 10.3 验收

- `BotMcpServer.toolNames()` 的默认目录只包含统一后的业务工具；
- 账号、会话、发送均走同一授权器/日志器；
- Agent schema 不暴露内部标记；
- `iibot-connect-agent` 已整合删除，仓库无空模块/POM 引用；
- 工具命名与 README、handoff、simulation2 docs 同步。

## 11. P7：依赖与文档收口

### 11.1 依赖

1. AgentLib：
   - 用户已裁定固定 `agentlib-mosire:0.2.4`，不降级；
   - 上游 0.2.4 tag/Pages 发布仍是干净仓库复现的前置；
   - 若 P7 时上游仍未发布，记录 `PUBLICATION_GAP`，不得用本机 `~/.m2` 冒充公开可解析；
2. Jackson 统一到 2.22.4，并让 Inbox 导入 Jackson BOM；
3. SLF4J 2.0.20；
4. Java-WebSocket 1.6.0（测试）；
5. SpotBugs annotations 4.10.5；
6. Maven plugins：
   - compiler 3.16.0；
   - resources 3.5.0；
   - surefire 3.6.1；
   - jar 3.5.1；
   - enforcer 3.6.3；
   - shade/checkstyle 保持最新已验证版本；
7. JUnit 6.1.3 独立迁移，不与业务重构混在同一批；
8. MCP SDK 2.1.0 由 AgentLib 上游处理，下游不硬覆盖；
9. 用干净本地 Maven 仓库验证所有依赖公开可解析；禁止依赖 `~/.m2` 手工文件冒充可复现。

### 11.2 文档

必须重写/更新：

- 根 `README.md`：产品定位、模块、构建、启动、Agent 对接；
- 根 `AGENTS.md`：删除文游红线，改 QQ 客户端边界；
- `docs/AGENTS.md` 与 `docs/superpowers/AGENTS.md`：当前设计/计划入口；
- `docs/agents/STATUS.md`：当前状态、门禁、未验证、下一步；
- `docs/agents/WORKFLOW.md`、`VERIFICATION.md`、`ENVIRONMENT.md`：删除文游专用门禁与过期 AgentLib 事实；
- `config/README.md`、`config/bot/README.md`、schema/examples；
- `iibot-app/AGENTS.md`、`iibot-bot/AGENTS.md`、`iibot-connect/AGENTS.md`；
- simulation2/agent handoff 文档更新为统一 `qq_*` 工具和 Inbox 自动同步流程。

文档清理：

- 旧文游 specs/plans/status：删除或标历史并移出导航；
- `DESIGN_RULINGS.md` 只保留仍有效的 D-025..D-028 与 bot/QQ 相关裁定，旧文游索引移入历史；
- `docs/upstream` 三库契约更新到实际发布版本；
- README 的模块图必须与实际 POM 一致。

### 11.3 门禁

```bash
# Inbox 先构建安装（如改动 inbox）
cd InboxMosire
./mvnw -B -ntp clean install
./scripts/gate.sh full

# iiBot
cd ../iiBotMosire
./tools/gate.sh docs
./tools/gate.sh full
```

- 校验 `java -jar ... --help` 不再出现 legacy；
- 干净本地 Maven 仓构建通过；
- 生成的 jar 做 class/tool 扫描，确认无文游模块；
- 真实 QQ/DSH/OneBot 联调、长跑、日志真实轮转：未跑写 `NOT_RUN`。

## 12. 文件所有权

| 阶段 | 主要文件 |
|---|---|
| P0 | `docs/superpowers/**`、`docs/agents/STATUS.md`、`DESIGN_RULINGS.md`、需求原稿 |
| P1 | 根 `pom.xml`、`iibot-taskflow/**`（删除）、`iibot-app/**`、`simulation/**`、旧 config/examples |
| P2 | `iibot-app/src/main/resources/log4j2.xml`、`iibot-connect/.../ops/**`、`iibot-bot/.../mcp/BotToolCallLoggingTool.java`、各 runtime 类 |
| P3 | `iibot-app/.../config/**`、`iibot-app/.../bot/BotConfig*.java`、`config/**`、`.gitignore` |
| P4 | `iibot-connect/.../core/**`、`iibot-connect-agent/**`（迁移源）、`iibot-app/.../bot/BotRuntime.java`、`iibot-app/.../reply/**` |
| P5 | `iibot-bot/.../store/**`、`iibot-bot/.../inbox/**`、`iibot-app/.../inbox/**`、MCP tool schema |
| P6 | `iibot-bot/.../mcp/**`、`iibot-connect/**`、`BotRuntime.java`、`simulation2/dsh/**`；删除 `iibot-connect-agent/**` |
| P7 | 两个仓库的 POM、README/AGENTS/docs/config |

每个阶段开始前先 `git status`；禁止 `git add -A`；禁止跨仓 commit。

## 13. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 删除 taskflow 引发大量编译/测试断链 | P1 先删模块、再按编译错误删引用；每步 focused，最后 full |
| 多账号路由错误导致发错人 | thread.accountId 为唯一路由；无账号/不匹配 fail-closed；fake adapter 跨账号测试 |
| 自动已读导致 Agent 看不到消息 | 消息板历史永久可读；读游标可重读；Inbox 不是事实源；升级前测试并发/崩溃 |
| Inbox 同步失败 | 消息板先成功，Inbox 可重试对账；UI 警告但不丢正文 |
| 日志泄漏正文/secret | key-only、白名单、测试数据扫描、日志目录不上传 |
| 配置 schema 破坏现有本地配置 | 提供迁移表和可选迁移命令；旧 legacy 不迁移；启动错误给 pointer |
| AgentLib 0.2.4 不可公开解析 | 用户已冻结 0.2.4；本地/私有构建可继续，但 P7 干净仓库门禁在公开发布前只能 BLOCKED/NOT_RUN |
| 旧文档继续误导 | P0 先登记新目标，P7 统一重写/归档；所有旧入口加状态头 |
| 当前 dirty worktree 丢改动 | 开工前冻结/提交或独立 worktree；每次按文件 diff 执行 |

## 14. 最终验收清单

- [ ] 默认 reactor 仅有 QQ 客户端模块；
- [ ] 默认 jar 无 `taskflow`、无 `tf_*` 文游工具；
- [ ] 多账号启动、入站隔离、出站按账号路由测试通过；
- [ ] Agent 工具不要求 `itemId/expectedRevision/snapshotToSeq/必填幂等键`；
- [ ] `qq_conversation_read` 不消费，`qq_conversation_ack(threadId)` 无内部标记地推进并同步 Inbox；
- [ ] 统一 MCP 工具目录来自消息板语义层 + 已整合的账号工具层；
- [ ] INFO/DEBUG 日志分级可配置，正文/token 零命中；
- [ ] 配置 `--check-config` 可诊断，有效配置脱敏，首次生成单文件；
- [ ] `tools/gate.sh docs` PASS；
- [ ] `./mvnw -B -ntp clean verify` PASS（记录实际 tests/failures/errors）；
- [ ] 干净 Maven 仓库解析成功（受 AgentLib 0.2.4 公开发布影响，未发布则 BLOCKED）；
- [ ] 真实 QQ/DSH/长跑未跑项明确标 `NOT_RUN`。

## 15. 执行记录

- 2026-10-28：完成本计划及三份设计文档，作为独立 P0 checkpoint 提交；未改生产代码、未跑 Maven、未启动 QQ/Inbox/MCP。

- 2026-10-28：用户确认继续使用 AgentLib 0.2.4；采用新分支按责任区提交/推送；整合并删除 `iibot-connect-agent`；读取只预览、处理完显式 ack。
- 2026-10-28 P1a：删除 `iibot-taskflow` 与 app 旧文游/legacy 包；`iibot-app` 收缩为 20 个 main、12 个 test 源文件；根 reactor 4 模块；`tools/gate.sh docs` PASS；`tools/gate.sh full` PASS（174 tests / 0 failures / 0 errors / 0 skipped）。提交 `0acd0a0` 并推送。
- 2026-10-28 P1b：删除 legacy `simulation/` 与 `config/legacy`；simulation2 `01-prepare.sh` 改为复用现有 `bot.sim.local.json` 或从示例生成。`tools/gate.sh docs` PASS（files=78 links=194）；`tools/gate.sh full` PASS（174 tests / 0 failures / 0 errors / 0 skipped）。提交 `bdef235` 并推送。
- 2026-10-28 P2：实现 SLF4J MDC `LogContext`、root/ops 日志级别与滚动配置、入站/MCP/出站/Inbox 关联 ID 和关键 DEBUG 覆盖；`tools/gate.sh full` PASS（192 tests / 0 failures / 0 errors / 0 skipped）。提交 `e84c679` 并推送。
- 2026-10-28 P3：实现配置 v2、多账号 schema、来源追踪错误、`--check-config`/`--print-effective-config`/`--log-level`、单文件 bootstrap、schema/examples 和 JSON logging 应用；`tools/gate.sh docs` PASS（files=78 links=195）；`tools/gate.sh full` PASS（208 tests / 0 failures / 0 errors / 0 skipped）。
- 2026-10-28 P4：新增 AccountRegistry 与多账号 runtime；BotMain 启动全部 enabled account，入站按绑定 accountId 隔离，出站按 thread.accountId 选独立 OutboxSender；账号身份校验、启动失败回滚、forward/media 按账号路由；`tools/gate.sh docs` PASS（files=78 links=195）；`tools/gate.sh full` PASS（221 tests / 0 failures / 0 errors / 0 skipped）；direct-provider 工具完整多账号化留 P6。
- 2026-10-28 P5：新增 consumer_thread_state、qq_conversation_read/ack、InboxDigestService.ackThrough；read 只预览并记录 deliveredSeq，ack 只传 threadId，先同步 Inbox 再推进 readSeq；默认 MCP 不再注册 bot_thread_ack；`tools/gate.sh docs` PASS（files=78 links=195）；`tools/gate.sh full` PASS（229 tests / 0 failures / 0 errors / 0 skipped）。
- 2026-10-28 P6a：默认 MCP 统一为 qq_*；新增 qq_account_list/status/login_info；qq_conversation_send 幂等键可选；默认 Inbox 仅保留 inbox_rest + qq_inbox_binding_descriptor；删除 iibot-connect-agent，reactor 3 模块；`tools/gate.sh docs` PASS（modules=3 files=77 links=192）；`tools/gate.sh full` PASS（213 tests / 0 failures / 0 errors / 0 skipped）。P6b 待办 direct-provider 逐账号化。
- 2026-10-28 P6b：recent/history/friend request/user note 增加可选 accountId 与逐账号 provider/service；好友申请迁移到 account 分区 `friend_request_v2`；多账号未指定 accountId 返回 ACCOUNT_REQUIRED；`tools/gate.sh docs` PASS（modules=3 files=77 links=192）；`tools/gate.sh full` PASS（225 tests / 0 failures / 0 errors / 0 skipped）。
- 2026-10-28 P7a：Jackson 2.22.4（BOM）、SLF4J 2.0.20、JUnit 6.1.3、Java-WebSocket 1.6.0、Maven plugins 刷新；`./mvnw -B -ntp clean verify` PASS（225 tests / 0 failures / 0 errors / 0 skipped）；Inbox 同步升级 Jackson/SLF4J/JUnit 并修复 Jackson dataformat-yaml 2.18.4 混杂。
- 2026-10-28 P7b：旧文游 specs/plans/status/reports 归档到 `docs/archive/legacy-game/`，docs checker 排除 archive；当前设计索引/README/AGENTS/config/handoff 收口；`tools/gate.sh docs` PASS（modules=3 files=39 links=96）；`tools/gate.sh selftest` PASS；`tools/gate.sh full` PASS（225 tests / 0 failures / 0 errors / 0 skipped）。空 Maven 仓库因 AgentLib 0.2.4 公开缺失/下载超时记 `PUBLICATION_GAP` / `NOT_RUN`。
