# InboxMosire Mode A 通知流接入

> **状态**：有效（2026-10-25，实现与自动化门禁完成；真实 QQ/LLM 外发 NOT_RUN）
> **用户原话**：
> > 「按ModelA接入，把已有的通知板替换成如此工作流」
> **上游契约**：`/home/cna/InboxMosire/docs/upstream/OTHER-PROJECT-INTEGRATION-PROMPT.md`

## 1. 范围与所有权

本设计把 InboxMosire 作为 iiBotMosire 的**宿主通知板**，替代总 SimulatorAgent 通过
`tf_ticket_board {"wait":true}` 持续长轮询的工作流。

- **权威状态仍在 iiBot**：Ticket、Post、ActionPool、Batch、Turn 和世界事实的读写在
  iiBot 领域服务中；InboxMosire 不保存第二份业务正文。
- **Inbox 只保存通知与 claim 生命周期**：item 标题只写类型/状态，payload 只放
  `ticketId`、`postId`、revision 等引用；正文必须由 Agent 用 `tf_ticket_get` /
  `tf_post_get` 回查。
- **目标 Agent 只调用 iiBot 自己的控制 MCP**；不直连 InboxMosire 中央 MCP。
- **DSH adapter 只做唤醒**：读取 `BindingDescriptor`，订阅 status/SSE/occupancy，
  不 claim、不 complete、不读取 item 正文。

## 2. 运行方式（Mode A）

iiBot app 在配置了 `inbox` 块时，于自身进程内启动 `inbox-embedded`：

1. 使用显式 `inbox.dataDir`（默认 `<dataDir>/inbox`）保证 SQLite 和 token 文件随 app
   数据目录持久化；
2. 自动 ensure `inbox.inboxId`；
3. 签发 program MCP token（`items:write/read/manage`、`status:read`、`inbox:occupy`）
   并写入 `0600` 文件；
4. 用 provisioner client 通过 `issueBinding` 签发 wake token，并持久化不含 raw token 的
   `BindingDescriptor`；
5. 把 `InboxClientMcpTools` 的 11 个 `inbox_*` 工具注入 iiBot 控制 MCP；
6. 额外注册 `tf_inbox_binding_descriptor`，只返回 descriptor 及本地路径，不返回 token。

embedded server 使用随机回环端口。因此 app 重启后端口和 descriptor 都可能变化，DSH
session 必须重新 bind。跨机器访问不支持；生产/远程场景需另行设计 TLS、固定 daemon 与
鉴权，不在本切片范围。

## 3. 通知桥

app 启动后运行一个宿主侧通知桥，按 `inbox.pollIntervalMillis` 周期扫描领域状态并
`push` 去重 item。它不是 Agent 轮询，Agent 不需要 `sleep` 或反复调用 MCP。

首版通道与触发条件：

| channel | 触发条件 | payload 引用 | dedupeKey |
|---|---|---|---|
| `ticket` | 工单状态为 `OPEN`/`CLAIMED`/`CONFIRMED` | `ticketId/type/status/revision` | `ticket:<id>:r<revision>` |
| `publication-review` | 推文为 `PENDING_APPROVAL` | `postId/visibility/revision` | `post-review:<id>:r<revision>` |

- 标题只含工单类型或推文可见性，不含玩家正文、私密内容、路径或凭据。
- `push` 失败只记录稳定错误类型，下一次扫描根据同一 dedupeKey 重试；不得因通知失败
  回滚领域状态。
- inbox item 是 at-least-once 信号；Agent 必须以 `tf_*` 的 revision/status 校验是否仍需处理。
- 同一 ticket/post revision 只产生一个 item；关闭/完成后的旧 item 由 Agent 回查后
  `inbox_complete` 或 `inbox_release`。

## 4. 工具面替换

启用 inbox 时：

- 注册：`inbox_status`、`inbox_list`、`inbox_claim`、`inbox_renew`、`inbox_complete`、
  `inbox_release`、`inbox_push`、`inbox_occupancy`、`inbox_occupy`、`inbox_vacate`、
  `inbox_credential_info`、`tf_inbox_binding_descriptor`；
- **不注册** `tf_ticket_board` 的 wait 工作流；工单摘要仍可用 `tf_ticket_list`，详情用
  `tf_ticket_get`；
- 未启用 inbox 时保留原控制面，避免破坏离线测试/诊断。

## 5. 目标 Agent 循环

```text
[INBOX_HOST_EVENT]
  -> inbox_status / inbox_list
  -> inbox_claim(选中的 itemIds)
  -> 用 tf_ticket_get / tf_post_get / tf_status 读取权威状态
  -> 执行实际业务动作
  -> 必要时 inbox_renew
  -> inbox_complete(completed|failed) 或 inbox_release
  -> available=0 时结束回合；不轮询
```

固定边界：

- 唤醒消息不是通知正文，不得把标题/payload 当事实；
- 只 complete 自己 claim 的 item；
- 仅看过但未处理不得标记 completed；
- 没有可处理项时结束回合；
- 收到 item 不等于授权绕过玩家硬确认、发布审核或真实外发边界；
- raw token、claim token、私密正文不进入日志/会话可见输出。

## 6. 失败语义与验收

- 配置启用 inbox 但 embedded server、token 写入或 binding provisioning 失败 ⇒ app 启动
  fail-closed；不得静默回退到旧长轮询。
- 通知桥单次失败不停止 app；下一次扫描重试同一 dedupeKey。
- 验收：
  1. `tools/list` 同时包含 `inbox_*` 与 `tf_inbox_binding_descriptor`；
  2. 启用 inbox 时 `tf_ticket_board` 不出现；
  3. 新建 ACTION_DRAFT / PENDING_APPROVAL 推文后，`inbox_list` 能看到安全引用；
  4. `claim -> complete` 与 `claim -> release` 走程序自有 MCP；
  5. descriptor 不包含 raw token，token 文件权限为 `0600`；
  6. DSH bind 后 occupancy 正确，unbind 后清空；
  7. app close 后 embedded 端口关闭，显式 dataDir 保留。

## 7. 未覆盖

- 批次 `COMMITTED`、投递 `FAILED/UNKNOWN` 的独立通知通道；
- Inbox item 的跨进程 retention/归档策略；
- Mode B 固定 daemon/TLS/远程绑定；
- 多 app 共用一个 inbox 的一致性策略。


## 8. 修订记录

- 2026-10-25：实现 Mode A 嵌入选装。`inbox` 配置块缺省关闭；启用时
  dataDir 缺省为 app `dataDir/inbox`，inboxId 缺省 `iibot-app`，bindingLabel
  缺省 `dsh-simulator-agent`，channels 缺省 `ticket/publication-review`，
  pollIntervalMillis 缺省 1000，bindingTtlHours 缺省 24。
- 持久文件名固定为 `program-mcp.token`、`dsh-wake.token`、
  `binding-descriptor.json`；前两者强制 POSIX `0600`。
- 通知桥 ticket 标题只含工单类型，publication-review 标题只含 visibility；
  两类 payload 均只含本 spec 规定的引用字段。
- 验收证据：[`../status/2026-10-25-inbox-mode-a-notification-workflow.md`](../status/2026-10-25-inbox-mode-a-notification-workflow.md)。
