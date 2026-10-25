# simulation Agent Skill 目录

> 更新时间：2026-10-25（Asia/Shanghai）
> 本目录只登记 active Skill；每个 Skill 一个文件夹，正本为 `SKILL.md`。

| Skill | 路径 | 用途 | 加载方 |
| --- | --- | --- | --- |
| `iibot-referee-behavior` | `../../.agents/skills/iibot-referee-behavior/SKILL.md` | 通用推演/GM 行为：Inbox、行动裁定、结果发布、故障处理 | DSH GM |
| `iibot-secretary-behavior` | `../../.agents/skills/iibot-secretary-behavior/SKILL.md` | 通用私人秘书行为：报名、草案、确认、结果查询 | iibot 共享 Skill |
| `iibot-live-simulation-gm` | `../../.agents/skills/iibot-live-simulation-gm/SKILL.md` | 真实 QQ/MCP 联调工程操作与故障排查 | 外部 GM/维护 Agent |

## 加载规则

- `iibot-secretary-behavior` 不直接作为文件路径加载给玩家；由 `../../scripts/09-load-secretary-skill.sh` 读取并写入 iibot 共享 Skill 区。
- `iibot-referee-behavior` 供 DSH GM 使用，不加载给玩家秘书。
- `iibot-live-simulation-gm` 是操作手册，不是世界内行为规则。
- 世界专有名词、角色、机构和资源不得写入这些通用 Skill。

## Skill frontmatter 契约

```text
name
description
status: active
owner
last_reviewed
next_review
```
