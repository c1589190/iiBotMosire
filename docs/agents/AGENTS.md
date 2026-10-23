# docs/agents —— 项目维护手册目录

本目录只放**项目维护**：文档系统、Skill、工作流、验证、环境、提交与协作。
代码/架构/数据/契约/公式等**项目设计**在 `../superpowers/`。

## 当前入口

- 实时状态：[`STATUS.md`](STATUS.md)
- 维护裁定：[`MAINTENANCE_RULINGS.md`](MAINTENANCE_RULINGS.md)
- 工作流：[`WORKFLOW.md`](WORKFLOW.md)
- 验证：[`VERIFICATION.md`](VERIFICATION.md)
- 环境：[`ENVIRONMENT.md`](ENVIRONMENT.md)
- Skill 目录：[`SKILLS.md`](SKILLS.md)

## 局部规则

- 本目录不写模块边界、事件载荷、提示词语义、回合状态机等设计裁定。
- 历史长文进入本目录时必须在文件头标历史状态与取代者；不得把旧命令写成当前事实。
- 每次收工/交接优先更新 `STATUS.md`，并按真实证据写“未验证项”。
