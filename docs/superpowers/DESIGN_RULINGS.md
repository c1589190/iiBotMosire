# 设计裁定索引（项目设计）

> **本文属于项目设计，不属于项目维护。**
> 维护裁定在 [`../agents/MAINTENANCE_RULINGS.md`](../agents/MAINTENANCE_RULINGS.md)；
> 当前状态只在 [`../agents/STATUS.md`](../agents/STATUS.md)。
> 历史文游裁定已归档：[`../archive/legacy-game/README.md`](../archive/legacy-game/README.md)。

## 一、当前有效裁定

| 编号 | 主题 | 状态 | 正本 |
|---|---|---|---|
| D-001 | QQ 接入与可替换 Adapter | 有效 | [需求原稿](specs/2026-10-24-user-requirements-source.md) |
| D-006 | Java 21 + Maven | 有效 | [需求原稿](specs/2026-10-24-user-requirements-source.md) |
| D-009 | 文档体系与设计/维护分账 | 有效 | [需求原稿](specs/2026-10-24-user-requirements-source.md) |
| D-010 | 私聊 + 群聊来源 thread | 有效 | [需求原稿](specs/2026-10-24-user-requirements-source.md) |
| D-019 | 外发逻辑消息最小间隔 | 有效 | [需求原稿](specs/2026-10-24-user-requirements-source.md) |
| D-023 | 文本约束只在 Skill/AGENTS.md，程序不清洗 | 有效 | [需求原稿](specs/2026-10-24-user-requirements-source.md) |
| D-024 | Inbox 来源未读桶/预览/unreadCount | 有效；手动 ack 部分由 D-026 替代 | [需求原稿](specs/2026-10-24-user-requirements-source.md) |
| D-025 | iibot 只做统一 QQ Agent 客户端；文游退出默认产品 | 已实施 P1 | [需求原稿](specs/2026-10-24-user-requirements-source.md) |
| D-026 | 多账号路由、服务端 read/ack、统一 `qq_*` MCP | 已实施 P4–P6 | [需求原稿](specs/2026-10-24-user-requirements-source.md)；[目标架构](specs/2026-10-28-unified-qq-agent-client-architecture.md) |
| D-027 | debug/info 日志与易用配置系统 | 已实施 P2/P3 | [需求原稿](specs/2026-10-24-user-requirements-source.md)；[日志](specs/2026-10-28-logging-levels-and-diagnostics.md)；[配置](specs/2026-10-28-configuration-system.md) |
| D-028 | 依赖最新且可复现、文档一致 | P7a 已升级；AgentLib 0.2.4 发布待补 | [需求原稿](specs/2026-10-24-user-requirements-source.md)；[计划](plans/2026-10-28-unified-qq-agent-client-refactor-plan.md) |

## 二、当前专题入口

| 主题 | 入口 | 说明 |
|---|---|---|
| 统一 QQ Agent 客户端架构 | [`specs/2026-10-28-unified-qq-agent-client-architecture.md`](specs/2026-10-28-unified-qq-agent-client-architecture.md) | 多账号、消息板事实源、服务端 read/ack、统一工具面 |
| 配置文件系统 v2 | [`specs/2026-10-28-configuration-system.md`](specs/2026-10-28-configuration-system.md) | accounts schema、来源追踪、`--check-config`、单文件 bootstrap |
| 日志分级与诊断 | [`specs/2026-10-28-logging-levels-and-diagnostics.md`](specs/2026-10-28-logging-levels-and-diagnostics.md) | INFO/DEBUG、MDC 关联、配置化轮转、脱敏 |
| OneBot 11 富消息与历史 | [`specs/2026-10-27-onebot11-rich-messaging-history-media-and-requests.md`](specs/2026-10-27-onebot11-rich-messaging-history-media-and-requests.md) | 消息段、历史、媒体、好友申请契约 |
| Inbox Mode A 接入 | [`specs/2026-10-25-inbox-mode-a-notification-workflow.md`](specs/2026-10-25-inbox-mode-a-notification-workflow.md) | 程序嵌入 Inbox 的通知/唤醒接线 |
| 当前实施计划 | [`plans/2026-10-28-unified-qq-agent-client-refactor-plan.md`](plans/2026-10-28-unified-qq-agent-client-refactor-plan.md) | P0–P7 责任区、验收、风险 |

## 三、当前未裁定

1. 多 consumer 并发隔离与 per-consumer Inbox 绑定；
2. `qq_account_use` 动态默认账号工具是否开放给普通 Agent；
3. `qq_inbox_binding_descriptor` 是否完全移出 Agent MCP，改由宿主专用 handoff；
4. 回复成功后是否自动 ack（当前第一版统一要求显式 ack）。

## 四、设计与计划的边界

- `specs/**`：设计与契约，能作为实现依据；未获用户确认的标 `提议`。
- `plans/**`：按设计拆出的执行计划；不是设计本身。
- 历史文游 specs/plans/status/reports 在 `docs/archive/legacy-game/`，不进入当前导航。
- 维护流程去 [`../agents/`](../agents/AGENTS.md)。
