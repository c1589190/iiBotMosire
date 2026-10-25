# .agents —— 工程 Agent 资产目录

> 本目录属于项目维护，不是产品运行时数据。

## 目录职责

| 路径 | 放什么 | 是否可被宿主发现 |
|---|---|---|
| `skills/<name>/SKILL.md` | **active** 工程 Skill | 是 |
| `notes/proposed/` | 提议中的决策解释 | 否 |
| `notes/implemented/` | 已落地决策解释 | 否 |
| `notes/archived/` | 已取代/归档决策解释 | 否 |
| `notes/README.md` | 决策解释书写规则 | 否 |

- 工程 Skill 的状态机、frontmatter、体量和检查规则见 [`../docs/agents/SKILLS.md`](../docs/agents/SKILLS.md)。
- 模拟工作区的 active Agent Skill 位于 [`../simulation/.agents/skills/`](../simulation/.agents/skills/)，
  登记在 [`../simulation/docs/agents/SKILLS.md`](../simulation/docs/agents/SKILLS.md)；
  本项目级 `.agents/skills/` 当前不保存这些 Skill。
- `.work/skill-candidates/` 放候选，不进本目录；不把未验证流程放进 `skills/`。
- 决策解释记录“为什么这样定、被谁取代”，不替代 `docs/superpowers/specs/**` 或维护裁定。
- 本目录不放密钥、运行时数据、数据库、日志或本机绝对路径。
