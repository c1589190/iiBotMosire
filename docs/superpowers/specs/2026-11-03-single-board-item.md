# 2026-11-03 「板」的边界：板是动态的，注入是静态快照

> **状态**：结论文档（2026-11-03）。结论是**什么都不用改**；记下来防止再被误设计成
> 「合并成一条 Inbox item」。当前实现未因此改变。

## 1. 用户原话

- 2026-11-03："一个 iiBot 进程维护**一张**待推送 Inbox 板；未 act 条目按来源动态追加/合并……
  Agent 空闲时把当前整张板作为**一次**消息推送，推送后清空待推送缓冲。"
- 2026-11-03 纠正一："ack 不是对整板的。"
- 2026-11-03 纠正二："板子本身不需要动啊，反正注入的时候，就自动是一个静态的形状了，
  真正的板子肯定是灵活调整的。"

**用户裁定**：板 = 动态事实源（未 ack 的条目一直留着、随时被追加/合并/ack）；
注入 = 注入那一刻对板的一次**静态快照**。两者都不需要新类型、新 kind、新端口方法。

## 2. 结论

1. **板不动**：仍是「每个来源一条 active Inbox item」，`ack` 永远 per-source，
   `ackThrough(threadId, deliveredSeq)` 只推进该 thread 的游标；未 ack 的条目
   继续 available，不因为「推送过」而消失。
2. **注入即快照**：`inbox-wake` 每次唤醒读的是**当前 available 全量**并渲染成一条消息
   （`GET /items?limit=summaryMaxItems` + 逐条详情），本身就是「当时这张板的静态形状」；
   渲染完不改板、不删条目，只推进「通知进度」`lastWokenRevision`。
3. **不做**：`InboxBoard` 类型、`kind=digest_board`、板级端口方法、板级 ack。
   这些是把「快照」误当成「事实源」的产物。
4. iiBot 侧这一轮只有两件事真正落地：板内正文可配（`messageBoard.summaryMaxChars` /
   `contextMessages` / `contextMaxChars`）与板上带 `messageKey`（取图用）。

## 3. 验收判据

- A1：`qq_conversation_ack` 入参与语义保持 per-source，没有任何「按 itemId 一次 ack 整板」。
- A2：未 ack 的条目在后续唤醒里依然出现（不是「只推增量」）。
- A3：一次唤醒消息可同时含多来源（编号 + `source=` + 各自时间线）。
- A4：`InboxDigest` 的单来源不变量不变（`InboxDigestTest` 全绿）。
- 真实 QQ 双来源联调：`NOT_RUN`，需用户当次授权。
