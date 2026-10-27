# config

运行配置目录。Bot 是默认启动模式：

```bash
java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar
```

如果 `config/bot/` 中没有任何 `*.json`，第一次启动会自动生成
`00-base.json`、`10-napcat.json`、`20-control-inbox.json`、`30-policy.json`
四个模板并退出（exit 2），不会启动 QQ/Inbox/MCP。编辑模板并设置环境变量后，
再次执行同一命令即可启动。已有 JSON 不会被覆盖。

不带 `--config` 时读取 `config/bot/`；legacy 必须显式：

```bash
java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar --mode legacy
```

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

## 推荐布局

```text
config/
  bot/
    00-base.json
    10-napcat.json
    20-control-inbox.json
    30-summary-outbox-weighting.json
  legacy/
    ... legacy 配置源 ...
  examples/
    bot/       # 可复制到 config/bot/ 的示例片段
```

示例片段在 `config/examples/bot/`，不是默认运行配置；先复制并改掉占位账号/环境变量名。

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
