# config

> **2026-10-28 规划中变更**：配置系统将按
> [配置文件系统设计](../docs/superpowers/specs/2026-10-28-configuration-system.md)
> 升级为多账号 schema、单文件首次生成、`--check-config` 和脱敏有效配置输出。
> 本文件以下内容描述当前 P1 实现，实施 P3 后整体替换。

运行配置目录。Bot 是唯一启动模式：

```bash
java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar
```

如果 `config/bot/` 中没有任何 `*.json`，第一次启动会自动生成
`00-base.json`、`10-napcat.json`、`20-control-inbox.json`、`30-policy.json`
四个模板并退出（exit 2），不会启动 QQ/Inbox/MCP。编辑模板并设置环境变量后，
再次执行同一命令即可启动。已有 JSON 不会被覆盖。

不带 `--config` 时读取 `config/bot/`。

## 日志

默认同时写控制台和滚动文件：

```text
logs/iibot.log        # 技术日志
logs/iibot-ops.log    # event=... 运维事件流
```

可通过系统属性/环境变量覆盖：

| 配置 | 系统属性 | 环境变量 | 默认 |
|---|---|---|---|
| 日志目录 | `-Diibot.log.dir` | `IIBOT_LOG_DIR` | `logs` |
| 技术日志级别 | `-Diibot.log.level` | `IIBOT_LOG_LEVEL` | `INFO` |
| 运维日志级别 | `-Diibot.ops.log.level` | `IIBOT_OPS_LOG_LEVEL` | `INFO` |
| 单文件最大值 | `-Diibot.log.maxSize` | `IIBOT_LOG_MAX_SIZE` | `20 MB` |
| 归档保留数 | `-Diibot.log.maxHistory` | `IIBOT_LOG_MAX_HISTORY` | `30` |

两个文件按天和大小滚动，归档 gzip 压缩。日志只记录 ID、状态、计数、错误码与耗时，
不记录聊天正文、token、Authorization 或完整协议 JSON。`debug` 仅用于临时排障；
P3 会把 `logging` 块纳入 JSON 配置，并提供 `--log-level`。

完整目标设计见
[日志分级与诊断](../docs/superpowers/specs/2026-10-28-logging-levels-and-diagnostics.md)。

## 多文件规则

- `--config` 可重复；值可以是单个 JSON 文件，也可以是目录。
- 目录只读取直接子级的 `*.json`，按文件名升序装载。
- 多个源必须位于同一目录；配置中的相对路径以该目录为基准。
- 对象递归合并；数组、标量和 `null` 以后文件为准。
- 同一个 JSON 文件内重复 key 仍然拒绝，不会静默覆盖。
- raw token / QQ 凭据只放环境变量；配置只写 `*TokenEnv` / `accessTokenEnv` 名称。

## Inbox embedded 网络参数

`inbox` 块支持：

```json
{
  "apiHost": "127.0.0.1",
  "apiPort": 0,
  "mcpHost": "127.0.0.1",
  "mcpPort": 0,
  "mcpPath": "/mcp",
  "heartbeatMillis": 15000,
  "claimSweepIntervalMillis": 5000
}
```

`apiPort/mcpPort=0` 表示随机回环端口；固定端口绑定失败会 fail-closed。
`apiHost/mcpHost` 只允许 loopback；`mcpPath` 必须是以 `/` 开头且不含
query/fragment 的路径。

## Inbox 未读预览

Inbox item 只保存每个来源的最后 `n` 条未读摘要作为预览；完整正文仍由
`bot_thread_read` / `bot_thread_history` 等工具读取。

`n` 使用现有两个配置项的有效上限：

```json
{
  "summary": { "maxMessagesPerDigest": 100 },
  "weighting": { "maxMessagesPerDigest": 20 }
}
```

上例有效预览上限为 20。item content 还会返回 `unreadCount` 与
`hasMoreUnread`；预览不足时 Agent 必须读取完整来源历史，不能把预览当全文。

## 当前布局

```text
config/
  bot/
    README.md
  examples/
    bot/       # 可复制到 config/bot/ 的示例片段
```

当前实际运行配置由首次启动生成到 `config/bot/`。示例片段在
`config/examples/bot/`，不是默认运行配置；先复制并改掉占位账号/环境变量名。

## 显式示例

```bash
# 一个目录
java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar --config config/bot

# 多个文件；按参数顺序覆盖
java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar \
  --config config/bot/00-base.json \
  --config config/bot/10-napcat.json \
  --config config/bot/20-local-override.json
```
