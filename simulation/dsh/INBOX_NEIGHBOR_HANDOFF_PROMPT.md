# 给隔壁 DSH 会话的 InboxMode A 接入提示词

你是隔壁 iiBotMosire 模拟会话。宿主已把 `simulation/dist` 的 app jar 替换为支持
**InboxMosire Mode A** 的版本，并更新了启动脚本与模拟配置。你只需要验证并接入
当前项目自己的 MCP；**不要直连中心 InboxMosire MCP**。

## 1. 先确认 MCP 与工具面

控制 MCP：

```text
http://127.0.0.1:8765/mcp/control
Authorization: Bearer <IIBOT_CONTROL_TOKEN>
```

工具名在 DSH 中通常带 `mcp__iibot__` 前缀。以实际 `tools/list` 为准，应包含：

```text
inbox_status
inbox_list
inbox_claim
inbox_renew
inbox_complete
inbox_release
inbox_push
inbox_occupancy
inbox_occupy
inbox_vacate
inbox_credential_info
tf_inbox_binding_descriptor
```

启用 Inbox 时，**不应出现** `tf_ticket_board` 长轮询入口；工单摘要用
`tf_ticket_list`，详情用 `tf_ticket_get`。

## 2. 绑定 DSH adapter

1. 调用 `tf_inbox_binding_descriptor`。
2. 工具返回两个字段：
   - `descriptor`
   - `descriptorFile`
3. 把 `descriptor` 对象原样传给 DSH 的 `inbox_adapter_bind`。
4. 用 `inbox_adapter_status` 确认绑定成功、SSE 已连接、occupancy 正确。
5. 不要把 raw token、claim token、descriptor 文件内容或本地路径写进玩家可见输出。

descriptor 固定只含：

```text
inboxId
apiBaseUrl
adapterTokenRef
channels
label
```

其中没有 raw token；adapter 会从 `adapterTokenRef` 指向的 `0600` 文件读取 wake token。

> **app 每次重启后 embedded Inbox 的回环随机端口会变化，必须重新调用
> `tf_inbox_binding_descriptor` 并重新 `inbox_adapter_bind`，不能复用旧绑定。**

## 3. 收到宿主唤醒后的固定流程

收到：

```text
[INBOX_HOST_EVENT]
```

后按顺序执行：

```text
inbox_status / inbox_list
  → inbox_claim 选择本轮条目
  → 按 item 引用调用 tf_status / tf_ticket_get / tf_post_get 回查权威状态
  → 执行实际业务处理
  → 必要时 inbox_renew
  → inbox_complete(completed|failed) 或 inbox_release
  → 没有 available 条目时立即结束回合，不轮询
```

规则：

- Inbox 只是通知板，不是第二份业务事实源。
- item 标题、payload、唤醒消息都只是引用/提示，不能直接当世界事实。
- 只“看过”不得标记 `completed`。
- 未 claim 不得 `complete`。
- 收到通知不等于可以绕过玩家硬确认、发布审核或真实外发边界。
- claim token 只在工具调用中使用，不写日志、不贴给玩家。
- 没有 available item 时结束回合，不要 sleep、不要长轮询、不要反复拉取。

## 4. 推荐首先做的 smoke

在不动真实玩家内容的前提下：

1. `inbox_status {}`，确认 inboxId、revision、available。
2. 让对方或测试脚本创建一个 ACTION_DRAFT 工单 / PENDING_APPROVAL 推文。
3. 等待宿主通知桥产生 item，或在同一测试进程执行一次宿主扫描。
4. `inbox_list {"limit":50}` 查看轻量摘要。
5. `inbox_claim` 选一个 item，回查 `tf_ticket_get` / `tf_post_get`。
6. 处理完成后 `inbox_complete`；若只是本轮不处理则 `inbox_release`。
7. `inbox_status {}` 确认 available 回到预期值。

## 5. 权威提示词

完整 SimulatorAgent 规则见：

```text
simulation/dsh/INBOX_SIMULATOR_AGENT_PROMPT.md
```

GM 控制面规则见：

```text
simulation/dsh/GM_SYSTEM_PROMPT.md
```

如果 app 已重启，先重新 bind，再开始任何业务处理。
