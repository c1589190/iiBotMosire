# InboxMosire Mode A 通知工作流实现快照

> **状态**：历史快照（2026-10-25）。**当前状态入口**：[`../../agents/STATUS.md`](../../agents/STATUS.md)。
> **日期**：2026-10-25（Asia/Shanghai）
> **对应设计**：[`../specs/2026-10-25-inbox-mode-a-notification-workflow.md`](../specs/2026-10-25-inbox-mode-a-notification-workflow.md)
> **上游契约**：`/home/cna/InboxMosire/docs/upstream/OTHER-PROJECT-INTEGRATION-PROMPT.md`

## 1. 已实现

- `iibot-app` 引入 `inbox-embedded`、`inbox-client`、`inbox-mcp-tools-client`。
- 新增可选 `inbox` 配置块：`enabled/dataDir/inboxId/bindingLabel/channels/
  pollIntervalMillis/bindingTtlHours`；缺省关闭，启用时默认 dataDir 为 app
  `dataDir/inbox`（当前模拟配置即 `simulation/runtime/inbox`）。
- app 内嵌启动 `EmbeddedInboxServer`，只绑随机回环端口，显式 dataDir 持久化。
- 自动签发 program MCP token 与 DSH wake token，分别写入
  `program-mcp.token` / `dsh-wake.token`，POSIX 权限严格校验为 `0600`。
- 自动签发并持久化 `binding-descriptor.json`；descriptor 固定仅含
  `inboxId/apiBaseUrl/adapterTokenRef/channels/label`，无 raw token。
- 新增 `tf_inbox_binding_descriptor`；只返回 descriptor 与 descriptor 文件路径。
- 控制 MCP 注入 11 个 `inbox_*` 工具；启用 Inbox 时隐藏
  `tf_ticket_board`，保留 `tf_ticket_list/get` 及工单业务写工具。
- 宿主通知桥周期扫描权威 Ticket/Post 快照：
  - ticket channel：`OPEN/CLAIMED/CONFIRMED`；
  - publication-review channel：`PENDING_APPROVAL`；
  - payload 仅含引用字段；dedupeKey 按对象 + revision 稳定生成；
  - push 失败只记录稳定错误类型，下一次扫描复用同一 dedupeKey；
  - 同一 revision 成功确认后不再重复 push，Inbox 服务端 dedupe 作为第二层保护。
- 启动失败 fail-closed；app close 时先停通知桥再停 embedded server，不删除显式 dataDir。
- 更新总 SimulatorAgent、GM、README、DSH 接入与模拟配置；旧
  `tf_ticket_board wait` Agent 主流程被 Inbox 唤醒流程取代。

## 2. 门禁证据

全部命令在 `/home/cna/iiBotMosire` 实际运行。

| 命令 | 结果 | 证据 |
|---|---|---|
| `tools/gate.sh focused iibot-app test` | **PASS**；app 312 tests / 0 failures / 0 errors / 0 skipped；`FOCUSED TEST PASSED (NOT a full gate)` | `.work/inbox-gate-focused-final.log` |
| `tools/gate.sh full` | **PASS**；全仓 626 tests / 0 failures / 0 errors / 0 skipped（connect 38、connect-agent 21、taskflow 255、app 312）；shaded jar 生成 | `.work/inbox-gate-full-final.log` |
| `tools/gate.sh docs` | **PASS**；modules=4 files=485 links=171，skills=1 | `.work/inbox-gate-docs-final.log` |

本轮新增测试：

- `AppConfigTest`：3 个 Inbox 配置解析/禁用/非法值用例；
- `InboxHostRuntimeTest`：embedded 启动、两个 token 0600、descriptor 无 raw token、
  新 Ticket/待审 Post 入板、同 revision 去重、端口关闭、dataDir 保留；
- `InboxNotificationBridgeTest`：push 失败复用 dedupeKey、领域状态不回滚、
  同 revision 不重复确认；
- `AppBootstrapInboxMcpTest`：真 socket MCP `tools/list`、descriptor、
  全部 11 个 `inbox_*` 工具（含 renew/occupancy/occupy/vacate）、
  push→list→claim→complete、claim→release、宿主桥 dedupe 与 app close 端口关闭。

全仓 target jar sha256：

```text
a1b1711a998806d76cf2a39862e84f0b3dfde302bd1be97498d49b3c1f24adfb  iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar
```

## 3. 绑定/通知 smoke 证据

使用隔离 `.work/inbox-smoke/run-live-2`、Fake LLM/Fake QQ adapter，仅验证本机
Inbox/MCP/DSH adapter 链路；**不是真实 QQ/LLM 联调**。

- 从当前项目 MCP `tools/list` 得到 **83** 个工具（隔离 Fake 装配）；11 个 `inbox_*` 与
  `tf_inbox_binding_descriptor` 均在，`tf_ticket_board` 不存在。
- 经当前项目 MCP 手工走通 `inbox_push → inbox_list → inbox_claim → inbox_complete`：
  push 得到 `revision=3`，list count 从 2 变 3，claim 返回引用 payload，
  complete 返回 `outcome=COMPLETED` 且 `available=2`。
- 创建 ACTION_DRAFT 工单与 PENDING_APPROVAL 推文后，
  `inbox_status`：`revision=2, available=2`，
  channels 各 `available=1`；即通知桥已分别产生 ticket 与 publication-review item。
- `tf_inbox_binding_descriptor` 返回字段仅 `descriptor` + `descriptorFile`；
  descriptor 字段仅固定五项。
- DSH adapter v2 bind `app-smoke` 后：
  `inbox_occupancy.occupied=true`，`occupiedBy=session-...`。
- `inbox_adapter_unbind` 后：`inbox_adapter_status isBound=false`；
  `inbox_occupancy.occupied=false`、`occupiedBy=null`。
- program/wake token 文件权限均 `0600`；descriptor 文件含固定五字段且不含
  program/wake token 原文。
- 进程停止后 control/embedded API 端口均连接拒绝；显式 inbox dataDir 与
  `inbox.db` 保留。证据目录：`.work/inbox-smoke/run-live-2/`。

## 4. 模拟区部署与启动 handoff

- `simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar` 已替换为
  `a1b1711a998806d76cf2a39862e84f0b3dfde302bd1be97498d49b3c1f24adfb`。
- 替换前 jar 备份：
  `simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar.backup-20261025-221343-pre-inbox`。
- `simulation/config/iibot.sim.local.json` 已加入 Inbox 配置；修改前备份：
  `simulation/config/iibot.sim.local.json.backup-20261025-221343-pre-inbox`。
- 新增 `simulation/scripts/08-inbox-descriptor.sh`；`02-start.sh` 在 MCP ready 后自动
  调用它，生成 0600 `simulation/runtime/inbox-binding-descriptor.json`。
- 新增 `simulation/dsh/INBOX_NEIGHBOR_HANDOFF_PROMPT.md`，给隔壁 DSH 会话直接接入。
- 模拟 app 实际启动 smoke：控制 MCP `127.0.0.1:8765/mcp/control`，
  `tools/list=88`，无 `tf_ticket_board`；`inbox_status` 从已有权威工单产生
  `available=1`；inbox token 文件 0600；descriptor 输出仅两字段、内部仅固定五字段。
  未执行真实 QQ 外发。smoke 进程随后退出，不保持常驻；测试时运行
  `simulation/scripts/02-start.sh` 重新生成随机端口 descriptor。

## 5. 未验证 / 风险

1. 未用真实 DSH LLM、真实 NapCat/QQ 或真实外部发送复跑本切片；smoke 使用
   Fake LLM/Fake adapter。真实外发仍为 `NOT_RUN`。
2. 未做“同一持久 inbox 在 app 重启后不会重新产生旧 revision item”的跨进程 smoke；
   当前有 Inbox 服务端 dedupeKey、桥本地成功集合和持久 SQLite 三层语义，但只验证了
   单进程周期扫描去重。
3. 每次 app 启动会签发新的 program/wake credential；旧 credential 保留到服务端 TTL
   或手动 revoke，缺少自动清理/轮换策略。
4. notification bridge 首版不覆盖 COMMITTED batch、delivery `FAILED/UNKNOWN`、
   complaint 等通道（符合设计未覆盖项）。
5. Embedded server 随机端口意味着 app 重启后必须重新 bind；这是当前 Mode A 的明确约束，
   不是可复用连接。
6. `tf_ticket_board` 仅在未启用 Inbox 的兼容/诊断装配中保留；本快照未做真实
   DSH 会话对旧工具面的兼容回归。
