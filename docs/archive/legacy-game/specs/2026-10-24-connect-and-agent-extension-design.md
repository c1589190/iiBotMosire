# 外部接入 Lib 与 Agent 扩展层设计（已确认方向）

> **状态**：有效（用户 2026-10-24 已确认分层方向；connect / connect-agent / taskflow / app 均已实现并测试）
> **日期**：2026-10-24
> **本文取代**：无
> **用户原话**：见 [`2026-10-24-user-requirements-source.md`](2026-10-24-user-requirements-source.md) §五；补充原话：
> 「先把和NapcatQQ等外部接入框架的对接给做成一个标准的项目内lib，然后再让其他模块依赖这个独立lib，也方便后续复用」
> 「这个包可以做一个扩展层，这个扩展层同时依赖connect包和agentlib，提供connect包对标准Agent的工具、工具权限支持；在这些基础设施之上实现交互-推演任务流」

## 1. 分层

```text
iibot-connect
  独立外部接入 Lib；不依赖 AgentLib/业务域
        ▲
        │
iibot-connect-agent
  扩展层：依赖 connect + agentlib-mosire
  把 connect 能力变成标准 AgentTool、权限元数据和 MCP 可复用工具
        ▲
        │
iibot-taskflow
  交互-推演任务流：秘书/GM/玩家硬确认/回合/中央推演
        ▲
        │
iibot-app
  组合根与运行入口
```

**依赖红线**：

- `iibot-connect` 不 import `io.mosire.agentlib.*`、`io.mosire.iibot.taskflow.*`；
- `iibot-connect-agent` import `iibot-connect` 与 `io.mosire.agentlib.*`，不 import taskflow 领域类型；
- `iibot-taskflow` 不 import OneBot/NapCat 类型，不绕过 connect 发送消息；
- 外部 MCP 与内部 Agent 的工具执行共用 AgentLib 工具授权入口，不另造第二套权限判定。

## 2. `iibot-connect` 契约

### 2.1 目标

- NapCat/OneBot 11 是首个实现，但上层只依赖统一消息/连接抽象；
- 连接生命周期、重连、心跳、鉴权、消息编解码、发送回执、限速、去重、分段全在 Lib 内；
- 可独立测试，不要求启动真实 QQ。

### 2.2 公共概念

```text
ConversationType = PRIVATE | GROUP
InboundMessage {
  adapterId, accountId, messageId, senderId,
  conversationType, groupId?, text, receivedAt,
  dedupeKey
}
OutboundMessage {
  adapterId, targetType, targetId, text,
  idempotencyKey
}
SendReceipt = SENT(adapterMessageIds) | FAILED_BEFORE_SEND(reason) | UNKNOWN(reason)
AdapterStatus = DISCONNECTED | CONNECTING | CONNECTED | CLOSING | CLOSED + lastError
MessagingAdapter {
  adapterId(); start(listener); send(message); status(); close();
}
```

- `UNKNOWN` 语义：请求可能已经到达 QQ，但本地没有拿到确认；**不得自动重试**。
- `dedupeKey` 由 adapter 生成，至少含 `accountId + messageId + conversationType`；群消息含 `groupId`。
- 公开 API 不暴露 OneBot JSON、CQ Code、raw WebSocket、token。
- Adapter 内部可保留协议对象，但只用于编解码/日志脱敏。

### 2.3 NapCat v1 传输

- 事件：正向 WebSocket（根路径）。
- 动作：HTTP POST（`send_private_msg` / `send_group_msg` / `get_login_info` 等）。
- 支持 `Authorization: Bearer` 与配置注入的 access token；日志不打印 token/完整 URL 查询串。
- 发送文本分段 + 每目标限速；未知事件不崩溃；meta_event 可观测。
- 重连指数退避 + jitter，`close()` 幂等并终止重连。

### 2.4 测试契约

- Codec 单测：私聊/群聊、array/string、非文本、未知字段、非法 JSON。
- HTTP 动作 mock：成功、`retcode != 0`、超时→UNKNOWN、鉴权头。
- WS mock：事件回调、去重、断线重连、关闭。
- 不要求真实 NapCat 才能通过；真实联调另记 `NOT_RUN`/证据。

## 3. `iibot-connect-agent` 契约

### 3.1 职责

- 把 connect Lib 的发送、查询、状态能力映射为 AgentLib `AgentTool`；
- 提供工具级权限元数据：`ToolSpec`（所需 token、sensitive、destructive、noExport）、角色约束、资源命名空间、参数脱敏；
- 提供工具工厂/注册辅助，让内部 Agent 和外部 MCP 都能复用同一工具实现；
- 不实现世界、秘书、GM、回合或决策。

### 3.2 典型工具

| 工具 | 能力 | 建议权限 |
|---|---|---|
| `qq_send_message` | 向私聊/群发送文本，返回三态回执 | DEFAULT；sensitive=true；资源 `qq` 写 |
| `qq_adapter_status` | 查看连接健康/最近错误 | GUEST；只读 |
| `qq_list_adapters` | 列出已装配 adapter | GUEST；只读 |
| `qq_get_login_info` | 获取当前账号信息（`AccountInfoProvider` 通用接口） | GUEST；只读 |

> 精确名称以实现为准；工具描述、schema 与错误码要稳定。

### 3.3 权限执行

- 工具自身声明 `AgentTool.spec()` / `roles()` / `resources()`；
- 宿主必须通过 `ToolCallAuthorizer` 执行；
- 资源路径建议使用 `qq:<adapterId>/<targetType>:<targetId>` 形式，具体由实现与测试冻结；
- 发送成功/失败/未知都映射为稳定结果，不把底层异常原文泄露给模型；
- 外部 MCP 只复用允许暴露的工具；`noExport`/include 过滤与调用判定面必须一致。

### 3.4 测试契约

- 工具名唯一、schema 合法、描述非空；
- GUEST 不能发送；DEFAULT + sensitive/destructive/resource 授权后可以；
- 资源 scope 不匹配时实际不发送；
- adapter 的三种发送回执映射正确；
- 工具调用经 `ToolCallAuthorizer` 而非直接 `execute`；
- 泄露密钥的输入/异常不出现在结果中。

## 4. `iibot-taskflow` 契约

- 只使用 `connect` 的统一收发与 `connect-agent` 的工具能力；不出现协议类型；
- 每玩家一个秘书 Agent；秘书通过 GM 过滤 brief 获取信息；
- 玩家自然语言 → 秘书生成 `DecisionDraft` → 同一私聊 `/确认 <draftId>` → 宿主写入有效 `Decision`；
- 回合状态机、冻结快照、中央推演提案、世界版本、outbox、事件流与审计在 taskflow 内；
- 外部 MCP 是 taskflow 控制面的工具化入口，不是绕过状态机的后门。

## 5. 验收判据

### 功能

- [ ] `iibot-connect` 可独立发送/接收并脱离 AgentLib/业务域编译。
- [ ] Mock NapCat 的事件/动作契约测试通过。
- [ ] `iibot-connect-agent` 工具在无权限时拒绝、有权限时执行，且资源 scope 生效。
- [ ] 任务流能完成：私聊秘书 → GM brief → 自然语言 draft → `/确认` → open/lock/adjudicate/commit/publish。
- [ ] 其他玩家、群聊、外部 MCP 不能代替玩家硬确认。
- [ ] 外部 MCP 全游戏控制工具仍受 revision/幂等/状态机约束。

### 负向

- [ ] Token/密钥不出现在日志、异常消息、工具结果或模型上下文。
- [ ] 发送超时返回 UNKNOWN，不自动重发。
- [ ] 事件重复投递不重复消耗或重复回复。
- [ ] 秘书上下文不出现其他玩家私密 fact；brief 引用不可见 fact 时零发布。
- [ ] 冻结后 draft 不可确认；重复 commit 不重复世界变更。
- [ ] 非法/过期/未知实体的 AdjudicationProposal 零落盘。

## 6. 实施顺序

1. `iibot-connect`：SPI + codec + NapCat HTTP/WS + mock tests；
2. `iibot-connect-agent`：工具包装 + 权限元数据 + authorizer tests；
3. `iibot-taskflow`：领域状态机、brief/draft/confirm、事件/outbox；
4. `iibot-app`：组合根、配置、Fake E2E；
5. 真实 NapCat/LLM/MCP E2E（需要用户提供真实连接与模型配置）。

> 当前各阶段实际完成度以 [`../agents/STATUS.md`](../../agents/STATUS.md) 为准，本文不记录运行数字。
