# iibot-connect —— 局部 AGENTS

## 职责

独立外部接入 Lib：统一消息/连接 SPI、标准 OneBot 11 与 LLBot/NapCat 扩展能力。不依赖 AgentLib，不实现业务域。当前实现包名暂保留 `napcat` 以兼容历史，新增能力按标准 OneBot 11 设计。

## 边界

- 根路由与常驻红线见 [../../AGENTS.md](../AGENTS.md)。
- 项目设计见 [../../docs/superpowers/AGENTS.md](../docs/superpowers/AGENTS.md)。
- 当前状态见 [../../docs/agents/STATUS.md](../docs/agents/STATUS.md)。
- 本模块不依赖 AgentLib、SQLite、Inbox 或业务域；OneBot/NapCat 类型只能出现在接入层，不得泄漏到 `iibot-bot` 业务模型。
- 本模块只改自己的代码与测试；跨模块契约先修改设计文档。

## 验证

```bash
mvn -B -ntp -pl iibot-connect -am test
```

> 未实际运行的命令不得在报告里写成通过。
