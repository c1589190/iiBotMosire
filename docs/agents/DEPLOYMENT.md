# 部署手册（2026-11-06 / Alpha20261106）

> **本文件属于项目维护。** 规定怎么把这一版装到一台干净机器上、怎么核对、怎么回滚；
> 运行序列与契约本身在 `docs/superpowers/specs/**`，不在这里重复。

## 一、这一版是什么

一次**跨三仓**的改动：唤醒从「每条消息都叫醒」改成「服务端拼好一段文本、按档位推」，
媒体入站即落盘，配置由 schema 自动纠正。三个仓库必须**一起升级**，单独升一个会半残。

| 组件 | 版本 | 仓库 | 说明 |
|---|---|---|---|
| iiBot | `Alpha20261106` | `c1589190/iiBotMosire` | 全依赖内置的单个 jar，JDK 21 |
| Inbox 服务端 | `Alpha20261106`（内嵌仍为 `0.1.0-SNAPSHOT` 依赖） | `c1589190/InboxMosire` | 新增 delivery/ack 与 `inbox.push` |
| inbox-wake 插件 | `v0.3.0` | `c1589190/InboxMosire-Hermes` | 消费 push、回 ack、首条引导 |
| 宿主 | Hermes ≥ 2026-11-06 的本地构建 | `NousResearch/hermes-agent` | 需要 `inject_message(media_urls)` 与 `agent.tool_result_images`，见 §五 |

拓扑不变：

```
QQ ──NapCat──► iiBot(8765 MCP / 46500 API / 46501 MCP)
                 │  入站 → 消息板 → 渲染文本 → POST /items
                 ▼
              Inbox 服务端(46600 API / 46601 MCP)
                 │  SSE: inbox.push（重要 1s / 普通 30s）
                 ▼
              inbox-wake 插件 ──注入──► Hermes 回合
                 └── POST /deliveries/{id}/ack
```

## 二、装 Inbox 服务端

```bash
git clone git@github.com:c1589190/InboxMosire.git && cd InboxMosire
git checkout Alpha20261106
./mvnw -o -B -ntp -pl inbox-server -am package -DskipTests
# 产物：inbox-server/target/inbox-server-0.1.0-SNAPSHOT-all.jar（约 19 MB）
```

初始化与凭据（token 只打印一次，落 0600 文件，不进日志）：

```bash
java -jar inbox-server-*.jar init --data-dir /srv/inbox/data
java -jar inbox-server-*.jar token issue --label iibot \
     --inbox main --scope items:write --scope items:read \
     --scope items:manage --scope events:read --scope status:read
```

启动（推送档位与服务端标志，默认值即推荐值）：

```bash
java -jar inbox-server-*.jar serve --data-dir /srv/inbox/data \
  --api-port 46600 --mcp-port 46601 \
  --notify-threshold 200 \
  --important-delay-millis 1000 \
  --normal-delay-millis 30000 \
  --delivery-ttl-millis 600000 \
  --delivery-max-attempts 3 \
  --delivery-sweep-interval-millis 500
```

启动日志会自报：`Inbox 推送配置 threshold=200 importantDelay=1s normalDelay=30s ttl=600s maxAttempts=3 sweep=500ms`。

**从旧版升级**：首次启动自动迁移（`schema_migration.version = 2`，新增 `inbox_delivery` 表与
`inbox_event.event_type/delivery_id/data_json` 三列）。旧的 `inbox.changed` 事件**仍然发**，
插件侧可随时退回旧路径。迁移前先复制 `inbox.db`。

## 三、装 iiBot

```bash
# 从 release 页或 dist/ 取
java -jar iibot-app-Alpha20261106.jar --check-config --config config/bot/bot.json
```

`--check-config` 现在会**先跑配置医生**：把 schema 不认识的键搬进 `x-unknown`、把过时键改名，
写回前留 `bot.json.bak-<时间戳>`；类型/范围错误仍然照旧报错退出。只想看不想改就加 `--no-fix-config`。

最小配置（完整字段见 `config/README.md` 与 `config/schema/bot-config.schema.json`）：

```json
{
  "dataDir": "data",
  "accounts": {"main": {"accountId": "…", "oneBot": {"eventWebSocketUri": "ws://…", "httpBaseUri": "http://…"}}},
  "control": {"bearerTokenEnv": "IIBOT_CONTROL_TOKEN"},
  "inbox": {"inboxId": "iibot-bot", "apiUrl": "http://127.0.0.1:46600", "apiPort": 46500, "mcpPort": 46501},
  "messageBoard": {"weighting": {"defaultNotifyThreshold": 200, "normalDelayMillis": 30000, "importantDelayMillis": 1000}},
  "media": {"retentionDays": 7, "prewarm": true, "prewarmMaxBytes": 5242880}
}
```

启动：

```bash
java -jar iibot-app-Alpha20261106.jar --config config/bot/bot.json
```

媒体临时区落 `data/media/`，`iibot-media-sweeper` 每 6 小时按 `media.retentionDays` 清理；
入站即抓字节（图片全量、语音/文件 ≤ `prewarmMaxBytes`），直链过期不再是死路。

## 四、装宿主插件

```bash
cp -r InboxMosire-Hermes/plugins/inbox-wake ~/.hermes/plugins/
```

`~/.hermes/config.yaml` 关键项：

```yaml
plugins:
  enabled: [billion-context, inbox-wake]      # persona-mode 已按 D-009 取消
  entries:
    inbox-wake:
      settings:
        apiUrl: http://127.0.0.1:46600
        inboxId: iibot-bot
        adapterTokenFile: file:/…/dsh-wake.token
        wakeMode: view-ack
        pushMode: on          # 服务端能推 inbox.push 时，changed 只刷水位
        pushGraceMs: 2000
        firstRunGuide: true   # 首条注入先说明 AGENTS.md / PERSONA.md
mcp_servers:
  inbox-mosire: {url: "http://127.0.0.1:46601/mcp"}
  iibot:        {url: "http://127.0.0.1:8765/mcp/bot"}
```

`pushMode` 三档：`auto`（默认，收到过一次 push 后自动转 push 优先）/`on`（直接 push 优先）/
`off`（忽略 push、退回 changed 唤醒）。

## 五、宿主侧前提（否则看图仍然坏）

宿主必须是包含 2026-11-06 两个本地提交的构建：

1. `hermes_cli/plugins.py:588` 的 `inject_message(..., media_urls, media_types)` —— 注入能带图；
2. `hermes_cli/config_defaults.py` 的 `agent.tool_result_images`（`hoist|inline|off`，默认 `hoist`）
   + `agent/tool_dispatch_helpers.py` 的图片提升 —— 工具返回的图能进模型上下文。

判断标准只需一条：带图跑一次后

```sql
select id, tool_name, substr(content,1,120) from messages
 where content like '%_multimodal%' order by id desc limit 3;
```

有行 = 像素真进了请求；空 = 还停在「文本 + `MEDIA:` 路径」的老行为。

## 六、装完核对清单

| # | 检查 | 期望 |
|---|---|---|
| 1 | `--check-config` | `CONFIG_VALID`，stderr 里没有 `[config-doctor]` 报错 |
| 2 | Inbox 启动日志 | `threshold=200 importantDelay=1s normalDelay=30s` |
| 3 | 私聊发一条 | ≤2s 注入一次；`[HH:mm] #seq 昵称（备注）：正文` |
| 4 | 群里普通消息 | ≈30s 注入一次，且**只注入一次**（不再出现 `[INBOX_HOST_EVENT]` 整窗重投） |
| 5 | 首条注入 | 先出现"工作区说明"（AGENTS.md / PERSONA.md），且全程只出现一次 |
| 6 | `curl -X POST …/deliveries/{id}/ack` | `acked:true`；重复调用 `acked:false` |
| 7 | 带图消息 + `qq_message_get` | 返回 `images[]`；宿主库里出现 `_multimodal` |
| 8 | `data/media/` | 入站后即有文件；`saved` 字段与 `downloads/` 落点符合预期 |

## 七、回滚

| 层 | 动作 |
|---|---|
| 插件 | `pushMode: off`（一条配置，不用改代码） |
| iiBot | 换回 `iibot-app-Alpha20261103.2.jar`，同步改 `run/boot-env.sh` 与 `start-bot.sh` 里的文件名 |
| Inbox | 换回旧 jar 并恢复迁移前的 `inbox.db` 副本 |
| 数据 | `inbox_digest_state` 新列可留（旧版忽略）；`bot.db` 无需回滚 |

## 八、常见故障

| 现象 | 原因 | 处理 |
|---|---|---|
| 启动即退，报 `unknown field` | 配置里有 schema 外的键且用了 `--no-fix-config` | 去掉该开关让医生搬进 `x-unknown`，或手工删 |
| 注入两次 | 插件还是旧版，`pushMode` 不生效 | 升级到 `v0.3.0`，确认 `pushMode: on` |
| 30s 才唤醒 | 这是普通消息档位 | 想让某人/某群走快档：`qq_user_note_upsert` 给 priority ≥ 200，或 `qq_source_weight_set` 把 `sourceWeight` 抬到阈值以上 |
| 图打不开 `MEDIA_UNAVAILABLE` | 入站时没抓到（旧数据）或宿主没升级 | 新消息应已预热；历史消息只能重抓，宿主要求见 §五 |
| `rejected the last 3 calls … Paused` | 宿主 MCP 熔断器（3 次/60s），不是服务端限流 | 减少并发外发，串行调用 |
