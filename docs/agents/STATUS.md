# iiBotMosire 当前状态

> **更新时间**：2026-10-24 03:24（Asia/Shanghai）
> **本文是唯一实时状态入口。** `docs/superpowers/status/*` 都只是某时点的历史快照。
> 当前事实与设计/维护裁定冲突时：设计/维护裁定优先；本文只记录“现在实际是什么”。

## 1. 当前目标

- **真实单人 E2E 已完成**：玩家私聊 → 真实 LLM 秘书草稿 → 玩家 `/确认` → MCP 冻结 → 中央推演 Agent（真实 LLM）→ COMMITTED → MCP 发布 → 公共群公开叙事 + 玩家私聊 outcome 均已真实出站。
- 当前回合 `PUBLISHED`，`worldVersion=1→2`，`eventSeq=14`；服务保持运行，等待下一轮指令。
- 仍需用户协同：下一回合的行动意图/确认；DSH 外部会话接入尚未完成。
- **已完成首个可运行纵向切片**：外部接入 Lib → Agent 工具扩展层 → 交互-推演任务流 → 组合根 → 认证 MCP 控制面。
- 本轮已实现：
  - `iibot-connect`：OneBot 11/NapCat 事件 WS + HTTP Action、鉴权、重连、心跳、去重、限速、分段、三态回执；
  - `iibot-connect-agent`：`qq_*` AgentTool、角色/资源权限、统一经 `ToolCallAuthorizer`；
  - `iibot-taskflow`：秘书/GM brief、自然语言决策草案、私聊硬确认、运行期设定提案/审核、回合状态机、中央推演、事件审计、18 个 taskflow 控制工具（app 另加 `tf_run_adjudication_agent`，MCP 共 19 个）；
  - `iibot-app`：严格配置、世界事实装载、Workspace/Docs、SQLite 事件审计、outbox、逐用户 mailbox、自动冻结、Bearer MCP 传输、可运行 Main；
  - Maven Wrapper 3.9.16、可执行 shaded jar。
- **下一步进入第 2 回合/多玩家/DSH host 真实验证**；当前第 1 回合已真实完成。

## 2. 源码基线

| 项 | 当前事实 |
|---|---|
| 仓库 | `/home/cna/iiBotMosire` |
| Git | **未初始化**（当前目录没有 `.git/`） |
| Maven reactor | `io.mosire.iibot:iibot-parent:0.1.0-SNAPSHOT`，4 个模块 |
| Java | OpenJDK 21.0.12.1 |
| Maven Wrapper | 3.9.16；`./mvnw -v` 实测 |
| 上游三库 | `io.mosire:{agentlib,workspace,docs}-mosire:0.2.3`（Pages 固定版） |
| 可运行产物 | `iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar`，约 21 MB |
| Git/提交 | 无 Git；未提交、未推送 |
| 本机工作树 | 仅本项目文件；`target/` 等构建产物存在 |

## 3. 最近功能门禁

| 项 | 实际结果 |
|---|---|
| 最新构建 | `./mvnw -B -ntp package`：**BUILD SUCCESS**；**185 tests / 0 failures / 0 errors / 0 skipped** |
| 分模块 | connect 29、connect-agent 21、taskflow 71、app 64 |
| 最近 full clean | 待本轮停止服务后复跑；上次 full clean 为 169 tests 的旧基线 |
| Shade | 产出可运行 `iibot-app-0.1.0-SNAPSHOT.jar`（约 21 MB），Main-Class 已核 |
| 文档/工具链 | `tools/gate.sh pre` PASS（docs + skills + `./mvnw -v`） |
| 文档 selftest | check-docs 11/11；skills-lint 6/6 |
| `./mvnw -v` | Apache Maven 3.9.16 + Java 21，rc=0 |
| `java -jar ... --help` | rc=0，输出用法 |
| 真实 NapCat 私聊收发 + 真实 LLM 秘书回复 | **PASS（部分真实 E2E）**：玩家 `<player-qq>` 私聊“你先说句话证明你能说和”，Bot 于 03:14:51 返回真实秘书文本；来源为用户提供的 QQ 对话记录 |
| 真实 LLM API 连通性 | **PASS**：基础文本 `OK`；真实 tool_call `get_time` 与第二轮工具结果回填成功 |
| 真实 NAPCAT WS 连接 | **PASS**：App 进程到 `127.0.0.1:3001` ESTAB；`get_login_info` 返回 Bot `<bot-qq>` |
| 真实本地 MCP client | **PASS**：`initialize` → `tools/list(19)` → `tf_status`/`tf_open_turn`/`tf_turn_get` |
| 真实单人完整回合 | **PASS**：OPEN→LOCKED→ADJUDICATING→COMMITTED→PUBLISHED；中央推演约 14s；worldVersion 2；公开群消息 id 1536303685；玩家私聊 outcome id 138548704 |
| 真实 LLM 全 App 流程 | **PASS**：秘书 → draft → `/确认` → lock → 中央 LLM 推演 → commit → publish 已真实完成；见 `docs/superpowers/status/2026-10-24-real-single-player-e2e.md` |
| 真实 DSH/MCP E2E | **PARTIAL**：本地 Java MCP client 已真实连接 19 工具；DSH 外部会话未接入 |
| 长跑/崩溃恢复 | **NOT_RUN** |
| 应用启动 smoke（dummy 密钥） | 进程启动后保持运行至 6s timeout；生成 `events.db`、`world/bible.md`、审计 LOG |
| 真实 `NapCatAdapter` + 本地 Mock NapCat E2E | 通过；WS 入站→HTTP Action 出站全链路，连续 5 次通过 |
| 进程重启恢复 smoke（dummy 密钥） | 首启生成 `taskflow-state.json`/events.db；同 dataDir 第二次启动日志 `recovery=true worldVersion=1 eventSeq=4` |
| MCP 启用 smoke | 无 token GET → 401；带 token GET → 400（方法/协议头不符，符合预期）；进程保持运行 |

## 4. 当前有效设计/维护入口

| 类型 | 入口 |
|---|---|
| 根路由与常驻红线 | `AGENTS.md` |
| 文档系统规则 | `docs/AGENTS.md` |
| 项目维护裁定 | `docs/agents/MAINTENANCE_RULINGS.md` |
| 用户需求原话与 D-xxx | `docs/superpowers/specs/2026-10-24-user-requirements-source.md` |
| 接入 Lib / Agent 扩展层 | `docs/superpowers/specs/2026-10-24-connect-and-agent-extension-design.md` |
| 目标运行设计 | `docs/superpowers/specs/2026-10-24-target-runtime-design.md` |
| 外部 MCP 控制面 | `docs/superpowers/specs/2026-10-24-external-mcp-control-plane.md` |
| 实施计划与进度 | `docs/superpowers/plans/2026-10-24-next-implementation-plan.md` |
| 首个纵向切片历史快照 | `docs/superpowers/status/2026-10-24-first-vertical-slice.md` |
| 真实单人 E2E 历史快照 | `docs/superpowers/status/2026-10-24-real-single-player-e2e.md` |

## 5. 已实现与已验证的能力

- QQ 消息去重、重连、心跳、限速、文本分段、发送三态 `SENT/FAILED_BEFORE_SEND/UNKNOWN`；
- Agent 工具权限元数据与 `ToolCallAuthorizer` 统一执行；
- 通用账号信息查询 `qq_get_login_info`（AccountInfoProvider，不依赖 NapCat 具体类）；
- 完整领域状态快照原子持久化与重启恢复（身份/世界/回合/draft/decision/设定/幂等回执）；重启前校验事件 seq、promptVersion、身份映射，不一致 fail-closed；
- 玩家秘书与 GM brief 的可见性隔离；
- 自然语言生成结构化草案；只有同一私聊 QQ 的 `/确认 <draftId>` 才产生有效决策；
- 回合 OPEN→LOCKED→ADJUDICATING→COMMITTED→PUBLISHED、revision CAS、幂等；
- 发布后结果分发：公开叙事进入公共群，玩家私密 outcome 进入对应私聊，稳定幂等键且进程内不重复；
- 运行期设定：玩家 `/提议设定`，推演 `/设定`、`/待审设定`、`/通过设定`、`/拒绝设定`；批准后 worldVersion+1 并生成新 WorldFact；
- 非法提案/未知玩家/过期 worldVersion 零世界变更；
- 外部 MCP 19 工具（含 `tf_run_adjudication_agent`）、Bearer 常数时间校验、回环绑定、独立 allow-list、POST/GET SSE 代理；
- Workspace/Docs 实际写入世界圣经与审计 LOG；
- SQLite 事件审计与 `afterSeq` 只读游标；真实发布后由 `afterMutation` 触发结果分发。
- 真实单人回合的草稿、硬确认、冻结、中央 LLM 推演、提交、发布与分发均有实际出站证据；
- v2-A 即时 ACK 已实现：已登记玩家/推演私信先发独立 ACK，再异步处理正式回复；重复 dedupeKey 不重复 ACK。

## 6. 未验证 / 风险 / blocker

1. **真实 DSH 外部会话未接入**：当前由本地 Java MCP client 驱动；DSH host 会话尚未实际连接。
2. **真实多玩家/多回合/长跑未运行**：仅完成单玩家第 1 回合。
3. **逐事件回放未实现，重启恢复依赖完整快照**：`taskflow-state.json` 原子持久化并在启动时恢复，事件库 seq 只做一致性锚点；快照与事件 seq 不一致时 fail-closed。Secretary 的 ContextWindow 聊天历史不恢复。
4. **公共群目前只作为公开广播边界**：群内玩家对话、@ 触发、群内决策未接入。
5. **外部 MCP 仅本地单 supervisor**：远程 TLS/多租户/长期 SSE/Last-Event-ID 重放未验；不要把端口暴露公网。
6. **无 Git 基线**：未提交/未推送；后续需要用户决定初始化与提交策略。
7. **依赖/静态质量门禁有限**：当前 `verify` 主要有 enforcer + tests + shade，尚未接入 Spotless/Checkstyle/SpotBugs；不要把它等同于完整质量门禁。
8. **上游编译 warning**：AgentLib 注解在 javac 编译期有 `SuppressFBWarnings` 缺失 warning，不影响当前构建，但说明注解仅编译期处理。

## 7. 下一步

1. 按 M-004 等用户给出下一回合行动意图，再跑第 2 回合。
2. 接入真实 DSH host 会话，验证外部 Agent 观察/推进/事件游标。
3. 做多玩家/多回合/断线重连/长跑；补齐事件逐条回放与崩溃点注入。
4. 真实 E2E 证据写入 `docs/superpowers/status/`；未跑项继续标 `NOT_RUN`。
