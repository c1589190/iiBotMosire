# Bot 运维日志补全设计

> **状态**：历史（2026-10-28）
> **取代者**：[2026-10-28 日志分级、诊断与运维可观测性设计](2026-10-28-logging-levels-and-diagnostics.md)
> **勿据此开工**：本文保留为 2026-10-27 事件目录与落盘基线；新增级别、配置、诊断和验收以取代者为准。
> **范围**：只增强 Bot-first 运行路径的可观测性与日志落盘；不改变消息、Inbox 或 MCP 契约。

## 1. 需求来源

用户原话（2026-10-27，逐字）：

> 「注意到iibot程序本体的log运维记录并不全面，能不能完善一下？」

目标：让现场排障仅凭日志就能回答“进程何时启动、连上了谁、收到/丢弃了哪条消息、
摘要是否发布、MCP 调了什么工具、外发三态是什么、为何关闭”，同时不泄漏正文与凭据。

## 2. 现状事实

- `iibot-app/src/main/resources/log4j2.xml` 只有 Console appender，进程退出或终端滚动后
  记录丢失，也没有单独的运维事件流。
- Bot-first 运行路径多数关键类没有日志：`BotIngestionService`、`InboxDigestScheduler`、
  `BotReplyService`、`BotInboxRuntime` 等；`BotMcpServer` 只有启动一行。
- `NapCatAdapter` 已有 `AdapterStatus`/`AdapterEvent` 状态对象，但 `emit(...)` 没有写入日志。
- 已有约束：token/API key/完整 QQ 凭据、聊天正文、完整原始 OneBot JSON 不进入日志
  （`AGENTS.md` §2、`docs/superpowers/specs/2026-10-26-bot-first-main-agent-architecture.md` §9）。

## 3. 目标与非目标

### 3.1 目标

1. 日志持续落盘，默认 `logs/iibot.log`，按天/大小滚动并压缩，保留有界。
2. 独立运维事件流 `logs/iibot-ops.log`，事件行统一带 `event=<稳定名称>` 与 `k=v` 字段。
3. 覆盖进程、Adapter、入站消息、摘要调度与发布/ack、MCP HTTP 与逐工具调用、外发三态、
   好友申请、来源权重写操作、运行关闭。
4. 所有日志均为结构化、可 grep、脱敏摘要；日志失败不得改变业务结果。

### 3.2 非目标

- 不新增持久业务审计表，不把日志当作唯一下游事实源。
- 不记录消息正文、备注正文、回复正文、关键词内容、token、Authorization、原始协议 JSON。
- 不改变 Inbox 的 summary/content、MCP 工具面或外发幂等语义。

## 4. 目标运行序列

```text
进程启动
  -> 记录 pid/java/cwd/configSource 摘要
  -> BotRuntime 打开 bot.db
  -> 记录 thread 装载数量
  -> Inbox embedded 启动并记录实际回环端口/descriptor 路径
  -> 记录恢复 flush 的 thread 数与发布数
  -> Bot MCP 启动并记录工具数
  -> Adapter 启动/连接/重连/断开事件
  -> 入站：adapter 去重 -> ingest 持久化 -> 摘要调度
  -> 摘要：发布/更新/冲突/ack/审计失败
  -> MCP：HTTP 鉴权/失败 + 工具调用开始结果/耗时/错误码
  -> 外发：SENT / FAILED_BEFORE_SEND / UNKNOWN
  -> 关闭：各组件结果与异常类型
```

## 5. 日志通道与格式

- 技术日志：根 logger -> Console + `logs/iibot.log`。
- 运维事件：logger `iibot.ops` -> Console + `logs/iibot-ops.log`（`additivity=false`）。
- 目录覆盖：`-Diibot.log.dir=/path` 优先，其次环境变量 `IIBOT_LOG_DIR`，默认 `logs/`。
- 滚动：按天 + 20 MB，保留 30 个归档，`.gz` 压缩。
- 事件格式：`event=<name> key=value ...`；字符串值不得包含换行；不打印请求体。
- 事件等级：成功/状态迁移 INFO；可恢复拒绝 WARN；影响运维处置的异常 ERROR；
  高频细粒度信息 DEBUG（进入技术日志，不污染运维主记录）。

## 6. 事件目录

| 领域 | 事件 |
|---|---|
| 进程 | `process_start`、`bot_runtime_starting`、`bot_runtime_ready`、`bot_runtime_close`、`bot_shutdown_requested` |
| Adapter | `adapter_start`、`adapter_event`、`adapter_send_start`、`adapter_send_finish`、`adapter_close` |
| 入站 | `inbound_ingest`、`inbound_digest_enqueue_failed` |
| 摘要 / Inbox | `inbox_embedded_starting/started/stopped/failed`、`inbox_client_call`、`inbox_digest_published`、`inbox_digest_reconciled`、`inbox_digest_skipped`、`inbox_digest_failed`、`inbox_ack`、`inbox_priority_adjusted` |
| MCP | `mcp_started`、`mcp_stopped`、`mcp_auth_denied`、`mcp_request_rejected`、`mcp_upstream_error`、`mcp_http_request`、`mcp_tool_call` |
| 外发 | `outbound_send` |
| 业务操作 | `reply_idempotent_hit`、`reply_send`、`friend_request_received`、`friend_request_decided`、`user_note_upserted`、`source_weight_updated` |
| 生命周期 | `component_close_failed`、`bot_runtime_close` |

## 7. 不变量与失败语义

1. 日志消息不得包含正文、token、Authorization、完整 OneBot JSON；参数只记录 key 名，
   不记录 value。
2. `iibot.ops` 写失败不得抛出到业务调用栈，不影响入库、Inbox、MCP 与外发。
3. 外发三态日志只读结果，不触发重试；`UNKNOWN` 仍由人工处置。
4. 同一事件字段顺序稳定，字段值使用单行输出；异常只记录类型/稳定错误码。
5. 默认监听仍为回环；日志文件不改变网络行为。

## 8. 验收判据

1. 正常启动产生 `logs/iibot.log` 与 `logs/iibot-ops.log`，包含启动、端口、工具数、
   adapter 状态行。
2. 一条重复入站消息记录 accepted 与 duplicate 两种 `inbound_ingest outcome`。
3. 摘要发布与 ack 分别产生 `inbox_digest_published` 与 `inbox_ack`。
4. MCP 工具成功/失败各产生 `mcp_tool_call`，包含 `tool`、`outcome`、`elapsedMs`、
   `argKeys`；失败带稳定 `code`，不含参数值。
5. 日志中搜索测试正文与测试 token 必须 0 命中。
6. 负向：日志 appender 不可用或 logger 调用异常时业务仍返回原结果（由日志实现保证，
   单测验证工具包装器不改变结果与异常）。

## 9. 测试锚点与文件所有权

- 日志配置：`iibot-app/src/main/resources/log4j2.xml`
- 工具包装器：`iibot-bot/src/main/java/io/mosire/iibot/bot/mcp/BotToolCallLoggingTool.java`
- 运行接线：`BotMcpServer`、`BotRuntime`、`BotMain`、`BotInboxRuntime`、`NapCatAdapter`
- 业务事件：`BotIngestionService`、`InboxDigestScheduler`、`InboxDigestService`、
  `BotReplyService`、`FriendRequestService`、`SourceWeightService`、`OutboxSender`
- 测试：`BotToolCallLoggingToolTest`、现有 focused/full 门禁
- 文档：本文件、`config/README.md`、`docs/agents/STATUS.md`

## 10. 文件与证据边界

仅运行期日志文件，不提交 `logs/**`；最终报告区分“代码/单测已验”和
“真实 QQ/DSH 运维日志联调 NOT_RUN”。
