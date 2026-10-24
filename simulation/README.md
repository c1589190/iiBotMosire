# iiBotMosire P8 全真模拟目录

这个目录用于 **P8 真实链路联调**：真实 LLBot/QQ、真实 LLM、真实 DSH MCP 控制方。
它使用独立的 `simulation/runtime/`，不会覆盖 `/home/cna/.config/iibot/runtime/`。

> **真实发送边界**：`tf_post_publish`、经真人确认的
> `tf_post_compose(mode=PUBLISH_NOW)`、D-017 自主入团报名卡，以及显式启用的
> `tf_qq_send_private/tf_qq_send_group` 会触发真实 QQ 外发。
> DRAFT、生成器、包 commit、记录投影都不会自动外发。第一次真实发送前先确认目标群/玩家。

## 目录

```text
simulation/
  config/
    iibot.sim.example.json        # 手工配置模板
    iibot.sim.local.json          # 01-prepare 生成，含本机非密钥配置，已 gitignore
    world-facts.sim.json          # 雾灯港模拟世界
  init/
    identities.local.json         # 从现有真实配置抽取的群/玩家/推演，已 gitignore
  dist/                           # 模拟用 shaded jar + sha256，已 gitignore
  scripts/
    00-check.sh                   # Java/Jar/env/端口检查
    00-build.sh                   # 构建并复制新版 jar 到 simulation/dist
    01-prepare.sh                 # 生成独立 config + identities + runtime
    02-start.sh                   # 后台启动 app，等待 MCP
    03-stop.sh                    # 停止 app
    04-wait-mcp.sh                # 只等待 MCP
    05-init-control.sh            # 完整 MCP 初始化：群/身份/秘书/世界/回合
    05a-init-control-plane.sh     # 只初始化控制面（不注册玩家、不开回合）
    05b-register-players.sh       # 注册玩家并绑定秘书（不自动开回合）
    05c-open-turn.sh              # 当前无活动回合时开回合
    06-mcp.sh                     # 本地 MCP smoke 客户端
    07-opening.sh                 # 创建零玩家 PUBLIC 开场草稿（不审核/不发送）
  dsh/
    cordis.patch.yml              # DSH headless MCP client overlay
    GM_SYSTEM_PROMPT.md           # DSH GM 控制方提示词
  runtime/                        # SQLite/日志/records/秘书区，已 gitignore
  logs/                           # app 日志/pid，已 gitignore
```

## 0. 前提

- JDK 21、仓库 Maven Wrapper、模拟目录内已构建的 `simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar`；
- LLBot 已运行：`127.0.0.1:3001` WS、`127.0.0.1:3000` HTTP；LLBot 兼容差异见 `simulation/dsh/LLBOT_MIGRATION.md`；
- `/home/cna/.config/iibot/{llm,napcat,control}.env` 已有真实 LLM/QQ/MCP token；
- 真实 QQ 测试玩家已加入公开群，且允许 Bot 私聊；
- 当前 `8765` 没有被旧 app 占用。
- 若目标玩家与 Bot 不是好友，可设置 `napcat.privateTempGroupId` 为双方共同群；
  LLBot/OneBot 通过 `send_private_msg {user_id, group_id}` 走群临时会话。
- `outbox.minSendIntervalMillis` 默认 150ms，生产范围 100..200ms；所有逻辑外发消息
  （含 ACK 与正式回复）共用同一个节奏锁，避免连续消息挤在一起。

检查：

```bash
cd /home/cna/iiBotMosire
simulation/scripts/00-check.sh
```

## 0.1 构建/更新模拟 jar

当前已生成：

```text
simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar
simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar.sha256
```

需要重建时：

```bash
simulation/scripts/00-build.sh
```

`02-start.sh` 优先使用 `simulation/dist/` 的 jar；不存在时才回退到
`iibot-app/target/`。

## 1. 生成独立模拟配置

```bash
simulation/scripts/01-prepare.sh
```

它会：

- 从 `/home/cna/.config/iibot/iibot.local.json` 复制 **非密钥** 配置；
- 把 `dataDir` 改为 `simulation/runtime`；
- 清空 `players`，让玩家通过 MCP 动态注册；
- 保留一个 bootstrap director（配置要求至少一个推演身份）；
- 生成 `simulation/init/identities.local.json` 保存真实公开群、玩家 QQ、推演 QQ；
- 不把任何 API key/token 写入仓库。

## 2. 启动 app + MCP

```bash
simulation/scripts/02-start.sh
```

成功输出类似：

```text
APP_STARTED pid=...
MCP: http://127.0.0.1:8765/mcp/control
```

停止：

```bash
simulation/scripts/03-stop.sh
```

日志：

```bash
tail -f simulation/logs/app.log
```

## 3. 动态初始化 MCP

`01-prepare.sh` 生成的是 **零玩家** 配置：`players=[]`，但保留至少一个 bootstrap
director。启动后有两种初始化方式。

### 3.1 完整初始化（含玩家与开回合）

```bash
simulation/scripts/05-init-control.sh
```

它会按顺序调用：

1. `tf_control_initialize`
2. `tf_control_set_public_group`
3. `tf_control_register_director`
4. `tf_control_register_player`
5. `tf_control_bind_secretary`
6. `tf_control_load_world`（新增一条公开模拟事实）
7. 若当前没有活动回合，`tf_open_turn`

脚本幂等键固定，可重复执行；重复执行不会重复注册/开回合。它仍可用于完整初始化。

### 3.2 零玩家 → 公共开场 → 注册玩家 → 开回合

交互式 DSH 推荐按以下顺序；每一步都可单独执行：

```bash
# 1) 只初始化控制面、设置公开群、注册 director、装载世界；不注册玩家、不开回合
simulation/scripts/05a-init-control-plane.sh

# 2) 创建 PENDING_APPROVAL 的 PUBLIC 开场草稿；不自动审核/不发送
simulation/scripts/07-opening.sh "雾灯港·序章" "潮水退去后……" "opening-001"

# 3) 人工/DSH 审核并发布；只有 tf_post_publish 会真正发公共群
#    tf_post_list → tf_post_get → tf_post_decide(APPROVE)
#    → tf_post_publish；第一次真实发送前先确认目标群。

# 4) 再注册玩家、绑定秘书
simulation/scripts/05b-register-players.sh

# 5) 最后开回合
simulation/scripts/05c-open-turn.sh
```

`07-opening.sh` 只调用一次 `tf_publication_opening`，参数通过 0600 临时 JSON
文件传递，脚本不打印正文。重复使用同一幂等键不会重复建草稿/事件。

完成后可查看：

```bash
simulation/scripts/06-mcp.sh --list           # 默认模拟装配 77 个；以实际 tools/list 为准
simulation/scripts/06-mcp.sh tf_status '{}'
simulation/scripts/06-mcp.sh tf_record_query '{"kind":"opening"}'
simulation/scripts/06-mcp.sh tf_ticket_board '{}'
```

### 3.3 平台级 QQ 能力（D-017）

默认工具：

```text
tf_qq_chat_list          # 默认 configured=当前推演群；可展开 recent/groups/friends/all
tf_ticket_board          # wait=true 时长轮询到第一条工单/板面变更或超时
tf_qq_history_read       # 省略目标默认当前群；可查任意 Bot 可访问私聊/群
tf_qq_group_members      # 群成员
tf_qq_message_get        # 按消息 ID 取消息；可带 target 回扫 live cache 未命中的旧消息，用于核验入团来源
tf_post_compose          # 外部控制方自由撰写推文；DRAFT 或 PUBLISH_NOW；守卫拒绝时明确报错
tf_player_onboard_from_group  # 来源消息校验后自主注册、绑定秘书、发报名卡
```

显式开启 `control.platform.allowArbitrarySend=true` 后额外出现：

```text
tf_qq_send_private
tf_qq_send_group
```

这些工具默认关闭且只暴露给 supervisor。自主入团默认只允许当前推演群；
`control.platform.onboardingAllowedGroupIds` 可显式扩展，`["*"]` 表示全部
Bot 可访问群。读取始终受 QQ/LLBot 权限约束，不能读取他人私聊或未加入的群。

## 4. 接 DSH Agent

DSH 的 `@deepseek-ai/dsh-mcp-client` 使用 streamable-http。给 headless profile 加一个
patch overlay：

```bash
export IIBOT_CONTROL_TOKEN="$(sed -n 's/^IIBOT_CONTROL_TOKEN=//p' \
  /home/cna/.config/iibot/control.env)"
# DSH 自身的模型凭据与 iiBot app 的 IIBOT_LLM_API_KEY 是两套；按你的 DSH 配置准备。
# 例如 headless 的 deepseek 路由需要 DEEPSEEK_API_KEY 或 DSH credentials service。
```

先确认 app 和 MCP 已启动；然后：

```bash
dsh --profile headless \
  --patch /home/cna/iiBotMosire/simulation/dsh/cordis.patch.yml \
  "$(cat /home/cna/iiBotMosire/simulation/dsh/GM_SYSTEM_PROMPT.md)"
```

如果 DSH 报找不到 `@deepseek-ai/dsh-mcp-client`，在对应 profile 安装
（本机 DSH 实际已能解析该 MCP client）：

```bash
dsh plugin --profile headless add @deepseek-ai/dsh-mcp-client@0.0.1-rc.1
```

验证 patch 合成（不会启动 app）：

```bash
dsh --profile headless \
  --patch /home/cna/iiBotMosire/simulation/dsh/cordis.patch.yml \
  --dump-config | grep -A12 mcp-iibot-control
```

DSH 里工具名会带命名空间，例如：

```text
mcp__iibot__tf_control_initialize
mcp__iibot__tf_status
mcp__iibot__tf_open_turn
mcp__iibot__tf_ticket_board
mcp__iibot__tf_package_create
mcp__iibot__tf_publication_opening
mcp__iibot__tf_publication_generate
mcp__iibot__tf_post_publish
```

真正接入步骤（插件安装、token、patch、headless/web、验证）见：
[`simulation/dsh/DSH_CONNECT.md`](dsh/DSH_CONNECT.md)。

`simulation/dsh/GM_SYSTEM_PROMPT.md` 是给 DSH 的控制方提示词。把文件内容作为任务/系统提示
传入；不要用 QQ 聊天内容提升身份。

### DSH 与「推演」的当前分工

- DSH：外部 GM/控制方，负责初始化、驱动回合、看工单、组包、审核和发布；
- DSH 还可以用 `tf_post_compose` 自行撰写普通公告/私聊，不依赖批次或 app 内 LLM；
- app 内 `agentRoutes.adjudicator`：批次行动的实际中央 LLM 推演；
- 批次行动正文仍由 app 内 adjudicator 生成；但控制方可以直接发独立推文，
  不再需要先和 app 内 Agent 对账。

## 5. 一轮真实循环

零玩家开场阶段先走：

```text
零玩家启动（players=[]，directors>=1）
  → DSH: tf_control_initialize / set_public_group / register_director / load_world
  → DSH: tf_publication_opening  （只建 PUBLIC PENDING_APPROVAL 草稿）
  → DSH: tf_post_list / tf_post_get / tf_post_decide
  → DSH: tf_post_publish         （这一步才发公共群）
  → DSH: tf_control_register_player / tf_control_bind_secretary
  → DSH: tf_qq_chat_list / tf_qq_history_read / tf_qq_message_get
  → DSH: tf_player_onboard_from_group（公屏玩家报名时）
  → DSH: tf_open_turn
  → DSH: tf_record_query {"kind":"opening"} 查看开场投影摘要/hash
```

之后进入有玩家的一轮：

```text
玩家 QQ 私聊秘书
  → 秘书提出行动草稿 / ACTION_DRAFT ticket
  → DSH: tf_ticket_board / tf_ticket_get
  → DSH: tf_ticket_promote_action
  → DSH: tf_action_pool_list / tf_action_pool_get
  → DSH: tf_package_create / append / replace / remove
  → DSH: tf_package_decide / decide_item
  → DSH: tf_package_commit
  → DSH: tf_publication_generate
  → DSH: tf_post_list / tf_post_get / tf_post_decide
  → DSH: tf_post_publish   ← 这里才真实发 QQ
  → DSH: tf_record_turn_list / tf_record_turn_get / tf_record_query
```

第一次先只发一条明确的公共/私密测试推文，确认群和玩家收件正确后再扩大。

## 5.1 玩家体验文档与自动化边界

玩家可见内容必须遵守：

```text
simulation/player-experience/
```

- `01-player-visible-style.md`：玩家可见文风；
- `02-secretary-playbook.md`：秘书行为；
- `03-confirmation-and-recovery.md`：一次硬确认与断线恢复；
- `06-editorial-checklist.md`：发布前审稿；
- `07-automation-boundaries.md`：自动监控只读。

Agent 没有待处理工单时，用：

```bash
simulation/scripts/06-mcp.sh tf_ticket_board '{"wait":true,"waitSeconds":300}'
```

长轮询会在已有待处理工单、下一次成功工单变更/新工单到达或超时时返回；
`waitTimedOut=true` 后可以重新调用，不表示自动处理授权。

`simulation/scripts/board-autopilot.py` 默认已降级为**只读 watchdog**：

- 不生成日推；
- 不推进 campaign clock；
- 不自动回复工单；
- 不批准/发布自己生成的内容；
- 只有人工显式加 `--publish-existing` 时，才允许处理已经存在的待审帖。

## 5.2 全新初始化提示词

需要清空 runtime 后重开时，把：

```text
simulation/dsh/FRESH_INIT_GM_PROMPT.md
```

整份内容作为 DSH GM Agent 的首条提示词。它要求：

- 保留旧 runtime 备份；
- 用新 jar 启动；
- 零玩家初始化 + 加载秘书共享 Skill；
- 只创建公共开场草稿；
- 停在“等待真人确认发布”状态，不自动发 QQ。

## 6. 直接 MCP smoke

```bash
# 查看工具
simulation/scripts/06-mcp.sh --list

# 只读状态
simulation/scripts/06-mcp.sh tf_status '{}'

# 查看当前推演群
simulation/scripts/06-mcp.sh tf_qq_chat_list '{"scope":"configured"}'

# 读取任意 Bot 可访问群历史（示例）
simulation/scripts/06-mcp.sh tf_qq_history_read '{"chatType":"group","targetId":"123456","count":20}'

# 外部控制方直接写草稿（不发送）
simulation/scripts/06-mcp.sh tf_post_compose '{"mode":"DRAFT","visibility":"PUBLIC","title":"公告","body":"正文","idempotencyKey":"announce-001"}'

# 创建公共开场草稿（推荐用 07-opening.sh 封装参数文件与权限）
simulation/scripts/07-opening.sh "雾灯港·序章" "潮水退去后……" "opening-001"

# 示例：用 JSON 文件传复杂参数
cat > /tmp/ticket-args.json <<'JSON'
{"status":"OPEN","type":"ACTION_DRAFT"}
JSON
simulation/scripts/06-mcp.sh tf_ticket_list @/tmp/ticket-args.json
```

`06-mcp.sh` 只读取 `IIBOT_CONTROL_TOKEN`，不把 token 写入任何文件。

## 7. 常见问题

- `control port 8765 already in use`：先 `simulation/scripts/03-stop.sh`，或检查旧进程；
- app 启动失败：看 `simulation/logs/app.log`，不要复用旧 runtime；
- 真实 QQ 发送失败：检查 NapCat `3000/3001` 和 `napcat.env`；
- DSH 工具未出现：先 `--dump-config` 确认 patch 合成，再查 MCP client 插件/连接日志；
- DSH 启动时提示 `MISSING_CREDENTIAL`：这是 DSH 自己的模型凭据缺失，不是 iiBot 的 LLM key；
- 若 DSH 输出 SOCKS proxy 警告：给 DSH 设置 `http://`/`https://` 代理变量，或清掉 SOCKS `all_proxy`；
- 所有 MCP 工具都走 `ToolCallAuthorizer`，不要绕过工具直接改 runtime 文件。
