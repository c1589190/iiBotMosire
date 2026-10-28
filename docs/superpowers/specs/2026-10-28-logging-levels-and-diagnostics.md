# 日志分级、诊断与运维可观测性设计

> **状态**：提议（2026-10-28）
> **本文取代**：[2026-10-27 Bot 运维日志补全设计](2026-10-27-bot-operational-logging.md)（2026-10-28 起由其完整扩展）。
> **用户原话**：
> > 「此外还有之前一直没补的运维，包括完善的debug/info log、完善且易用的配置文件系统」
> **关联**：[统一 QQ Agent 客户端架构](2026-10-28-unified-qq-agent-client-architecture.md)、[配置系统设计](2026-10-28-configuration-system.md)、[改造计划](../plans/2026-10-28-unified-qq-agent-client-refactor-plan.md)

## 1. 目标

让现场排障能够仅凭日志回答：

- 当前加载了哪些配置源，合并后的有效配置是什么；
- 进程启动了哪些账号、Adapter、Inbox 和 MCP 端口；
- 哪条 QQ 消息被接收、去重、入库、生成未读和唤醒；
- Agent 调用了哪个工具、读写哪个账号/thread、耗时、稳定结果码；
- 出站消息的三态、使用的账号和幂等键；
- 为什么启动失败、为什么某账号不可用、为什么 Inbox 或发送失败。

日志必须：

1. 默认 INFO 级别可读、可 grep；
2. DEBUG 可按包/组件临时开启，且不改变业务；
3. 运维事件与普通技术日志分文件；
4. 绝不泄漏正文、token、Authorization、完整 OneBot JSON 或文件中的密钥；
5. 日志失败不影响业务结果。

## 2. 非目标

- 不引入 Prometheus、OpenTelemetry、远程日志平台；
- 不把日志当业务审计事实源或消息正文副本；
- 不记录 QQ 聊天正文、备注正文、回复正文、关键词值和工具参数值；
- 不在日志中提供任意 SQL/JSON dump 能力。

## 3. 当前事实与缺口

- `iibot-app/src/main/resources/log4j2.xml` 已有 Console、`iibot.log`、`iibot-ops.log` 三个 appender，默认 root INFO，日志目录可由 `-Diibot.log.dir` / `IIBOT_LOG_DIR` 覆盖。
- 现有 `OpsLog` 只提供 info/warn/error 和少量 debug 调用；`iibot.ops` logger 固定 INFO，DEBUG 运维事件不会进入运维文件，也没有按包调节级别的入口。
- 多数启动/配置合并/账号切换/游标推进/工具调用细节仍没有稳定的 INFO/DEBUG 事件。
- 配置加载失败主要输出一行异常消息，缺少配置源、JSON pointer、实际值类型和修复提示。
- `iibot.log` 与 `iibot-ops.log` 没有统一的请求/消息关联 ID，跨组件追踪困难。
- `iibot.log` 的滚动大小、保留数量和级别目前不能在 JSON 配置中统一管理。
- 日志中尚未建立可自动验证的“正文/密钥零命中”测试。

## 4. 日志通道

| 通道 | 目的 | 默认级别 | 内容约束 |
|---|---|---:|---|
| Console | 开发与前台运行 | INFO | 简洁；可按配置关闭 |
| `logs/iibot.log` | 技术调试与异常定位 | INFO | 允许 DEBUG；不记录正文；异常只带脱敏类型/稳定码 |
| `logs/iibot-ops.log` | 运维事件流 | INFO | 单行 `event=... k=v`；不打印堆栈、请求体或完整协议对象 |

- `iibot-ops.log` 只承载可运维事件，不把普通调试语句灌进去；
- 技术日志可以包含组件生命周期、配置合并、游标计算和异常类型；
- 两类日志共用同一时间源、进程 ID 和 `traceId`；
- 默认文件权限 `0600`，目录 `0700`；
- 按天 + 大小滚动、gzip、有界保留数。

## 5. 级别规范

| 级别 | 使用场景 | 示例 |
|---|---|---|
| DEBUG | 内部状态细节、合并顺序、游标计算、重试计划、可选能力的内部决策 | 配置源逐文件合并、未读数计算、游标推进前后 |
| INFO | 正常运行里程碑和可审计业务状态 | 进程启动、账号连接、Inbox ready、MCP ready、入站 accepted、发送 SENT |
| WARN | 可恢复拒绝、降级、外部状态不确定但服务仍运行 | 重复消息、可选账号不可用、Inbox 同步待对账、工具参数校验失败 |
| ERROR | 需要人工处理的失败、无法完成的启动、状态不确定 | 配置无效、端口冲突、账号启动失败、发送 UNKNOWN、Inbox 不可用 |

- DEBUG 不得记录参数值、正文、原始协议 JSON、完整 URL 查询串或异常 message；
- WARN/ERROR 必须带稳定 `code`；
- 业务失败通过工具结果返回，不因日志记录而改变返回值；
- 发送类 `UNKNOWN` 必须是 ERROR，并明确“不得自动重发”。

## 6. 结构与关联字段

运维事件统一格式：

```text
event=<stable_name> traceId=<id> pid=<pid> accountId=<id> threadId=<opaque> key=value ...
```

字段规则：

- `event` 稳定、蛇形小写、不可随意改名；
- `traceId` 在一次入站/工具调用/出站流程内保持一致；
- 只记录 ID、状态、计数、错误码、耗时、参数 key 名；
- 字符串值不可包含空白换行；需要多字段时拆成固定键；
- 可选字段缺失时不输出 `null`；
- 事件名和字段顺序有测试锁定。

建议关联 ID：

- 入站：`messageKey` / `traceId=inbound:<hash>`
- MCP：`callId`（服务端生成，不返回给模型即可）
- 出站：`deliveryKey` / 服务端幂等键
- 配置启动：`bootId`

## 7. 分层覆盖要求

### 7.1 配置与启动

- DEBUG：配置源列表、逐文件合并顺序、每个覆盖键、相对路径解析、默认值来源；
- INFO：`config_loaded`、有效账号数、启用 Adapter 数、日志目录、数据目录、MCP/Inbox 监听端口、工具数；
- WARN：`secret_env_missing`、可选功能未实现、非致命目录权限降级；
- ERROR：`config_invalid`、`config_source_invalid`、`port_bind_failed`、`startup_rollback_failed`。

### 7.2 Adapter / 账号

- INFO：`account_registered`、`adapter_starting`、`adapter_connected`、`adapter_disconnected`、`adapter_reconnecting`、`adapter_closed`；
- WARN：可恢复协议拒绝、重连等待、账号登录信息不匹配的上报；
- DEBUG：连接状态迁移、心跳、去重命中、限速等待、分段计划；
- ERROR：鉴权失败、启动失败、Adapter 异常关闭。

### 7.3 消息板

- INFO：`inbound_accepted`、`inbound_duplicate`、`thread_created`、`reply_persisted`、`delivery_state_changed`；
- DEBUG：`thread_projection_updated`、`unread_computed`、`read_cursor_advanced`、`inbox_mapping_changed`；
- ERROR：数据库写失败、事务回滚、游标不一致、迁移失败。

### 7.4 Inbox

- INFO：`inbox_embedded_started/stopped`、`inbox_item_published/updated/archived`、`inbox_wake_dispatched`（仅元数据）；
- WARN：`inbox_sync_deferred`、`inbox_item_conflict`、`inbox_descriptor_missing`；
- DEBUG：Inbox HTTP 请求动作、目标 item id、revision 前后、未读计数；
- 不记录 Inbox 正文、标题、token 或 descriptor 内的 token 路径内容。

### 7.5 MCP

- INFO：`mcp_started/stopped`、鉴权成功/拒绝、工具名、结果、耗时、错误码、参数 key 名；
- DEBUG：请求方法、协议版本、会话 id 的哈希摘要、上游状态码；
- 不记录 Authorization、完整请求体、工具参数值和工具结果正文。

### 7.6 出站

- INFO：`outbound_intent_created`、`outbound_send_finished`、`outbound_retry_suppressed`；
- ERROR：`outbound_unknown`、`adapter_missing`、`account_unavailable`；
- DEBUG：发送前账号路由、目标类型、分段数量、节流等待；
- 绝不记录正文。

### 7.7 关闭与恢复

- INFO：`shutdown_started`、`component_closed`、`shutdown_completed`；
- WARN：组件关闭超时/幂等重复关闭；
- ERROR：`shutdown_component_failed`；
- 启动恢复必须记录对账的 thread 数、item 数、修正数和剩余冲突数。

## 8. 日志配置

目标：JSON 配置文件中的 `logging` 块为统一入口，环境变量/系统属性作为启动早期 fallback。

```json
{
  "logging": {
    "directory": "./logs",
    "console": true,
    "file": true,
    "rootLevel": "INFO",
    "opsLevel": "INFO",
    "levels": {
      "io.mosire.iibot.connect": "INFO",
      "io.mosire.iibot.bot": "INFO",
      "io.mosire.iibot.app": "INFO",
      "io.mosire.iibot.inbox": "INFO"
    },
    "maxFileSize": "20MB",
    "maxHistory": 30
  }
}
```

优先级：

```text
CLI > 环境变量 > JSON logging > 内置默认值
```

允许的早期 fallback：

- `IIBOT_LOG_DIR` / `-Diibot.log.dir`
- `IIBOT_LOG_LEVEL` / `-Diibot.log.level`
- `IIBOT_OPS_LOG_LEVEL` / `-Diibot.ops.log.level`

实现约束：

- 配置加载前先初始化最小日志；
- 配置校验成功后应用 JSON 日志设置；
- 配置失败也必须能在控制台看到带配置路径的错误；
- 级别变更只影响日志，不触发业务重启；
- `levels` 中未知包名拒绝，避免静默误配；
- 日志目录创建失败 fail-closed，不静默退回当前目录。

## 9. 诊断命令

目标 CLI：

```bash
java -jar iibot-app.jar --check-config
java -jar iibot-app.jar --check-config --config config/bot
java -jar iibot-app.jar --log-level DEBUG
```

`--check-config` 只做加载与本地校验，不连接 QQ、不启动 Inbox/MCP：

- 输出每个配置源及其 order；
- 输出合并后的有效配置树，敏感值显示 `<redacted:env:NAME>`；
- 列出缺失/未设置的环境变量；
- 输出解析后的数据目录、日志目录、账号 aliases、账号 ID、loopback 约束结果；
- 错误格式包含 `file`、`jsonPointer`、`expected`、`actualType`、`hint`、`rc=1`。

如未来增加 `doctor`，只能做本地进程/端口/文件检查，不自动修复，不自动重启。

## 10. 不变量与失败语义

1. 日志调用抛出异常不得影响业务返回值或异常；
2. 日志中不得出现聊天正文、回复正文、用户备注正文、token、Authorization 和完整协议 JSON；
3. 工具参数只能记录 key 名，不能记录 value；
4. 日志格式错误不得导致进程退出；
5. 日志轮转失败只级联为 ERROR 日志，不得让消息重复发送；
6. `UNKNOWN` 不因日志重试或自动补发；
7. 事件字段、错误码和级别是稳定契约，需测试锁定。

## 11. 验收判据

1. 启动后 `logs/iibot.log` 与 `logs/iibot-ops.log` 存在且权限为 0600；
2. 默认 INFO 下可看到进程、配置摘要、账号注册、Inbox/MCP ready、入站 accepted、发送结果；
3. 设置 DEBUG 后能看到配置合并、游标推进、路由选择和去重命中，且默认关闭；
4. 以包含正文和 token 的测试数据执行入站/回复/工具调用，日志中正文和 token 命中数为 0；
5. `--check-config` 对合法配置 rc=0，并输出 sources、有效配置和脱敏后的 secret 引用状态；
6. `--check-config` 对未知字段/类型错误输出 file + JSON pointer + hint，rc=1；
7. 日志实现抛错时业务结果不变（日志包装器单测）；
8. 轮转大小和保留数可按配置生效（至少单测/临时目录 smoke）；
9. 任何真实 QQ/DSH 日志验收未跑时标 `NOT_RUN`。

## 12. 测试锚点

- `iibot-app/src/main/resources/log4j2.xml`
- `iibot-connect/src/main/java/io/mosire/iibot/connect/ops/OpsLog.java`
- `iibot-bot/src/main/java/io/mosire/iibot/bot/mcp/BotToolCallLoggingTool.java`
- `iibot-app/src/main/java/io/mosire/iibot/app/bot/BotMain.java`
- `iibot-app/src/main/java/io/mosire/iibot/app/bot/BotRuntime.java`
- `iibot-bot/src/main/java/io/mosire/iibot/bot/ingest/BotIngestionService.java`
- `iibot-bot/src/main/java/io/mosire/iibot/bot/reply/BotReplyService.java`
- `iibot-app/src/main/java/io/mosire/iibot/app/inbox/BotInboxRuntime.java`
- 新增日志测试使用临时目录与捕获 appender；禁止对真实 QQ 发送测试。

## 13. 修订记录

- 2026-10-28：将原 2026-10-27 运维事件设计扩展为日志分级、结构化关联、诊断 CLI、配置化轮转和正文/密钥零命中验收；原文件保留为历史设计依据。
