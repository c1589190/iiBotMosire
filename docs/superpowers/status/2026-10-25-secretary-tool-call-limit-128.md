# 秘书单响应工具调用上限改为 128 实现快照

> **状态**：历史快照（2026-10-25）。**当前状态入口**：[`../../agents/STATUS.md`](../../agents/STATUS.md)。
> **对应裁定**：D-020 2026-10-25 修订。
> **需求原稿**：[`../specs/2026-10-24-user-requirements-source.md`](../specs/2026-10-24-user-requirements-source.md) §5.9。

## 1. 用户原话

> 「竟然不许？我不是说了吗，别乱限制上限，改成128，你耳朵聋了吗？」

触发事实：模型单次响应派发 10 个 `secretary_post_read_visible`，被原默认上限 8 拦截为
`TOOL_CALL_LIMIT`，10 个读取调用全部未执行，并留下悬空 tool_call 上下文。

## 2. 代码修改

- `AgentRunner.DEFAULT_MAX_TOOL_CALLS_PER_RESPONSE`：`8` → `128`。
- `AgentRunner(LlmClient)` 默认构造继续使用该常量。
- `SecretarySession` 默认构造使用该常量，秘书单响应最多 128 个工具调用。
- `GmAideAgent` 不再硬编码 `8`，改为使用
  `AgentRunner.DEFAULT_MAX_TOOL_CALLS_PER_RESPONSE`（128）。
- 保留 128 轮 / 5 分钟回合预算；本次只调整单响应工具调用上限。

## 3. 测试

新增回归测试：

- `AgentRunnerTest.defaultRunnerAcceptsTenToolCallsInOneResponse`
  - 单次响应构造 10 个工具调用；
  - 断言 10 个全部执行、写回 10 条 tool result、最终 `StopReason.FINISHED`。
- `SecretarySessionBudgetTest.defaultsAre128RoundsFiveMinutesAnd128ToolCalls`
  - 断言默认单响应工具调用上限为 128。

实际门禁：

```text
tools/gate.sh focused iibot-taskflow test
PASS：taskflow 256 tests / 0 failures / 0 errors / 0 skipped

tools/gate.sh full
PASS：全仓 627 tests / 0 failures / 0 errors / 0 skipped
connect 38 / connect-agent 21 / taskflow 256 / app 312
```

## 4. 部署

- target/dist shaded jar sha256：
  `55212c2ddb272d42000bec28a71eaac722769fb3e55021da72b729a08a08f010`
- 旧 dist jar 备份：
  `simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar.backup-20261025-224319-pre-tool-call-128`
- `javap -constants` 核验：
  `DEFAULT_MAX_ROUNDS = 128`；
  `DEFAULT_MAX_TOOL_CALLS_PER_RESPONSE = 128`。
- `SecretarySession` / `GmAideAgent` 构造字节码均引用 128。

## 5. 未验证 / 风险

- 真实玩家消息复测 `NOT_RUN`；需重启 app 后由用户/隔壁会话验证。
- 原 player-1 active cache 若仍带悬空 tool_call，替换 jar/上限不会自动修复该历史；
  仍需要切换/清理该缓存，或按后续修复补齐/回滚悬空工具结果。
- 单响应 128 个工具调用仍可能带来较大的上下文/耗时压力；这是用户明确设定的上限，
  不是自动无限循环。
