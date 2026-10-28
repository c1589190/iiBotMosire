# 历史快照：2026-10-24 首个纵向切片

> **历史快照**：本文件只记录该时点的事实，不是当前状态入口。
> **当前实时状态**：[`../../agents/STATUS.md`](../../agents/STATUS.md)
> **勿据此开工**：后续变更以新 STATUS 与设计文档为准。

## 完成范围

- 4 模块 Maven reactor + Maven Wrapper 3.9.16。
- `iibot-connect`：NapCat/OneBot 11 事件 WS + HTTP Action、鉴权、去重、限速、分段、重连、心跳、发送三态。
- `iibot-connect-agent`：`qq_*` AgentTool、角色/资源权限、`ToolCallAuthorizer` 执行。
- `iibot-taskflow`：领域状态机、秘书/GM brief、自然语言决策草案、私聊硬确认、中央推演、事件审计、外部控制工具（18 个，含设定提案/审核）。
- `iibot-app`：严格配置、世界事实、Workspace/Docs、SQLite 审计、outbox、逐用户 mailbox、自动冻结、Bearer MCP 传输、shaded Main。
- 真实外部依赖均未连接。
- 完整领域状态快照原子持久化/重启恢复已实现；逐事件回放未实现。

## 实际证据

```text
./mvnw -v
Apache Maven 3.9.16
Java 21.0.12.1
rc=0

tools/gate.sh full
BUILD SUCCESS
Tests run: 175, Failures: 0, Errors: 0, Skipped: 0
产出 iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar

java -jar iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar --help
rc=0
```

## 证据边界

- 这是 **Mock/Fake/本地 socket** 测试，不是真实 NapCat、真实 LLM 或真实 DSH 连接。
- SQLite 事件库当前是审计账本，不是已实现的引擎回放恢复。
- 没有 Git 提交。
