# hermes-iibot 单容器镜像

把「hermes gateway + iiBot（内嵌 Inbox + 两套 MCP）」装进一个镜像、一个容器。

```
容器内
  PID 1  /init (s6-overlay，宿主镜像自带)
   ├─ cont-init.d/01-hermes-setup   宿主自带：UID 重映射、卷 chown、seed .env/SOUL.md
   ├─ cont-init.d/05-iibot-seed     本目录：首启才落 bot.json / config.yaml / 插件 / 人设占位
   ├─ s6 service: iibot             java -jar /opt/iibot/iibot-app.jar --config /opt/iibot/data/bot.json
   └─ CMD: /opt/iibot/cmd.sh        等 /healthz → exec hermes gateway run
```

三个端口全在容器内 `127.0.0.1`，和裸机拓扑一致：`8765`（业务工具 MCP）、`46500`（Inbox HTTP）、`46501`（Inbox MCP）。

## 为什么 iiBot 走 s6、gateway 走 CMD

宿主镜像自己写死了这条分工（`docker/s6-rc.d/main-hermes/run` 的注释：CMD 是主程序，sidecar 才是 s6 服务）。
照它的来：iiBot 崩了 s6 拉起；gateway 退出即容器退出，交给 compose 的 `restart` 策略。

## 版本钉在哪

`versions.env` 三个变量，`verify.sh` 会校验它们与 `Dockerfile`、`compose.yaml` 一致：

| 变量 | 含义 |
|---|---|
| `HERMES_BASE` | 宿主基础镜像（自带 s6、seed 钩子、`HERMES_HOME=/opt/data`） |
| `IIBOT_VERSION` + `IIBOT_SHA256` | iiBot 的 release jar 及其摘要（构建时 `sha256sum -c`） |
| `PLUGINS_REF` | 插件仓的 tag；构建期拉 tarball，**镜像里不留第二份插件源码** |

镜像 tag 建议写成 `hermes-iibot:<IIBOT_VERSION>-plugins-<PLUGINS_REF>`，任何一条版本线变了就是新镜像。

注意：插件仓的 **git tag**（`v0.2.6`）与**插件自身版本**（`inbox-wake 0.2.4`）是两套编号，不是笔误。

## 用

```bash
cp env.example .env && $EDITOR .env       # 填三个 key
./verify.sh                               # 静态自检，不需要 docker daemon
docker compose build
docker compose up -d
docker compose logs -f hermes-iibot
```

首次启动会发生：seed `$HERMES_HOME/config.yaml` 与两个插件 → s6 起 iiBot → 内嵌 Inbox 签发 token 到
`/opt/iibot/data/inbox/` → `/healthz` 200 → 起 gateway。第一轮对话里 `/inbox status` 应显示 `sse: open`。

进容器和 agent 对话（容器里没有桌面，TUI 用 exec 拿）：

```bash
docker exec -it hermes-iibot hermes chat
```

自检：

```bash
docker exec hermes-iibot hermes plugins doctor inbox-wake
docker exec hermes-iibot python3 /opt/hermes-reference/check-vision.py
curl -s -H "Authorization: Bearer $(cat …)" 127.0.0.1:46500/v1/inboxes/iibot-bot/status
```

## 两个 MCP 的 Bearer 从哪来

| 端口 | 用途 | 凭据 | 会变吗 |
|---|---|---|---|
| 8765 | 业务工具面（`qq_*`） | 环境变量 `IIBOT_CONTROL_TOKEN`（`bot/config` 的 `control.bearerTokenEnv`） | 不会，运维侧常量 |
| 46501 | Inbox MCP（`inbox_*`） | `<data>/inbox/program-mcp.token`，iiBot 每次启动重签 | **每次重启都变** |

所以 `.env` 里只需要给 `IIBOT_CONTROL_TOKEN`；`cmd.sh` 在起 gateway 前会：

```sh
export MCP_IIBOT_API_KEY="$IIBOT_CONTROL_TOKEN"
export MCP_INBOX_MOSIRE_API_KEY="$(cat /opt/iibot/data/inbox/program-mcp.token)"
```

裸机上的等价取值：

```bash
grep '^IIBOT_CONTROL_TOKEN=' ~/.config/iibot/control.env | cut -d= -f2-   # 8765
cat bot2/data/inbox/program-mcp.token                                     # 46501（每次重启后要重取）
```

## 两个卷

| 卷 | 挂载点 | 装什么 |
|---|---|---|
| `iibot-data` | `/opt/iibot/data` | `bot.json`、`bot.db`、`inbox/`（含每次启动重签的 token 与 `binding-descriptor.json`）、`media/`、`logs/` |
| `hermes-state` | `/opt/data` | `config.yaml`、`plugins/`、`sessions/`、`plugin-data/`、`logs/`、`auth.json` |

**token 绝不进镜像**：iiBot 每次启动都重签 `dsh-wake.token` / `program-mcp.token`，
写进 `iibot-data` 卷；inbox-wake 0.2.4 起会读 `binding-descriptor.json` 自动跟随。

## 从裸机迁移

```bash
docker volume create iibot-data
docker run --rm -v iibot-data:/d -v /path/to/bot2/data:/src alpine sh -c 'cp -a /src/. /d/'
cp /path/to/bot2/config/bot/bot.json /tmp/bot.json   # 手工把 dataDir/logging 改成 /opt/iibot/data
docker run --rm -v iibot-data:/d -v /tmp/bot.json:/b.json alpine cp /b.json /d/bot.json
```

Hermes 侧状态**不迁移**（`/opt/data` 是独立 `HERMES_HOME`）：会话历史与绑定会重新长出来，
首轮 `autoBind` 或 `/inbox bind iibot-bot` 即可。宿主机那份 `~/.hermes` 保持不动。

容器与宿主机**不能同时跑**：三端口单占。

## 升级与回滚

```bash
# 改 versions.env 里的 IIBOT_VERSION / PLUGINS_REF → 构建 → 起
./verify.sh && docker compose build && docker compose up -d
```

卷不动，会话与绑定保留。回滚就是 `image:` 换回旧 tag 再 `up -d`。

## 已知坑

- 别给 gateway 加 `--idle-exit`：空闲退出会带走整个容器。
- `media/` 会持续增长（QQ 图片/视频缓存），自己定清理策略。
- NapCat 不在容器里：`bot.json` 的 `accounts.main.oneBot` 要指向容器能访问到的 NapCat。
- 基础镜像里没有 JRE，本 Dockerfile 装 `openjdk-21-jre-headless`；换基础镜像时记得保留。
- 宿主补丁（极简 system prompt）构建期 `patch`：上游若已内置 `system_prompt_override` 会自动跳过。
- **改了 `bot.json` 要重启容器**（iiBot 只在启动时读配置）。
