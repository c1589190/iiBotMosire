# 《七柱之下：败退之后》

> 帕拉丁帝国在古比奥—帕拉丁战争后的国内政治—军事推演。
> 当前版本：个人本整理版（2026-10-24）。
> 当前状态：设定与开本文档已收口；运行态以 `CURRENT_STATE.md` 为准。

## 这是什么

玩家扮演帕拉丁帝国中的一名自拟角色。地方官、中央基层官员、军官、幕僚、神庙联络人、商队管事等都只是灵感，不构成固定模板。故事发生在帝国官方庆祝“善意撤退”之后：军费、欠饷、税册、难民、神庙质问和元老院调查同时压到西境。

这是一个低魔、强财政约束、重后勤与政治后果的世界。角色不是无所不能的英雄；权力来自职位、法权、预算、人手和信息。

个人本采用一名玩家、一名私人秘书的结构。其他人物与机构由 GM 和中央推演控制。

## 先看哪里

1. **接手或排查当前状态**：`CURRENT_STATE.md`
2. **了解世界与开局局势**：`01-world-baseline.md`
3. **准备个人本报名**：`02-player-signup.md`
4. **执行报名与开本流程**：`04-onboarding-workflow.md`
5. **给秘书下发规则**：`07-secretary-skill.md`、`10-secretary-AGENTS.md`
6. **准备第一场戏**：`03-opening-post.md`、`08-personal-opening.md`

## 秘书 Skill 系统

秘书规则的正本不是运行态自动生成的文件，而是本目录中的可版本化文件：

| 文件 | 角色 |
| --- | --- |
| `07-secretary-skill.md` | 共享 Skill 正本；玩家文风、主动查询、行动确认与隐私边界 |
| `10-secretary-AGENTS.md` | 秘书工作区 `AGENTS.md` 模板；D-016 详细规则正本之一 |
| `05-secretary-start-here.md` | 开本检查单与首轮读取顺序 |
| `12-secretary-bootstrap.md` | 旧工作区规则覆盖材料 |
| `../player-experience/02-secretary-playbook.md` | 玩家体验层的秘书行为补充 |

`simulation/runtime/shared-skills/`、`simulation/runtime/secretaries/` 与
`simulation/runtime/init/` 是运行态生成物或本局数据，不进入 Git；需要长期保留的规则
变更必须先回写到上述正本，再重新下发。
- D-020：玩家秘书与 GM 推演秘书默认 128 轮 / 5 分钟 / 单响应 128 工具；
  明确行动直接起草，资料缺口主动查 brief / 提交 `SETTING_QUERY`，不把问题退回玩家补资料。

## 当前正本

| 文件 | 用途 |
| --- | --- |
| `CURRENT_STATE.md` | 当前事实、文档收口结果、未执行事项与停止点 |
| `00-setting-audit.md` | 原典来源、冲突、裁定与 GM 使用边界 |
| `01-world-baseline.md` | 帕拉丁战后设定集正本；玩家常识与 GM 信息边界 |
| `02-player-signup.md` | 个人本报名表；姓名、性别、年龄、职位、其他介绍五项 |
| `03-opening-post.md` | 世界内公共开场稿；不是系统公告 |
| `04-onboarding-workflow.md` | 报名接收、角色校准、工作区材料、开局卡与开本顺序 |
| `05-secretary-start-here.md` | 秘书开本检查单与首次私聊方式 |
| `06-player-notice.md` | 可直接使用的玩家可见开本通知草稿 |
| `07-secretary-skill.md` | 共享 Skill 正本；玩家文风、报名、行动确认与隐私边界 |
| `08-personal-opening.md` | 个人本开局包：固定世界层、弹性压力池、开局卡模板与留白边界 |
| `09-simulation-retrospective.md` | 上一轮真实联调复盘；历史证据，不是当前运行状态 |
| `10-secretary-AGENTS.md` | 秘书工作区 `AGENTS.md` 模板；D-016 详细规则正本之一 |
| `11-player-world-primer.md` | 秘书转述给玩家的最短世界常识；不要求玩家阅读 |
| `12-secretary-bootstrap.md` | 在旧工作区 `AGENTS.md` 尚未重建时，下发给秘书的当前报名规则覆盖材料 |
| `world-facts.palatinia.json` | 建议装载的公开世界事实与 GM 私密事实；未自动执行 |
| `archive/` | 旧报名表、旧通知、旧协议；不得作为当前流程使用 |

## 报名与开本口径

- 玩家只需要提供五项：**姓名、性别、年龄、职位、其他介绍**。
- 职位不设固定模板；其他介绍是自由文本，可写身世、性格、经历、目标、关系、秘密或开局重点。
- 提供五项即视为提供报名信息；确实缺失的内容标为“暂定/可后补”，不阻塞。
- 不要求 QQ、活跃时段、六位确认码、二次确认或阅读工作区文件。
- 报名推文只用于 GM 审核，不公开，也不要求玩家理解内部编号。
- 角色卡、开局卡和必要世界信息由秘书在私聊中直接转述。
- 正式行动只在草案终稿出现一次 `/确认 <短确认码>`；该确认码必须来自宿主工具。
- 真实出站只能由 `tf_post_publish` 触发，且发布前必须由真人 GM 明确确认目标与摘要。

## 文档层级与冲突顺序

1. 用户当次明确指令。
2. 项目设计裁定（尤其 D-015 玩家体验、D-016 最小 system prompt）。
3. `simulation/player-experience/` 中的体验、文风、确认与发布规则。
4. 本目录的当前正本：`04`、`05`、`07`、`10` 与 `08`。
5. `00` 与 `01` 中的世界设定。
6. `09` 与 `archive/` 中的历史记录。

任何冲突不得静默用旧版本覆盖当前正本。需要改变规则时，先更新当前正本，再通知秘书刷新 Skill 与 `AGENTS.md`。

## 玩家可见内容的硬边界

- 不出现文件名、路径、工具名、内部编号、版本号、状态枚举或异常栈。
- 不出现 Markdown 标题、表格、代码块、反引号或加粗残留。
- 不出现“测试、联调、自动监控、系统说明、GM刚刚没有……”等后台口吻。
- 一条消息只讲一个焦点；先事实，再未知，最后给 2–3 个下一步。
- 不要求玩家阅读文件；玩家需要知道的内容由秘书直接说出来。
- 不泄露其他人物或玩家的私密事实；个人本中尤其不能把 GM 私有真相当成玩家已知信息。

## 资料依据

世界设定与历史考据来自：

```text
/home/cna/Simos-18Lvt/backup-origin/nodes/n0000.md
/home/cna/Simos-18Lvt/backup-origin/nodes/n0001.md
/home/cna/Simos-18Lvt/backup-origin/nodes/n0005.md
/home/cna/Simos-18Lvt/backup-origin/nodes/n0006.md
/home/cna/Simos-18Lvt/backup-origin/nodes/n0007.md
/home/cna/Simos-18Lvt/backup-origin/situation/02-截至n0008军队编制总录.md
/home/cna/Simos-18Lvt/backup-origin/situation/03-截至n0008人口与政治经济总录.md
```

原典冲突与本局补齐见 `00-setting-audit.md`。当前玩家与运行态事实见 `CURRENT_STATE.md`，不得从历史复盘反推当前状态。
