# 配置文件系统设计

> **状态**：已实现（P3，2026-10-28）
> **本文取代**：无；在 D-022 多文件配置基础上扩展到多账号、日志、诊断与安全校验。
> **用户原话**：
> > 「此外还有之前一直没补的运维，包括完善的debug/info log、完善且易用的配置文件系统」
> **关联**：[统一 QQ Agent 客户端架构](2026-10-28-unified-qq-agent-client-architecture.md)、[日志分级与诊断](2026-10-28-logging-levels-and-diagnostics.md)、[改造计划](../plans/2026-10-28-unified-qq-agent-client-refactor-plan.md)

## 1. 目标

配置文件系统必须让第一次使用者在不读源码的情况下完成：

1. 生成一份可启动的配置；
2. 看懂每个字段的用途、默认值、取值范围和密钥引用方式；
3. 用多个小文件覆盖不同环境；
4. 启动前发现错误，并获得文件名、JSON 路径、期望类型和修复提示；
5. 查看最终生效配置，同时不泄露 secret；
6. 配置多个 OneBot/QQ 账号，并让消息板按账号路由。

## 2. 非目标

- 不在 JSON 中保存 raw token、密码、Authorization 或完整 QQ 凭据；
- 不提供远程配置中心、热更新全部字段或任意脚本表达式；
- 不保留 `--mode legacy` 和文游配置；
- 不把日志文件路径、端口、账号等配置散落到多个隐式来源；
- 不允许未知字段静默忽略。

## 3. 当前事实

- `JsonConfigBundle` 支持 `--config` 重复、目录直接子级 JSON、文件名升序、对象深合并、数组/标量覆盖、同文件重复 key 拒绝。
- `ConfigArguments` 只支持 `--config` 与 `--help`；没有 `--check-config`、effective-config 输出或日志级别参数。
- `BotConfigLoader` 严格拒绝未知字段，但错误多只带字段名，不带来源文件和 JSON pointer；合并后无法知道某个值来自哪个文件。
- `BotConfig` 当前是单账号结构：顶层 `accountId` + 单个 `napcat`。
- `BotConfigBootstrap` 首次启动生成四个文件：`00-base.json`、`10-napcat.json`、`20-control-inbox.json`、`30-policy.json`；没有生成一份完整可读的单文件示例，也没有 `$schema` 提示。
- `logging` 目前不受 JSON 配置控制；日志目录和级别主要靠系统属性/环境变量。
- 默认生成目录没有统一被 `.gitignore` 保护，存在把本机运行配置误提交的风险。
- 文档 `config/README.md` 与 `config/examples/bot/` 仍混合 legacy 与当前 bot 说明。

## 4. 配置原则

1. **单一 schema**：一个 `bot-config.schema.json` 是字段、类型、默认值和约束的机器可读来源。
2. **严格**：未知字段、重复账号、错误类型、非法 URI、非回环监听、缺失 secret 环境变量全部 fail-closed。
3. **可解释**：每个最终值都能追溯到文件、环境变量覆盖或默认值。
4. **可覆盖**：小文件可覆盖大文件；敏感值只用环境变量引用。
5. **可诊断**：不启动业务就能检查配置并打印脱敏有效配置。
6. **密钥零明文**：配置中只出现 `*TokenEnv` / `accessTokenEnv` 的变量名。

## 5. 目标配置形状

以下为提议 schema，实施时由 JSON Schema 与 Java record 同步冻结。

```json
{
  "$schema": "../schema/bot-config.schema.json",
  "dataDir": "./data",
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
  },
  "control": {
    "host": "127.0.0.1",
    "port": 8765,
    "path": "/mcp/bot",
    "bearerTokenEnv": "IIBOT_CONTROL_TOKEN"
  },
  "inbox": {
    "dataDir": "./data/inbox",
    "inboxId": "iibot-bot",
    "bindingLabel": "dsh-bot",
    "channels": ["qq"],
    "bindingTtlHours": 24,
    "apiHost": "127.0.0.1",
    "apiPort": 0,
    "mcpHost": "127.0.0.1",
    "mcpPort": 0,
    "mcpPath": "/mcp",
    "heartbeatMillis": 15000,
    "claimSweepIntervalMillis": 5000
  },
  "messageBoard": {
    "summaryDebounceMillis": 2000,
    "maxPreviewMessages": 20,
    "outbox": {
      "sendTimeoutSeconds": 15,
      "minSendIntervalMillis": 150
    },
    "weighting": {
      "defaultPrivateSourceWeight": 300,
      "defaultGroupSourceWeight": 0,
      "defaultNotifyThreshold": 100,
      "signalWeights": {
        "DM": 200,
        "MENTION": 200,
        "REPLY_TO_BOT": 180,
        "NAME": 120,
        "COMMAND": 250
      },
      "keywordWeights": {},
      "aliases": [],
      "commandPrefixes": ["/", "!"],
      "quietMillis": 180000,
      "cooldownMillis": 60000
    }
  },
  "logging": {
    "directory": "./logs",
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

### 5.1 账号

- `accounts` 是 `alias -> account config` 映射，alias 必须匹配 `[a-zA-Z0-9._-]+`；
- `accountId` 是消息板分区键，必须全局唯一；
- `adapter` 首版只允许 `onebot11`；
- 启动时必须把配置的 `accountId` 与 Adapter 查询到的登录账号核对；不一致时该账号 fail-closed；
- `accessTokenEnv` 可为空；若填写，必须指向非空环境变量；
- 账号级相对路径不允许；账号配置只保存连接信息。

### 5.2 messageBoard

- `summaryDebounceMillis`：入站摘要防抖窗口；
- `maxPreviewMessages`：Inbox 预览最多保留的入站消息数；
- `outbox.sendTimeoutSeconds`：发送回执等待上限；
- `outbox.minSendIntervalMillis`：全局逻辑消息最小发送间隔；
- `weighting`：来源权重、触发词、冷却和优先级，只影响唤醒与排序，不过滤消息。

### 5.3 logging

详细字段、优先级和实现约束见 [日志分级与诊断](2026-10-28-logging-levels-and-diagnostics.md) 第 8 节。配置中不允许写 raw token 路径或值。

## 6. 配置源与优先级

### 6.1 源

```text
--config <file-or-dir> ...       显式配置源，可重复
IIBOT_CONFIG=<file-or-dir>       仅在没有 --config 时使用
<repo>/config/bot/               默认目录
```

- CLI 显式 `--config` 优先，不自动模板生成；
- 未显式指定时读取默认目录；目录无 `*.json` 才允许首次模板生成；
- 同一目录内只读取直接子级 `*.json`，按文件名升序；
- 隐藏文件、`*.local.json` 按明确规则处理，不能靠隐式忽略造成“改了没生效”。

### 6.2 合并

- 对象递归合并；
- 数组、标量和 `null` 由后文件整体覆盖；
- 同一文件内重复 key 拒绝；
- 合并时记录每个 JSON pointer 的来源文件和覆盖者，用于错误和 `--check-config`；
- 多文件必须位于同一目录，相对路径以该目录为基准；若未来允许跨目录，必须为每个字段定义独立 base，不在本阶段做。

### 6.3 运行期覆盖

- 密钥：只通过 `*Env` 指定的环境变量读取；
- 日志早期参数：`IIBOT_LOG_DIR`、`IIBOT_LOG_LEVEL`、`IIBOT_OPS_LOG_LEVEL`，仅用于配置加载前和紧急排障；
- 其余运行参数不提供隐式环境变量覆盖，避免“配置文件 vs 环境变量”二义性。

## 7. 首次生成与文件布局

### 7.1 推荐布局

```text
config/
  README.md
  schema/
    bot-config.schema.json
  examples/
    bot.full.json
    bot.multi-account.json
  bot/
    README.md
    bot.json             # 首次运行时生成，默认 gitignored
```

### 7.2 生成规则

- 默认目录没有任何可读 JSON 时，生成**单一完整** `config/bot/bot.json` 后退出 rc=2；
- 生成阶段不启动 QQ、Inbox、MCP，不访问网络；
- 生成文件权限 0600；
- 生成文件只含环境变量名，不含 secret；
- 文件顶部无法写注释，因此在 `bot.json.example`/README 中给逐字段注释；
- 生成后输出下一步命令、必须修改的账号字段和缺失的环境变量；
- 不覆盖任何已有 JSON；
- `config/bot/*.json` 默认不提交；如需共享配置，提交到 `config/examples/` 的脱敏示例。

### 7.3 多文件示例

```bash
# 基础结构
java -jar iibot-app.jar --config config/bot/00-base.json

# 本机账号覆盖
java -jar iibot-app.jar \
  --config config/bot/00-base.json \
  --config config/bot/10-accounts.local.json

# 目录模式：按文件名排序
java -jar iibot-app.jar --config config/bot
```

## 8. 校验与错误诊断

### 8.1 解析顺序

```text
CLI 参数
  → 解析配置源列表
  → 读取 JSON 文档并记录 fingerprint
  → 深合并并记录来源
  → schema/record 类型校验
  → 跨字段不变量校验
  → secret env 存在性检查
  → 路径规范化与目录可写性检查
  → peer host/port/path 约束
  → 生成有效配置
  → 只有在全部通过后才启动运行时
```

### 8.2 错误格式

```text
CONFIG_INVALID
  file: /path/config/bot/10-accounts.json
  jsonPointer: /accounts/main/oneBot/httpBaseUri
  expected: http/https URI with host
  actual: string
  hint: httpBaseUri 应指向 OneBot HTTP Action 根地址
```

禁止只输出：

```text
配置错误: 缺少 host
```

### 8.3 跨字段检查

- 账号 alias/accountId 唯一；
- `control.host/apiHost/mcpHost` 只能回环；
- Inbox API/MCP 端口范围合法且不为负数；
- `control.path`/`mcpPath` 格式合法；
- `minSendIntervalMillis` 在允许范围内；
- secret env 名合法；启用账号和 control 的必需 secret 缺失时失败；
- 同一目录多文件相对路径基准一致；
- 未知顶层/嵌套字段拒绝并给出近似建议（例如 `napcat` 已迁移到 `accounts.<alias>.oneBot`）。

## 9. 有效配置与 CLI

### 9.1 命令

```bash
java -jar iibot-app.jar --check-config
java -jar iibot-app.jar --check-config --config config/bot
java -jar iibot-app.jar --print-effective-config --config config/bot
```

- `--check-config`：本地校验，不启动网络组件，退出码 0/1；
- `--print-effective-config`：输出脱敏 JSON；secret 只输出 `<env:VAR_NAME:SET|MISSING>`；
- `--log-level DEBUG`：仅覆盖本次启动的 root 日志级别；证据中必须写明这是临时覆盖；
- `--help` 显示所有参数与配置文档入口。

### 9.2 脱敏

以下字段永远不得输出原值：

- `*TokenEnv` 不是 secret 值本身，可以输出变量名；
- 任何字段名包含 `token`、`secret`、`password`、`authorization`、`credential` 时只输出占位；
- 环境变量存在性输出为 `SET` / `MISSING`，不输出长度、前缀或 hash；
- 配置指纹只输出 SHA-256 前 12 位，不能反推 secret。

## 10. 迁移

当前旧配置是单账号、顶层 `napcat`、`summary`、`outbox`、`weighting`。迁移到新 schema：

| 旧 | 新 |
|---|---|
| `accountId` | `accounts.<alias>.accountId` |
| `napcat` | `accounts.<alias>.oneBot` |
| `summary.debounceMillis` | `messageBoard.summaryDebounceMillis` |
| `summary.maxMessagesPerDigest` | `messageBoard.maxPreviewMessages` |
| `outbox` | `messageBoard.outbox` |
| `weighting` | `messageBoard.weighting` |
| 顶层无 | `logging` |

实施 P3 时提供两种迁移路径：

1. 文档手工迁移（必须支持，作为兼容底线）；
2. `--migrate-config <old> --output <new>` 一次性转换，转换前不覆盖已有文件，转换后提示 secret env 仍需外部设置。

迁移失败必须保留原文件不变。旧 `--mode legacy` 与文游配置不迁移，直接删除。

## 11. 文件与权限

- 运行配置默认写 `config/bot/bot.json`，目录权限 0700、文件 0600；
- `.gitignore` 至少忽略 `config/bot/*.json`，保留 README/schema/examples；
- 配置文件不得包含 raw secret；如检测到疑似 token 字段值（长度/模式），给出拒绝或强警告；
- 日志目录、SQLite、Inbox descriptor/token 文件路径均从配置解析并写入启动摘要，但 secret 内容不写日志。

## 12. 验收判据

1. 空目录首次启动生成单一 `bot.json`，rc=2，权限 0600，不启动任何网络组件；
2. 合法单账号配置 `--check-config` rc=0，退出后无 QQ/Inbox/MCP 连接；
3. 未知字段输出 file + JSON pointer + hint，rc=1；
4. 类型错误、非法 URI、非回环监听、重复 alias/accountId、缺失必需 secret env 均 fail-closed；
5. 多文件合并顺序与覆盖规则有测试，数组整体替换、对象深合并；
6. `--print-effective-config` 中的 token、Authorization、secret 环境变量值零命中；
7. 多账号配置能生成稳定的 accountId/alias 集合，供 AccountRegistry 使用；
8. 日志配置应用后 root/ops/package levels 生效，非法级别/未知包拒绝；
9. 文档从零到启动无需读源码；
10. `tools/gate.sh docs` 通过，真实启动 smoke 未跑写 `NOT_RUN`。

## 13. 测试锚点

- `iibot-app/src/main/java/io/mosire/iibot/app/config/ConfigArguments.java`
- `iibot-app/src/main/java/io/mosire/iibot/app/config/JsonConfigBundle.java`
- `iibot-app/src/main/java/io/mosire/iibot/app/config/SecretProvider.java`
- `iibot-app/src/main/java/io/mosire/iibot/app/bot/BotConfig.java`
- `iibot-app/src/main/java/io/mosire/iibot/app/bot/BotConfigLoader.java`
- `iibot-app/src/main/java/io/mosire/iibot/app/bot/BotConfigBootstrap.java`
- `config/schema/bot-config.schema.json`
- `config/README.md`、`config/examples/*`
- 新增测试覆盖：来源追踪、错误 pointer、脱敏、环境变量、多账号、日志级别、迁移。

## 14. P3 实施记录

已实现：

- 顶层 `$schema/dataDir/accounts/control/inbox/messageBoard/logging` schema；
- `accounts` alias 映射与多账号配置校验；P4 已让 runtime 启动全部 enabled account，第一个 enabled 作为 direct-provider 的 active 账号；
- 来源文件/JSON pointer/expected/actual/hint 结构化错误；
- `--check-config`、`--print-effective-config`、`--log-level`；
- 单一 `config/bot/bot.json` 首次生成，0600/0700、不覆盖、rc=2；
- 脱敏有效配置输出，secret 只显示 `<env:NAME:SET|MISSING>`；
- JSON `logging` 在首个业务 Logger 初始化前应用，支持 root/ops/包级级别、console/file 开关和轮转属性；
- schema、单账号示例、多账号示例及手工迁移表。

未实现 / 后续：

- `--migrate-config` 可选迁移命令；
- P4 多账号运行和出站路由已实现；
- 配置热更新。

## 15. 修订记录

- 2026-10-28：提出统一 schema、多账号配置、日志配置、有效配置诊断、首次单文件生成和旧配置迁移。
- 2026-10-28 P3：配置 v2、CLI 诊断、来源追踪、单文件 bootstrap、JSON 日志应用与示例/schema 已实现。
