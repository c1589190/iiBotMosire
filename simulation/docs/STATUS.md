# simulation 当前状态

> 更新时间：2026-10-25 23:31（Asia/Shanghai）
> 本文只记录当前事实；设计与行为规则见 `.agents/skills/`，世界设定见 `world/`。

## 运行状态

- app：**已停止**
- Inbox adapter：**已解绑**
- MCP 回环代理：**已停止**
- `8765` / `8766`：已关闭
- LLBot：未由本工作区停止
- 后台 watcher/autopilot：无活动进程

## 最后观测到的运行快照

- `worldVersion=10`
- `engineRevision=27`
- `lastEventSeq=201`
- 玩家数：2
- 当前回合：turn 1，状态 `LOCKED`，revision `14`
- 当前行动：`act-7c1d662b-6adb-4749-b72c-1f74e97f86dd` 为 `QUEUED`
- 旧玩家行动票据 `066003e0-42eb-465b-beae-3355c818de9a` 仍为 `OPEN`，未硬确认
- 待审推文：最后一次核对为 0
- app 停止前没有进行中的 Inbox claim

## 当前权威位置

- 运行事实：`../runtime/taskflow-state.json`、`../runtime/events.db`
- 世界文档：`world/`
- 推演行为：`../../.agents/skills/iibot-referee-behavior/SKILL.md`
- 秘书行为：`../../.agents/skills/iibot-secretary-behavior/SKILL.md`
- 工程操作：`../../.agents/skills/iibot-live-simulation-gm/SKILL.md`
- 部署 jar：`../dist/iibot-app-0.1.0-SNAPSHOT.jar`

## 已知未完成项

- `act-7c1d662b-6adb-4749-b72c-1f74e97f86dd` 的中央裁定此前连续两次 `REJECTED`，未生成可发布结果。
- 旧票据 `066003e0-42eb-465b-beae-3355c818de9a` 未被确认，不应推进。
- 旧的项目级 Skill 副本 `.agents/skills/iibot-live-simulation-gm/SKILL.md`
  已于 2026-10-25 清理；现行工程操作 Skill 只保留在
  `../../.agents/skills/iibot-live-simulation-gm/SKILL.md`。

## 历史入口

- `campaign/palatinia/CURRENT_STATE.md`：旧快照，只作历史参考，不是当前状态。
- `campaign/palatinia/09-simulation-retrospective.md`：历史复盘。
- `../runtime.backup-*`：历史备份，不得作为当前事实。
