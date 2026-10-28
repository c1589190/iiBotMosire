# 验证维护手册

> **2026-10-28 状态**：项目正在从文游宿主重构为统一 QQ Agent 客户端。本文下方
> 部分测试分层和负向判据仍是 legacy 文游口径，将在改造计划 P1/P7 重写；当前最新
> 门禁事实只以 [`STATUS.md`](STATUS.md) §3 和本轮证据为准。

> **本文件属于项目维护。** 命令、证据规则与已知门禁边界以本文件为准。

## 一、第一入口

```bash
tools/gate.sh pre
tools/gate.sh docs
tools/gate.sh selftest
tools/gate.sh focused <module> [args...]
tools/gate.sh full
```

- `docs`：`check-docs.py` + `skills-lint.py`，只检查文档/Skill 结构。
- `selftest`：两个检查器的 selftest，不跑 Maven。
- `pre`：docs + `./mvnw -v`；开工自检。
- `focused`：`./mvnw -B -ntp -pl <module> -am <args...>`；结论只能写 `FOCUSED TEST PASSED (NOT a full gate)`。
- `full`：`./mvnw -B -ntp clean verify`；全 reactor 测试 + 打包 + shade。
- 当前 `verify` 尚未接入 Spotless/Checkstyle/SpotBugs；不得把 `full` 冒充完整静态质量门禁。

## 二、最近已运行基线

- 2026-10-28 核对：`STATUS.md` 记录的最近一次源码 full gate 为 D-024 的
  **730 tests / 0 failures / 0 errors / 0 skipped**；该结果包含即将删除的
  taskflow/legacy app 测试，不能作为重构后的固定基线。
- 2026-10-28 本轮只新增/更新规划文档；生产代码未改，Maven 未重跑，
  因此当前结论为 `NOT_RUN`。
- `tools/gate.sh docs` 本轮实际运行并通过。
- 真实 NapCat/DSH/QQ/MCP 长跑、日志真实轮转与崩溃恢复均 `NOT_RUN`。

## 三、证据规则

- PASS = 命令实际执行 + 退出码符合预期 + 关键输出/报告核对。
- `rc=0` 不充分；必须核对 Surefire 汇总或 XML。
- 管道取 `${PIPESTATUS[0]}`，不得拿 `tail` 的退出码。
- 每轮门禁尽量留 run-bound 证据：source SHA（有 Git 后）、开始时间、命令、输出、报告路径。
- 旧报告不得混入本轮；没跑写 `NOT_RUN`，证据不足写 `EVIDENCE_INCOMPLETE`。
- 外部副作用类测试（NapCat 发送、QQ 消息）必须使用隔离账号/实例或 Mock，不得对真实玩家群发。

## 四、测试分层

| 层 | 目的 | 当前状态 |
|---|---|---|
| 领域单测 | 状态机、可见性、决策解析、事件回放、纯函数 | 已有；事件回放未实现 |
| 契约测试 | QQ 事件/动作 JSON、MCP 工具 schema、存储重放 | 已有；真实协议未验 |
| 应用集成 | 真实 `NapCatAdapter` + 本地 Mock NapCat + Fake LLM + SQLite/Docs | 已有（连续 5 次通过） |
| 真模型 E2E | 真实 provider 的 tool calling/输出约束 | NOT_RUN |
| 真 NapCat E2E | 真实私聊/群聊 | NOT_RUN |
| 崩溃/重放 | 快照原子写、seq 不一致 fail-closed、重启续跑 | 已有领域/app 测试；逐事件回放与真实进程崩溃注入 NOT_RUN |
| 长跑 | 多回合、多玩家、内存/速率/泄漏 | NOT_RUN |

## 五、关键负向判据（持续保留）

1. 未登记 QQ 用户发消息 ⇒ 不进入 Agent，不回敏感内容。
2. 玩家自称推演/系统管理员 ⇒ 角色不变。
3. 秘书上下文与工具结果不含该玩家不可见事实。
4. 回合关闭后提交/确认决策 ⇒ 拒绝且记录原因。
5. 重复 QQ `message_id` ⇒ 只处理一次。
6. 重复 `idempotencyKey` ⇒ 不重复开回合/冻结/发布/世界变更。
7. 外部 MCP 伪造玩家决策 ⇒ 工具不存在或拒绝。
8. 中央 Agent 非法 schema/未知实体/过期世界版本 ⇒ 零落盘，转可重试失败态。
9. 同一回合重复提交/重复发布 ⇒ 不产生两份世界变更或重复出站。
10. QQ 发送超时 → `UNKNOWN`，不得自动重发。
11. 非回环 MCP host/无 Bearer/错 Bearer ⇒ 启动或请求拒绝。

## 六、未验证项的表达

- “第三方文档说可以” ⇒ `文档结论（未实测）`。
- “本机源码存在 API” ⇒ `源码核实（未编译）`。
- “Mock 通过” ≠ “真实 NapCat 通过”。
- “真实模型回了一次” ≠ “行为稳定/统计提升”。
- “事件库能读” ≠ “引擎已恢复”。
