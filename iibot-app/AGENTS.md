# iibot-app —— 局部 AGENTS

## 职责

组合根：配置、连接、Taskflow、存储、MCP 与可运行入口的唯一装配点。

## 边界

- 根路由与常驻红线见 [../../AGENTS.md](../AGENTS.md)。
- 项目设计见 [../../docs/superpowers/AGENTS.md](../docs/superpowers/AGENTS.md)。
- 当前状态见 [../../docs/agents/STATUS.md](../docs/agents/STATUS.md)。
- 不得跳过 `ToolCallAuthorizer`（如模块依赖 AgentLib）；不得把 OneBot/NapCat 类型泄漏到 `iibot-taskflow` 以外/业务层。
- 本模块只改自己的代码与测试；跨模块契约先修改设计文档。

## 验证

```bash
mvn -B -ntp -pl iibot-app -am test
```

> 未实际运行的命令不得在报告里写成通过。
