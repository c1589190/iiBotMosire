# LLBot 兼容运行交接单

> 更新时间：2026-10-25 01:13（Asia/Shanghai）
> 用途：把当前 LLBot 兼容运行交给 `/home/cna/iiBotMosire/simulation` 的隔壁 DSH 会话启动。
> 当前状态：app、MCP 凭据代理、两个只读 watcher 已停止；LLBot Docker 保持运行。

## 0. 不要改的东西

- 不要运行 `01-prepare.sh`，除非明确要**清空当前 runtime 重新初始化**；
- 不要用 `01-prepare.sh` 覆盖现有玩家、回合和记录；
- 不要替换正在运行中的 jar；
- 不要把 token 写进仓库、聊天回复或日志。

## 1. 现成资产

```text
仓库：/home/cna/iiBotMosire
jar：simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar
sha256：484bebc2d486353c9cf9367838a9b415b7f0c60d7e486dd90da2c087c913d3cb
LLBot：127.0.0.1:3001 WS / 127.0.0.1:3000 HTTP（LLOneBot 8.3.2）
iibot env：~/.config/iibot/napcat.env（0600，含 LLBot token，不要打印）
MCP：127.0.0.1:8765/mcp/control
回环代理：127.0.0.1:8766（供 DSH Connector 使用，无鉴权）
runtime 备份：simulation/runtime.backup-20261025-011306-llbot-handoff
```

`simulation/dist` 已经是 LLBot 兼容版本；不要把旧 jar 换回来。

## 2. 启动

在 `/home/cna/iiBotMosire` 或 `/home/cna/iiBotMosire/simulation` 均可执行：

```bash
cd /home/cna/iiBotMosire

# 1) 启动 app
simulation/scripts/02-start.sh

# 2) 启动 DSH Connector 使用的回环凭据代理
nohup python3 simulation/scripts/iibot-auth-proxy.py \
  > simulation/logs/auth-proxy.log 2>&1 &

# 3) 启动只读 watcher
nohup python3 simulation/scripts/gm-public-watch.py --interval 30 \
  > simulation/runtime/public-watch/launch.log 2>&1 &
nohup python3 simulation/scripts/board-autopilot.py --interval 10 \
  > simulation/runtime/board-autopilot/launch.log 2>&1 &
```

不要在 DSH 会话里重复启动第二个 app；`02-start.sh` 检测到 app 已在运行会直接返回。

## 3. 启动后只读核验

```bash
simulation/scripts/04-wait-mcp.sh
simulation/scripts/06-mcp.sh tf_status '{}'
simulation/scripts/06-mcp.sh tf_qq_chat_list '{"scope":"groups"}'
simulation/scripts/06-mcp.sh tf_qq_chat_list '{"scope":"recent","count":5}'
```

预期：

- `tf_status` 返回 `recovery=true`，当前回合状态按 runtime 恢复；
- `groups` 返回 LLBot 可见群；
- `recent` 在 LLBot 上由群目录兜底（LLBot 没有 `get_recent_contact`）；
- DSH Connector 连接 `http://127.0.0.1:8766/mcp/control`，健康检查应 PASS。

## 4. 当前已知差异

- LLBot 8.3.2 没有 `get_recent_contact`；iibot 已自动回退到 `get_group_list`；
- 所以 `scope=recent` 不是严格的最近联系人排序，真实会话仍需用 groups/friends/configured；
- `get_group_msg_history`、`get_friend_msg_history`、`get_msg`、群成员和好友列表已实测通过；
- `send_private_msg` / `send_group_msg` / `send_msg` 路由存在，但真实外发尚未执行；
- 真实外发仍需要用户明确目标和验收内容。

## 5. 停止

```bash
cd /home/cna/iiBotMosire
simulation/scripts/03-stop.sh

# 可选：停掉本机回环代理与 watcher
for f in simulation/runtime/iibot-auth-proxy.pid \
         simulation/runtime/public-watch/watch.pid \
         simulation/runtime/board-autopilot/autopilot.pid; do
  [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null || true
done
```

LLBot Docker 是独立服务，不要用本项目的停止脚本停它。
