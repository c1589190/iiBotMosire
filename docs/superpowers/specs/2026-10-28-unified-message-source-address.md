# 统一消息来源地址与全唤醒契约

> **状态**：实施中（2026-10-28）
> **用户原话**：「4 问题来了，你以什么定义消息值得wake？我认为还是目前保持全唤醒」
> 「5 记得老DevMosire的消息来源系统吗？我们需要一个统一的消息来源字段，例如qq_group/xxxx/xxxx、qq_private/xxxx、qq_system/xxxx等等，地址系统要做好」
> 「app的系统消息应该直接叫app_system」
> **关联**：统一 QQ Agent 客户端架构、P8 计划；不改变消息板是业务事实源。

## 一、目标

把 conversation / protocol event / application event 的定位统一成可解析、可校验、
可迁移的来源地址。地址同时用于消息板、MCP 输出、Inbox digest、wake 与日志。

## 二、地址语法

```text
qq_group/{accountId}/{groupId}
qq_private/{accountId}/{peerId}
qq_system/{accountId}/{eventType}
app_system/{eventType}
```

- `qq_group` / `qq_private` 表示会话来源，不是发送者来源；发送者仍是独立字段 `senderId`。
- `accountId` 必填，保证多账号下私聊/群聊地址全局唯一。
- `qq_system` 表示 QQ 协议/账号级系统事件，如 `friend_request`、`adapter_state`。
- `app_system` 表示 iiBot 应用级事件，不带 QQ 账号段；后续可扩展
  `app_system/{component}/{event}`。
- 未来 `wechat_group/{account}/{group}`、`discord_private/...` 沿用
  `family_kind` 形式，不需要重建地址模型。
- `/` 只作为分隔符；segment 内部使用 UTF-8 百分号编码。空段、未知形状、畸形 `%`
  编码和控制字符 fail-closed。canonical 输出统一小写 `family_kind`。

## 三、兼容性

- 旧内部键 `thread:<group|private>:<base64 account>:<base64 target>` 可解码为
  `ChatSourceKey`，再投影为 canonical source；不解码旧 `qq_group:123` 这类无账号旧格式。
- 旧 SQLite 数据不需要重写消息主键；`source` 由现有
  `accountId + chatType + targetId` 确定性推导。
- 旧 Inbox digest content 缺少 `source` 字段时，从 `threadId` 推导；推导失败保留空串，
  不阻塞旧 item 的读取。
- 新 MCP 输出必须带 `source`；`threadId` 在迁移期保留内部/兼容别名，不再作为新契约的
  主字段。

## 四、验收判据

- `qq_group/account/group`、`qq_private/account/peer`、`qq_system/account/event`、
  `app_system/event` 均可 parse → canonical 往返；
- 含空格、斜杠、Unicode 的 segment 必须百分号编码且可还原；
- 群/私聊 source 可无损映射 `ChatSourceKey` 并还原旧 threadId；
- 非法地址（空段、错误段数、畸形 `%`、空 `app_system`）全部拒绝；
- 消息/thread MCP 输出包含 canonical `source`；
- 来源地址不承载 token、正文、revision、itemId 或内部 seq。

## 五、全唤醒

- 新的入站消息产生未读 item 并 wake；`sourceWeight/notifyThreshold/quiet/cooldown`
  不作为 wake gate，只保留排序/诊断意义；
- 同一 revision 不重复 wake；出站不 wake；
- 热群噪 wake 由 `inbox_rest` 或宿主策略控制，不在 Bot 侧静默。
