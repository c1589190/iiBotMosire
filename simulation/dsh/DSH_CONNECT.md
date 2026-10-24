# 把 iiBot MCP 真正接入 DSH

> 本文只写接入步骤；实际运行前 app 必须已启动，且 `simulation/dist` 必须是包含新工具的新 jar。
> MCP endpoint：`http://127.0.0.1:8765/mcp/control`
> serverName：`iibot`，DSH 中工具名会显示为 `mcp__iibot__tf_*`。

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
mcp_connector_tools_list / mcp_connector_tool_search 发现 tf_* 工具
mcp_connector_session_tools preview-activate → activate
```

本机当前实测（2026-10-24）：

- Connector 插件可用；
- `mcp_connector_status` 返回 **0 条连接**；
- `mcp_connector_tool_search query=tf_status` 返回 **0 个匹配**；
- app 未运行，`127.0.0.1:8765` 空闲；
- `simulation/dist` 仍是旧 jar。

所以 Connector 能用，但必须先把 app/MCP 启动起来，再配置连接。

另一条路线是下面的 headless/manual MCP client patch，适用于不使用 Connector
插件的独立 DSH profile。

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
tf_ticket_board
tf_qq_chat_list
tf_qq_history_read
tf_player_onboard_from_group
tf_post_compose
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
        toolCallTimeoutMs: 360000
        failOnStartupError: true
```

`toolCallTimeoutMs` 设为 360 秒，是为了让 `tf_ticket_board {"wait":true,"waitSeconds":300}`
能完整等待；默认 60 秒会在长轮询返回前被 DSH 截断。

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
4. 调 `tf_ticket_board {"wait":true,"waitSeconds":120}` 可等待工单。

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
