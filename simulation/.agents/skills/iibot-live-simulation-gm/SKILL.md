---
name: iibot-live-simulation-gm
description: 运行 iiBotMosire 真实 QQ/MCP 推演时的 GM 操作、回合发布与故障排查流程
status: active
owner: repo-maintainers
last_reviewed: 2026-10-24
next_review: 2026-11-24
---

# iiBot 真实推演 GM 操作

> 面向维护/操作 Agent 的工程 Skill；不是产品内运行时 Skill。
> 真实任务证据：[真实推演复盘](../../../campaign/palatinia/09-simulation-retrospective.md)。

## 何时使用

- 需要通过真实 NapCat、真实 LLM、MCP control plane 跑多人推演；
- 需要处理报名、秘书工作区、行动草案、裁定包、批次和推文发布；
- 需要在故障后判断是 app、MCP、秘书 Session、NapCat 还是发布链路的问题；
- 需要把测试环境从“能跑”推进到“有可审计反馈”。

## 何时不使用

- 只改产品代码、不跑真实 MCP 的普通开发任务；
- 只想看静态设定、不建立玩家/回合状态；
- 用户没有授权自动外发或自动处理工单；
- 相邻工作区的设定引用未经用户确认；不得默认扫描或写入相邻目录。

## 前置条件

- 工作目录：仓库根。
- app 使用隔离的模拟配置，不覆盖真实 `~/.config/iibot/runtime`。
- 已确认 `IIBOT_CONTROL_TOKEN`、`IIBOT_NAPCAT_ACCESS_TOKEN`、`IIBOT_LLM_API_KEY` 只存在于宿主环境。
- 已确认公开群、私聊临时会话群和玩家身份映射。
- 先执行环境检查，再启动 app；不要在旧 app 占用 `8765` 时启动新实例。

## 步骤

### 1. 启动与健康检查

1. 运行 `simulation/scripts/00-check.sh`。
2. 运行 `simulation/scripts/01-prepare.sh`，但只在用户确认会刷新身份和配置时执行。
3. 运行 `simulation/scripts/02-start.sh`。
4. 用 `tf_status`、`tf_control_initialize`、`tf_gm_secretary_list` 核验：
   - app 是否恢复；
   - 玩家数和秘书数；
   - 公开群是否恢复；
   - 当前是否已有活动回合。
5. 检查日志只记录状态和错误类型，不写 token、QQ 凭据或正文。

### 2. 秘书健康与报名

1. `tf_gm_secretary_list` 只能证明 Session 注册在案，不能证明模型可响应。
2. 用极简 `tf_gm_secretary_chat` 做健康检查；若返回“秘书暂时无法回复”，标记 Session 异常，不要当成玩家没提交。
3. 当前没有单 Session 重置工具时：
   - 不要擅自重启整个 app；
   - 重启会丢失所有内存对话上下文；
   - 先让用户决定是否重启或由开发补单 Session reset。
4. 报名规则：
   - 玩家填写报名表，或已在私聊提供报名内容，即视为完成报名；
   - 不要求 QQ、活跃时段、六位确认码或二次确认；
   - 缺失项标 `【待补】/【待核对】`，先提交再追问；
   - 秘书直接创建 `PRIVATE` 报名推文，不公开、不批准、不声称录取。
5. 报名推文可由自动处理只做 `APPROVE`，不要把它作为私聊回显发布；玩家反馈用独立 `PRIVATE` 状态说明。
6. 角色审核后初始化工作区：
   - `player/world-basics.md`
   - `player/character-sheet.md`
   - `player/opening-orders.md`
   - `player/contacts.md`
   - `gm/registration-review.md`
   - `gm/secret-notes.md`

### 3. 行动与回合

1. 读取所有 `ACTION_DRAFT` 完整正文；识别同一玩家的修订版，旧版标为被替代，不重复入包。
2. 行动正文若仍含“草案/未确认/等待 /确认”等元话语：
   - 不要直接把它当已执行行动；
   - 测试模式下若用户明确授权，可先加载 DIRECTOR_ONLY 事实，声明“GM 已接受为提交行动”；
   - 更稳妥的做法是重新生成一份干净的最终 ACTION_DRAFT。
3. 使用正确顺序：
   - `tf_ticket_promote_action`
   - `tf_action_pool_list/get`
   - `tf_package_create`
   - `tf_package_append/replace_item/remove_item`（仅在 PROPOSED）
   - `tf_package_decide` 或 `tf_package_decide_item`
   - `tf_package_commit`
4. 每次 commit 后检查：
   - `batchStatus` 是否 `COMMITTED`；
   - result 是否是**玩家可读的推演结果**，不是模型思考过程或“无世界变化”；
   - 如果只是 no-op/meta，不要进入发布；先修正行动语义或世界事实。
5. 行动批次与 legacy Taskflow turn 状态不是同一套：
   - package/batch 可以独立提交；
   - `tf_status` 的 turn 仍可能保持 `OPEN`；
   - 不要声称“回合已自动结算”，除非相应状态机确实完成。

### 4. 发布与反馈

1. 批次生成后调用 `tf_publication_generate`。
2. 检查生成的公共与私密正文：
   - 公共部分不得泄露私密事实；
   - 私密正文必须只发给对应玩家；
   - 不得把模型推理链、内部 prompt 或工具日志直接发布。
3. 玩家反馈优先使用私聊；群发只在用户明确要求时进行。
4. 发送状态按 `PUBLISHED` + `deliveryState` 处理：
   - `SENT` 才算送达；
   - `FAILED` 时先查原因，不自动重发；
   - `ONEBOT_RETCODE_200` 常见于群禁言/目标不可用；
   - 使用 `tf_post_retry_delivery` 前确认目标已恢复，且同一消息不要反复撞。
5. 报名/状态/开局卡属于“反馈类推文”，不需要 COMMITTED 行动批次；但不得在其中宣称未裁定的行动结果。

### 5. 监控与自动处理边界

- 5 秒 watchdog 只负责采样和告警；不自动生成日推、不自动编造行动结果。
- 不要用前台 `sleep 180` 作为常规监控；用独立后台进程并轮询状态文件。
- 自动发布必须得到用户明确授权；即使授权，也只处理已存在的推文，不应循环生成新内容。
- `ACTION_DRAFT` 自动回执可以发给玩家“已收到，等待裁定”，但不得自动关闭、晋级或伪造结果。
- 需要自动回复 `SETTING_QUERY`/`COMPLAINT` 时，先用 GM aide 生成安全回复，回复后记录到工单。
- 外部 watchdog 必须：
  - 5 秒间隔；
  - 捕获新工单/待审推文；
  - 处理 `PENDING` 推文与过期 `FAILED`；
  - 记录 PID、心跳、lastError；
  - 不把“已看到”误报成“已处理”；
  - 不依赖 shell `$!`，由 Python 自身写 PID 文件。

### 6. 世界事实与测试模式

- 角色审核、开局卡或行动接受后，立即更新相关 world fact。
- 不要让旧的 `sim-player-private` 继续声称“报名未提交”或“角色待审核”；这会让中央裁决拒绝行动。
- 测试模式可在 DIRECTOR_ONLY fact 中明确：
  - 本批次行动已由 GM 接受；
  - 不要求玩家硬确认；
  - 仍按世界事实裁定，不保证成功。
- 世界事实只补必要角色权限和测试上下文，不写密钥、QQ 凭据或内部 prompt。

## 已知问题与解决方案

| 现象 | 根因/边界 | 解决方案 |
|---|---|---|
| `players 不能为空数组` | 配置加载器把空 players 当非法 | 新 jar 已修复；旧版本需要至少一个占位身份或更新配置加载器 |
| `TASKFLOW_SNAPSHOT_EVENT_CORRUPT` | 事件正文 key 含 `secret` 被整对象替换 | 更新脱敏器；快照必须结构无损，不能做字段名级对象替换 |
| 私聊发送 `ONEBOT_RETCODE_200` | 非好友/群禁言/目标不可用 | 使用私聊临时群配置；先确认群/好友状态；失败时用 retry 工具且不要循环重试 |
| 群发 `ONEBOT_RETCODE_200` | 群禁言或 NapCat 侧限制 | 停止重试，确认群禁言已解除；私聊反馈可先走；不要连续轰炸 |
| 秘书返回“暂时无法回复” | Session 存在但模型/工具链路不可用 | 用极简 ping 复核；没有单 Session reset 时请求用户/开发决定重启 |
| 重启后秘书说“没有报名” | 对话上下文只在内存 | 不要宣布玩家没报名；查工作区/推文/外部历史；把 `CONTEXT_LOST` 当显式状态 |
| 报名被秘书扣住 | 旧确认码/补齐后才提交 | 填表即报名；先建 PRIVATE 报名推文，缺失项后补；禁止要求二次确认 |
| ACTION_DRAFT 内容含“未确认”仍被 commit | 行动状态机没有区分 proposal/confirmed | 禁止未确认草案 promote；测试模式下重新生成干净最终行动并加载 DIRECTOR_ONLY 事实 |
| 中央批次返回“无世界变化” | 行动语义未确认或旧事实冲突 | 不发布；修正事实/行动后重新建包；或明确记录 `NO_OP` |
| `tf_open_turn` 要求 reason，而 `tf_ticket_promote_action` 拒绝 reason | MCP 写工具字段不一致 | 按工具 schema 调用；开发侧统一 envelope |
| 并行 dispatch 后文件缺失 | 同 player 工作区并发写竞态 | 同一玩家串行写；写后做存在性校验 |
| 自动循环连发日推 | 把“监控”误当成“生成内容” | 停止 autopilot；watchdog 只告警；行动反馈按回合一次性生成 |

## Gate

在仓库根执行：

```bash
python3 tools/skills-lint.py
python3 tools/check-docs.py
tools/gate.sh docs
```

必须看到：

- `SKILLS LINT PASSED`；
- docs check 通过；
- 本 Skill 已在 `docs/agents/SKILLS.md` active 表中登记。

## Report

任务收尾必须报告：

- 当前 app/MCP/群/玩家/秘书状态；
- 本轮实际读写的文件；
- 已发送消息的 `postId`、消息 ID、`deliveryState`；
- 未发送、FAILED、UNKNOWN、待审、未验证项；
- 是否触碰相邻工作区；若触碰读取，说明范围和授权；
- autopilot/watchdog 是否停止。

## 失败与停止条件

出现以下任一情况立即停止外发：

- 无法确认目标群/玩家；
- 发送状态 `FAILED/UNKNOWN` 且原因不明；
- 秘书健康检查失败且无法区分上下文丢失；
- 行动草案未确认却被自动送入批次；
- 批次结果是推理链/元话语；
- 世界事实与角色卡互相矛盾；
- 用户未明确授权自动发送或自动循环。

## 维护

- Owner：`repo-maintainers`
- 最近复核：2026-10-24
- 下次复核：2026-11-24
- 退役条件：宿主内建了可靠的会话状态/重置、事件订阅、角色 canonical 模型、直接玩家通知和自动回合编排后，本流程可降级或退役。
- 修改本 Skill 后必须重新运行 `tools/skills-lint.py` 与 `tools/gate.sh docs`。
