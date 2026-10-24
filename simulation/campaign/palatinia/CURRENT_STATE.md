# 当前状态总结

> 更新时间：2026-10-25（01:45 Asia/Shanghai）
> 当前方向：《七柱之下：败退之后》个人本
> 本文件只记录“现在是什么状态、哪些文档已成为正本、哪些事情尚未执行”，不替代设定裁定。

## 一、一句话结论

当前 Palatinia 个人本已完成全量重新初始化。此前“雾灯港”的根因是旧测试世界事实与 Palatinia 事实同时留在运行态，秘书读到了错误的一组；不是 Palatinia 设定错误，也不是模型单独幻觉。

本轮已停止旧 app、备份旧 runtime、用只含 Palatinia 6 条事实的初始文件重新 bootstrap，重新注册玩家和秘书、写入共享 Skill、重建工作区规则与当前处境，并发送新的特尔尼河港开局。玩家已经开始游玩，第一项行动为“必须聆听”。

## 二、已经确认的事实

- 帕拉丁战后的世界设定可以使用；主舞台仍是通行历 1445 年 11 月的西境新行省。
- 玩家已提供角色五项：康斯坦丁、男、20 岁、商人、来西境做生意的；其他背景未指定。
- 旧 9 人 runtime 备份为 `simulation/runtime.backup-20261024-214715`；中间初始化快照备份为 `simulation/runtime.partial-20261024-214911`。
- 第二次全量重开前的 runtime 备份为 `simulation/runtime.backup-20261024-224944-pre-reinit`。
- 原运行态的 `worldFacts=11`，其中 4 条来自旧 `simulation/config/world-facts.sim.json` 的雾灯港测试世界；Palatinia 6 条只是追加，没有替换旧事实。
- 新 runtime 从零 bootstrap：初始 worldVersion 1 只有 6 条 Palatinia 事实；注册玩家后追加 2 条玩家可见事实。
- 玩家说“必须聆听”后，曾出现秘书把可选留白误当成待补并追问第一项行动的问题。已修正世界事实、AGENTS、current-situation 和共享 Skill：五项报名完成后的家庭/秘密/目标/关系等是可选留白，短句行动直接按行动处理。
- 当前 worldVersion 5、共 10 条事实；`palatinia-player-1-registration` 与 `palatinia-player-1-opening` 记录报名与开局，`palatinia-player-1-loan-grain-outcome-1` 记录第一轮裁定，`palatinia-player-1-assets-1` 记录资产账本。
- 共享 Skill `skills/palatinia-secretary.md` 已重新写入；秘书工作区 AGENTS、世界常识、当前处境和个人规则均已重建。
- 秘书 cache：`Palatinia 售样购粮草案后` 0 条且 active；此前 `Palatinia 资产明确后` 17 条、`Palatinia 第一轮结算后` 19 条、`Palatinia 未知信息修正` 20 条、`Palatinia 第一幕·聆听修正` 24 条与 `默认对话` 12 条均保留但不活跃。
- 新的私聊开局已发出，私聊历史可见；系统 `deliveryState` 因 NapCat HTTP timeout 为 `UNKNOWN`，但正文已出现在私聊历史中，未自动重试。
- 内部秘书健康检查和假设玩家“去市场看看”的试答均成功，回答只使用特尔尼河港与当前角色资料，没有雾灯港内容。

## 三、旧文档的具体问题

1. **报名表过长且最低要求不清。**
   旧表最多 32 项，玩家不知道哪些必须填；当前个人本只要求姓名、性别、年龄、职位和其他介绍五项。

2. **报名流程存在互相冲突的版本。**
   v1 要求精确回复六位确认码；v2 取消确认码但仍要求 QQ 后四位；v3 才改成“提供信息即报名”。根目录同时保留三套，秘书容易引用错。

3. **旧通知要求玩家阅读文件。**
   玩家只有 QQ 私聊，没有工作区阅读界面；要求玩家打开 `player/*.md` 会直接制造挫败。

4. **旧 README 状态过期。**
   它仍写着 9 名玩家、9 个秘书和已开本，而实际 runtime 与当前个人本方向并不一致。

5. **玩家可见内容混有后台信息。**
   旧通知出现“GM刚刚没有做……”、版本号、路径和流程说明；这些不是世界内内容。

6. **旧开场与招募稿承担了不同职责。**
   一份是公开招募文案，一份是已发布开局；缺少一套个人本专用的报名、角色校准与开局材料，同时给玩家保留角色人生和第一项行动的定义空间。

## 四、现有正本文档

| 文件 | 当前用途 | 读者 |
| --- | --- | --- |
| `CURRENT_STATE.md` | 当前事实、遗留项与执行边界 | 真人 GM / 开发维护 |
| `00-setting-audit.md` | 原典来源、冲突与裁定 | 真人 GM |
| `01-world-baseline.md` | 帕拉丁战后设定集正本 | GM；经秘书转述给玩家 |
| `02-player-signup.md` | 个人本五项报名信息与自由介绍口径 | 秘书内部使用；玩家只看到聊天提问 |
| `03-opening-post.md` | 世界内公共开场稿 | 真人 GM 审核后使用 |
| `04-onboarding-workflow.md` | 报名、角色暂定、开局卡与工作区初始化流程 | 秘书 / GM |
| `05-secretary-start-here.md` | 秘书开本检查单与首条回复 | 秘书 / GM |
| `06-player-notice.md` | 可直接私聊或作为公告基础的世界内开本说明 | 玩家可见草稿 |
| `07-secretary-skill.md` | 共享 Skill 正本 | 秘书 Agent |
| `08-personal-opening.md` | 个人本开局包、弹性压力池与开局卡 | 真人 GM |
| `09-simulation-retrospective.md` | 上一轮真实联调历史复盘 | 开发维护；不是当前状态 |
| `10-secretary-AGENTS.md` | 秘书工作区 `AGENTS.md` 规则模板 | 秘书 Agent |
| `11-player-world-primer.md` | 供秘书转述的最短世界常识 | 秘书 Agent |
| `12-secretary-bootstrap.md` | 兼容旧 `AGENTS.md` 的当前报名规则覆盖材料 | 秘书 / GM |
| `world-facts.palatinia.json` | 可装载的公开事实与 GM 私密事实建议包 | GM / MCP 初始化 |
| `archive/` | 旧版报名、通知与协议 | 只作历史证据 |

## 五、当前个人本设计口径

- **玩家规模**：一名玩家、一名私人秘书；其他人物、机构和冲突由 GM 与中央推演控制。
- **玩家身份**：由玩家自拟的帕拉丁帝国角色；地方官、文书、审计员、军官、幕僚、神庙联络人、商队管事等都只是灵感，不设固定模板。
- **开场地点**：不预先锁定；根据玩家职位与介绍，从列蒂、特尔尼、卡普亚通道、古比奥边境、首都或驿路中选择最自然的一处。玩家也可以提出自己的开局地点。
- **世界压力**：只固定战后第一个冬天的宏观局势与若干可能压力，不预先规定玩家必然卷入哪一场事件。具体开局麻烦由 GM 与玩家根据职位、介绍和兴趣共同确定。
- **报名最低要求**：姓名、性别、年龄、职位、其他介绍。职位不设固定模板；其他介绍为自由文本，玩家保留角色人生与关系的主要定义权。
- **确认规则**：报名不需要确认码或二次确认；只有正式行动草案终稿需要一次宿主生成的 `/确认 <短确认码>`。
- **信息规则**：玩家不阅读文件、不理解内部 ID、不填写 QQ、不选择活跃时段；秘书把必要内容直接转述。
- **发布规则**：公共开场可以写成世界内稿，但真实发送仍须真人 GM 明确确认；`tf_post_publish` 之前不得外发。

## 六、当前运行快照

> 真实 QQ 号、群号和临时 postId 只在与真人 GM 的聊天报告中出现，不写入仓库文档。

- iibot app 已用新 jar 重启，MCP 控制面可用；凭据代理和 DSH Connector 已重新接入。
- 控制面已设置用户指定的公开群；director 1 名。
- 当前注册玩家 1 名，已绑定独立秘书 Session。
- `tf_status`：worldVersion=5、playerCount=1、directorCount=1、第 1 回合 `OPEN`；engineRevision=20、eventCount=20。
- `tf_invariant_check`：PASS。
- `simulation/dist` jar 已替换为 sha256 `484bebc2d486353c9cf9367838a9b415b7f0c60d7e486dd90da2c087c913d3cb`；旧 jar 备份为 `simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar.backup-20261025-010828`。
- 替换前 runtime 备份为 `simulation/runtime.backup-20261025-010828-pre-llbot-compat`；app PID 8852，MCP 8765 正常；QQ 后端切到 LLOneBot 8.3.2（WS 3001 / HTTP 3000）。
- 实际 MCP 工具数：77（以 `tools/list` 为准）；`tf_qq_send_private/group` 因任意发送策略默认关闭而不在工具面。
- 初始事实文件 `simulation/config/world-facts.sim.json` 只含 Palatinia 6 条事实；配置的 `promptVersion` 为 `palatinia-personal-v1`。
- 当前运行态 8 条事实：
  - 6 条 Palatinia 世界事实；
  - 2 条仅玩家可见事实：康斯坦丁报名信息与特尔尼河港开局/当前聆听处境。
- 共享 Skill `skills/palatinia-secretary.md` 已写入；当前玩家工作区有：
  - `AGENTS.md`：Palatinia 当前版；
  - `player/world-basics.md`：玩家可见世界常识；
  - `player/current-situation.md`：康斯坦丁当前处境、留白边界和可选行动方向；
  - `gm/personal-bootstrap.md`：报名与开本规则覆盖。
- 秘书规则已追加“未知信息与连续意图”：
  - 不能确认的事实不阻塞，先明确说明，再转成行动中的前置调查步骤；
  - 一条消息里的连续意图合并成一份草案，不要求玩家拆成两条；
  - 未知数字写“待查明/待谈定”，不得编造利率、价格、库存或 NPC 态度。
- 秘书 cache：`Palatinia 售样购粮草案后` 为 active、0 条消息；此前 cache 全部保留但不活跃。
- 第一轮借款与扩购囤粮行动已由玩家确认，旧草案关闭，新草案已 promote 为行动并完成包裁定。
- `pkg-a343720d-2adc-433e-be2b-622b891c428a` 已 `COMMITTED`；批次 `batch-35d7e659-e09a-4c3b-9695-a91078279600` 已 `COMMITTED`。
- 私密结果已发送（deliveryState=SENT）；公共叙事草稿按个人本要求被拒绝，未群发。
- 新玩家可见事实 `palatinia-player-1-loan-grain-outcome-1` 已写入；资产账本 `palatinia-player-1-assets-1` 已写入：现钱 80 银币、货样约 20 银币、合计约 100 银币，无已确认债务/仓单/店铺/伙计/靠山。
- 秘书 Skill 已追加：资料缺数字时先 `request_brief`，仍无数据则主动提交 `SETTING_QUERY` 向推演询问，不得把问题退回玩家；并且要给出暂定口径或调查步骤。
- 开局与聆听续幕均已发送；第二条 `deliveryState=SENT`，私聊历史可见。
- 内部秘书健康检查通过；假设玩家说“我去市场看看”时，秘书只使用特尔尼河港、粮价/仓租、税吏、军需粮队和商贩等当前资料回答，没有出现旧世界内容。
- DSH MCP Connector 与只回环 token 注入代理已重新接入；Connector 健康检查 PASS，77 个工具可用。
- QQ 后端已切换到 LLOneBot 8.3.2；WS 事件与 HTTP 只读动作已实测，`get_recent_contact` 缺失由群目录兜底。
- 玩家 2 的开局卡私密帖已 PUBLISHED 但 deliveryState=FAILED；原因是 LLBot 不支持主动群临时会话，且该玩家尚未主动私聊 Bot。未自动重试，等待玩家先发起临时会话后由 GM 显式重试。
- 只读公屏 watcher 与工单板 watcher 已启动，均无 lastError。
- 已初始化报名记录：`notes/palatinia-player-1-registration.md`；GM 私有文档中保存康斯坦丁五项信息与留白边界。
- 已开启第 1 回合，状态 `OPEN`，回合号 1，计划关闭时间为 2026-10-24 17:08 UTC；当前无草案/决策提交。
- 已在 23:08 发送“报名记录与第一幕：特尔尼市场”私聊，按玩家此前回复的“1”从市场开局；私聊历史可见。
- 玩家“必须聆听”已按行动处理，并已补发市场争执的可感知细节。
- 玩家随后询问特尔尼粮市情况；秘书调用 `request_brief`，宿主生成并签发基于可见事实的 brief（`BRIEF_REQUESTED` / `BRIEF_ISSUED`），秘书据此回复。没有创建 ACTION_DRAFT，没有 package/batch，worldVersion 仍为 3，未产生新的世界裁定。
- 23:34 玩家提出“把货卖了，然后全部去买粮食，能买多少买多少”；秘书首轮读取共享 Skill、AGENTS、current-situation、brief 和旧工单，正好用满 8 轮，触发 `stoppedByPolicy` 的“刚才处理太久了”降级文案；没有执行任何世界行动。
- 已由 GM 接管整理 `ACTION_DRAFT`：`特尔尼河港售样购粮草案`，状态 OPEN，确认提示已发给玩家；尚未确认、尚未 promote。
- 已修改秘书 Skill/AGENTS：明确行动直接起草，不先 request_brief、不列目录、不读历史/已关闭工单；首轮资料只读一次，优先产出最终回答。

## 七、剩余步骤

1. 玩家选择下一项行动：确定借款额度并比较放贷人、查看仓房，或核对可谈粮源。
2. 秘书整理成干净的行动草案；只有草案终稿且玩家完成宿主确认后，才进入行动池。
3. GM 建包、裁定、commit，并生成玩家可见结果。
4. 需要公开传播时，先审核推文，再由 GM 显式发布；`UNKNOWN` 不自动重试。
5. `tf_post_compose` 守卫、旧消息回扫、出站 150ms 节奏和 D-020 128 轮预算已进入部署 jar；真实行动/资料缺口 smoke 待跑。

## 八、当前停止点

LLBot 兼容 jar 已就位；app、MCP Connector、凭据代理和两个只读 watcher 当前已停止，等待隔壁会话按 `simulation/dsh/LLBOT_HANDOFF.md` 启动。第一轮借款/粮源调查已结算，售样购粮草案已补发并等待玩家确认；当前没有执行中的新世界行动。
