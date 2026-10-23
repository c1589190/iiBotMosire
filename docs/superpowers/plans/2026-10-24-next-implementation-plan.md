# 实施计划与进度（v1，2026-10-24）

> **状态**：P0–P6 的 Fake/Mock 纵向切片已实现；P7 真实外部 E2E 待连接。
> **性质**：计划不是设计；事件/状态机/接口以 `docs/superpowers/specs/**` 为准。
> **当前事实**：以 [`../../agents/STATUS.md`](../../agents/STATUS.md) 为准；本文件只记录计划与阶段进度。

## 0. 总原则

- 先验证三库可解析/可编译，再写领域逻辑。
- 先做 Fake/Mock 纵向切片，再连接真实 NapCat/LLM/MCP。
- 每个阶段有独立验收判据；没有证据不进入下一阶段。
- 不把真实 QQ 消息发到测试玩家；不把真实密钥放进仓库。

## 1. 模块与依赖（已落地）

```text
iibot-connect          独立外部接入 Lib；不依赖 AgentLib/业务域
      ▲
iibot-connect-agent    依赖 connect + agentlib；映射 AgentTool/权限
      ▲
iibot-taskflow         领域、秘书/GM Agent、回合、外部控制工具
      ▲
iibot-app              组合根、SQLite 审计、Workspace/Docs、Outbox、MCP、Main
```

- 根 POM：`io.mosire.iibot:iibot-parent:0.1.0-SNAPSHOT`。
- Maven Wrapper：3.9.16。
- 三库：`io.mosire:{agentlib,workspace,docs}-mosire:0.2.3`。

## 2. 阶段进度

| 阶段 | 内容 | 状态 | 当前证据/边界 |
|---|---|---|---|
| P0 | Maven 骨架、三库解析、Wrapper | **完成** | `./mvnw -v` 3.9.16；`package` 成功 |
| P1 | 领域状态机与 Fake 纵向切片 | **完成** | taskflow 66 tests；全 Fake 流程测试 |
| P2 | SQLite 事件审计/outbox | **部分完成** | 事件审计、`afterSeq` 只读游标、outbox、完整快照重启恢复；**逐事件回放未实现** |
| P3 | NapCat Adapter + Mock 契约 | **完成（Mock）** | connect 23 tests；真实 NapCat `NOT_RUN` |
| P4 | 每玩家秘书 Agent/可见性 | **完成（Fake LLM）** | 秘书、brief、硬确认、A/B 隔离测试；真模型 `NOT_RUN` |
| P5 | 中央推演 Agent | **完成（Fake LLM）** | 合法提交/非法零落盘/可重试测试；真模型 `NOT_RUN` |
| P6 | 外部 MCP 控制面 | **完成（本地）** | 19 工具、Bearer、回环、独立 allow-list、POST/GET SSE 代理；真实 DSH `NOT_RUN` |
| P7 | 真实外部 E2E、长跑、崩溃恢复 | **未开始/阻塞于凭据** | 需真实 NapCat/LLM；回放恢复另立实现 |

## 3. 已实现验收点

### P0

- [x] 4 模块 reactor 编译；
- [x] 三库 0.2.3 从 Pages 解析；
- [x] 每模块 `AGENTS.md`；
- [x] Maven Wrapper 3.9.16；
- [x] shaded jar + Main-Class。

### P1/P2

- [x] 回合 OPEN→LOCKED→ADJUDICATING→COMMITTED→PUBLISHED；
- [x] expectedRevision CAS、幂等键、重复确认/重复 commit 不重复变更；
- [x] 冻结快照不可变、late draft/逾期确认拒绝；
- [x] 非法 AdjudicationProposal 零世界变更；
- [x] TaskflowEvent 落 SQLite，可按 `seq > afterSeq` 升序读取；
- [x] 完整领域状态快照原子持久化 + 重启恢复（seq/prompt/身份不一致 fail-closed）；
- [ ] 事件流逐条回放/投影 hash（待做）。

### P3

- [x] OneBot 11 codec：private/group、array/CQ、未知字段、非文本；
- [x] HTTP Action mock：鉴权、成功、retcode 失败、超时 UNKNOWN、分段、幂等；
- [x] WS 契约：连接、事件、去重、heartbeat/notice、重连、close；
- [x] token 不出日志/toString；
- [ ] 真实 NapCat 根 WS + HTTP Action；真实速率/文本长度；真实重连/心跳。

### P4/P5

- [x] 每玩家秘书 `ContextWindow` 与串行边界；
- [x] 秘书只注入 GM brief/公共事实，不读全量世界；
- [x] 自然语言 → `decision_draft_create/update` → 预览；
- [x] 同一私聊 `/确认 <draftId>` 才产生 Decision；
- [x] 自然语言“确认”、他人确认、群聊确认不提交；
- [x] 中央 Agent 仅通过 `submit_adjudication` 提交结构化提案；
- [x] 玩家 `/提议设定`、推演 `/设定` / `/待审设定` / `/通过设定` / `/拒绝设定`；批准后生成新 WorldFact；
- [x] 非法/缺失工具调用进入失败态且 worldVersion 不变；
- [ ] 真实 LLM tool calling/推理内容/超时/成本；真实上下文压缩。

### P6

- [x] 独立 ToolRegistry 与 18 个控制工具；
- [x] 无决策提交/确认、关机、改角色、任意 QQ 发送工具；
- [x] Bearer 常数时间校验、只回环、精确路径、请求体上限；
- [x] 内部/外部 MCP 之间流式代理（POST SSE + GET SSE）；
- [x] 官方 MCP client 的 initialize/tools/list/tools/call 测试；
- [ ] 真实 DSH 连接、长时间 SSE/重连/Last-Event-ID 重放、远程 TLS/认证代理。

### P7

- [ ] 真实 NapCat 隔离 QQ 小号私聊收发；
- [ ] 真模型玩家秘书 + 中央推演；
- [ ] DSH 真实 MCP 会话；
- [ ] 多玩家并发、长回合、速率/内存/数据库增长；
- [ ] 崩溃点、事件回放、恢复。
- [ ] 证据落 `.work/` 与 dated status，未跑项标 `NOT_RUN`。

## 4. 下一步顺序

1. 用户提供真实 NapCat/OneBot 连接、Bot QQ、公共群、测试玩家/推演 QQ；密钥只通过环境变量注入。
2. 真实 LLM provider/baseUrl/model/API key 配置后，先跑单个玩家秘书对话。
3. 用隔离数据目录启动真实 app，跑 `/开回合` → 草稿 → `/确认` → `/冻结` → `/推演` → `/发布`。
4. DSH 连接本机 MCP，验证 Bearer、tools/list、event_poll、revision 冲突。
5. 单独实现事件流回放器与恢复测试（不能把当前审计读取冒充恢复）。
6. 真实 E2E 通过后再评估性能、长跑、上下文压缩与公共群玩法。

## 5. 停止条件

- 真实凭据缺失：外部 E2E 保持 `BLOCKED/NOT_RUN`，不伪造成功。
- 真实 NapCat 根 WS 与 HTTP Action 不兼容：记录实际协议错误，调整传输策略，不改领域层。
- LLM 不调用工具/输出不稳定：先做输出校验与重试策略，不绕过玩家硬确认或中央提案校验。
- 事件回放未实现前，不得声称崩溃恢复通过。
