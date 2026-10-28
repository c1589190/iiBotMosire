# iiBotMosire 用户需求原话与裁定

> **状态**：有效（2026-10-28 重构）
> **历史原稿**：[`../../archive/legacy-game/specs/2026-10-24-user-requirements-source.md`](../../archive/legacy-game/specs/2026-10-24-user-requirements-source.md)
> **规则**：用户原话优先；历史 D-001..D-024 的完整正文已归档，本文只保留当前产品仍有效的裁定。

## 一、当前产品裁定

| 编号 | 主题 | 当前结论 |
|---|---|---|
| D-001 | QQ 接入与可替换 Adapter | 保留；OneBot 11 为当前协议，Adapter 必须可替换 |
| D-006 | Java + Maven | 保留 |
| D-009 | 文档体系先行 | 保留；设计/维护分账、唯一 STATUS、机械门禁 |
| D-010 | 私聊 + 群聊 | 保留；消息板按来源分 thread |
| D-019 | 外发最小间隔 | 保留；逻辑消息共享节奏限制 |
| D-023 | 正文约束只在 Skill/AGENTS.md | 保留；程序不得清洗、静默替换或 fallback |
| D-024 | Inbox 是来源未读桶 | 未读桶/预览/unreadCount 保留；Agent 手动 revision/snapshot ack 由 D-026 服务端游标替代 |
| D-025 | iibot 只做统一 QQ Agent 客户端 | 文游模块、旧 app 代码、旧运行区退出默认产品 |
| D-026 | 通用 QQ 工具 + 消息板服务端状态 | connect-agent 整合删除；多账号路由；read/ack 由服务端维护 |
| D-027 | debug/info 日志与易用配置 | 已实现 P2/P3；后续只维护 |
| D-028 | 依赖最新且可复现、文档一致 | 已完成 P6/P7a 主要依赖刷新；AgentLib 0.2.4 公开发布仍待上游 |

## 二、当前用户原话

### D-025 产品重新定位

> 「首先，iibot模块不额外提供程序化的文游服务，只作为给各类Agent软件提供一个统一QQ消息客户端的功能，因此文游相关模块可以标弃用删除了」

裁定：

1. iibot 默认产品是为各类 Agent 软件提供统一 QQ 客户端能力；
2. 文游/legacy 代码、旧 MCP、旧模拟区、旧配置退出默认构建和运行；
3. 物理删除后的历史由 Git 保留。

### D-026 消息板、通用 QQ 工具与内部标记

> 「那这玩意不应该做成现有iibot的上游实现吗，天然的MCP工具，为啥没接进去」

> 「你的意思是，iibot的消息板适配相关lib，自己独立实现了一套发消息，原本通用的qq对接lib给晾在一边了，导致现在Agent一个iibot都不能切换Onebot配置，工具调用也不统一？你不难猜我的需求吧，就是消息板这块怎么办，总不能让Agent自己维护一个额外的标记系统吧」

裁定：

1. 一个 iibot 进程必须支持多个已配置 OneBot/账号，入站按账号隔离，出站按 thread.accountId 路由；
2. 消息板是正文、历史、未读、幂等和发送状态唯一事实源；Inbox 只做唤醒/预览；
3. Agent 不维护 revision、snapshot、itemId、内部 seq 或必填幂等键；
4. 读取语义：`qq_conversation_read(threadId)` 只预览并记录服务端 deliveredSeq；
   Agent 处理完成后调用 `qq_conversation_ack(threadId)`，ack 不接受内部标记；
5. 默认 MCP 统一为 `qq_*` 业务工具；`iibot-connect-agent` 整合删除。

### D-027 debug/info 日志与配置系统

> 「此外还有之前一直没补的运维，包括完善的debug/info log、完善且易用的配置文件系统，规划一下，出修改计划，写文档」

裁定：

1. 日志覆盖 INFO 运维事件和可配置 DEBUG 细节；技术日志与 ops 事件分文件；
2. 日志只记录 ID、状态、计数、错误码、耗时和参数 key，不记录正文、token、Authorization、完整协议 JSON；
3. 配置严格、可解释、可覆盖、可脱敏输出、启动前可诊断，并支持多账号；
4. 首次生成、schema、示例和 README 让用户不读源码即可启动；
5. 密钥只通过环境变量名引用。

### D-028 依赖与文档基线

> 「其次，iibot、inbox依赖的agentlib是最新版本吗？去调查一下，项目设计基本上没有其他花头了，主要就是依赖是否为最新版本、文档是否完善等」

> 「1 继续用0.2.4」

裁定：

1. iibot 与 inbox 固定 `agentlib-mosire:0.2.4`，不降级；
2. 上游 0.2.4 未公开 tag/Pages 属于 `PUBLICATION_GAP`，干净仓库可复现前必须补发布；
3. 其他依赖按最新稳定版收口；JUnit 6 单独迁移；
4. 文档必须与统一 QQ 客户端目标一致，旧文游文档归档，不进入当前导航。

## 三、当前验收判据

- 默认 reactor 只有 `iibot-connect` / `iibot-bot` / `iibot-app`；
- 默认 Agent MCP 工具名统一为 `qq_*`，不出现 `bot_*` / `tf_*` 业务工具；
- 多账号消息按 accountId 隔离，出站按 thread.accountId 路由；
- Agent 只传 threadId 即可 read + ack，不传 revision/snapshot/itemId/内部 seq；
- 日志 INFO/DEBUG 可配置，正文/token/Authorization 零命中；
- 配置有 schema、严格错误定位、脱敏有效配置和单文件首次生成；
- docs gate 与 full gate 通过；真实外部联调未跑写 `NOT_RUN`。

## 四、当前未裁定

1. 多 consumer 并发隔离与 per-consumer Inbox 绑定；
2. 是否提供宿主/运维侧的 `qq_account_use` 动态默认账号工具；
3. `qq_inbox_binding_descriptor` 是否完全移出 Agent MCP，改由宿主专用 handoff；
4. 回复成功后是否自动 ack（当前第一版统一要求显式 ack）。

历史 D-001..D-024 的详细正文与所有旧原话见归档原稿。
