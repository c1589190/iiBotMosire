# iiBotMosire 当前状态

> **更新时间**：2026-10-24 18:50（Asia/Shanghai）
> **本文是唯一实时状态入口。** `docs/superpowers/status/*` 都只是某时点的历史快照。
> 当前事实与设计/维护裁定冲突时：设计/维护裁定优先；本文只记录“现在实际是什么”。

## 1. 当前目标

- **P0 已完成**：v3 行动池 + AgentLib 裁定包 + 48 MCP 工具已通过 full gate，提交为 `0695675`；应用保持停止。
- **P1 已完成**：`events.db` 完整内容基础（events + event_contents、`EventContentStore`/`EventAppendPort`、schema v1、迁移、hash/可见性/脱敏/事务）已通过 full gate 与阶段评审，提交为 `a52626f`。
- **P2 已完成**：Post/Complaint/ActionPool 已改 events.db 事件源；Taskflow 完整快照事件已接入；旧 posts/complaints/taskflow-state 已有两阶段迁移与 reconciled 对账；阶段评审 PASS，full gate 413 tests PASS，提交 `0fbceaa`。DecisionPackage/Batch 因 AgentLib 缺 restore/事务边界推迟 P5。
- **P3 已完成**：动态控制初始化、公开群、玩家/推演注册、秘书绑定、世界装载、动态 ACK/投递路由与 GM 私有文档已通过 full gate 和阶段评审，提交 `615595d`；完整控制面 55 工具。
- **P4 已完成**：统一 Ticket/消息板、秘书 submit/list/read、GM 8 个 Ticket 工具与 events.db 快照已通过 full gate 和阶段评审，提交 `5fb5d8c`；完整 MCP 60 工具。
- **P5 已完成**：ACTION_DRAFT ticket → ActionPool、DecisionPackage/Batch 命令事件与启动恢复、canonical action owner、ActionPool 投影 reconcile 已通过 full gate 和五轮对抗评审，提交 `06d9e1a`；完整 MCP 61 工具。
- **P6 已完成**：发布编排、Public/Private 推文草稿、GM 审核发布、records Markdown 投影、记录查询工具已通过 full gate 和多轮对抗评审，提交 `d3a9c91`；完整 MCP 65 工具。
- **P7 已由用户裁定跳过**（2026-10-24），不做全领域 events.db 回放/重建。
- **P8-A 已完成**：允许 `players=[]` 启动；新增 GM 独立 PUBLIC 开场推文 `tf_publication_opening`；`records/openings` 投影；完整 MCP 66 工具。full gate **526 tests PASS**，提交 `1480c55`。独立评审在用户要求提交时中止，评审结论未完成。
- **下一步 P8 真实模拟**：真实 DSH/Hermes MCP、真实 LLM/NapCat/QQ、零玩家公共开场→审核发布→动态注册玩家→多批次/发布/记录链路联调；按 M-004 需要用户协同与真实外部确认。
- **理想工作流仍按 P0–P8 计划推进**：`events.db` 是目标 canonical 领域事实源；P1 只完成内容基础，领域事件全量接入与回放尚未完成，Markdown 仍将是投影。
- **v2 功能切片已落地**：即时 ACK、每玩家秘书工作区、GM 工作区/秘书对话、推文草稿→GM 审核→GM 发布、多模型路由、只读 GM 推演秘书。
- **v1 真实单人 E2E 已完成**：玩家私聊 → 真实 LLM 秘书草稿 → 玩家 `/确认` → MCP 冻结 → 中央推演 Agent（真实 LLM）→ COMMITTED → MCP 发布 → 公共群公开叙事 + 玩家私聊 outcome 均已真实出站。
- 当前运行服务恢复状态为第 1 回合 `PUBLISHED`，`worldVersion=2`，`eventSeq=14`；v2 真实 QQ ACK/工作区/GM aide/私人推文发布已验证。
- 仍需用户协同：第二轮真实行动/决策、公共推文真实发布、多玩家与 DSH 外部会话验证。
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
| Git | **已初始化并推送**：`origin git@github.com:c1589190/iiBotMosire.git`，分支 `main` |
| Maven reactor | `io.mosire.iibot:iibot-parent:0.1.0-SNAPSHOT`，4 个模块 |
| Java | OpenJDK 21.0.12.1 |
| Maven Wrapper | 3.9.16；`./mvnw -v` 实测 |
| 上游三库 | `io.mosire:{agentlib,workspace,docs}-mosire:0.2.3`（Pages 固定版） |
| 可运行产物 | `iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar`，约 21 MB |
| Git/提交 | 本地 P8-A feature `1480c55`；P6 为 `d3a9c91`；远程原 HEAD `99dc328`（本次 push 后更新）；STATUS 收口提交随后 |
| 本机工作树 | 仅本项目文件；`target/` 等构建产物存在 |

## 3. 最近功能门禁

| 项 | 实际结果 |
|---|---|
| 最近 full clean（P8-A） | **526 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**（connect 29、connect-agent 21、taskflow 230、app 246），`tools/gate.sh full` PASS；日志 `.work/p8a-full.log` |
| P8-A focused / 评审 | focused **526/526 PASS**；独立评审已启动但在用户要求提交时主动中止，**无最终评审结论**；66 MCP 工具断言通过 |
| P8-A MCP 工具面 | `tf_publication_opening` 接入；`players=[]` 启动、GM 公共开场草稿、records/openings、动态注册后开回合已测 |
| 当前门禁状态 | P8-A 已完成；P8 真实模拟进行中/等待真实环境协同；未跑项写 `NOT_RUN` |
| Shade | 产出可运行 `iibot-app-0.1.0-SNAPSHOT.jar`（约 21 MB），Main-Class 已核 |
| 文档/工具链 | `tools/gate.sh pre` PASS（docs + skills + `./mvnw -v`） |
| 文档 selftest | check-docs 11/11；skills-lint 6/6 |
| `./mvnw -v` | Apache Maven 3.9.16 + Java 21，rc=0 |
| `java -jar ... --help` | rc=0，输出用法 |
| 真实 NapCat 私聊收发 + 真实 LLM 秘书回复 | **PASS（部分真实 E2E）**：玩家 `<player-qq>` 私聊“你先说句话证明你能说和”，Bot 于 03:14:51 返回真实秘书文本；来源为用户提供的 QQ 对话记录 |
| 真实 LLM API 连通性 | **PASS**：基础文本 `OK`；真实 tool_call `get_time` 与第二轮工具结果回填成功 |
| 真实 NAPCAT WS 连接 | **PASS**：App 进程到 `127.0.0.1:3001` ESTAB；`get_login_info` 返回 Bot `<bot-qq>` |
| 真实本地 MCP client | **PASS**：v2 重启后 `tools/list=28`；工作区/GM aide/推文工具均真实调用 |
| 真实单人完整回合 | **PASS**：OPEN→LOCKED→ADJUDICATING→COMMITTED→PUBLISHED；中央推演约 14s；worldVersion 2；公开群消息 id 1536303685；玩家私聊 outcome id 138548704 |
| 真实 LLM 全 App 流程 | **PASS**：秘书 → draft → `/确认` → lock → 中央 LLM 推演 → commit → publish 已真实完成；见 `docs/superpowers/status/2026-10-24-real-single-player-e2e.md` |
| 真实 DSH/MCP E2E | **PARTIAL**：本地 Java MCP client 已连接 34 工具并完成共享 Skill、投诉提交/GM回应/真实私聊发送；DSH 外部会话未接入 |
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
| v2 功能切片历史快照 | `docs/superpowers/status/2026-10-24-v2-agent-workspace-permissions-models.md` |
| 共享 Skill / 投诉历史快照 | `docs/superpowers/status/2026-10-24-shared-skills-and-complaints.md` |
| v3 设计 | `docs/superpowers/specs/2026-10-24-decision-packages-action-pool-and-turn-records.md` |
| 理想工作流/events.db 设计 | `docs/superpowers/specs/2026-10-24-ideal-gm-control-workflow-and-events-db.md` |
| 理想工作流实施计划 | `docs/superpowers/plans/2026-10-24-ideal-workflow-implementation-plan.md` |

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
- 外部 MCP 48 工具（含行动池、裁定包、批次与 `tf_run_adjudication_agent`）、Bearer 常数时间校验、回环绑定、独立 allow-list、POST/GET SSE 代理；
- Workspace/Docs 实际写入世界圣经与审计 LOG；
- SQLite 事件审计与 `afterSeq` 只读游标；真实发布后由 `afterMutation` 触发结果分发。
- 真实单人回合的草稿、硬确认、冻结、中央 LLM 推演、提交、发布与分发均有实际出站证据；
- v2-A 即时 ACK 已实现：已登记玩家/推演私信先发独立 ACK，再异步处理正式回复；重复 dedupeKey 不重复 ACK。
- v2-B 秘书工作区已实现：每玩家独立 Workspace/Docs 四目录；秘书三工具；GM 四工具（list/read/dispatch/chat），路径逃逸/跨玩家 fail-closed。
- v2-C 推文已实现：PENDING_APPROVAL→APPROVED/REJECTED→PUBLISHED；仅 GM/DIRECTOR/SUPERVISOR 审核发布；`posts.json` 原子持久化；`PostDistributor` 发公共群/玩家私聊；UNKNOWN 不自动重发。
- v2-D 多模型与 GM 推演秘书已实现：`llm` default + `llmRoutes` + `agentRoutes(secretary/adjudicator/gmAide)`；`GmAideAgent` 9 个只读工具，可读全量世界/回合/brief/事件/所有工作区/所有推文，只返回 MCP/GM；无发送/写入工具。
- v2 真实本地 MCP/LLM 验证：工作区下发/读取、秘书真实 LLM 总结、GM aide 真实 LLM 全量读取、真实秘书 LLM 创建私人推文草稿、MCP supervisor 批准并通过 `tf_post_publish` 真实发送到玩家私聊（deliveryState=SENT）。
- v2 真实私信 ACK：玩家真实私信在 04:15:17 收到 ACK，正式秘书回复于 04:15:31 出站；ACL/dedupe/异步顺序成立。
- 真实共享 Skill：GM MCP 写入 `skills/secretary-response-guide.md`，真实秘书 LLM 读取并复述规则。
- 真实投诉闭环：玩家真实投诉 `0f43536a-8868-48c1-949a-16c8b3515372` → 秘书提交 → GM MCP 查看 → GM 回应 → ComplaintDistributor 私聊发送消息 id `1282368080`，`deliveryState=SENT`。秘书还正确拒绝 GM 代替玩家提交投诉。
- 重启重复发送修复：真实重启曾发现 v1 回合结果重复发送；已新增 `JsonDeliveryLedger` 逐消息持久化声明（`turn:<id>:public` / `turn:<id>:player:<playerId>`），并验证再次重启无重复外发。
- v2 full assembly MCP 34 工具（18 ExternalControlToolSet + tf_run_adjudication_agent + tf_gm_aide_chat + 4 GM 工作区 + 4 GM 推文 + 3 共享 Skill + 3 投诉）。
- 全局共享 Skill 区已实现：`dataDir/shared-skills/{public,skills}`；秘书只读两工具，GM MCP list/get/write，GmAide 只读；无需逐玩家复制。
- 投诉闭环已实现：秘书 submit/list/read；GM list/get/respond；`complaints.json` 持久化；ComplaintDistributor 真实私聊回应；SUBMITTED→RESPONDED；未知/其他玩家不可见。
- v3 行动池/裁定包已实现：秘书可跨回合提交自然语言行动；GM 可建包、追加/替换/删除、逐条/整包裁决；一个包 commit 产生一个推演批次；同一回合可多批次。当前行动池/包/批次仍为进程内存储。
- P1 `events.db` 完整内容基础已实现：headers + `event_contents` 同事务写入；JSON/Markdown/TEXT 严格二选一；`PRAGMA user_version=1`；旧 AgentLib 审计库幂等迁移为 `event_version=0/content_missing=1`；SHA-256 正文 hash 读取重算 fail-closed；大小上限；敏感键/重叠 secret 覆盖掩码脱敏；主库及 WAL/SHM best-effort 600。
- P2 现有领域事件接入已实现：Post/Complaint 使用 events.db 事件源 store；ActionPool 使用事件源快照；`TASKFLOW_SNAPSHOT` 保存完整 Taskflow 快照；`payload_json` summary 只含 `idempotencyKeyHash` 等索引；旧 posts/complaints/taskflow-state 使用 exact `migrated` + `reconciled` 两阶段标记，并校验目标事件 seq/type/contentHash；事件版本写侧/读侧均 fail-closed。
- P3 动态控制初始化已实现：MCP 可设置公开群、注册玩家/推演、幂等绑定秘书、装载世界；engine 是身份事实源；动态 QQ 路由、ACK、回合结果、私密推文/投诉回应实时生效并重启恢复；`CONTROL_STATE` 持久化公开群；GM 私有文档 `gm-private/{world,plot,settings,notes}` 安全隔离。
- P4 统一工单已实现：Ticket 类型 SETTING_QUERY/ACTION_DRAFT/COMPLAINT/GM_NOTE；秘书 submit/list/read 按 playerId 隔离；GM board/list/get/claim/reply/batch_reply/close/note_create；TicketSnapshot 写入 events.db，幂等结果带 actor/request fingerprint；batchReply 100 条整批原子。
- P5 行动/裁定包已实现：ACTION_DRAFT ticket 可 promote 为 ActionPool 行动；包命令 STARTED/APPLIED/FAILED 与 BATCH_COMPLETED 全状态写入 events.db；启动命令重放恢复包/批次（commit 不重跑 executor）；canonical action 单 owner；ActionPool 投影可 reconcile；ignore/create 同锁防 TOCTOU。
- P6 发布/记录已实现：COMMITTED batch 由 GM 触发生成 Public/Private 草稿；PostService 跨命令 fingerprint 与 publication-draft-aware 复用；`PUBLICATION_GENERATED` 确定性 ID/恢复；records/turns staging 原子投影、manifest/hash 对账、父级软链防护；`tf_publication_generate` + `tf_record_turn_list/get/query`。
- P8-A 零玩家/开场已实现：`players=[]` 可启动，directors 仍非空；GM 通过 `tf_publication_opening` 创建 PUBLIC `PENDING_APPROVAL` 开场草稿，必须经 `tf_post_decide`/`tf_post_publish` 才发公共群；records/openings 独立投影；完整 MCP 66 工具。模拟区 jar 在 `simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar`（本地忽略，不在 Git）。

## 6. 未验证 / 风险 / blocker

1. **第二轮真实行动/公共推文未跑**：ACK/工作区/GM aide/私人推文/共享 Skill/投诉真实闭环已验证；第二轮决策与公共推文尚未跑。
2. **真实 DSH 外部会话未接入**：当前由本地 Java MCP client 驱动；DSH host 会话尚未实际连接。
3. **真实多玩家/多回合/长跑未运行**：当前仅 1 名真实玩家、1 个已发布回合。
4. **模型热切换未实现**：当前按 `agentRoutes` 配置切换模型并重启；无运行时 admin 工具。
5. **跨进程并发 ledger 未验**：`JsonDeliveryLedger` 保证同 JVM 多实例与重启场景；多进程同时写同一 ledger 未验。
6. **投诉状态有限**：当前只有 SUBMITTED→RESPONDED，无关闭/升级/多次回复。
7. **逐事件回放未实现，重启恢复依赖完整快照**：`taskflow-state.json` 原子持久化并在启动时恢复，事件库 seq 只做一致性锚点；快照与事件 seq 不一致时 fail-closed。Secretary 的 ContextWindow 聊天历史不恢复。
8. **公共群目前只作为公开广播边界**：群内玩家对话、@ 触发、群内决策未接入。
9. **外部 MCP 仅本地单 supervisor**：远程 TLS/多租户/长期 SSE/Last-Event-ID 重放未验；不要把端口暴露公网。
10. **events.db 尚未成为完整领域事实源**：P2 已接 Post/Complaint/ActionPool 和 Taskflow 完整快照；P3 控制/审计事件与 P4 Ticket 快照已写；P5 包/批次命令事件已写并从 events.db 恢复。事件回放仍未覆盖全部领域，AgentLibEventSink 仍为旧读取端口兼容双写 payload/payload_json。
11. **P4/P5/P6/P8-A 边界与残留**：旧 ComplaintService 的 GmAide 只读工具与 ComplaintDistributor 仍兼容旧投诉，完整 MCP 旧 complaint 写面已隐藏，但旧投诉与新 COMPLAINT ticket 仍分裂；Ticket 全量快照/包命令事件未来可能触 4MB 上限，需归档/分页策略；APPLIED 写失败发生在包内存变更后，只 fail-stop，不宣称跨域原子；BATCH_COMPLETED 缺失时恢复只从 APPLIED 重建 batch，不主动补写缺失事件；跨实例并发 publication generate 仍可能因同确定性 ID/不同 createdAt 冲突而 fail-closed（当前单实例组合根）；终态/无 turn 的 COMMITTED batch 生成 publication 时 turnId=null，不会进入 turn 投影，语义待明确；已有 publication drafts 但 LLM 重试本身失败时仍返回 LLM/OUTPUT 错误。P8-A：空 players + control 关闭只 WARN，仍可启动但没有运行期加人入口；opening 只允许 PUBLIC，不允许 GM 任意创建私密推文；独立评审未完成，不能把 focused/full gate 当作 P8-A 阶段评审 PASS。
12. **P2/P3 迁移/控制残留**：旧 JSON 迁移使用 exact reconciled 完成标记并校验目标事件 seq/type/contentHash；运行期 taskflow-state.json 与 TASKFLOW_SNAPSHOT 双写为 best-effort，启动 ensureRecorded 追平 stale/missing；JSON 保存失败而 snapshot event 成功时，重启仍会在 anchor mismatch 处 fail-closed。P3 控制注册在 session 创建前先发布 route，session 创建失败可能短暂半状态；GM 文档写幂等仍是进程内，chmod 失败与 parent fsync 未硬化；旧 engine 全局 idempotency namespace 仍可能让旧写工具用其他命令的 key 返回假成功。事件查询面的 per-player visibility scope 尚未实现。
13. **配置侧删除身份不撤销 engine 身份**：按当前非目标“不删除玩家/推演”，从启动配置移除身份不会删除已恢复/动态注册身份；需在后续控制面明确撤销语义。
14. **P1 非阻断硬化点**：极端自重叠 secret 的掩码填充复杂度未做基准；chmod 故障注入、并发 close/append 压力、真实历史 events.db 迁移演练未跑。
15. **依赖/静态质量门禁有限**：当前 `verify` 主要有 enforcer + tests + shade，尚未接入 Spotless/Checkstyle/SpotBugs；不要把它等同于完整质量门禁。
16. **上游编译 warning**：AgentLib 注解在 javac 编译期有 `SuppressFBWarnings` 缺失 warning，不影响当前构建，但说明注解仅编译期处理。

## 7. 下一步

1. **P8 真实模拟**：真实 DSH/Hermes MCP、真实 LLM/NapCat/QQ，零玩家公共开场→审核发布→动态注册玩家→多玩家/多工单/多批次/发布/记录全链路；真实外部发送按 M-004 先与用户确认。
2. P7 已跳过；P8-A 独立评审未完成，需在真实模拟前决定是否补评审；未跑项继续标 `NOT_RUN`，不得用 Mock/loopback 代替真实联调结论。
