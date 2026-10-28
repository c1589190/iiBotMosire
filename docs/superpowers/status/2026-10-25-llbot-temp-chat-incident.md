# LLBot 群临时会话投递失败排查快照

> **状态**：历史快照（2026-10-25）。**当前状态入口**：[`../../agents/STATUS.md`](../../agents/STATUS.md)。
> **对应需求**：LLBot 迁移、D-017 私密投递
> **迁移记录**：旧 `simulation/dsh/LLBOT_MIGRATION.md`（工作树已删除，Git 历史可追溯）

## 结论

不是 token、端口或 iibot 发送路由配置错误，而是 LLBot 8.3.2 的平台限制：

- LLBot 不支持 Bot 主动发起群临时会话；
- iibot 的 `send_private_msg {user_id, group_id}` 到达 LLBot 后，
  目标不是好友时进入 `TempC2CFromGroup`；
- LLBot 只有在本地 `temp_chat_info` 存在时才保留临时会话类型，
  否则降级为普通 C2C；
- QQ 返回 `code=16`：`发送失败，请先添加对方为好友`；
- LLBot HTTP 层以 `retcode=200` 返回该失败，旧适配器显示为
  `ONEBOT_RETCODE_200`。

## 证据

- LLBot 8.3.2 源码：`src/ntqqapi/api/msg.ts` 在缺失 `temp_chat_info` 时把
  `TempC2CFromGroup` 降级为 `C2C`；
- `temp_chat_info` 只由入站 `TempMessage` 事件写入；
- LLOneBot issue #725 / #752 / #706 均确认主动临时会话不受支持。

## 修复

- 适配器保留服务端错误文本；
- 临时会话被拒且错误为“先添加对方为好友”时，返回稳定错误：

```text
LLBOT_TEMP_CHAT_REQUIRES_INBOUND
```

- 不自动重试，不自动改发群消息；
- 目标玩家主动私聊 Bot 后，LLBot 写入 `temp_chat_info`；
- GM 再显式调用 `tf_post_retry_delivery`。

## 验证

```text
tools/gate.sh full
PASS
619 tests / 0 failures / 0 errors / 0 skipped
```

最新 target jar `f9f2e448...`；当前运行中的 app 仍是 `484beb...`，未替换。
