# config/bot

默认 bot 配置目录。

第一次执行 `java -jar iibot-app-0.1.0-SNAPSHOT.jar` 且本目录没有任何 `*.json` 时，
程序会自动生成四个配置模板并退出，不会启动 QQ/Inbox/MCP。再次执行同一命令即启动。
也可以手动把 `config/examples/bot/*.json` 复制到这里。

生成后至少修改：

- `00-base.json.accountId`
- `10-napcat.json.eventWebSocketUri` / `httpBaseUri` / `accessTokenEnv`
- `20-control-inbox.json.control.bearerTokenEnv` 和 Inbox id/label
- 如需要固定 Inbox API/MCP 端口，修改同文件的 `inbox.apiPort` / `mcpPort`；
  默认 `0` 表示随机回环端口

实际 token 放环境变量，不写进本目录。默认目录没有 `*.json` 时启动会 fail-closed。
