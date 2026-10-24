# 《七柱之下：败退之后》真实推演流程记录与开发问题复盘

> 状态：历史复盘，不是当前运行状态或当前报名流程正本。
> 记录时点：2026-10-24
> 用途：供 iiBotMosire 后续开发、MCP 工具设计和秘书 Agent 优化使用
> 本次环境：隔离模拟 runtime、9 名已注册玩家、9 个秘书 Session、真实 NapCat 私聊/群聊、真实 LLM
> 说明：本文只记录本次实际观察到的事实；未验证项单独标注。

---

## 一、结论摘要

这次推演证明了几条主要链路可用：

1. 新 jar 能在 `players=0` 的情况下启动，并能恢复已有玩家状态；
2. 公开群和私聊的临时会话发送路径可用；
3. 私人秘书 Session 可以注册、绑定、读取共享 Skill 和工作区；
4. 外部 MCP 可以驱动行动池、裁定包、批次、推文审核和私聊发布；
5. 5 秒外部 watchdog 能够捕获新工单和新报名推文。

但当前也存在明确的结构性问题：

1. **秘书 Session 的生命周期不可观测、不可单独重启。** 本次 player-3/6/7 的 `gmChat` 持续返回“秘书暂时无法回复”，但 `tf_gm_secretary_list` 仍显示 9 个 Session。
2. **秘书聊天上下文完全在内存中。** app 重启后，玩家已经发送的报名内容会丢失，除非已经落成推文/工单/notes。player-8 的案例表明：玩家实际只发送了招募公告模板，不是填写后的报名表；但另一个真实风险是聊天记录不可恢复。
3. **报名/角色状态没有 canonical 领域模型。** 报名只存在于私聊、notes、PRIVATE 推文和 GM 手动文件里，没有独立的 `Application`/`Character` 可查询对象。
4. **ACTION_DRAFT 的“草案/未确认”语义没有被行动包状态机识别。** 第一版行动正文自称“未确认、等待 /确认”，仍可被 promote/package/commit，中央 LLM 只能返回“无世界变化”，但批次仍标记 `COMMITTED`。
5. **旧世界事实会污染新角色。** `sim-player-private` 仍写着“报名表尚未提交”，与已下发的角色卡冲突，导致中央推演拒绝裁定行动。
6. **秘书行为依赖自由文本和 Skill，不是硬状态机。** Skill 能减少“秘书自行裁定”的倾向，但不能阻止秘书把报名扣在手里等待确认。
7. **MCP 写工具字段不一致。** `tf_open_turn` 需要 `reason`，`tf_ticket_promote_action` 又拒绝 `reason`；实际驱动时会产生无意义的 schema 重试。
8. **并行工作区写入存在竞态。** 同一玩家多个 `tf_gm_secretary_dispatch` 并行执行时，曾出现部分返回 OK 但文件缺失，需串行补齐。
9. **没有原生的 5 秒面板订阅或事件通知。** 当前只能通过外部 Python watchdog 轮询，且 PID 文件曾记录 setsid 父进程而非实际 worker。

---

## 二、已完成的实际流程

### 2.1 原典提取与设定落盘

从：

```text
/home/cna/Simos-18Lvt/backup-origin
```

提取并交叉检查：

- `nodes/n0000.md`：帕拉丁帝国、元首制、军团、社会阶级、宗教；
- `nodes/n0001.md`：西征、列蒂/特尔尼/卡普亚、周边小国；
- `nodes/n0005.md`：古比奥战役、内政国策、军事编制；
- `nodes/n0006.md`：战后火器/神庙/军法/北方矿区；
- `nodes/n0007.md`：古比奥“善意撤退”宣传、三国同盟失败；
- `situation/02-截至n0008军队编制总录.md`；
- `situation/03-截至n0008人口与政治经济总录.md`。

产出：

- `00-setting-audit.md`
- `01-world-baseline.md`
- `02-player-signup.md`
- `03-opening-post.md`
- `07-secretary-skill.md`
- `08-public-opening.md`

### 2.2 环境与玩家初始化

- 世界事实切换到帕拉丁开局，`worldVersion=3`；
- 9 名玩家注册并绑定 9 个秘书 Session；
- 公共群：`1104560326`；
- 私聊临时会话群：`1104560326`；
- 每个秘书工作区初始化：
  - `player/world-basics.md`
  - `player/registration-form-v2.md`
  - `player/start-here-v3.md`
  - `gm/registration-protocol-v3.md`
- 公共招募推文已发布；
- 公共开局推文已发布，QQ 消息 ID `338980722`。

### 2.3 报名/开局反馈

已形成并私发：

- player-1：角色卡 + 开局卡；卡普亚北隘税关；
- player-2：GM 暂定角色卡 + 开局卡；卡普亚北隘外营百夫长；
- player-4：GM 暂定角色卡 + 开局卡；卡普亚北区粮政与市场特别专员。

player-2/4 的报名推文已 `APPROVED`，没有公开；三张开局卡均 `PRIVATE / PUBLISHED / SENT`。

player-8 只发送了招募公告全文和设定问答，没有填写后的报名表；已补发澄清私信，QQ 消息 ID `888551781`。

### 2.4 第一回合与行动

- 第一回合已开启：`0b7ffb98-eabc-46a3-979f-38eccfef0188`，状态 `OPEN`；
- player-1 的旧 ACTION_DRAFT 已晋升为行动 `act-57c18ead…`；
- 裁定包 `pkg-c3c15847…` 已批准；
- 批次 `batch-51191cff…` 返回 `COMMITTED`，但推演正文是“无世界变化/待裁定”，因为行动原文自述“未确认、等待 /确认 draftId”，且旧世界事实与角色卡冲突；
- 新的干净 ACTION_DRAFT 已生成：`f2668024-ff99-493a-a975-88dd5aaf2049`，尚未重新晋级。

---

## 三、按时间顺序的关键操作

1. 生成模拟配置并启动零玩家 app；
2. 发布公共招募与私聊 onboarding；
3. 初始化 9 名玩家和秘书；
4. 建立共享 Skill `skills/palatinia-secretary.md`；
5. 通过外部 watchdog 检测面板/待审推文；
6. player-1 提交报名，审核并初始化角色卡；
7. player-1 提交 ACTION_DRAFT；
8. 尝试第一回合 package/commit；
9. 中央 LLM 因行动语义和旧世界事实拒绝执行；
10. 生成新的干净行动工单；
11. 改为直接给 player-1/2/4 发开局卡；
12. 通过 NapCat 历史核验 player-8，确认没有填好的报名表。

---

## 四、开发问题清单

### P0-1 秘书 Session 健康状态不可见、不可单独恢复

**现象：**

- `tf_gm_secretary_list` 始终显示 9 个 Session；
- player-3/6/7 的 `tf_gm_secretary_chat` 多次返回：
  - 首次报名补交流程：`秘书处理超时或达到安全上限，请稍后重试。`
  - 极简 ping：`秘书暂时无法回复，请稍后重试。`
- 同一玩家的 `tf_gm_secretary_read` 可以正常读取工作区文件；
- 说明 `SecretarySession` 本身存在，但模型/工具执行链路不可用。

**影响：**

- GM 无法判断是 LLM 超时、上下文溢出、工具循环、Session 暂停还是未知异常；
- 无法只重启该秘书，重启整个 app 会丢失全部内存对话上下文。

**建议：**

新增稳定的秘书状态/恢复工具：

```text
tf_gm_secretary_status
tf_gm_secretary_reset
tf_gm_secretary_resume
```

状态至少包括：

```text
READY / BUSY / PAUSED / ERROR / CONTEXT_LOST
lastErrorCode
lastToolCallAt
contextCharacters / contextMessages
pendingApplication
workspaceReady
skillVersion
```

`tf_gm_secretary_reset` 应支持单 playerId 重建 Session，并保留工作区/notes/已提交推文。

---

### P0-2 秘书上下文不持久化，重启后丢失报名内容

**现象：**

- app 曾在运行过程中被重启；
- 重启后秘书工作区文件仍在，但聊天上下文和未提交的报名整理稿不在；
- player-8 的实际消息历史只能通过 NapCat API 回查，app 内秘书无法访问更早的私聊历史。

**影响：**

- 玩家已经发送、但秘书尚未落成推文/工单的报名会丢失；
- 秘书重启后无法继续原来的报名整理；
- GM 只能依赖外部聊天历史或让玩家重发。

**建议：**

- 为每个玩家持久化秘书会话的关键状态（至少：当前报名整理稿、待补项、确认状态、最近一次用户消息摘要）；
- 或在 app 重启时明确返回 `CONTEXT_LOST`，禁止秘书假装“没有报名数据”；
- 增加从 NapCat 历史按玩家恢复的安全接口（可选、需要隐私边界）；
- 报名/行动草稿应在第一轮整理后立即落成领域事件，而不是只留在上下文中。

---

### P0-3 缺少 canonical 报名/角色模型

**现象：**

- 报名存在于私聊、notes、PRIVATE 推文和 GM 文件；
- 没有 `tf_character_get`、`tf_application_get` 或 `playerProfile` 领域对象；
- player-2/4 的很多必填字段缺失，GM 只能手工写角色卡补全；
- 旧世界事实 `sim-player-private` 仍停在“报名未提交”，与已下发的角色卡矛盾。

**建议：**

建立领域模型：

```text
RegistrationApplication
- applicationId
- playerId
- formVersion
- rawSource
- normalizedFields
- missingFields
- status: DRAFT / SUBMITTED / UNDER_REVIEW / RATIONALIZED / ACTIVE
- submittedAt / reviewedAt / reviewedBy

CharacterSheet
- characterId
- playerId
- name
- office
- jurisdiction
- legalStatus
- resources
- constraints
- publicGoals
- privateGoals
- secretRef
- startLocation
- startSituation
- status: PROVISIONAL / ACTIVE / RETIRED
```

新增 MCP：

```text
tf_character_get
tf_character_list
tf_character_approve
tf_character_update
```

GM 审核后自动生成/更新玩家专属世界事实，避免旧事实污染。

---

### P0-4 ACTION_DRAFT 语义与状态机不匹配

**现象：**

- 第一版 action body 明确写着：
  - “秘书整理的行动草案”
  - “未作任何结果裁定”
  - “等待玩家 /确认 <draftId>”
- 系统仍允许将它 promote 到 ActionPool，再建包、裁决和 commit；
- 中央 LLM 正确地拒绝把它当已发生行动；
- 但批次最终仍返回 `COMMITTED`，只是正文说“无世界变化”。

**影响：**

- 行动包状态机将“提议”误当成“已确认行动”；
- 批次 `COMMITTED` 表示执行成功，但实际没有任何世界变化；
- 发布编排如果直接消费该批次，会产生一条“没有发生任何事”的公开/私密推文。

**建议：**

1. ACTION_DRAFT 增加明确状态：
   ```text
   PROPOSED / AWAITING_PLAYER_CONFIRMATION / CONFIRMED / PROMOTED
   ```
2. `tf_ticket_promote_action` 只允许 `CONFIRMED` 状态的 ACTION_DRAFT；
3. 增加 GM 接受的独立语义：
   - 玩家硬确认，或
   - GM 明确标记“测试/直接接受”，并记录 actor、时间、原因；
4. 对 action body 做结构化字段化，至少区分：
   - 目标
   - 行动步骤
   - 资源
   - 时限
   - 玩家要求/请求
   - 元话语（“这是草案”“等待确认”）
5. 当前系统可新增：
   ```text
   tf_ticket_confirm_action
   tf_action_accept
   ```
   用于 GM/玩家明确确认，不要依赖自由文本推断。

---

### P0-5 中央裁决批次允许“空结果 COMMITTED”

**现象：**

- `batch-51191cff…` 的 `status=COMMITTED`；
- 批次 result 明确说“不产生任何世界状态变更”；
- 发布编排要求 batch `COMMITTED` 才能生成草稿，因此会把一个 no-op 批次当作可发布结果。

**建议：**

- 增加批次结果状态：`COMMITTED / NO_OP / REJECTED / NEEDS_CLARIFICATION`；
- 如果推演只返回“待裁定/无变化”，不应返回 COMMITTED；
- 发布编排拒绝 `NO_OP`，或要求 GM 显式 `forceFeedback`；
- 在批次摘要中突出 `worldChanged=false`。

---

### P1-1 秘书报名规则曾被旧流程拖住

**现象：**

- 秘书早期被要求“缺项追问、确认码、二次确认”；
- 玩家认为“填表就完成了报名”；
- 秘书继续要求确认/补齐，导致报名被扣在手里；
- 后来改为 v3：填表即报名，缺失项标【待补】，先提交后追问。

**建议：**

- 把“报名完成”定义写成宿主硬状态，而不是 Skill 文本；
- 在报名工具中提供 `submit_even_if_incomplete=true` 或 `missingFields` 字段；
- 禁止报名创建前因为缺项而阻塞；
- 统一表单版本，删除旧的 `registration-form.md`，只保留 `registration-form-v2.md` 或一个版本化路径；
- 在公共公告和秘书说明里统一显示“填写/提供信息即视为提交，无需确认码”。

---

### P1-2 共享 Skill 不是自动强制执行的

**现象：**

- `skills/palatinia-secretary.md` 已创建并被 9 个秘书读取；
- 之后仍有秘书按旧 notes 或旧协议执行；
- 修改 Skill 后，需要重新给每个秘书发 GM 消息要求重读；
- app 重启后，Session 上下文丢失，Skill 只留在共享目录，不会自动覆盖到所有已打开会话。

**建议：**

- 给 Skill 增加版本号和强制刷新接口：
  ```text
  tf_shared_skill_publish
  tf_gm_secretary_reload_skill
  ```
- Session 启动时记录已读 Skill hash；
- 旧版本自动标记 `STALE_SKILL`；
- 关键硬规则不能只靠 Skill，必须落到领域工具/状态机。

---

### P1-3 工作区并行写竞态

**现象：**

- 同一玩家多个 `tf_gm_secretary_dispatch` 并发执行时，返回 OK，但部分文件实际缺失；
- 顺序补齐后正常；
- 出现过 `start-here-v2.md` 在部分工作区缺失，而其他工作区存在。

**建议：**

- 对同一 playerId 的工作区写入加单写锁；
- `writeGmMaterial` 返回前做存在性校验；
- 批量下发提供单事务接口；
- 或禁止同一玩家并行 dispatch，并在文档/工具层明确串行。

---

### P1-4 监控与 watchdog 可用性

**现象：**

- 宿主没有内置的 5 秒面板订阅；
- 本次使用外部 Python watchdog；
- `setsid nohup python3 ... &` 写入的 `watch.pid` 是父进程 PID，实际 worker 是另一个 PID，导致 `kill`/状态检查曾误判；
- watchdog 只在 pending posts 变化时读取正文，帖子被批准/移除时没有对应的“移除事件”；
- 当前数据库/领域层没有事件推送，轮询要反复启动 Java MCP client，资源开销较大。

**建议：**

1. 宿主内置事件订阅/面板 watch；
2. 或者提供长连接 MCP client 和 `tf_event_poll` 游标；
3. 增加 `tf_board_watch` 服务端游标，而不是每次 5 秒启动 JVM；
4. watchdog 记录 pending 条目消失/状态转换；
5. PID 由 Python 自身写入，不依赖 shell `$!`；
6. 提供 supervisor/restart 机制。

---

### P1-5 私聊反馈工具缺失

**现象：**

- 给单个玩家发一条简单反馈，需要：
  1. 找该玩家秘书；
  2. 让秘书创建 PRIVATE post；
  3. GM approve；
  4. GM publish。
- 没有直接的 `tf_gm_private_message` / `tf_gm_notify_player`。

**影响：**

- 开局卡、报名状态、角色卡更新等反馈流程非常重；
- 秘书必须作为“作者”被借用；
- 不适合测试和客服场景。

**建议：**

新增安全的 GM 私聊通知工具：

```text
tf_gm_notify_player
- playerId
- title
- body
- idempotencyKey
- reason
```

应有权限、长度、幂等、送达状态和审计；不暴露 QQ，不需要经过秘书 Agent。

---

### P1-6 MCP 写工具字段不一致

**现象：**

- `tf_open_turn` 缺少 `reason` 时校验失败；
- `tf_ticket_promote_action` 带 `reason` 时 shell 报：
  ```text
  property 'reason' is not defined in the schema and the schema does not allow additional properties
  ```
- 不同写工具对 `reason`、`expectedRevision`、`idempotencyKey` 的要求不统一。

**建议：**

- 统一所有写工具的公共 envelope：
  ```text
  expectedRevision
  idempotencyKey
  reason
  ```
- 统一 schema 生成器，避免同类工具字段不一致；
- 错误回执返回具体字段名和工具名，不要只返回通用 validation failed。

---

### P1-7 世界事实更新与角色状态脱节

**现象：**

- 已下发 player-1 角色卡，但世界事实仍写着“报名表尚未提交、角色卡待审核”；
- 中央裁决 Agent 因此拒绝执行行动；
- 需要手工 load 新 fact 覆盖旧 fact，但没有角色状态同步工具；
- 旧 fact ID 仍持续存在，版本更新容易遗漏。

**建议：**

- 角色审核通过时自动更新相关 world fact；
- 每个玩家一个可查询的 private character fact；
- 改变角色状态时 worldVersion 自增；
- 提供 `tf_character_sync_world` 或在 `tf_character_approve` 内自动同步。

---

### P2-1 旧文件/旧流程遗留

- `registration-form.md`、`registration-form-v2.md`、`start-here.md`、`start-here-v2.md`、`start-here-v3.md` 同时存在；
- 秘书多次引用旧版本；
- 工作区中旧笔记记录旧确认码流程；
- 玩家不知道应该打开哪一个版本。

**建议：**

- 工作区只保留一个 current 文件，旧版本归档到 `archive/`；
- 或在文件头强制显示 `CURRENT` / `DEPRECATED`；
- 玩家看到的 `start-here` 固定为最新版本；
- 增加版本号并在公共公告同步。

---

### P2-2 报名信息缺失没有统一展示

- player-2/4 的报名有大量缺失项；
- 秘书列出待补项后，玩家仍不知道“是否已完成报名”；
- GM 需要手工判断哪些可以合理化，哪些必须追问。

**建议：**

- 报名结果页固定显示：
  ```text
  status: SUBMITTED / PROVISIONAL
  blockingMissingFields: ...
  nonBlockingMissingFields: ...
  assumptions: ...
  canStart: yes/no
  ```
- `secret`、目标、个人立场、红线等个人创作信息不可由 GM 代填；
- 军团编号、驻地、职位等可以由 GM 合理化，但必须标注 `PROVISIONAL`。

---

## 五、当前真实状态

### 5.1 环境

- app：运行中，PID `691590`
- MCP：`127.0.0.1:8765`
- 玩家：9
- 秘书 Session：9（但 player-3/6/7 实际不可用）
- 世界版本：3
- 当前回合：第一回合 `OPEN`，turnId `0b7ffb98-eabc-46a3-979f-38eccfef0188`
- 已停止 5 秒外部 watchdog
- 待审推文：0

### 5.2 面板

- 工单总数：4
  - `f2668024…`：player-1 第一回合最终行动，`OPEN`
  - `347b2ef1…`：player-1 旧行动，`CLAIMED`，关联已提交行动 `act-57c18ead…`
  - `c4e8d704…`：player-8 设定查询，`OPEN`
  - `1935c81e…`：player-1 功能自检，`RESOLVED`
- Action Pool：1 条，`act-57c18ead…` 状态 `COMMITTED`
- Package：1 个，`pkg-c3c15847…` 状态 `COMMITTED`
- Batch：1 个，`batch-51191cff…` 状态 `COMMITTED`，但正文无世界变化

### 5.3 玩家

| playerId | 报名/角色 | 反馈 |
|---|---|---|
| player-1 | 康斯坦丁，角色卡已下发 | 开局卡已私发；旧行动批次 no-op；新行动工单待处理 |
| player-2 | 法兰，部分报名，暂定卡已下发 | 报名已 APPROVED，开局卡已私发 |
| player-4 | 阿莉娜，部分报名，暂定卡已下发 | 报名已 APPROVED，开局卡已私发 |
| player-3 | 报名情况未知 | 秘书会话不可用 |
| player-5 | 无报名数据 | 无 |
| player-6 | 报名情况未知 | 秘书会话不可用 |
| player-7 | 报名情况未知 | 秘书会话不可用 |
| player-8 | 仅发招募公告模板和设定问答，无填写报名表 | 已私发报名核验说明，消息 ID `888551781` |
| player-9 | 无报名数据 | 无 |

---

## 六、优先开发建议

### P0

1. 单秘书 Session 状态与重置接口。
2. 秘书对话/报名草稿持久化，明确 `CONTEXT_LOST`。
3. `RegistrationApplication` + `CharacterSheet` canonical 领域模型。
4. ACTION_DRAFT 确认状态机，禁止未确认草案直接 promote/commit。
5. 批次 `NO_OP` 状态，避免空结果 `COMMITTED`。
6. 角色审核自动同步 world fact，删除/覆盖旧状态。

### P1

1. 工作区并发写锁和写入后校验。
2. 内置 5 秒事件订阅/游标，替代外部轮询。
3. 统一 MCP 写工具 envelope。
4. `tf_gm_notify_player` 直接私聊反馈接口。
5. 共享 Skill 版本化与强制刷新。
6. 报名工作区只保留 current form/protocol，旧文件归档。

### P2

1. 报名缺失项分级（blocking/non-blocking）。
2. 开局卡模板标准化（地点、身份、局势、资源、可做事项、待补项）。
3. 角色状态、公开身份和 private facts 的统一展示。
4. 为多玩家/多回合准备回放与恢复测试。

---

## 七、建议补的回归测试

1. 9 个秘书中任意一个 Session 卡死时，`tf_gm_secretary_status` 能返回 `ERROR/PAUSED`；
2. 单秘书 reset 后，工作区文件仍存在，聊天上下文明确标记丢失；
3. player 只提供部分报名信息时，可直接创建 `SUBMITTED + PROVISIONAL` 报名，不阻塞；
4. 同一玩家并行 dispatch 3 个文件，全部存在且内容 hash 正确；
5. ACTION_DRAFT 未确认时，promote/package 必须失败或进入 `AWAITING_CONFIRMATION`；
6. 行动正文中带“未确认/等待确认”等元话语时，不应被当成已执行行动；
7. 中央 LLM 返回“无变化”时，批次应为 `NO_OP`，发布编排拒绝生成推文；
8. 角色卡更新后，中央 adjudicator 读取到的是新角色事实，不是旧 `sim-player-private`；
9. 公共群/私聊消息的 `SENT/FAILED/UNKNOWN` 与 `tf_post_retry_delivery` 行为一致；
10. 外部 watchdog 检测 pending post 出现、变化、批准、撤回/消失四类状态。

---

## 八、证据文件

- 设定与流程文档：
  - `simulation/campaign/palatinia/`
- NapCat 面板/发送日志：
  - `simulation/logs/app.log`
- 外部 watchdog 记录（已停止）：
  - `simulation/runtime/board-watch/events.jsonl`
  - `simulation/runtime/board-watch/status.json`
- 秘书工作区：
  - `simulation/runtime/secretaries/player-*/`
- 角色卡与开局任务：
  - `simulation/campaign/palatinia/characters/player-1/`
  - `simulation/campaign/palatinia/characters/player-2/`
  - `simulation/campaign/palatinia/characters/player-4/`

---

## 九、未验证边界

- player-3/6/7 是否有报名内容无法从当前 app 侧确认；
- 中央裁决未在修正后的新行动工单 `f2668024…` 上重新跑通；
- 第一回合尚未形成可发布的行动批次和正式回合记录；
- 外部 watchdog 未重新启动；
- 未进行多回合、多玩家同时行动、长上下文恢复测试。
