# 《七柱之下：败退之后》campaign 记录

> 世界设定已迁移到：`../../docs/world/`。
> 当前运行状态：`../../docs/STATUS.md`。
> 本目录只保留报名、开本、秘书工作区模板、历史复盘与旧材料。

## 保留文档

| 文件 | 用途 |
| --- | --- |
| `04-onboarding-workflow.md` | 报名接收、角色校准、工作区材料、开局卡与开本顺序 |
| `05-secretary-start-here.md` | 秘书开本检查单与首次私聊方式 |
| `07-secretary-skill.md` | 历史兼容入口；正本已迁到 `.agents/skills/iibot-secretary-behavior/SKILL.md` |
| `09-simulation-retrospective.md` | 历史真实联调复盘；不是当前状态 |
| `10-secretary-AGENTS.md` | 秘书工作区 `AGENTS.md` 模板 |
| `12-secretary-bootstrap.md` | 旧工作区规则覆盖材料 |
| `CURRENT_STATE.md` | 历史快照；当前状态见 `../../docs/STATUS.md` |

## 相关正本

- 世界公开材料：`../../docs/world/public/`
- 世界秘密数据：`../../docs/world/secret/`
- 推演/GM 行为：`../../.agents/skills/iibot-referee-behavior/SKILL.md`
- 秘书行为：`../../.agents/skills/iibot-secretary-behavior/SKILL.md`
- 真实联调工程操作：`../../.agents/skills/iibot-live-simulation-gm/SKILL.md`
- 运行事实：`../../runtime/taskflow-state.json`、`../../runtime/events.db`

## 报名与开本口径

- 玩家只需要提供五项：姓名、性别、年龄、职位、其他介绍。
- 职位不设固定模板；其他介绍是自由文本，可写身世、性格、经历、目标、关系、秘密或开局重点。
- 提供五项即视为提供报名信息；确实缺失的内容标为“暂定/可后补”，不阻塞。
- 不要求 QQ、活跃时段、六位确认码、二次确认或阅读工作区文件。
- 报名推文只用于 GM 审核，不公开，也不要求玩家理解内部编号。
- 角色卡、开局卡和必要世界信息由秘书在私聊中直接转述。
- 正式行动只在草案终稿出现一次 `/确认 <短确认码>`；该确认码必须来自宿主工具。
- 真实出站只能由 `tf_post_publish` 触发，且发布前必须由真人 GM 明确确认目标与摘要。

## 文档规则

1. 当前事实只写在 `../../docs/STATUS.md`；本目录不再维护“当前状态”。
2. 世界设定、公开推文和秘密数据只写在 `../../docs/world/`。
3. Agent 行为规则只写在 `../../.agents/skills/`；本目录的 `07` 只是兼容入口。
4. 历史文档保留原文，但必须标注历史状态并指向当前入口。
5. 不把秘密事实复制到公开材料；不把世界专有名词写进通用 Skill。
