# D-020 秘书 Agent 回合预算实现快照

> **状态**：历史快照（2026-10-24）。**当前状态入口**：[`../../agents/STATUS.md`](../../agents/STATUS.md)。
> **对应需求**：D-020
> **需求原稿**：[`../specs/2026-10-24-user-requirements-source.md`](../specs/2026-10-24-user-requirements-source.md) §5.9

## 实现

- `AgentRunner.DEFAULT_MAX_ROUNDS = 128`；
- `AgentRunner.DEFAULT_TIME_BUDGET = Duration.ofMinutes(5)`；
- `AgentRunner.DEFAULT_MAX_TOOL_CALLS_PER_RESPONSE = 8`；
- `SecretarySession` 与 `GmAideAgent` 的默认构造路径统一使用这些常量；
- `AgentRunner(LlmClient)` 的兼容默认构造也改为 128/5 分钟；
- `AdjudicationService` 保持原 8 轮/120 秒，不在本次范围；
- 预算耗尽回执会报告：
  - 行动草案是否创建；
  - 查询/投诉是否提交；
  - 世界状态未改变；
  - 玩家是否需要重新描述。

## 测试

```text
tools/gate.sh full
PASS
617 tests / 0 failures / 0 errors / 0 skipped
connect 36 / connect-agent 21 / taskflow 255 / app 305
```

新增 `SecretarySessionBudgetTest`：

- 默认常量 128 / 5 分钟 / 8；
- 无进展时明确说未创建、未提交、未改变世界、需要重发；
- 已成功提交 `ACTION_DRAFT` 时明确说已创建、尚未确认、不需要重述。

## 部署状态

- 当前 `simulation/dist` 仍是上一版 `256944e469859f5bf84cb35a7850d5377a277cbbaef4c024a98c6effeeba8d52`；
- 最新 target jar：`0cef4ed434c172acf412fb0b7495e919d479e1af154da84e0eebe87f7a151d6a`；
- app/proxy 由真实推演会话保持运行，未停止、未替换 jar；
- 真实玩家消息验收未跑。
