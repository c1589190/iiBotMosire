# 对外 MCP 总控面设计（v1，已实现切片）

> **状态**：有效（本地控制面已实现并通过 Mock MCP E2E；真实 DSH 连接未验）
> **日期**：2026-10-24
> **用户原话依据**：「也许还可以做一个对外MCP工具来方便外部Agent（如你，DSH）总控事件流，保证不出岔子」
> 以及后续补充：
> 「全部功能。方便外部Agent总控」
> **本文取代**：无

## 1. 目标

让本地外部 Agent（首期 DSH）通过标准 MCP 工具：

1. 观察 QQ Bot 健康状态、世界版本、当前回合、决策与事件流；
2. 检查关键不变量，发现卡死、重复、越权、未知出站等异常；
3. 在受状态机保护的范围内推进全部**游戏事件流**；
4. 不破坏身份、状态机、revision、幂等、审计和玩家硬确认这些宿主不变量。

## 2. 非目标与边界

- 不提供通用 QQ 聊天/群发工具；
- 不提供玩家决策提交/确认工具；外部 supervisor 不能代替玩家硬确认；
- 不提供进程停机、重启、改玩家角色、改密钥、任意 shell/文件写工具；
- 不做公网多租户；当前只允许回环；
- “全部功能”解释为**全部游戏事件流控制**，非游戏运维（Q9）仍建议保持不提供。

## 3. 已实现拓扑

```text
外部 Agent / DSH
      │  Authorization: Bearer <IIBOT_CONTROL_TOKEN>
      │  http://127.0.0.1:8765/mcp/control
      ▼
ControlMcpServer（app/mcp）
  · 回环 host 硬校验、精确路径、请求体上限、常数时间 Bearer 校验
  · 透明代理（method/query/MCP headers；POST/GET SSE 流式转发）
      ▼
内部 AgentToMcpServer（仅 127.0.0.1:0，/internal/mcp）
  · 独立 ToolRegistry
  · ToolCallAuthorizer.standard()
  · caller = DEFAULT + 精确 19 工具白名单 + roles=supervisor
      ▼
ExternalControlToolSet（taskflow/control）
  · actor = 已认证 supervisor（构造期绑定）
  · expectedRevision + idempotencyKey + reason 强制
      ▼
TaskflowEngine 状态机
```

## 4. 工具面（19 个）

### 4.1 只读（5）

| 工具 | 说明 |
|---|---|
| `tf_status` | 引擎版本、世界版本、回合状态、计数摘要 |
| `tf_turn_get` | 当前/指定回合、快照、draft、decision、committed proposal |
| `tf_event_poll` | `afterSeq` 排他游标读取事件摘要；不消费游标 |
| `tf_invariant_check` | 当前状态可安全判定的不变量逐项 PASS/FAIL |
| `tf_setting_list` | 列出 pending 设定或读取单条提案摘要（正文有界预览） |

### 4.2 全游戏事件流写（14）

| 工具 | 说明 |
|---|---|
| `tf_register_player` | 注册玩家身份 |
| `tf_register_director` | 注册推演身份 |
| `tf_load_world_facts` | 装载新世界版本事实（PUBLIC/DIRECTOR_ONLY/PLAYERS） |
| `tf_open_turn` | 开启回合（分钟选择） |
| `tf_lock_turn` | 冻结 OPEN 回合 |
| `tf_start_adjudication` | LOCKED → ADJUDICATING |
| `tf_commit_adjudication` | 提交结构化推演提案，经 engine 校验后原子提交 |
| `tf_fail_adjudication` | ADJUDICATING → FAILED_RETRYABLE |
| `tf_retry_adjudication` | FAILED_RETRYABLE → ADJUDICATING |
| `tf_publish_turn` | COMMITTED → PUBLISHED |
| `tf_issue_brief` | GM/DIRECTOR/SUPERVISOR 签发经过滤 brief；来源事实必须对玩家可见 |
| `tf_setting_propose` | 提交 PUBLIC/DIRECTOR_ONLY/PLAYERS 设定提案 |
| `tf_setting_decide` | 批准/拒绝设定提案；批准生成新 WorldFact 并 worldVersion+1 |
| `tf_run_adjudication_agent` | 运行项目内置中央推演 Agent；要求 revision/幂等/原因，经 MCP 调用真实 LLM |

**不提供**：`decision_submit`、`decision_confirm`、`qq_send_*`、`shutdown`、`reload_config`、`set_role`、shell/文件写。

## 5. 安全与权限

- `ControlMcpServer` 默认 `enabled=false`；启用时必须配置 `bearerTokenEnv`，token 只从环境变量读取。
- 仅允许回环 host（`127.0.0.1`/`localhost` 等）；非回环在 bind 前硬拒绝。
- 外部 Authorization 不转发给内部 MCP；内部 MCP 只绑回环随机端口。
- ToolRegistry 只含上述 19 工具；caller 权限集精确 allow 这 19 个，未给 SYSTEM。
- 所有工具 `roles={"supervisor"}`；资源命名空间 `taskflow` 默认 DENY，需显式授予。
- 所有工具经 `ToolCallAuthorizer`；资源拒绝原样走 `RESOURCE_DENIED`。
- 每个写请求强制 `expectedRevision + idempotencyKey + reason`；缺一返回 `INVALID_ARGUMENTS`。
- 重复 idempotencyKey 由控制层缓存并再次由 engine 幂等兜底。
- token 不出现在 `toString()`、日志或异常文案。
- 事件读取不消费游标；外部面默认不提供 QQ 私聊原文，只提供任务流事件摘要。

## 6. 错误契约

稳定外部错误码：

`INVALID_ARGUMENTS`、`REVISION_CONFLICT`、`WRONG_STATE`、`NOT_FOUND`、`FORBIDDEN`、`CONFLICT`、
`POLICY_DENIED`、`EVENT_STREAM_UNAVAILABLE`、`INTERNAL_ERROR`。

- ToolResult 失败通过 MCP `isError` 返回，不把内部异常堆栈或 token 回显；
- 错误状态/revision/未知实体零副作用；
- 调用方必须重读状态后决策，不自动重试未知写。

## 7. 生命周期

- 由 `iibot-app` 组合根启动：
  - 若配置 `control.enabled=true` 且未注入自定义 ControlPlane，构造 `ControlMcpServer`；
  - `AppRuntime.start()` 启动 adapter/scheduler/control；
  - `AppRuntime.close()` 按顺序关闭 control、事件读口、审计库。
- `ControlMcpServer.close()` 幂等：停 front server → 关 AgentToMcpServer → 关前端 executor。
- MCP 控制面故障不拖垮 QQ/任务流；但启动期 fail-closed。

## 8. 验收与已运行证据

已通过：

- 官方 MCP Java SDK client 携 Bearer 完成 `initialize → tools/list → tools/call(tf_status/tf_register_player)`；
- 工具清单为 19 个，无决策确认/关机/QQ 发送/shell；
- 无/错 token → 401；大小写/空白/前后缀不匹配拒绝；
- 非回环 host 启动拒绝；
- 精确路径、请求体上限 413；
- POST SSE 与 GET SSE 监听流经代理可用；
- close 幂等；token 不泄漏到 `toString()`/异常；
- 重复 key、revision 冲突、错误状态：控制层测试覆盖零副作用。

仍未验证：

- 真实 DSH 连接；
- 远程 TLS/反向代理/多租户；
- 长时间 SSE、断线重连、Last-Event-ID 重放；
- 真实 QQ/LLM 下的全链路。

> 证据见 `docs/agents/STATUS.md` §3 与 `docs/superpowers/status/2026-10-24-first-vertical-slice.md`。
