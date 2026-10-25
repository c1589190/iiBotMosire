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

## 验证

```bash
mvn -B -ntp -pl iibot-app -am test
```

> 未实际运行的命令不得在报告里写成通过。
