# docs/AGENTS.md —— simulation 文档目录规则

> 更新时间：2026-10-25（Asia/Shanghai）
> 上级入口：`../AGENTS.md`；项目根入口：`../../AGENTS.md`。

## 目录职责

| 路径 | 收什么 | 规则 |
| --- | --- | --- |
| `STATUS.md` | 当前运行事实摘要 | 唯一当前状态入口；每次开工/收工更新 |
| `agents/SKILLS.md` | active Agent Skill 目录 | 只登记 `.agents/skills/*/SKILL.md` |
| `world/` | 世界设定、公开材料、秘密数据 | 写作规则见 `world/AGENTS.md` |
| `archive/` | 历史快照与旧文档 | 文件头必须标历史日期与取代者 |

## 写作规则

1. 当前事实写 `STATUS.md`，历史快照写 `archive/` 或 `campaign/`，并在文件头标注“历史快照”。
2. 事实与推断分开：推断标“推测/待确认”，不写成已发生事实。
3. 结论必须有来源：路径、命令、rc、时间或工具回执；未运行标 `NOT_RUN`。
4. 引用用户原话必须逐字；日期使用用户实际日期。
5. 使用相对 Markdown 链接；禁止 `@` 长文导入。
6. 不写 token、API key、QQ 凭据、完整私聊正文、内部 prompt 或运行物理路径到玩家可见文档。
7. 世界专有名词只放 `world/`；通用 Skill 不得绑定具体世界。
8. 世界文档是设定源，不是运行事实；当前运行态以 `../runtime/taskflow-state.json` 与 `../runtime/events.db` 为准。

## 当前入口

- 当前状态：`STATUS.md`
- 世界设定：`world/README.md`
- 世界文档规则：`world/AGENTS.md`
- Skill 目录：`agents/SKILLS.md`
