# config — Bot 配置系统 v2

> 设计依据：[配置文件系统设计](../docs/superpowers/specs/2026-10-28-configuration-system.md)、
> [日志分级与诊断](../docs/superpowers/specs/2026-10-28-logging-levels-and-diagnostics.md)。
> P3 已实现单文件首次生成、多账号 schema、严格校验、来源追踪、脱敏诊断和 JSON 日志配置。
> P3 完成配置 schema/诊断；P4 启动全部 enabled account；P6b 要求多账号 direct-provider 工具显式传 `accountId`。

## 目录

```text
config/
  README.md
  schema/bot-config.schema.json   # 机器可读 schema，结构必须与 loader 一致
  examples/bot.full.json          # 单账号完整脱敏示例
  examples/bot.multi-account.json # 多账号示例
  bot/README.md                   # 默认运行目录说明
  bot/bot.json                    # 首次启动生成，本地文件，gitignored
```

`config/bot/*.json` 默认不提交；只提交 schema、README 和 `config/examples/` 下的脱敏示例。

## 从零开始

1. 在仓库根目录运行首次启动：

   ```bash
   java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar
   ```

   当 `config/bot/` 中没有任何 `*.json` 时，程序生成单一完整模板
   `config/bot/bot.json`（目录 0700、文件 0600），输出下一步提示并以 **rc=2** 退出。
   此过程只写本地文件，不启动 QQ/Inbox/MCP，不访问网络。

2. 编辑 `config/bot/bot.json`：至少把 `accounts.main.accountId` 改成真实 QQ 账号，
   确认 OneBot WS/HTTP 地址，并设置：

   ```bash
   export IIBOT_NAPCAT_ACCESS_TOKEN='...'
   export IIBOT_CONTROL_TOKEN='...'
   ```

   配置只保存环境变量名，绝不保存 token 值。

3. 校验配置，不连接网络：

   ```bash
   java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar --check-config
   ```

   rc=0 表示结构、跨字段、loopback 与 secret 环境变量均通过；rc=1 表示配置不可启动。
   stdout 会输出 sources、secret 的 `SET`/`MISSING` 状态和脱敏有效配置；错误只写 stderr。

4. 打印脱敏有效配置：

   ```bash
   java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar --print-effective-config
   ```

5. 启动：

   ```bash
   java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar
   ```

## Schema

顶层字段：

| 字段 | 必需 | 说明 |
|---|---|---|
| `$schema` | 否 | 建议指向 `../schema/bot-config.schema.json` |
| `dataDir` | 是 | 消息板/SQLite/媒体根目录；相对合并后配置目录解析 |
| `accounts` | 是 | `alias -> account` 对象，至少一个 enabled |
| `control` | 是 | Bot MCP 监听设置与 bearer token 环境变量名 |
| `inbox` | 是 | embedded Inbox 设置 |
| `messageBoard` | 否 | 摘要防抖、预览上限、outbox 和 weighting |
| `logging` | 否 | 日志目录、console/file、root/ops/包级级别和轮转 |

账号形状：

```json
{
  "accounts": {
    "main": {
      "enabled": true,
      "adapter": "onebot11",
      "accountId": "100000001",
      "oneBot": {
        "eventWebSocketUri": "ws://127.0.0.1:3001/",
        "httpBaseUri": "http://127.0.0.1:3000/",
        "accessTokenEnv": "IIBOT_NAPCAT_ACCESS_TOKEN",
        "privateTempGroupId": "",
        "suppressSelfMessages": true,
        "requestTimeoutSeconds": 10,
        "maxTextLength": 2000
      }
    }
  }
}
```

严格规则：

- `accounts` 至少一个 `enabled=true`；runtime 启动全部 enabled account；多账号下最近消息/远端历史/好友申请/用户备注工具必须显式传 `accountId`；
- alias 必须匹配 `[A-Za-z0-9._-]+`；所有 `accountId` 必须全局唯一；
- `adapter` 首版只允许 `onebot11`；
- WS URI 只允许 `ws/wss`，HTTP base URI 只允许 `http/https`，都必须带 host；
- `control.host`、`inbox.apiHost`、`inbox.mcpHost` 只允许回环地址；
- `control.bearerTokenEnv` 必需；`oneBot.accessTokenEnv` 可选。填写后必须是合法环境变量名，
  且启用账号的 secret 在启动/`--check-config` 时必须非空；disabled 账号不强制；
- 未知字段 fail-closed，不静默忽略；
- `logging.levels` 只接受 `io.mosire.iibot.connect` / `bot` / `app` / `inbox`
  及其子包、`iibot.ops`，级别只接受 `TRACE/DEBUG/INFO/WARN/ERROR/OFF`。

## 多文件合并

- `--config` 可重复，值可以是文件或目录；目录只读直接子级非隐藏 `*.json`，按文件名升序；
  隐藏文件（`.` 开头）不参与读取；`*.local.json` 是显式参与合并的本地覆盖文件，不靠隐式忽略；
- 多个源必须在同一目录；相对路径以该目录为基准；
- 对象递归深合并；数组、标量和 `null` 由后文件整体覆盖；
- 同一个 JSON 文件内重复 key 拒绝；
- 合并后每个 JSON pointer 保留来源文件与覆盖链；错误包含 `file`、`jsonPointer`、
  `expected`、`actual`、`hint`。

示例：

```bash
java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar \
  --config config/bot/bot.json \
  --config config/bot/local.override.json \
  --check-config
```

## CLI

```text
--config <file-or-dir>          可重复；目录按文件名排序深合并
--check-config                  加载+校验，不启动网络；rc=0/1
--print-effective-config        输出脱敏有效 JSON；secret 只显示 env 名/状态
--log-level <LEVEL>             TRACE|DEBUG|INFO|WARN|ERROR|OFF；覆盖本次 root/ops
-h, --help                      帮助
```

`--check-config` 和 `--print-effective-config` 都不会构造 BotRuntime；真实 QQ/DSH/MCP/Inbox
联调必须另行获得授权，未运行时要写 `NOT_RUN`。

配置错误示例（stderr）：

```text
CONFIG_INVALID
file: /path/config/bot/bot.json
jsonPointer: /accounts/main/oneBot/httpBaseUri
expected: http/https URI with host
actual: string
hint: URI 缺少 host: not-a-uri
```

## 日志

`logging` 块在首个业务 Logger 初始化前生效：

```json
{
  "logging": {
    "directory": "../logs",
    "console": true,
    "file": true,
    "rootLevel": "INFO",
    "opsLevel": "INFO",
    "levels": {
      "io.mosire.iibot.connect": "INFO",
      "io.mosire.iibot.bot": "INFO",
      "io.mosire.iibot.app": "INFO"
    },
    "maxFileSize": "20MB",
    "maxHistory": 30
  }
}
```

优先级：`--log-level`（root/ops）> JSON `logging.rootLevel/opsLevel` > 早期环境变量/系统属性
fallback > 内置默认。包级 `logging.levels` 按配置应用；`--log-level` 不覆盖显式包级级别。
日志文件 `iibot.log`/`iibot-ops.log` 使用 0600 权限，按天和大小滚动并 gzip。
日志禁止正文、token、Authorization 和完整协议 JSON。

## 旧配置手工迁移

| 旧字段 | 新字段 |
|---|---|
| 顶层 `accountId` | `accounts.<alias>.accountId` |
| `napcat` | `accounts.<alias>.oneBot` |
| `summary.debounceMillis` | `messageBoard.summaryDebounceMillis` |
| `summary.maxMessagesPerDigest` | `messageBoard.maxPreviewMessages` |
| 顶层 `outbox` | `messageBoard.outbox` |
| 顶层 `weighting` | `messageBoard.weighting` |
| `weighting.maxMessagesPerDigest` | 删除；使用 `messageBoard.maxPreviewMessages` |
| 无 | `logging` |

`--migrate-config` 不是 P3 必需项；当前支持文档手工迁移。旧 `--mode legacy` 和文游配置不迁移。
