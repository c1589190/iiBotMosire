# iibot-app —— 局部 AGENTS

## 职责

组合根：配置、连接、Taskflow、存储、MCP、InboxMosire Mode A 生命周期与可运行入口的唯一装配点。

## 边界

- 根路由与常驻红线见 [../../AGENTS.md](../AGENTS.md)。
- 项目设计见 [../../docs/superpowers/AGENTS.md](../docs/superpowers/AGENTS.md)。
- 当前状态见 [../../docs/agents/STATUS.md](../docs/agents/STATUS.md)。
- 不得跳过 `ToolCallAuthorizer`（如模块依赖 AgentLib）；不得把 OneBot/NapCat 类型泄漏到 `iibot-taskflow` 以外/业务层。
- 本模块只改自己的代码与测试；跨模块契约先修改设计文档。
- InboxMode A 只在 `iibot-app` 组合：embedded server/token/descriptor/通知桥不得下沉到业务域；
  notification payload 只能含业务引用，不得包含正文、路径或 token。
- `app/media` 只保存受控图片资产并把 `ToolAsset` 注入 Bot MCP；不得把 raw URL/字节写入日志或工具文本。
- `BotConfig.weighting` 只保存权重默认值和关键词策略；来源权重状态在 `bot.db`，
  不写入 raw token/正文。
- 根 `config/` 是默认启动配置入口；`--config` 可重复接收文件/目录，同一目录内
  `*.json` 按文件名排序深合并。`--mode` 省略时默认 bot，legacy 必须显式
  `--mode legacy`。
- `BotConfig.Inbox` 的 apiHost/apiPort/mcpHost/mcpPort/mcpPath/heartbeatMillis/
  claimSweepIntervalMillis 直接传给 inbox-embedded；host 只允许 loopback，
  port=0 表示随机端口，固定端口冲突必须 fail-closed。
- 默认 `config/bot/` 完全没有 `*.json` 时，首次启动只生成内置模板并退出；
  已有配置不得覆盖，也不得在首次生成阶段启动 QQ/Inbox/MCP。

## 验证

```bash
mvn -B -ntp -pl iibot-app -am test
```

> 未实际运行的命令不得在报告里写成通过。
