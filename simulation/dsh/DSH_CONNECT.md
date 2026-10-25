# 把 iiBot MCP 真正接入 DSH

> 本文只写接入步骤；实际运行前 app 必须已启动，且 `simulation/dist` 必须是包含新工具的新 jar。
> MCP endpoint：`http://127.0.0.1:8765/mcp/control`
> serverName：`iibot`，DSH 中工具名会显示为 `mcp__iibot__tf_*`。
>
> QQ 后端：LLOneBot 8.3.2 / OneBot v11，WS `127.0.0.1:3001`、HTTP `127.0.0.1:3000`。
> 迁移细节与已知差异见 [`LLBOT_MIGRATION.md`](LLBOT_MIGRATION.md)。
> 当前交接启动步骤见 [`LLBOT_HANDOFF.md`](LLBOT_HANDOFF.md)。

## 0. 两条接入路线

当前 DSH 环境已经加载了 **MCP Connector 插件**（`mcp_connector_*` 工具可用），
因此本机 Web/Agent 会话优先使用 Connector 路线，不需要先手工安装
`@deepseek-ai/dsh-mcp-client` 或修改 `cordis.patch.yml`：

```text
mcp_connector_configure
  name: iiBot
  serverName: iibot
  transport: streamable-http
  url: http://127.0.0.1:8765/mcp/control
  authMode: bearer
  bearerToken: <IIBOT_CONTROL_TOKEN>
```

连接后用：

```text
mcp_connector_tools_list connectorId=__custom__ connectionKey=custom-iibot
mcp_connector_health_check connectorId=__custom__
mcp_connector_tool_search query=tf_status connectionKey=custom-iibot
mcp_connector_session_tools preview-activate → activate
```

注意：`iibot` 是 serverName/展示名，不是 Connector 的 connectorId。用户手工添加的
自定义连接在 tools_list/health_check 中使用 `connectorId=__custom__`；更精确的
单连接定位使用 `mcp_connector_status` 返回的 `connectionKey`（本机当前为 `custom-iibot`）。

本机当前实测（2026-10-24 22:30，新 jar）：

- app/MCP 已用新 jar 启动，`tf_status` 正常；
- Connector 插件可用，已配置一条自定义 streamable-http 连接；
- 因为 Connector 的 Bearer 字段需要明文凭据，本机测试使用
  `simulation/scripts/iibot-auth-proxy.py`：它从 `control.env` 读取 token，
  只监听 `127.0.0.1:8766`，向 iibot 上游注入 `Authorization`，DSH 侧连接为
  `authMode=none`；
- `mcp_connector_tools_list {"connectorId":"__custom__"}` 返回 **77 个工具**；
- 自定义连接的 `health_check` 使用 `connectorId=__custom__` 才是正常检查；
  传 `iibot` 会被当成目录 connector id，返回 not-configured；
- `mcp_connector_session_tools status` 可显示 0 个按会话激活工具，但该连接
  “始终注入”，工具仍可直接使用。

所以 Connector 路线已可用；token 不应直接粘贴进模型上下文，优先使用只回环代理或
等效的本机凭据注入方式。

另一条路线是下面的 headless/manual MCP client patch，适用于不使用 Connector
插件的独立 DSH profile。

## 0.1 本机实测接入快照（2026-10-24）

- app 新 jar 已启动，MCP `127.0.0.1:8765`；
- 为不把 Bearer token 放进模型上下文或 Connector 配置，使用回环凭据注入代理
  `127.0.0.1:8766`；
- DSH Connector 已连接，自定义连接 `connectorId=__custom__`、
  `connectionKey=custom-iibot`，健康检查 PASS；
- 当时实际工具数 77（旧 jar，未启用 Inbox Mode A）；
- 旧 jar 的 `tf_ticket_board wait=true` 曾是主通知入口；Inbox 接入后该 Agent 长轮询
  工作流已废弃，改由宿主通知桥 + DSH adapter 唤醒；
- `tf_post_compose DRAFT` 实测 PASS，未真实外发；
- 自主入团非报名消息安全拒绝 `OPT_IN_REQUIRED`，未注册、未发送；
- 任意发送工具当前未注册，符合默认关闭策略。

已发现并修复的源码问题（运行中的旧 jar 尚未替换）：

1. `tf_post_compose` 遇到后台字段/Markdown 不再静默用 fallback 覆盖正文；
   现在返回 `TEXT_GUARD_REJECTED` + `guardViolations` + `guardSanitizedBody`，
   并且不创建草稿。
2. `tf_qq_message_get` 在 NapCat live cache 未命中时，允许带
   `chatType/targetId/maxScan` 回扫历史；无 target 时返回
   `MESSAGE_NOT_IN_LIVE_CACHE` 和提示，而不是笼统 `MESSAGE_UNAVAILABLE`。
3. Connector 自定义连接使用 `connectorId=__custom__` +
   `connectionKey=custom-iibot`；`iibot` 只是 serverName/展示名。
4. 出站节奏（D-019）：所有逻辑外发消息共享最小间隔，默认 150ms，
   生产范围 100..200ms；ACK 与正式回复也共用同一节奏锁。

## 0. 当前必须满足的前提

```bash
cd /home/cna/iiBotMosire

# 1) 新 jar 已部署且 app/MCP 已启动
simulation/scripts/04-wait-mcp.sh
simulation/scripts/06-mcp.sh --list | head
```

`06-mcp.sh --list` 是 MCP 真实握手，不是只看端口。至少应看到：

```text
tf_status
tf_ticket_list
tf_ticket_get
tf_qq_chat_list
tf_qq_history_read
tf_player_onboard_from_group
tf_post_compose
inbox_status
inbox_list
tf_inbox_binding_descriptor
```

如果这里失败，先修 app/MCP，不要启动 DSH。`cordis.patch.yml` 设置了
`failOnStartupError: true`，MCP 不可用时 DSH 会直接启动失败。

## 1. 手工路线：安装 MCP client 插件

> 如果使用前面的 MCP Connector 插件，本节和下一节的手工 patch 可以跳过。

`@deepseek-ai/dsh-mcp-client` 不是 DSH 默认自带包，必须装进要使用的 profile。

```bash
# 一次性/无头控制方
dsh plugin --profile headless add @deepseek-ai/dsh-mcp-client@0.0.1-rc.1

# 或 Web UI 使用的 profile
dsh plugin --profile web add @deepseek-ai/dsh-mcp-client@0.0.1-rc.1
```

检查：

```bash
ls ~/.dsh/profiles/headless/node_modules/@deepseek-ai/dsh-mcp-client
# 或
ls ~/.dsh/profiles/web/node_modules/@deepseek-ai/dsh-mcp-client
```

## 2. 让 DSH 进程拿到 token

不要打印 token。启动 DSH 的同一个 shell 中执行：

```bash
set -a
. /home/cna/.config/iibot/control.env
set +a
test -n "$IIBOT_CONTROL_TOKEN" && echo TOKEN_READY
```

如果 DSH 已经在运行，它不会自动获得新 shell 的环境变量，必须重启 DSH。

## 3. 使用 patch

仓库内已验证的 patch：

```text
/home/cna/iiBotMosire/simulation/dsh/cordis.patch.yml
```

它包含：

```yaml
- insert:
    - id: mcp-iibot-control
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: iibot
        transport: streamable-http
        url: http://127.0.0.1:8765/mcp/control
        headers:
          Authorization: !!js '`Bearer ${process.env.IIBOT_CONTROL_TOKEN}`'
        toolCallTimeoutMs: 60000
        failOnStartupError: true
```

`toolCallTimeoutMs` 只约束普通 MCP 工具调用。Inbox Mode A 不靠长轮询：
没有 available item 时结束回合，下一条通知由 DSH adapter 唤醒。

## 4A. headless 接入

先启动 app，确保 `04-wait-mcp.sh` 通过，然后：

```bash
cd /home/cna/iiBotMosire
set -a
. /home/cna/.config/iibot/control.env
set +a

dsh --profile headless \
  --patch /home/cna/iiBotMosire/simulation/dsh/cordis.patch.yml \
  "$(cat /home/cna/iiBotMosire/simulation/dsh/GM_SYSTEM_PROMPT.md)"
```

首轮可先只让 DSH 调：

```text
mcp__iibot__tf_status
```

确认返回 MCP 状态后，再让它读取 `FRESH_INIT_GM_PROMPT.md` 做初始化。

## 4B. Web 接入

### 方式一：临时用 patch 启动（推荐先验证）

先启动 app，然后使用带 `--patch` 的 launcher 启动 web profile：

```bash
cd /home/cna/iiBotMosire
set -a
. /home/cna/.config/iibot/control.env
set +a

dsh --profile web \
  --patch /home/cna/iiBotMosire/simulation/dsh/cordis.patch.yml
```

当前机器已有 `dsh web` 进程时，不要在原进程上直接期待工具出现；
MCP client 是启动期插入的，必须重启到带 patch 的会话。

### 方式二：持久写入 web profile

如果确认要长期使用，可把同一个 `insert` 条目追加到：

```text
~/.dsh/profiles/web/cordis.patch.yml
```

不要重复添加同一个 `id: mcp-iibot-control`。web profile 的 `patchReload` 是
`live`，修改后可能触发热重载；app 未启动时可能因 `failOnStartupError` 导致
当前 DSH 会话异常，所以建议先启动 app，再修改/重启。

## 5. 验证工具真的进来了

1. DSH 工具列表里应出现 `mcp__iibot__tf_status`；
2. 调 `tf_status` 得到 `ok:true`；
3. 调 `tf_qq_chat_list {"scope":"configured"}` 能看到当前推演群；
4. 调 `tf_inbox_binding_descriptor`，把 descriptor 交给 `inbox_adapter_bind`
   （或先运行 `simulation/scripts/08-inbox-descriptor.sh` 生成 0600 descriptor 文件）；
5. 用 `inbox_adapter_status` 确认绑定；创建测试工单/待审推文后，宿主通知桥应 push；
6. 收到唤醒后 `inbox_status/list → claim → tf_ticket_get/post_get → complete/release`，
   `available=0` 即结束回合，不轮询。

最简可粘贴给隔壁会话的接入提示词：

```text
simulation/dsh/INBOX_NEIGHBOR_HANDOFF_PROMPT.md
```

若工具没出现，按顺序查：

```bash
# 插件是否真安装
ls ~/.dsh/profiles/<profile>/node_modules/@deepseek-ai/dsh-mcp-client

# patch 是否合成
IIBOT_CONTROL_TOKEN=dummy-not-used \
  dsh --profile headless \
  --patch /home/cna/iiBotMosire/simulation/dsh/cordis.patch.yml \
  --dump-config | grep -A15 mcp-iibot-control

# DSH 进程是否继承 token（只检查存在，不打印值）
test -n "$IIBOT_CONTROL_TOKEN" && echo TOKEN_READY
```

## 6. 当前已知阻塞

- 仓库内 patch 已经能通过 DSH schema/config 合成；
- 但当前 `headless` 和 `web` profile 都还没有安装
  `@deepseek-ai/dsh-mcp-client`；
- app 当前未运行，`127.0.0.1:8765` 空闲；
- `simulation/dist` 仍是旧 jar；
- 因此现在直接启动 DSH 不会得到新工具，必须先完成“部署新 jar → 启动 app/MCP →
  安装 MCP client 插件 → 用带 patch 的 DSH 会话启动”这一整条顺序。

## 7. 历史 smoke 结果（2026-10-24 22:31，旧 tf_ticket_board 工作流）

已通过 DSH MCP Connector 直接调用新 jar 的工具：

- `tf_status`：PASS。
- `tf_qq_chat_list scope=configured`：PASS，返回当前推演群。
- `tf_qq_history_read` 省略目标：PASS，默认读当前推演群。
- `tf_qq_group_members`：PASS，返回群成员目录。
- `tf_qq_message_get`：近期消息 PASS；历史较早的一条返回 `MESSAGE_UNAVAILABLE`，
  属于 NapCat 可访问范围差异，尚未判定为代码缺陷。
- 旧工作流中的 `tf_ticket_board wait=true waitSeconds=10` 超时语义当时 PASS；
  该入口在 Inbox Mode A 下已不再暴露。Inbox 唤醒的当前证据见
  `docs/agents/STATUS.md`。
- `tf_post_compose mode=DRAFT`：PASS；草稿可读取，未发送 QQ。
- `tf_player_onboard_from_group`：使用非报名来源消息时返回
  `OPT_IN_REQUIRED`，未注册、未发送，PASS（安全拒绝路径）。
- `tf_qq_send_private/group`：当前不在工具面，符合默认关闭策略。

发现一个需要开发侧确认的行为：

- `tf_post_compose` 的正文守卫在整段被判为后台内容时会替换为固定兜底句，
  同时只返回 `guardApplied=true`。本次一行联调正文含 `MCP`，草稿正文被整体替换为
  “我这边刚才没有整理出一句可读的回复……”。草稿未发布并已拒绝。
  建议对 GM 外部作者返回明确的校验错误或逐行删除提示，而不是静默替换成无关文本。

未执行：`tf_post_compose` 的 `PUBLISH_NOW`、成功自主入团与真实报名卡发送、任意发送
工具的真实外发；这些都需要先明确具体目标与验收内容。
