# 历史快照：2026-10-24 v2 功能切片

> **历史快照**：本文件只记录该时点事实，不是当前状态入口。
> **当前实时状态**：[`../../agents/STATUS.md`](../../agents/STATUS.md)

## 已实现

- 私信即时 ACK：已登记玩家/推演私聊先发独立幂等提示，再异步处理正式回复。
- 每玩家秘书 Workspace/Docs：`dataDir/secretaries/<playerId>/{public,player,gm,notes}`；秘书三个工作区工具；GM 四个 MCP 工具（list/read/dispatch/chat）；路径逃逸/跨玩家 fail-closed。
- 推文状态机：`PENDING_APPROVAL → APPROVED/REJECTED → PUBLISHED`；玩家/秘书不能批准或发布；GM/DIRECTOR/SUPERVISOR 才能审核/发布。
- `JsonPostStore`：`dataDir/posts.json` 严格 JSON、原子持久化、损坏 fail-closed；`PostDistributor` 发公共群/私聊并记录 SENT/FAILED/UNKNOWN，UNKNOWN 不自动重发。
- 多模型配置：`llm` default + `llmRoutes` + `agentRoutes(secretary/adjudicator/gmAide)`；配置切换，重启生效。
- `GmAideAgent`：独立模型/上下文，9 个只读工具，可读全量世界事实、回合、决策、brief、事件、所有秘书工作区、所有推文；无 QQ 发送/世界写/审批/发布/文件写工具。
- MCP full assembly：28 工具。

## 自动化验证

```text
./mvnw -B -ntp -pl iibot-taskflow -am test
  29 + 21 + 122 tests PASS

./mvnw -B -ntp -pl iibot-app -am test
  reactor 261 tests PASS
```

`tools/gate.sh full` 在本轮停服后复跑通过；详见当前 STATUS。

## 真实本地 MCP/LLM 验证（未发 QQ）

在真实 v2 app + 真实 LLM + 本地 MCP client 下：

1. `tf_gm_secretary_list` 列出 `player-1` 工作区四根；
2. `tf_gm_secretary_dispatch` 成功下发 `gm/round2-brief.md`；
3. `tf_gm_secretary_read` 成功读回材料；
4. `tf_gm_secretary_chat` 通过真实秘书 LLM 读取工作区并总结，未创建草稿/未发 QQ；
5. `tf_gm_aide_chat` 通过真实 GM 推演秘书读取世界/回合/工作区/推文摘要并输出分析；
6. `tf_gm_secretary_chat` 通过真实 LLM 成功调用 `secretary_post_create` 创建私人推文草稿 `2b1bcd53-dbbe-47e8-b0d4-6d3f9ef0a817`；
7. MCP `tf_post_decide` 由 `external-supervisor` 成功批准该推文，状态 `APPROVED`；
8. 经用户明确允许后执行 `tf_post_publish`，`external-supervisor` 将私人推文真实发送到玩家私聊；`deliveryState=SENT`。
9. 玩家真实私信在 04:15:17 收到 ACK，正式秘书回复 04:15:31 出站；真实 ACK/异步链路通过。
10. 重启时发现旧 `TurnResultDistributor` 会重复发送已发布回合结果；已新增 `JsonDeliveryLedger` 逐消息持久化声明并验证再次重启不重复外发。

## 未验证 / 待用户协同

- 真实公共推文发布未跑（私人推文真实发布已跑）；
- 第二轮真实行动/决策未跑；
- 真实 DSH host 会话未接入；
- 运行时热切换模型未实现（配置 + 重启）；
- 多玩家、多回合、长跑、逐事件回放未跑。
