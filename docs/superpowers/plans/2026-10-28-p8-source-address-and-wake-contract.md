# P8 来源地址与 wake 契约实施计划

> **状态**：进行中（2026-10-28）
> **设计**：[`../specs/2026-10-28-unified-message-source-address.md`](../specs/2026-10-28-unified-message-source-address.md)
> **用户裁定**：保持全唤醒；来源地址 `qq_group/...`、`qq_private/...`、`qq_system/...`、
> `app_system/...`；未来可扩展 wechat/discord。

## P8.1 地址核心与输出投影

- 新增 `MessageSourceAddress`：严格 parse/canonical/百分号编码、旧 threadId 解码；
- `ChatSourceKey.sourceAddress()` 与 `fromSourceAddress()`；
- MCP thread/message 输出增加 `source`；
- `InboxSource` 增加 source 投影；
- 单测覆盖 canonical、兼容、非法输入、Unicode。

验收：`iibot-bot` 单测 PASS；旧 threadId 可解码；MCP 输出 source 与 thread 一致。

## P8.2 Inbox / DSH wake 契约

- Inbox item 增加可选 `source` 字段并透传到 summary/wake；
- DSH wake 使用 source，不再暴露 itemId/revision 给模型；
- DSH programMcp 探测 `qq_conversation_read/ack`；
- wake 模板改为 `source → read → ack`，保持全唤醒。

验收：wake 文本含 source；无 `inbox_list/view` 指令；producer 不传 source 时旧行为不破坏。

## P8.3 状态正确性

- consumer 游标从 `inbox_digest_state.last_seq` / audit 回填；
- `qq_conversation_read` 默认只返回 IN；OUT/上下文分离；
- ack 固定 read 时 delivered 边界；新消息全唤醒但不重复同 revision；
- 迁移旧 SQLite fixture 测试。

验收：旧库不读 seq 0；OUT 不进入未读 ack 边界；read/ack 间新消息保留下一次。

## P8.4 展示与收尾

- 连接账号同步群名/私聊展示名；
- adapter `board:` 缓存失效；
- README/AGENTS/STATUS/DSH 模板同步 source 与全唤醒；
- 全量 docs/full 门禁，随后合并。
