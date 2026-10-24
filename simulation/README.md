# iiBotMosire P8 全真模拟目录

这个目录用于 **P8 真实链路联调**：真实 NapCat/QQ、真实 LLM、真实 DSH MCP 控制方。
它使用独立的 `simulation/runtime/`，不会覆盖 `/home/cna/.config/iibot/runtime/`。

> **真实发送边界**：只有 GM 调用 `tf_post_publish` 后，`PostDistributor` 才会发送 QQ。
> 草稿生成、包 commit、记录投影都不会自动外发。第一次真实发送前先确认目标群/玩家。

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
- NapCat 已运行：`127.0.0.1:3001` WS、`127.0.0.1:3000` HTTP；
- `/home/cna/.config/iibot/{llm,napcat,control}.env` 已有真实 LLM/NapCat/MCP token；
- 真实 QQ 测试玩家已加入公开群，且允许 Bot 私聊；
- 当前 `8765` 没有被旧 app 占用。
- 若目标玩家与 Bot 不是好友，可设置 `napcat.privateTempGroupId` 为双方共同群；
  NapCat 会通过 `send_private_msg {user_id, group_id}` 走群临时会话。

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
simulation/scripts/06-mcp.sh --list           # 应显示 67 个工具
simulation/scripts/06-mcp.sh tf_status '{}'
simulation/scripts/06-mcp.sh tf_record_query '{"kind":"opening"}'
simulation/scripts/06-mcp.sh tf_ticket_board '{}'
```

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

`simulation/dsh/GM_SYSTEM_PROMPT.md` 是给 DSH 的控制方提示词。把文件内容作为任务/系统提示
传入；不要用 QQ 聊天内容提升身份。

### DSH 与「推演」的当前分工

- DSH：外部 GM/控制方，负责初始化、驱动回合、看工单、组包、审核和发布；
- app 内 `agentRoutes.adjudicator`：批次行动的实际中央 LLM 推演；
- DSH 可以看到 `tf_turn_get` / `tf_package_get` / `tf_publication_generate` 的结果，但当前
  不能直接替换 app 内 `LlmActionBatchExecutor` 的模型。P8 先按现有链路跑通；
  若要让 DSH 直接产出推演正文，需要另改 adjudicator 路由或增加外部 proposal 提交口。

## 5. 一轮真实循环

零玩家开场阶段先走：

```text
零玩家启动（players=[]，directors>=1）
  → DSH: tf_control_initialize / set_public_group / register_director / load_world
  → DSH: tf_publication_opening  （只建 PUBLIC PENDING_APPROVAL 草稿）
  → DSH: tf_post_list / tf_post_get / tf_post_decide
  → DSH: tf_post_publish         （这一步才发公共群）
  → DSH: tf_control_register_player / tf_control_bind_secretary
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
