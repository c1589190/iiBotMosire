# config/bot

默认本地运行配置目录。

- 本目录没有 `*.json` 时，首次启动生成单一完整模板 `bot.json`（0600）并以 rc=2 退出；
- 不覆盖任何已有 JSON；`config/bot/*.json` 被 `.gitignore` 忽略；
- 生成和校验阶段不启动 QQ/Inbox/MCP，不访问网络；
- 配置只写环境变量名，例如 `IIBOT_NAPCAT_ACCESS_TOKEN` / `IIBOT_CONTROL_TOKEN`。

生成后先运行：

```bash
java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar --check-config
```

完整字段、示例和迁移表见 [`config/README.md`](../README.md)。
