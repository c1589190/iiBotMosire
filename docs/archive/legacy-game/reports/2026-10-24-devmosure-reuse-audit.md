# DevMosire 可复用实践审计：外部接入层、Agent 工具化/权限、事件流与任务流

> **状态**：调查结论（只读）· **日期**：2026-10-24（Asia/Shanghai）  
> **审计根**：`/home/cna/DevMosire`  
> **目标架构**：本仓 `iibot-connect` / `iibot-connect-agent` / `iibot-taskflow`，设计依据见
> [`../specs/2026-10-24-target-runtime-design.md`](../specs/2026-10-24-target-runtime-design.md) 与
> [`../specs/2026-10-24-external-mcp-control-plane.md`](../specs/2026-10-24-external-mcp-control-plane.md)。
>
> **边界**：本报告只做源码调查，不写生产代码；没有连接 QQ、NapCat、LLM、MCP 或游戏服务器；
> 没有执行 Maven 构建或测试。所有“测试通过”数字都只表示**仓库内既有 surefire 报告的历史记录**，不是本次复跑结论。

## 0. 结论先行

### 0.1 推荐复用顺序

| 目标问题 | 最值得先看 | 结论 |
|---|---|---|
| NapCat/OneBot 协议接入 | `WDMosire` 的 HTTP 优先慢路径 + `iBotMosire` 的 echo/测试思路 | 协议机制可参考；生产实现按本仓 `iibot-connect` SPI 重写 |
| 发送回执与外部副作用 | 本仓现有 `SendReceipt` 三态设计；旧仓均不足 | 必须保留 `SENT` / `FAILED_BEFORE_SEND` / `UNKNOWN`，旧仓只能作反例 |
| 工具权限边界 | `iBotMosire` 的 `PermissionGuard`（列表 + 调用双检查） | 可参考其“唯一授权边界 + 拒绝语义”；权限维度必须换成 AgentLib `ToolCallAuthorizer` |
| 事件/任务状态机 | `PlayerMosire` 的 `StateMachines`、`EventEnvelope`、`EventType`、job engine | 设计质量最高；但 GPL-3.0-or-later，不能直接复制代码 |
| 崩溃恢复/重放 | `PlayerMosire` 的 `Reconciler`、journal、`shouldReplay` | 可参考“以服务端事实为准、不重发已确认副作用”的原则 |
| 角色/模式化 Agent | `GoatMosire/example/GetGoat` 的 `AgentMode`、`AbstractAgent` 循环、按侧情报 brief | 只复用模式与提示词组织方式；不复用其无授权、无 CAS 的执行链 |
| QQ 命令路由 | `GameMosire` 的 `CommandDispatcher` | 可参考“有序、首个命中”的命令优先级；不能复用其授权/激活模型 |

### 0.2 最重要的六个判断

1. **旧仓没有一个是“按目标架构可直接投产”的完整答案。** `iBotMosire` 的拓扑是“Agent 管理系统 + QQ 遥控”，`WDMosire` 是“消息检索 + MCP 工具”，`PlayerMosire` 是 Minecraft Agent 实验，`GoatMosire` 是地图/回合制实验，`GameMosire` 是骰子命令 bot。目标架构的“玩家/推演角色、玩家秘书、GM 过滤、外部 MCP 控制面”必须由本仓自己定义。
2. **权限元数据不等于权限执行。** 只有 `iBotMosire` 在 `tools/list` 与 `tools/call` 两处真正调用了 `PermissionGuard`；`WDMosire`、`GoatMosire`、`PlayerMosire` 的 `Permission`/`ToolSpec` 大多是标签，`PlayerMosire` 的 CogLoop 还显式使用 `AutoApprovePermissionGate`。目标实现必须把 AgentLib `ToolCallAuthorizer` 作为**唯一执行入口**。
3. **发送超时不能被当作“失败”自动重试。** 旧 `iBotMosire` 和 `WDMosire` 都会把发送异常/超时折叠为普通错误或 `sent:false`，缺少“可能已发出”的 `UNKNOWN` 态；这会直接导致重复轰炸 QQ 用户。
4. **事件必须区分“可合并/可丢”和“关键/不可丢”。** `PlayerMosire` 的 `EventType` 与 `EventBackpressure` 给出了可用的分类思路，但它自己的“全是终态事件时允许超容量”会形成内存风险；权威领域事件不能走可丢队列。
5. **幂等索引必须持久化。** `PlayerMosire` 的 `JobEngine` 幂等表保存在内存，进程重启后不能防止重复；目标 taskflow 必须把“事件 + outbox + 幂等索引”放在可恢复存储中，并以 `expectedRevision` 做 CAS。
6. **许可证先于代码复用。** `iBotMosire`、`WDMosire` 为 Apache-2.0；`PlayerMosire`、`MCWDMosire` 为 GPL-3.0-or-later；`GoatMosire` 根目录没有 LICENSE 文件，`GameMosire` 没有 LICENSE。**本报告中的“可直接参考”仅指设计/测试思路，不授权复制代码或大段文本。**

---

## 1. 审计方法、证据等级与测试边界

### 1.1 实际执行过的只读检查

| 检查 | 状态 | 说明 |
|---|---|---|
| 目录/文件清单 | DONE | `find`、`git ls-files`、`git status` |
| 源码阅读 | DONE | `nl -ba`、`rg`；记录 `file:line` |
| Git 元数据 | DONE | `git log`、`git status`、`git remote -v`；只读 |
| 依赖字节码检查 | DONE（仅 WDMosire） | `javap` 检查 vendored `gsim-agentsmanager` 的 `ToolExecutionGuard` / `ToolRegistryMcpAdapter` |
| Maven 构建/测试 | **NOT_RUN** | 本报告未执行 `mvn` |
| 真实 NapCat / OneBot | **NOT_RUN（本次）** | 未建立任何 QQ/NapCat 连接 |
| 真实 LLM / MCP / Minecraft / 游戏服 | **NOT_RUN（本次）** | 未启动外部服务 |
| 破坏性命令 | 未执行 | 仅查看、统计与读取 |

### 1.2 历史 surefire 报告（二手工件，不是本次复跑）

统计方式：扫描各仓 `**/surefire-reports/*.txt` 并汇总其中 `Tests run/Failures/Errors/Skipped`。结果只说明“当时有人跑过”，不说明当前代码仍可编译或通过。

| 仓库 | 报告文件数 | 历史测试数 | 历史 fail/error/skip | 最近报告时间 |
|---|---:|---:|---|---|
| `iBotMosire` | 17 | 83 | 0 / 0 / 0 | 2026-08-08 01:15 |
| `WDMosire` | 5 | 39 | 0 / 0 / 0 | 2026-09-05 22:41 |
| `GoatMosire` | 1 | 16 | 0 / 0 / 0 | 2026-07-19 16:33 |
| `PlayerMosire` | 62 | 447 | 0 / 0 / 1 | 2026-09-07 00:38 |
| `GameMosire` | 0 | 0 | — | 无 |
| `MCWDMosire` | 0 | 0 | — | 无 |

补充：`PlayerMosire` 的 1 个 skipped 是 `JobStoreBenchTest`；`iBotMosire` 与 `WDMosire` 的 HTTP/WS/Mock 测试均为进程内 Mock，不构成真实 NapCat 证据。`WDMosire/docs/MIGRATION_PLAN.md` 记录了作者声称的真实 NapCat E2E，但本报告**没有复现**，只作为二手转述。

---

## 2. `iBotMosire`：最完整的 QQ 接入/工具权限原型，但生产语义不足

**仓库状态**：Git 干净，HEAD `4d8a08c`，Apache-2.0；本机无未提交修改。  
**定位**：Java 21，多 Maven 模块；Gateway + QQWS 双进程；Agent 通过 MCP 操作 QQ/服务器。该业务架构**不继承**，但协议/测试/权限边界值得拆读。

### 2.1 可复用点清单

| 路径:行 | 可复用点 | 结论 | 原因 |
|---|---|---|---|
| `iBotMosire/ibotmosire-napcat/src/main/java/com/ibotmosire/napcat/connection/OneBotConnection.java:24-35` | OneBot 正向 WS、echo 关联、重连、心跳的职责切分 | **可直接参考** | 类注释准确列出 echo、重连、心跳、事件分发四个机制，适合作为 `iibot-connect` 的 NapCat transport 设计 checklist |
| `OneBotConnection.java:104-134` | `call(action, params)` + `echo` + `pending` + 超时清理 | **可直接参考** | 并发调用各回各的响应；`MockNapCatServer` 有对应测试 |
| `OneBotConnection.java:137-164` | 关闭时 `failAllPending()` | **可直接参考** | 避免断线后的 pending future 悬挂 |
| `OneBotConnection.java:174-198` | echo 响应与无 echo 事件的分离 | **需重写** | 解析失败只 warn；没有事件 schema 校验、没有协议版本/错误分类、没有按 `sub_type` 判断 |
| `OneBotConnection.java:234-251` | 指数退避 + jitter 重连 | **需重写** | `onFailure` 与 `onClosed` 可能重复调度；没有 single-flight/generation token；不保证旧 socket 已关闭；`connect()` 没有 ready 状态 |
| `OneBotConnection.java:110-134` | `ws.send(...)` 发送后只等 echo | **需重写** | 忽略 `send()` 返回值；无 outbound queue/背压；`webSocket` 未 open 也可能发送；无 `UNKNOWN` 语义 |
| `iBotMosire/ibotmosire-napcat/src/main/java/com/ibotmosire/napcat/store/BoundedMessageBuffer.java:10-14` | “有界 + TTL + 补齐错过的消息”的需求描述 | **可直接参考** | 需求本身正确；但实现不足 |
| `BoundedMessageBuffer.java:48-59` | 每账号 `CopyOnWriteArrayList` + 超限删最旧 | **需重写** | 不是真正的环形缓冲；每次 eviction `remove(0)` 为 O(n)；未按 `message_id` 去重；无持久化 |
| `BoundedMessageBuffer.java:68-81` | `recent(selfId, sinceMillis)` | **需重写** | 注释说“最新在前”，实际为插入顺序（旧→新）；`sinceMillis` 使用接收时刻，不是事件时间；同毫秒边界可能重复/漏读；`size()` 不清理过期项 |
| `iBotMosire/ibotmosire-napcat/src/main/java/com/ibotmosire/napcat/ratelimit/SendRateLimiter.java:6-11` | per-account + per-target 令牌桶 | **可直接参考** | 目标隔离点正确，适合沿用“发送路径唯一限速”原则 |
| `SendRateLimiter.java:36-62` | 令牌桶实现 | **需重写** | 满载时直接丢弃而非排队/返回 retry-after；buckets 无上限、无过期清理；`accountId + ":" + target` 字符串拼接存在键碰撞风险；未覆盖全局限额 |
| `iBotMosire/ibotmosire-napcat/src/test/java/com/ibotmosire/napcat/test/MockNapCatServer.java:15-30` | 可注入 action、按 echo 回显、主动推事件、记录动作 | **可直接参考** | 是很好的契约测试基座；需补鉴权、延迟、失败 retcode、重复事件、连接断开等负例 |
| `MockNapCatServer.java:46-49,106-127` | `port=0` + `actualPort()` 等待绑定 | **可直接参考** | 适合 CI 并行测试，避免固定端口冲突 |
| `iBotMosire/ibotmosire-core/src/main/java/com/ibotmosire/core/tool/PermissionGuard.java:41-61` | `tools/list` 可见性过滤 | **可直接参考** | 敏感/破坏性工具对低权限不可见，且拒绝语义清楚 |
| `PermissionGuard.java:71-89` | `tools/call` 强制校验（存在性 → 敏感 → 等级 → 破坏性） | **可直接参考** | 这是旧仓最值得保留的“唯一授权边界”思想；但只有静态三元组，没有角色/资源/审批 |
| `iBotMosire/ibotmosire-core/src/main/java/com/ibotmosire/core/mcp/McpProtocol.java:113-134` | MCP `tools/list` / `tools/call` 分派与权限调用 | **可直接参考** | 把权限判定放在协议层调用工具之前，MCP 只是适配器 |
| `McpProtocol.java:119-133` | 工具错误用 `isError=true` 的 `content` 返回 | **可直接参考** | 工具失败不穿透 JSON-RPC；但错误文本需脱敏和稳定错误码 |
| `iBotMosire/ibotmosire-core/src/main/java/com/ibotmosire/core/mcp/ToolAdapter.java:31-46` | 工具 schema 拼装 | **需重写** | `getParameters()` 被当作 `properties`，而 `AgentTool` 注释说它是 JSON Schema；且始终返回空 `required`。旧 QQ 工具没有覆写 `getParameters()`，实际 schema 为空 |
| `ToolAdapter.java:70-88` | JSON 参数扁平化为 `Map<String,String>` | **需重写** | 数组/数字/嵌套对象被字符串化，丢失 JSON 类型；后续工具各自 `Long.parseLong`，容易产生参数注入/类型歧义 |
| `iBotMosire/ibotmosire-napcat/src/main/java/com/ibotmosire/napcat/NapcatTools.java:68-116` | `qq_send_message` 工具 | **需重写** | 参数没有 schema；发送只返回“已发送”没有 message id；`.get(15s)` 超时被当成普通失败；无 `UNKNOWN`，会导致错误重试 |
| `NapcatTools.java:118-150` | 消息列表工具 | **需重写** | 直接拼接文本返回，无结构化结果/游标；`sinceMillis` 语义不适合多实例/重放；无参数校验 |
| `NapcatTools.java:337-388` | `napcat_connect`/`disconnect` | **需重写** | 连接是异步的，工具返回“已挂载”时可能尚未 open；没有检查 `registry.connect` 后的 ready 状态 |
| `NapcatTools.java:391-428` | 账号 CRUD 占位 | **不建议复用** | 明确返回“P5 未实现”；不能把占位当完成 |
| `iBotMosire/ibotmosire-napcat/src/main/java/com/ibotmosire/napcat/AccountRegistry.java:39-55` | 账号唯一性 + client 保留账号拒绝 | **可直接参考** | “保留连接不可被 Agent 挂载”是防越权的结构性隔离；但旧仓的 `client/agent` 是账号池角色，**不是**目标 `PLAYER/DIRECTOR` 角色，不能混用 |
| `iBotMosire/ibotmosire-core/src/main/java/com/ibotmosire/core/mcp/McpHttpServer.java:69-126` | MCP HTTP 入口、Bearer 解析、头体校验 | **不建议复用** | 自研协议栈未证明 2026 兼容；请求体 `readAllBytes()` 无上限；无 token 回落 guest、未知 token 在 `Main` 的 `tokens()::get` 下可能 NPE/500，而不是 401；异常文本可能泄露内部信息 |
| `iBotMosire/ibotmosire-gateway/src/main/java/com/ibotmosire/gateway/Main.java:144-161` | `/events` SSE 广播 | **不建议复用** | `registerContext` 绕过 MCP 鉴权，`/events` 无 Bearer 校验；任何本机进程可订阅 Agent 输出；与目标 MCP 控制面语义不一致 |
| `iBotMosire/ibotmosire-agent/src/main/java/com/ibotmosire/agent/AgentManager.java:73-92` | 单槽位 Agent 会话 | **不建议复用** | 目标是每个玩家一个秘书 + 中央 GM；单槽位会串行化并污染上下文 |
| `iBotMosire/ibotmosire-agent/src/main/java/com/ibotmosire/agent/adapter/ClaudeCodeAdapter.java:56-77` | 子进程启动 + `--mcp-config` 注入 | **需重写** | `--dangerously-skip-permissions` 绕过 Agent 侧审批；API key 进入环境变量；prompt 进入进程参数；不能作为目标安全基线 |
| `iBotMosire/ibotmosire-agent/src/main/java/com/ibotmosire/agent/adapter/SubprocessAgentAdapter.java:218-252` | 子进程会话生命周期/状态 | **不建议复用** | `sendPrompt` 只打印文本，不能真正注入 stdin；轮次/时间预算只在 `status()` 被调用时检查，不是硬截止；`maxToolCalls` 实际未被限制 |
| `iBotMosire/ibotmosire-qqws/src/main/java/com/ibotmosire/qqws/router/CommandRouter.java:42-101` | `/agent` 命令路由 | **需重写** | 单会话、无身份/权限、无消息去重、无参数 schema；只可借鉴“命令先于 LLM”的思想 |
| `iBotMosire/ibotmosire-qqws/src/main/java/com/ibotmosire/qqws/Main.java:72-99` | 保留账号连接 + SSE 事件渲染 | **不建议复用** | 全局 `SessionTarget`、无认证、事件订阅与业务状态耦合；目标应使用 outbox 和明确的目标绑定 |

### 2.2 关键 bug / 安全风险（iBotMosire）

1. **未认证侧通道**：`McpHttpServer.registerContext` 直接创建 `/health`、`/events`，不经过 `authenticate()`；`Main.java:144-161` 的事件流会泄露 Agent 输出。风险等级高。
2. **token 校验语义分叉**：无 token 时 `McpClient.fromToken` 明确回落 guest（`McpClient.java:31-40`）；但 Gateway 传入的是 `ibot.tokens()::get`（`Main.java:142`），未知 token 会得到 `null` 再进入 `switch(level)`，可能 NPE/500，而不是显式 401。`ConfigLoader.levelForToken` 另有 guest 回落（`ConfigLoader.java:207-211`），但未与 MCP 调用路径统一。生产必须把“无凭据”“无效凭据”与 guest 区分，并统一 fail-closed。
3. **schema 基本不可用**：`AgentTool.getParameters()` 默认 `null`，QQ 工具未覆写；`ToolAdapter.describe` 固定输出空 `required`。模型几乎无法知道工具参数，调用只能靠试错。
4. **发送语义错误**：`NapcatTools.sendMessage` 对超时/取消/网络异常统一 `fail`，没有 `UNKNOWN`；目标若直接复用，会把“可能已发送”重试成重复消息。
5. **事件重复**：入站事件没有去重层；`BoundedMessageBuffer` 按插入追加，不按 `message_id` 去重；重连/重放时容易重复。
6. **连接重入**：`OneBotConnection.scheduleReconnect` 可被 `onClosed` 与 `onFailure` 各触发一次；`connect()` 不检查旧 socket，可能建立多个并发 WS。
7. **body 无上限**：`McpHttpServer.handle` 使用 `readAllBytes()`（`:76`），没有 `Content-Length`/大小上限，易被本机或错误代理打成内存 DoS。
8. **executor 泄漏**：`McpHttpServer.start` 创建 `newVirtualThreadPerTaskExecutor()`（`:63`），但构造器传入的 `executor` 是 `null`（`:61`），`close()` 只关闭非 null executor（`:195-201`），虚拟线程池可能不关闭。
9. **Agent 权限模型缺角色/资源**：`PermissionGuard` 只有 guest/default/system 与 sensitive/destructive；不能表达“这个秘书只能读本玩家会话”“只能向当前群发消息”等资源约束。
10. **危险子进程开关**：`ClaudeCodeAdapter.java:67` 使用 `--dangerously-skip-permissions`；即使网关侧有工具权限，也放大了 Agent 在宿主上的能力面。

### 2.3 测试可借鉴点与测试缺口

可借鉴：`OneBotConnectionTest` 的 echo 并发（`iBotMosire/ibotmosire-napcat/src/test/java/com/ibotmosire/napcat/connection/OneBotConnectionTest.java:59-75`）；`NapcatToolsTest` 的权限/工具集成（`:98-139`）；`MockNapCatServer` 的 action 注入；`ToolRegistryPermissionTest` 的 list/call 可见性负例（`iBotMosire/ibotmosire-core/src/test/java/com/ibotmosire/core/tool/ToolRegistryPermissionTest.java:119-148`）。

缺口：没有测试 `/events` 鉴权、未知 token 拒绝、请求体上限、发送超时后的 `UNKNOWN`、重连并发、事件去重、schema 完整性、`Map<String,String>` 类型丢失。

---

## 3. `WDMosire`：历史拉取/分页/重试最成熟，但 MCP 权限面几乎为零

**仓库状态**：Git 有未跟踪 `.omo/`，其余干净；HEAD `a371ff9`，Apache-2.0。  
**定位**：QQ 消息存储/查询 + MCP 工具；不是回合制业务。其 `docs/MIGRATION_PLAN.md` 声称迁移到 GSimulator `AbstractMcpServer` 并做过真实 NapCat E2E；本报告仅源码阅读，未复现。

### 3.1 可复用点清单

| 路径:行 | 可复用点 | 结论 | 原因 |
|---|---|---|---|
| `WDMosire/src/main/java/com/wdm/bot/bot/OneBotApi.java:33-45` | HTTP 优先、WS 回退的慢路径调用 | **可直接参考** | 历史消息在 NapCat 上可能很慢；HTTP 请求/响应天然一一对应，避免 WS echo 队列积压 |
| `OneBotApi.java:152-180` | 历史分页固定 `parse_mult_msg=false`、`disable_get_url=true` | **可直接参考** | 避免合并转发/URL 解析拖慢分页；这是实际协议经验 |
| `WDMosire/src/main/java/com/wdm/bot/bot/MessageService.java:121-163` | `scrollHistory` 去重、按游标翻页、无进展保护 | **可直接参考** | 用 `messageId` 去重，按返回列表边界取下一页游标，`!added`/游标不变即停止；比简单 `limit` 翻页可靠 |
| `MessageService.java:169-195` | 历史页失败重试 + 递增退避 | **可直接参考** | 适合“读取类”幂等操作；目标仍应加总时限和可取消 |
| `WDMosire/src/main/java/com/wdm/bot/bot/NapCatClient.java:80-120` | WS 响应/事件分流、echo 关联 | **需重写** | 概念正确；但非线程安全 listener 列表、无超时 pending 清理、无 ready 状态、无 single-flight 重连 |
| `WDMosire/src/main/java/com/wdm/bot/bot/BotAdapter.java:42-77` | 连接器生命周期与断线重连 | **需重写** | 固定 5 秒重连，无 jitter/指数退避；`onError` 本身不触发重连；`eventListeners` 为 `ArrayList`，并发注册不安全 |
| `WDMosire/src/main/java/com/wdm/bot/convert/MessageConverter.java:70-107` | 消息事件到稳定存储对象：ID、时间、发送者、sender、原始 extra | **可直接参考** | 保留原始段/未知字段用于审计；目标应明确 PII 保留/脱敏策略 |
| `MessageConverter.java:110-167` | 合并转发、引用、分段的安全转换 | **可直接参考** | 对嵌套 forward 有去重 visited 集合；但目标 v1 不应下载图片/文件 |
| `WDMosire/src/main/java/com/wdm/mcp/tools/QqSendMessageTool.java:42-54` | 完整 JSON Schema、enum、required、reply_to 组包 | **可直接参考** | 相比 iBot 的空 schema，这是正确方向；目标沿用“完整 JSON Schema”即可 |
| `QqSendMessageTool.java:56-59` | `permission()=WRITE` 元数据 | **需重写** | 只是元数据；`WDMosire` 的 MCP 传输层没有 caller 身份，无法据此拒绝 |
| `QqSendMessageTool.java:86-97` | `sent:false` 不抛错、返回半成功 | **需重写** | 只能表示成功/失败，不能表示“超时但可能已发送”；目标必须用 `UNKNOWN` |
| `WDMosire/src/main/java/com/wdm/mcp/WdmMcpServer.java:81-111` | 协议版本封顶回显 + `instructions` | **可直接参考** | 不谎报更高协议版本；对老客户端兼容有用 |
| `WdmMcpServer.java:126-166` | 编程式 JSON-RPC 分发、通知静默 | **可直接参考** | 适合作为协议适配层测试；但目标应优先使用 AgentLib MCP server |
| `WDMosire/src/main/java/com/wdm/mcp/McpHttpHandler.java:44-60` | 简单 POST → `WdmMcpServer` | **不建议复用** | 无鉴权、无 caller 身份、无请求体大小/并发/超时预算 |
| `WDMosire/src/main/java/com/wdm/mcp/McpServer.java:29-55` | 12 个工具一次性注册 | **需重写** | 无独立 registry、无按 caller 过滤、无 allow-list；目标需要独立控制面 registry |
| `WDMosire/src/test/java/com/wdm/bot/test/MockNapCatServer.java:20-60,148-174` | Mock 动作注入、事件推送、动作记录 | **可直接参考** | 测试基座好；与 iBot 的 Mock 可合并为共享契约测试工具 |
| `WDMosire/src/test/java/com/wdm/bot/test/AdapterIntegrationTest.java:43-107` | 并发 echo、事件投递、reply 目标验证 | **可直接参考** | 适合迁入 `iibot-connect` 的 contract test；还应补 token 鉴权/超时/UNKNOWN |

### 3.2 关键 bug / 安全风险（WDMosire）

1. **MCP 无认证**：`McpHttpHandler` 不解析 `Authorization`；`McpServer` 直接暴露 12 个工具。任何能访问端口的客户端都可调用发送/历史查询工具。
2. **Permission 仅装饰**：`QqSendMessageTool` 的 `permission()` 没有被 `WdmMcpServer` 调用；`javap` 检查 vendored `com.gsim.agentsmanager.tool.ToolExecutionGuard.checkMcp` 只判断工具存在和 `mcpExposed()`，不校验 `Permission.WRITE`。因此“WRITE 权限”在 WDM 的 MCP 入口没有实际约束。
3. **`sent:false` 不是错误**：若 NapCat 返回非 ok，工具仍以成功 `ToolResult` 返回，调用方可能误以为已发送。
4. **历史慢路径**：`getGroupMsgHistory` 超时靠 HTTP 长超时/重试；目标需总时限、可取消、不得把读取超时误判为业务失败。
5. **内存存储**：`MemoryMessageStore` 无 TTL/容量；重启丢失。目标事件存储必须持久化、可重放。
6. **listener 并发**：`NapCatClient.listeners` 是 `ArrayList`；`BotAdapter.onEvent` 在运行中追加 listener 时与 dispatch 遍历并发修改。
7. **重连固定 5 秒**：`BotAdapter.onDisconnected` 只会调度固定的 5 秒，没有单飞/上限/抖动；连续失败可能形成稳定风暴。
8. **异常信息泄漏**：`WdmMcpServer.handle` 的通用异常分支返回 `e.getMessage()`；底层 API 错误可能包含主机/URL/凭据上下文（未脱敏）。

### 3.3 未完成状态

`WDMosire` 的功能面看起来接近完成，但安全面不是“完成”：没有 caller 身份/权限/审批；MCP 的 GET SSE 只是 keepalive，不是真正推送；文档与测试对真实 NapCat 的 E2E 属历史声明，本次未复跑。

---

## 4. `GoatMosire`：模式化 Agent 循环与按角色情报 brief 的思路值得学，不能复用执行/权限

**仓库状态**：当前分支 `refactor/code-quality-and-docs`，HEAD `c26a072`；有未跟踪 `.claude/`、agents/caches/logs 数据；根目录没有 `LICENSE` 文件，README 仅以 badge 声称 MIT。  
**边界澄清**：当前 `GoatMosire` 根仓是六边形地图编辑器/MCP bridge，**没有 QQ 连接、OneBot 消息事件或 NapCat 实现**；与 QQ 相关的旧实现不在本仓当前分支。可复用价值在 `example/GetGoat` 的 Agent 循环与角色化模式。

### 4.1 可复用点清单

| 路径:行 | 可复用点 | 结论 | 原因 |
|---|---|---|---|
| `GoatMosire/example/GetGoat/src/main/java/com/cna/getgoat/agent/AgentMode.java:21-68` | 以 `AgentMode` 作为唯一扩展点：模式名、系统提示、工具定义、当前回合上下文、工具分发、回合收尾 | **可直接参考** | 很适合映射 `SECRETARY` / `GM_FILTER` / `ADJUDICATOR` 三种不同职责；避免为每种角色写一套 Agent 类 |
| `.../agent/AbstractAgent.java:86-191` | “构建上下文 → LLM → 工具调用 → 工具结果回填 → 最多 N 轮 → 收尾”循环 | **需重写** | 循环骨架值得保留；但没有 ToolCallAuthorizer、没有 CAS/revision、没有 idempotency、没有持久事件，不能直接用于目标 |
| `AbstractAgent.java:197-222` | 系统提示 + 历史回合重放 + 当前回合上下文 | **可直接参考** | “历史重放”必须保留 tool_call/tool_result 关联；目标可把历史替换为已过滤的会话事件 |
| `AbstractAgent.java:228-286` | 过往回合的 tool 消息重放 | **可直接参考** | 对保持模型上下文一致性很重要；需补“历史记录非当前事实”的显式标记 |
| `AbstractAgent.java:293-320` | 工具调用批量执行、`tool_call_id` 回填 | **需重写** | 缺少逐调用权限、超时、重试/取消、错误码与审计；不能直接执行 |
| `.../agent/mode/CommandMode.java:45-63` | 每模式只暴露工具定义 | **可直接参考** | 工具面按角色收窄的雏形；但定义列表与执行分发不是同一个白名单，容易“看见但不可调”或反之 |
| `CommandMode.java:110-134` | 用 `SideIntelMap` 生成“己方/已观测敌军/疑兵”情报 brief | **可直接参考** | 目标秘书/GM 的可见性 brief 可借鉴 `confirmed/estimated/outdated/decoy` 与不确定性标注；**不要复制业务世界模型** |
| `.../map/campaigns/intel/SideIntelMap.java:93-169` | 按 side 生成 prompt 友好的 markdown intel brief | **可直接参考** | 展示了“把过滤后的视图渲染成上下文，而不是把全量世界塞给模型” |
| `.../map/campaigns/intel/SideIntelEntry.java:16-50` | 实体可见性 + certainty + uncertaintyRadius | **可直接参考** | 对“玩家只看到自己知道的事实 + 不确定性”的表达有启发；目标是 `WorldFact`/`InfoBrief`，不要复用战役单位模型 |
| `.../agent/tool/ToolUnit.java:13-35` | 工具 = 定义 + 执行 + tool 消息格式 | **需重写** | 没有 `ToolSpec`/roles/resources/gate；`feedback` 手写 JSON 转义，不是安全边界 |
| `.../agent/tool/ToolRegistry.java:18-54` | 名称 → 工具注册与分发 | **需重写** | `register` 静默覆盖同名工具；`dispatch` 直接 `tool.execute(args)`；无授权、无 schema 校验、无去重 |
| `.../agent/tool/McpServer.java:87-171` | stdio JSON-RPC / tools list / tools call 的最小实现 | **不建议复用** | 无认证/权限/审批；stdio 只是本地信任，不能作为外部 MCP 控制面 |
| `.../agent/tool/SimulateRoundTool.java:52-101` | “工具 → 确定性回合模拟 → 返回结构化统计” | **可直接参考** | 对“任务执行结果必须来自真实模拟，而非模型文本”的思想有价值；但该例本身的回合数据/业务不可复用 |
| `GoatMosire/src/main/java/com/goatmosire/GoatMosireApp.java` / `src/main/java/com/goatmosire/mcp/McpServer.java:55-71` | 合并 goat/gsim 两个 registry，按前缀路由 | **需重写** | 路由思路简单，但没有 caller 身份、权限、allow-list；工具数量文档也不一致（README 22、ARCHITECTURE 24、MCP_GUIDE 51） |

### 4.2 关键 bug / 风险（GoatMosire）

1. **无授权执行**：`ToolRegistry.dispatch` 直接 `execute`；`AgentMode.dispatchTool` 也直接执行。没有 `ToolCallAuthorizer`。
2. **同名工具静默覆盖**：`ToolRegistry.register` 的 `LinkedHashMap.put` 会覆盖；目标应 fail-fast。
3. **缺少 revision/CAS**：`BranchSaveRoundTool` 只调 `bm.addRound`，不校验父节点版本或重复提交（`.../tool/BranchSaveRoundTool.java:34-53`）；不适合作为回合提交边界。
4. **参数缺失可 NPE**：`UnitMoveTool` 直接 `args.get("code").asText()`、`.asDouble()`（`.../tool/UnitMoveTool.java:29-38`），没有 required 校验。
5. **stdio MCP 无权限闸**：如果把该 server 放到本地 shell/外部 Agent 使用，所有 mutation 工具均可直接调用。
6. **许可/文档风险**：README 链接 `./LICENSE` 但文件不存在；工具数量文档彼此矛盾；示例 `example/GetGoat` 自身也没有 LICENSE。

### 4.3 测试状态

`GoatMosire` 当前仅看到 1 个 surefire 报告（`TerrainCanvasTest`，16 个测试）；示例 `GetGoat` 和根作者未提供完整测试报告。所有结论均为源码阅读。

---

## 5. `PlayerMosire`：事件/任务/恢复设计最接近目标，但 GPL 且尚未接线

**仓库状态**：Git 干净，HEAD `7886feb`；GPL-3.0-or-later（`LICENSE:1-10`、`NOTICE:1-25`）。README 明确“需求调查、兼容性实验与开发规划阶段”（`README.md:1-24`），`player-app/src/main/java/dev/playermosire/app/Main.java:5-19` 仍是 placeholder。  
**复用策略**：**只读参考设计，不复制代码/文本**；若本项目要复用实际代码，需接受 GPL-3.0 兼容性并单独做许可证裁定。

### 5.1 可复用点清单

| 路径:行 | 可复用点 | 结论 | 原因 |
|---|---|---|---|
| `PlayerMosire/environment-api/src/main/java/dev/playermosire/environment/api/ToolSpec.java:8-42` | 纯数据 `ToolSpec`：名称、描述、完整 JSON Schema、权限桶、工具组 | **可直接参考** | 与 AgentLib `AgentTool`/`ToolSpec` 的形态接近；尤其“完整 schema 字符串”比 iBot 的 properties-only 清晰 |
| `.../CapabilityToolBinding.java:26-71` | 能力 → 提交工具 + 通用 `job_status` 工具的规范命名 | **可直接参考** | 把“发起长任务”和“查询任务”分成两个工具；适合 targetflow 的 async 操作 |
| `.../EventEnvelope.java:41-98` | `schemaVersion/eventId/sessionId/epoch/jobId/monotonicSeq/timestamp/correlationId` | **可直接参考** | 字段覆盖了去重、排序、关联、恢复；目标可在此上补 `eventType`、`actor`、`turnId` |
| `.../EventType.java:23-66` | 可合并/可丢 vs 关键/不可丢事件分类 | **可直接参考** | `POSITION/PROGRESS` 可合并，`DEATH/DISCONNECT/PERMISSION_CHANGE/JOB_TERMINAL` 不可丢；目标 outbox/telemetry 应沿用此分类 |
| `.../world/chunk/EventBackpressure.java:28-89` | 有界 FIFO + 终态保护 + `drain()` | **可直接参考** | 溢出时优先淘汰可合并事件；但“全是终态则超容量”必须改成落盘/失败关闭 |
| `.../StateMachines.java:42-94` | 任务状态机与终态不可逆 | **可直接参考** | 覆盖取消、暂停、故障、恢复；可直接映射到 `JobState` / outbox 发送状态 |
| `.../ConnectionState.java:18-49` | 连接线性状态机 + 任意状态可回 `DISCONNECTED` | **可直接参考** | 适合抽象 QQ 适配器 `DISCONNECTED → AUTHENTICATING → CONNECTING → READY` 等状态 |
| `PlayerMosire/minecraft-body/src/main/java/dev/playermosire/body/job/DefaultJobEngine.java:84-121` | `submit(capability,args,idempotencyKey)` + 同键同参重放 | **需重写** | 设计意图正确；但幂等表是内存 `ConcurrentHashMap`，重启丢失；目标需持久化 |
| `DefaultJobEngine.java:171-183` | 所有状态写入经 `transitionTo` 并验证状态机 | **可直接参考** | 目标 `TurnCoordinator` 也应只有一个 transition 入口 |
| `DefaultJobEngine.java:187-283` | 租约、取消、postcondition 验证、终态收敛 | **可直接参考** | 协作取消 + 独立验证“执行完毕≠目标达成”很适合项目任务流 |
| `.../JobStore.java:13-22` | `save/load/listActive` 最小存储端口 | **可直接参考** | 目标可扩展为事件 + outbox + 幂等索引的事务存储 |
| `.../FileJournalJobStore.java:69-157` | 追加日志 + 每行哈希校验 + `shouldReplay` | **可直接参考** | 崩溃恢复/损坏不静默降级值得借鉴；哈希截断仅用于损坏检测，不是防篡改 |
| `.../SqliteJobStore.java:76-168` | UPSERT 单行状态 + 不静默读损坏库 | **可直接参考** | 适合目标 SQLite 任务/outbox 表；目标需把业务事件与 outbox 放同一事务 |
| `.../LeaseManager.java:15-55` | 多资源全有或全无租约 | **可直接参考** | 目标可用于“同一玩家/同一回合/同一 QQ 目标串行”互斥 |
| `.../Postcondition.java:22-36` | 用权威状态验证目标是否真正达成 | **可直接参考** | 避免“body 返回正常 = 任务成功”的假成功；适合回合提交/消息发布 |
| `.../recover/Reconciler.java:31-81` | 用服务端确认事实对账，冲突时安全中止 | **可直接参考** | 目标崩溃恢复应坚持“不被本地旧意图牵着走”；适用于 outbox `UNKNOWN` 和 QQ 连接恢复 |
| `.../recover/RecoverableNavigation.java:39-58` | RECONCILING 阶段、确认点、跳过已确认段 | **需重写** | 设计原则好；但它承认 `DefaultJobEngine` 没有 resume 入口，包装层不能替代引擎恢复；目标应把恢复做成 taskflow 内建能力 |
| `.../session/ReadyGate.java:22-93` | 只有所有必要元素到齐才 READY，且每个 epoch 重置 | **可直接参考** | 适配 QQ 时可定义“WS open + get_login_info ok + 心跳/事件正常”才 ready，避免连接后立刻发消息 |
| `.../session/SessionAdapter.java:41-68,238-314` | 连接 epoch、连接/断开等待、断开原因分类 | **可直接参考** | epoch 隔离 + 旧会话不复用是正确重连语义；协议特定代码不可复制 |
| `.../session/DisconnectReasonClassifier.java:49-74` | 纯函数、固定优先级分类断开原因 | **可直接参考** | 可直接泛化为 NapCat 断开/鉴权/限流/网络错误分类 |
| `PlayerMosire/player-app/src/main/java/dev/playermosire/app/tool/AsyncJobTool.java:95-123` | 工具调用 → job 提交 + JSON `jobId/state` 返回 | **需重写** | 工具层与 job engine 的边界思路好；但幂等键派生见下一行 |
| `.../tool/ToolKeys.java:31-38` | 用 capability + 参数摘要派生幂等键 | **不建议复用** | 相同参数的自然重复操作会被永久折叠，且不含 actor/turn/session；目标必须使用调用方显式 idempotencyKey + 作用域 |
| `.../app/cog/CogLoop.java:45-55,123-134` | 自动审批所有 player tools、把 `minecraft.foo` 改成 API-safe 名称 | **不建议复用（AutoApprove）** / **可直接参考（名称重写）** | `AutoApprovePermissionGate` 只适合测试；`minecraft.foo → minecraft_foo` 对 OpenAI 兼容函数名限制是可复用兼容技巧 |

### 5.2 关键 bug / 风险（PlayerMosire）

1. **无实际审批**：`CogLoop` 构造 `AutoApprovePermissionGate`（`CogLoop.java:127-134`），而工具 `Permission` 只是枚举。目标生产不能这样做。
2. **幂等只在内存**：`DefaultJobEngine` 的 `idempotency` 是 `ConcurrentHashMap`（`:48`），`store.listActive()` 没有用于重建索引；重启后用相同 key 可以创建新 job。
3. **无内建恢复入口**：`RecoverableNavigation.java:52-58` 明说 `DefaultJobEngine` 没有 public resume/load path；恢复由外部 wrapper 做，不能保证所有 job 都经过。
4. **终态队列可无界**：`EventBackpressure.enqueue` 在队列全为 terminal 时仍追加（`:62-65`），若外部持续产生不可丢事件会内存增长。
5. **File journal 读取全文件**：`lastLineOf` 使用 `Files.readAllLines`（`FileJournalJobStore.java:206-220`），日志大时内存/延迟不可控；目标需 tail 读或分段落盘。
6. **SHA-256 截断校验非防篡改**：`FileJournalJobStore.java:161-186` 只计算前 16 hex 的摘要；能写文件的人可重算，不能当安全签名。
7. **ToolSpec 权限桶与执行脱节**：`ToolSpec.permissionLevel` 是字符串，没有任何中心执行器用它与实际 caller 比较；目标应绑定到 AgentLib `AccessToken` + `AgentPermissionSet`。
8. **协议特定耦合**：`ReadyGate` 依赖 Minecraft 五种 wire 事件；只能借鉴“readiness 不是 connected”的原则，不能直接复用。

### 5.3 测试状态

仓库报告显示 447 个测试、0 failure、1 skipped（仅 `JobStoreBenchTest`）；这些测试集中在纯状态机、job store、协议适配、工具层，质量较高。但仍**没有**外部 MCP 鉴权、跨进程崩溃恢复、真实 QQ/NapCat、真实 LLM 的本次证据。

---

## 6. `GameMosire`：命令路由可用，QQ 可靠性/权限模型不可用

**仓库状态**：Git 干净，HEAD `2ca7386`；**没有 LICENSE 文件**；README 只有一行 `# GameMosire`；无 surefire 报告。  
**定位**：TRPG 骰子 bot，NapCat WS 收事件 + HTTP 查询 + 简单命令路由器。

### 6.1 可复用点清单

| 路径:行 | 可复用点 | 结论 | 原因 |
|---|---|---|---|
| `GameMosire/src/main/java/com/cna/gamemosire/adapter/ChatAdapter.java:9-69` | 平台无关 `ChatAdapter` + `MessageEvent` | **需重写** | 方向正确；但 `MessageEvent` 不含 `messageId`/`subType`/`selfId`/原始事件时间，无法生成目标去重键；`groupId` 在私聊被填 `qq_private:<user>`，语义混乱 |
| `ChatAdapter.java:36-45` | 历史记录默认空实现的 SPI | **可直接参考** | 便于可选能力降级；目标应明确返回“unsupported”而不是空列表静默 |
| `GameMosire/src/main/java/com/cna/gamemosire/adapter/NapcatAdapter.java:95-118` | WS 发动作 + random echo | **不建议复用** | 只发不关联响应；没有 pending future/超时/回执；发送失败只 log |
| `NapcatAdapter.java:120-123,393-421` | 关闭/底层通信 | **需重写** | 没有断线重连、没有速率限制、没有消息分段、没有幂等；`onClose/onError` 只记录 |
| `NapcatAdapter.java:220-289` | 群/私聊事件归一化 + 来源过滤 + 自身消息抑制 | **可直接参考** | 有 whitelist/blacklist 和 selfId 过滤的需求；但 selfId 初始为 `-1`，首次事件前可能漏过滤 |
| `GameMosire/src/main/java/com/cna/gamemosire/command/CommandDispatcher.java:42-65` | 有序匹配、首个命中、中英文句号归一化、正则优先级 | **可直接参考** | 命令路由简单清晰；目标 `/确认` 等硬命令可采用显式有序 parser，但权限必须在路由前校验 |
| `.../command/CommandResult.java:6-29` | 群回复 + 私聊回复双通道结果 | **可直接参考** | 对“公开公告 + 私密结果”有借鉴；不应直接复用其字符串结果模型 |
| `.../state/ActivationManager.java:20-44` | 按群+用户 TTL 激活、命中自动续期 | **不建议复用** | 这不是“硬确认”，也不能作为身份/权限来源；自动续期适合临时会话，不适合目标决策确认 |
| `GameMosire/src/main/java/com/cna/gamemosire/DiceBotCore.java:89-130` | 命令前置、非命令忽略、按会话回发 | **可直接参考** | 展示了“命令优先于 LLM/业务”的路由顺序；目标需补 actor 绑定与去重 |

### 6.2 关键 bug / 安全风险（GameMosire）

1. **归一化事件不可去重**：无 `message_id`/`sub_type`，无法构造目标要求的 `selfId + messageId + subType (+groupId)` 去重键。
2. **发送无回执**：`executeWsAction` 只 `send`，随机 echo 不建 pending，不知道 NapCat 是否接受，也无法处理超时/重发。
3. **无重连**：`onClose`/`onError` 只记录；连接失败不会恢复。
4. **无速率限制/分段**：高频回复、长文本直接发；目标会触发 QQ 风控或消息失败。
5. **selfId 初始化竞态**：`selfId=-1` 时收到自身消息可能被当作外部消息；`fetchSelfId` 失败后继续用事件 `self_id` 回填，但事件已入队。
6. **许可缺失**：根目录无 LICENSE，默认不能复制；仅可作为“命令路由/事件归一化”的思路参考。

### 6.3 测试状态

没有 `surefire-reports`，本次未执行 Maven；只能标记为**未测试**。`src/test` 有 5 个测试类源文件，但不能据此声称通过。

---

## 7. `MCWDMosire` 与其他“未完成/辅助”实现

### 7.1 `MCWDMosire`

**状态**：无 Git 元数据、无 LICENSE 文件；源文件头写 `SPDX-License-Identifier: GPL-3.0-or-later`、`Copyright (C) 2026 PlayerMosire contributors`；有构建产物 `target/mosire-observer-0.1.0.jar`，无测试报告。

| 路径:行 | 可复用点 | 结论 | 原因 |
|---|---|---|---|
| `MCWDMosire/src/main/java/dev/mcwmosire/observer/EventRecorder.java:14-69` | 服务端权威 JSONL 事件记录器；synchronized append；自动 `ts`；不依赖 JSON 库 | **可直接参考（仅测试 oracle）** | 适合做“服务端观察到的事实”对照，不适合作为生产事件存储 |
| `.../ObserverListener.java:57-80` | join/quit 等关键事件在服务端侧记录 | **可直接参考** | 证明“用可信服务端事实校验客户端 Agent”这一模式；仅限 test oracle |
| `ServerObserver.java:34-40` | 周期性 inventory 快照 | **可直接参考** | 可借鉴“定期快照 + 事件增量”的测试思路 |

**风险**：无文件轮转/容量上限；`GPL-3.0-or-later`；不是通用事件总线。

### 7.2 `testspace/RES_ISLAM/WDMosire` 与 `workspace/RES_ISLAM/WDMosire`

这是 `WDMosire` 的旧副本/实验副本，包含已被主仓删除的 `JsonRpcHandler`（例如 `testspace/RES_ISLAM/WDMosire/src/main/java/com/wdm/mcp/JsonRpcHandler.java`）。  
**结论：不建议复用**。旧副本与当前 `WDMosire/docs/MIGRATION_PLAN.md` 所述迁移结果不一致；以顶层 `WDMosire` 当前分支为准，不要基于副本做权限/协议结论。

### 7.3 `reference/` 与 `prompt-efficiency-lab/`

本次未把它们纳入 QQ/Agent 工具/任务流审计范围；它们是外部参考或实验材料，不能作为本项目许可证/接口证据。

---

## 8. 落到本次架构的接口建议

### 8.1 `iibot-connect`：独立 Lib 保持不变，只补可靠性契约

本仓当前 `iibot-connect` 已有比旧仓更干净的 SPI：
`MessagingAdapter`、`InboundMessage`、`OutboundMessage`、`SendReceipt`、`AdapterStatus`、`AdapterEvent`（见
`iibot-connect/src/main/java/io/mosire/iibot/connect/core/`）。
建议继续以这些类型为边界，吸收旧仓经验但不要回退：

```text
InboundMessage
  adapterId, selfId, messageId, userId, sessionType, groupId,
  textSegments, receivedAt, dedupeKey

OutboundMessage
  targetType, targetId, textSegments, idempotencyKey

SendReceipt
  SENT(messageIds) | FAILED_BEFORE_SEND(errorCode) | UNKNOWN(errorCode, messageIds?)

MessagingAdapter
  start(InboundHandler) / send(OutboundMessage) / status()
  / addEventListener / removeEventListener / close()
```

落地规则：

1. **去重键在 adapter 内生成**：`selfId + messageId + subType`，群聊再加 `groupId`；同一键在 TTL 窗口内只投递一次。
2. **发送三态不可降级**：只由 adapter 把协议异常映射为 `SendReceipt`；工具层不得把 `UNKNOWN` 改写成普通失败。
3. **adapter 不做业务**：不识别玩家/推演角色，不解析决策文本，不调用 LLM；角色映射只在 taskflow/app。
4. **HTTP 优先作为 v1 NapCat 动作路径**：参照 `WDMosire/src/main/java/com/wdm/bot/bot/OneBotApi.java:33-45,152-180`；WS echo 路径作为可配置备选，避免一开始就把 echo 队列当唯一真相。
5. **重连单飞**：adapter 内部用 generation/epoch + 单飞 scheduler；`onFailure`/`onClosed` 去重；旧连接先关闭再连接；每个 epoch 重置 readiness 和去重键。
6. **ready 不是 connected**：参考 `PlayerMosire/.../ReadyGate.java:22-93` 的原则，为 QQ 定义最小 ready 条件（WS open + `get_login_info` 成功 + 事件/心跳可观测），ready 前不发送业务消息。
7. **分段与限速在 connect 层完成**：参考 `TextSplitter` 的 Unicode code-point 切分和 `TargetRateLimiter` 的 per-target 隔离；但目标 rate limiter 应支持排队/可取消/背压，而不是满载静默丢弃。
8. **日志/错误脱敏**：所有异常摘要经 `Redaction` 类处理，禁止 token、Authorization、完整 query。

### 8.2 `iibot-connect-agent`：扩展层只做“能力 → 受权 AgentTool”，不做世界/回合

建议接口形态（示意，不写入生产代码）：

```java
public interface ConnectToolCatalog {
    // 由 app 从配置构造 principal；模型/QQ 参数不能构造或修改它
    List<AgentTool> toolsFor(ConnectPrincipal principal);
}

public record ConnectPrincipal(
        ActorRole role,              // SECRETARY / GM / DIRECTOR / SUPERVISOR
        String actorId,
        Set<String> resourceScopes  // 例如 qq:account:10001, qq:conversation:private:123
) {}
```

每个工具必须同时声明：

```java
String name();
Map<String,Object> jsonSchema();          // 完整 JSON Schema，不允许只返回 properties
ToolSpec spec();                         // requiredLevel/sensitive/destructive/noExport
Set<String> roles();                     // 例如 SECRETARY / GM / DIRECTOR / SUPERVISOR
ResourceManifest resources();            // QQ 账号/会话/目标命名空间
ToolGate gate(ToolContext ctx);          // 发送类工具可 Ask；管理类工具可 Block
Map<String,Object> ledgerArgs(ToolContext ctx); // 对 message/token 做摘要或脱敏
ToolResult execute(ToolContext ctx);
```

硬规则：

- **唯一入口**：所有调用只走 `ToolCallAuthorizer.execute(registry, toolName, context)`；禁止 `tool.execute(context)`、禁止 `PermissionGuard` 与 `ToolCallAuthorizer` 双轨并存。
- **身份来自宿主**：`principal` 由 app 从配置/连接绑定构造；禁止从模型参数、QQ 文本、外部 MCP 参数读取角色。
- **工具面分级**：
  - 秘书：只读 `world_lookup`、`turn_status`、`my_decision_status`；
  - 中央推演：只读完整世界 + `turn_snapshot_read` + `submit_adjudication`；
  - 外部 supervisor：只读事件/状态 + 受控 `turn_*`；
  - `qq_send_*`、账号管理、Agent 控制、文件/进程工具**不进入**玩家 Agent 工具面；发送统一走 outbox。
- **发送敏感、管理禁外发**：`qq_send_*` 可声明 `sensitive=true`（默认不放行），账号 CRUD/连接管理可声明 `noExport=true`；外部 MCP 的 `include` 与 allow-list 必须来自同一清单。
- **错误具名**：`PERMISSION_DENIED` / `RESOURCE_DENIED` / `INVALID_ARGUMENTS` / `IDEMPOTENCY_CONFLICT` / `DEPENDENCY_UNAVAILABLE`，不把 Java 堆栈返回给模型。
- **schema 统一**：用完整 JSON Schema，测试 `required`、enum、类型、`additionalProperties`；避免 iBot 的 `properties-only` 和 `Map<String,String>` 扁平化。

### 8.3 `iibot-taskflow`：事件/回合/outbox 三层，权威存储不可丢

建议在现有 `TaskflowEvent`、`EventSink`、`InMemoryEventStore` 基础上补以下端口（示意）：

```java
public interface EventStore extends EventSink {
    long append(TaskflowEvent event);                  // 存储分配 seq
    Page readAfter(long seq, int limit);
    long lastSeq();
}

public interface CommandLog {
    ReplayDecision reserve(String scope, String idempotencyKey, String requestHash);
    void complete(String scope, String idempotencyKey, CommandResult result);
}

public interface Outbox {
    // 与领域事件在同一事务内追加，outboundId 稳定
    String enqueue(OutboundMessage message, String eventId);
    OutboxView status(String outboundId);
    // 不提供“重发 UNKNOWN”的普通 API；只能人工/显式补偿
}
```

状态与命令规则：

1. **事件字段补齐**：在 `TaskflowEvent` 中增加 `schemaVersion`、`correlationId`、`causationId`、`worldVersion`；`idempotencyKey` 必须非空且作用域含 `turnId/actorId`。
2. **单一 transition 入口**：`TurnCoordinator` 使用 `expectedRevision` CAS；非法状态/过期 revision 返回具名冲突，零事件、零世界变更。
3. **硬确认由宿主完成**：`/确认 <draftId>` 必须校验同一私聊、玩家绑定、回合 `OPEN`、`previewHash`、过期时间；LLM/工具/外部 MCP 不得伪造。
4. **可见性在进入模型前过滤**：秘书 prompt、工具结果、brief 都必须从“该玩家可见事实”投影生成；禁止把完整 `WorldBible` 交给秘书。
5. **outbox 三态**：`PENDING → SENT | FAILED_BEFORE_SEND | UNKNOWN`；`UNKNOWN` 不自动重试，`turn.published` 必须等待所有必需消息终态并记录未确定项。
6. **事务边界**：领域事件 + 幂等索引 + outbox 记录在同一 SQLite 事务；外部 QQ 发送永远在事务提交之后，禁止“先发后记”。
7. **恢复**：启动时从事件流重建投影；非终态 job/outbox 进入 `RECONCILING`，用服务端/发送回执事实对账；已确认的副作用不重发。
8. **external MCP**：默认关闭、仅回环、独立路径/独立 registry；所有 mutating 工具要求 `idempotencyKey + expectedRevision + reason`；`event_poll(afterSeq)` 只读不消费；外部调用者固定为 `SUPERVISOR`，不可伪造玩家/推演。
9. **队列**：权威事件/outbox 不可丢；`POSITION/PROGRESS` 类高频事件可合并；终态事件不能因容量不足而无限追加，溢出应落盘或 fail-closed。

---

## 9. 建议的测试矩阵（目标模块，不是本次已运行）

| 模块 | 测试 | 依据/参考 |
|---|---|---|
| `iibot-connect` | echo 并发、乱序响应、echo 超时/断线 fail-all | iBot `OneBotConnectionTest.java:48-75` |
| `iibot-connect` | HTTP 动作回执三态；超时 → `UNKNOWN`，不自动重试 | WDM `QqSendMessageTool` 反例 + 本仓 `SendReceipt` |
| `iibot-connect` | 重连 single-flight、指数退避 + jitter、旧 socket 关闭、epoch 隔离 | iBot `:234-251` + Player `SessionAdapter` |
| `iibot-connect` | 入站去重：同 `selfId+messageId+subType+groupId` 只投一次 | 目标契约 §4.2；旧仓均缺 |
| `iibot-connect` | 限速：burst、排队/背压、可取消、per-account/per-target、bucket 清理 | iBot `SendRateLimiterTest` + 当前 `TargetRateLimiter` |
| `iibot-connect` | 文本切分：Unicode code point、长文本、换行边界 | 当前 `TextSplitter` |
| `iibot-connect` | MockNapCat：鉴权、延迟响应、错误 retcode、重复 echo、主动断线 | iBot/WDM `MockNapCatServer` |
| `iibot-connect-agent` | guest/default/system 与角色/资源交叉；list 不可见且 call 必拒 | iBot `PermissionGuard`、AgentLib `ToolCallAuthorizer` |
| `iibot-connect-agent` | 外部 MCP 看不到 `noExport` 管理/发送工具 | 本仓 MCP 契约 + AgentLib `ToolSpec.noExport` |
| `iibot-connect-agent` | 每次工具调用都经过 authorizer（spy/模拟）；直接 `execute` 不存在 | 目标设计红线 3/7 |
| `iibot-connect-agent` | 完整 schema；缺参、类型错误、数组/嵌套参数不被字符串化 | `WdmMcpServerTest.java:119-120` + iBot schema 缺口 |
| `iibot-connect-agent` | `ledgerArgs` 脱敏；工具错误不含 token/完整 URL | 当前 `Redaction` + 目标 D-008/D-013 |
| `iibot-taskflow` | 事件 seq 单调；同 `(scope,idempotencyKey)` 只追加一次；重放返回原回执 | Player `DefaultJobEngine` 但需补持久化 |
| `iibot-taskflow` | 回合状态机全转移表、非法转移零事件/零世界变更 | Player `StateMachinesTest`、目标 §3.6 |
| `iibot-taskflow` | `/确认`：同私聊、同玩家、previewHash、回合、过期、重复确认 | 目标 §3.4/§4.4；Game activation 不能替代 |
| `iibot-taskflow` | 冻结后晚到决策拒绝；一个回合最多一个 committed | 目标 §3.6/§4.5 |
| `iibot-taskflow` | 秘书 brief 只含玩家可见 fact；工具结果也过滤 | Goat `SideIntelMap` 思路 + 目标 §3.5 |
| `iibot-taskflow` | outbox `SENT/FAILED/UNKNOWN`；UNKNOWN 不自动重发；重复 idempotency 不重复发 | 目标 §4.7 + 本仓 `SendReceipt` |
| `iibot-taskflow` | 崩溃恢复：非终态 job/outbox 对账；已确认副作用不重发 | Player `Reconciler`/journal/`shouldReplay` |
| `iibot-taskflow` | 外部 MCP `event_poll` 不消费游标；写工具三要素/状态冲突/越权 | 目标 external MCP §5–§9 |
| `iibot-taskflow` | 敏感信息不进入日志、事件 payload、模型上下文 | 目标根红线 8 |

---

## 10. 风险、许可证与未完成状态汇总

### 10.1 许可证

| 仓库 | 许可证证据 | 复用判断 |
|---|---|---|
| `iBotMosire` | `iBotMosire/LICENSE:1-10`（Apache-2.0） | 代码/设计参考风险最低；但仍需保留版权/许可通知，不能把其业务架构当本项目设计 |
| `WDMosire` | `WDMosire/LICENSE:1-10`（Apache-2.0） | 可参考；若复制代码需保留 LICENSE/修改声明 |
| `GoatMosire` | 根目录无 LICENSE；`GoatMosire/README.md:8` 仅 badge 指向 `./LICENSE`（文件不存在） | **不能推定 MIT**；代码/示例默认不应复制 |
| `PlayerMosire` | `LICENSE:1-10` GPL-3.0；`NOTICE:1-25` 列出 GPL 依赖 | **复制代码/派生会触发 GPL-3.0**；只适合读设计；若接入须单独法律/项目许可裁定 |
| `GameMosire` | 根目录无 LICENSE | 默认保留所有权利；**不建议复制任何代码/文本** |
| `MCWDMosire` | 文件头 `SPDX-License-Identifier: GPL-3.0-or-later`；无独立 LICENSE 文件 | 只当作 GPL 测试 oracle 参考，不复制 |
| `GSimulator`（间接） | `GSimulator/LICENSE` 存在；Player NOTICE 标明 GPL-3.0 | 目标依赖的 AgentLib/发行包必须按其真实发布许可处理，不能凭旧仓印象 |

### 10.2 高优先级安全/可靠性风险

1. **外部 MCP 无鉴权就暴露等于远程控制 QQ/进程**：旧 `iBotMosire` 的 `/events`、`/health` 和 `WDMosire` 的 `/mcp` 都是反例。目标必须默认关闭、仅回环、独立 allow-list，并明确 AgentLib 0.2.3 fixed-caller 的鉴权缺口。
2. **`UNKNOWN` 缺失导致重复消息**：发送工具必须有“可能已发出”状态；目标 outbox 的 `UNKNOWN` 不可自动重试，只能人工/显式补偿。
3. **持久幂等缺失导致重复决策/重复回合**：只在内存 map 做 idempotency 不够；必须落到事件/命令日志并与 `expectedRevision` 联合。
4. **工具权限只在元数据里**：`WDMosire`、`PlayerMosire`、`GoatMosire` 都存在这个问题；目标必须 `ToolCallAuthorizer` 唯一入口。
5. **可见性只靠 prompt**：旧仓没有任何一个把“模型不可见事实”从工具结果/上下文彻底剥离；目标必须在进入模型前过滤。
6. **终态事件背压可导致无界内存**：`EventBackpressure` 的“terminal 永不丢”是正确的安全优先，但必须配落盘/fail-closed，不可无限扩容。
7. **schema/Map<String,String> 类型丢失**：iBot 的扁平参数会诱发参数猜测和错误解析；目标应使用完整 JSON Schema + 结构化参数。
8. **危险开关**：`--dangerously-skip-permissions`、`AutoApprovePermissionGate`、`--dangerously` 类设置不能进入生产默认配置。
9. **令牌/凭据**：配置文件明文 token、异常文本回显、进程环境变量传递都可能泄露；目标需 secret provider + 日志脱敏 + 事件 payload 禁止 secret。

### 10.3 未完成状态

| 仓库 | 未完成/不能宣称完成的部分 |
|---|---|
| `iBotMosire` | 账号 CRUD 工具仍是占位（`NapcatTools.java:391-428`）；未做真实 NapCat/LLM E2E 的本次证据；Agent 单槽位/危险审批开关不适合目标 |
| `WDMosire` | 功能测试有历史通过，但 MCP 无鉴权/无 caller 身份；历史真实 E2E 未复现；内存存储无持久化 |
| `GoatMosire` | 当前分支没有 QQ 接入；根 LICENSE 缺失；工具数量文档不一致；示例工具无统一权限/校验 |
| `PlayerMosire` | README 明示调查/规划阶段；Main 是 placeholder；job engine 无内建 resume；真实 Agent 集成未完成；GPL |
| `GameMosire` | 无重新连接、无发送回执、无去重、无权限、无测试报告、无许可证；仅可作为命令路由思路 |
| `MCWDMosire` | 仅 test oracle/观察插件；无测试报告；不应作为生产事件存储 |

---

## 11. 最终建议（按优先级）

### P0（写生产代码前必须闭合）

1. **许可证裁定**：明确本项目与 GPL-3.0 的 PlayerMosire 代码、无许可证的 Goat/Game 代码之间的边界；本报告建议“只参考设计，不复制代码”。
2. **唯一授权入口**：`iibot-connect-agent` 和 `iibot-taskflow` 的所有工具调用统一走 AgentLib `ToolCallAuthorizer`；不接受 `Permission` 标签或 `AutoApprovePermissionGate`。
3. **外部 MCP 安全模型**：默认关闭、仅回环、独立 token/身份、工具 allow-list；不要复用旧仓的 `/events`、`/mcp` 无鉴权实现。
4. **消息发送三态**：在 `iibot-connect` 固化 `SENT/FAILED_BEFORE_SEND/UNKNOWN`；`UNKNOWN` 永不自动重试；发送成功必须拿到 adapter message id。
5. **玩家硬确认不可由 LLM/MCP 代劳**：`/确认` 由宿主命令解析和状态机执行，工具只能创建/预览 draft。

### P1（首个纵向切片）

1. 先实现 `iibot-connect` 的 NapCat HTTP 动作 + WS 事件；用 WDM 的 HTTP 优先慢路径和 iBot/WDM 的 Mock 作为测试基座。
2. 用 `PlayerMosire` 的 `StateMachines`/`EventEnvelope`/`EventBackpressure` 思想定义 taskflow 事件与回合状态；不要把其 Minecraft 类型或 GPL 代码搬入。
3. 用 SQLite 同时落 domain event + outbox + 幂等索引；`expectedRevision` 贯穿所有回合命令。
4. 加入去重、分段、限速、脱敏、body size limit、连接 ready gate。
5. 以 Goat 的 `AgentMode`/`SideIntelMap` 为提示，建立 `SECRETARY` / `GM_FILTER` / `ADJUDICATOR` 三种隔离 prompt/工具面/上下文构造器。

### P2（扩展）

1. 外部 MCP 只读 `sys_status`/`turn_get`/`event_poll`/`invariant_check`；写工具后置，且三要素齐全。
2. 多 Agent 路由、回放、审计视图、outbox 人工补偿。
3. 真实 NapCat/LLM/MCP 联调与长跑证据；在此之前保持所有状态为 `NOT_RUN`。

---

## 12. 证据索引（建议后续实现者按此回读）

### 12.1 必读源码（按目标模块）

- 接入层 SPI：`iibot-connect/src/main/java/io/mosire/iibot/connect/core/{MessagingAdapter,InboundMessage,OutboundMessage,SendReceipt,AdapterStatus}.java`
- NapCat 参考：`WDMosire/src/main/java/com/wdm/bot/bot/{OneBotApi,NapCatClient}.java`；`iBotMosire/ibotmosire-napcat/src/main/java/com/ibotmosire/napcat/connection/OneBotConnection.java`
- 测试基座：`iBotMosire/ibotmosire-napcat/src/test/java/com/ibotmosire/napcat/test/MockNapCatServer.java`；`WDMosire/src/test/java/com/wdm/bot/test/MockNapCatServer.java`
- 权限参考：`iBotMosire/ibotmosire-core/src/main/java/com/ibotmosire/core/tool/PermissionGuard.java`；项目上游 `~/ProjectMosire/AgentLibMosire/src/main/java/io/mosire/agentlib/tool/ToolCallAuthorizer.java:20-63,169-215`
- 任务/状态：`PlayerMosire/environment-api/src/main/java/dev/playermosire/environment/api/{StateMachines,EventEnvelope,EventType}.java`；`PlayerMosire/minecraft-body/src/main/java/dev/playermosire/body/job/{DefaultJobEngine,JobStore,FileJournalJobStore,SqliteJobStore}.java`
- 恢复/可见性：`PlayerMosire/minecraft-body/src/main/java/dev/playermosire/body/recover/Reconciler.java`；`GoatMosire/example/GetGoat/src/main/java/com/cna/getgoat/map/campaigns/intel/SideIntelMap.java:93-169`
- 角色/模式：`GoatMosire/example/GetGoat/src/main/java/com/cna/getgoat/agent/{AgentMode,AbstractAgent}.java`

### 12.2 不应作为生产依据的旧实现

- `iBotMosire` 的 `AgentManager` 单槽位与 `--dangerously-skip-permissions`。
- `iBotMosire` 的 `/events` 未认证 SSE、`/health` 未认证、手写 MCP 传输。
- `WDMosire` 的 `McpHttpHandler` 无认证 `/mcp` 和 `permission()` 元数据。
- `GoatMosire`/`GetGoat` 的 `ToolRegistry.dispatch` + `AutoApprove` 式无授权执行。
- `GameMosire` 的 `NapcatAdapter`（无 echo future/无重连/无限速/无去重）和 `ActivationManager`（不是授权）。
- `testspace/RES_ISLAM/WDMosire` 旧副本。

---

## 13. 报告边界声明

本报告的所有“源码事实”来自 `/home/cna/DevMosire` 的只读阅读；所有“测试通过”数字来自仓库内历史 report 文件，本次**没有执行**构建、测试、真实 QQ、LLM、MCP 或游戏服连接。  
因此：

- 可直接参考 = 适合作为设计/测试思路和命名参照，不等于可直接复制；
- 需重写 = 机制有价值，但旧实现不满足目标的安全/一致性/恢复要求；
- 不建议复用 = 有明确反例或高风险，不得进入生产默认路径。

