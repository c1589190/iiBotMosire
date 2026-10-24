# MCP 平台级 QQ 能力与自主入团实现快照

> **状态**：历史快照（2026-10-24）。**当前状态入口**：[`../../agents/STATUS.md`](../../agents/STATUS.md)。
> **日期**：2026-10-24
> **对应需求**：D-013 / D-014 / D-017
> **计划**：[`../plans/2026-10-24-mcp-external-authoring-and-qq-chat-access-plan.md`](../plans/2026-10-24-mcp-external-authoring-and-qq-chat-access-plan.md)

## 1. 本轮实现

### 1.1 connectlib 平台能力

新增 `QqPlatformCapability` 及协议中立记录：

- `ChatConversation`
- `GroupMember`
- `ChatMessageDetail`
- `ChatHistoryPage.nonTextSkipped`

`NapCatAdapter` 实现：

- 最近会话：`get_recent_contact`
- 群列表：`get_group_list`
- 好友慢路径：`get_friend_list`
- 群成员：`get_group_member_list`
- 按消息 ID 取消息：`get_msg`
- 临时私聊发送：`send_private_msg` + `group_id`
- 群发送：`send_group_msg`
- 历史读取继续使用 `get_friend_msg_history` / `get_group_msg_history`

能力面只接受 typed 参数，不暴露 raw OneBot action/JSON。

### 1.2 MCP 工具面

默认模拟装配 77 个工具；显式开启 `control.platform.allowArbitrarySend=true` 后 79 个。

新增：

- `tf_qq_chat_list`
- `tf_qq_group_members`
- `tf_qq_message_get`
- `tf_post_compose`
- `tf_player_onboard_from_group`
- 条件启用：`tf_qq_send_private`、`tf_qq_send_group`

改造：

- `tf_qq_history_read`：
  - 省略目标默认当前推演群；
  - 任意 Bot 可访问私聊/群；
  - 不再要求已注册玩家/当前公共群；
  - 返回 `nonTextSkipped`；
  - 只给 targetId 时可自动解析类型。

### 1.3 外部自由发帖

`ExternalPostService`：

- `DRAFT`：创建 `PENDING_APPROVAL`；
- `PUBLISH_NOW`：创建 → 批准 → 发布；
- 不依赖 `ActionBatch` / `PublicationOrchestrator` / app 内 LLM；
- 复用 PostService 幂等、审计、正文守卫和 PostDistributor 送达状态；
- PUBLIC 目标为当前推演群，PRIVATE 目标为已注册玩家。

### 1.4 公屏自主入团

`PlayerOnboardingService`：

1. 校验 supervisor；
2. 校验 `sourceGroupId` 在 onboarding allowlist；
3. 通过 `get_msg` 校验来源消息存在、群号匹配、发送者 QQ 匹配；
4. 默认要求报名/加入 opt-in 文案；
5. 校验玩家上限；
6. 生成稳定 `player-<hash>`；
7. 注册玩家并绑定秘书；
8. 通过来源群临时私聊发送报名卡；
9. 使用 delivery ledger：单 QQ 只发送一次，`UNKNOWN` 不自动重发。

D-017 例外：通过上述策略时，不要求 app 内 Agent 审核或逐次真人确认。

### 1.5 权限策略

新增 `control.platform`：

```json
{
  "allowArbitrarySend": false,
  "onboardingEnabled": true,
  "onboardingAllowedGroupIds": [],
  "onboardingRequireOptIn": true,
  "onboardingMaxPlayers": 64
}
```

默认空 allowlist 表示只允许当前 `publicGroupId`；`["*"]` 表示全部 Bot 可访问群。

### 1.6 工单板长轮询（D-018）

- `TicketService` 增加变更版本 `changeVersion`、原子 `stateView()` 与
  `awaitChange(version, timeout)`。
- `tf_ticket_board` 增加 `wait` / `waitSeconds`：
  - 已有 OPEN/CLAIMED/CONFIRMED 立即返回；
  - 否则阻塞到下一次成功工单变更/新工单到达或超时；
  - 默认 300 秒、上限 1800 秒；超时返回 `waitTimedOut=true`；
  - 只有成功 commit 才唤醒，存储失败不唤醒。
- 测试：`TicketServiceChangeWaitTest`、`GmTicketBoardToolWaitTest`。

## 2. 验证证据

```text
tools/gate.sh full
PASS
604 tests / 0 failures / 0 errors / 0 skipped
connect 36 / connect-agent 21 / taskflow 251 / app 296
```

shaded jar：`iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar`
sha256 `36be71e69743e89e6c94285613409c8ada55a53a27c2c8777bcb9b3dd66374e8`

`java -jar ... --help` rc=0。

## 3. 未验证 / 风险

1. **真实 NapCat smoke NOT_RUN**：没有用新 jar 对真实 QQ 群/私聊执行工具调用。
2. **真实自主入团 NOT_RUN**：没有向真实公屏玩家发送报名卡。
3. **好友列表慢**：本机 `get_friend_list` / `get_friends_with_category` 超过 40 秒未返回；
   `scope=friends/all` 可能返回 partial + `FRIENDS_TIMEOUT`。
4. **报名卡回执不跨重启细节化**：delivery ledger 防止重复发送，但历史回执只返回
   `ALREADY_ATTEMPTED`，没有独立持久化消息 ID 查询工具。
5. **任意发送工具默认关闭**：开启后依赖 delivery ledger 和 adapter 三态，未做真实长跑。
6. **平台边界**：仍只能读取 Bot 参与私聊/已加入群；未加入群和他人私聊不可读。
7. **跨群入团后的后续私信路由**：报名卡使用来源群临时会话；但 `tf_post_compose`
   的 PRIVATE 仍走配置里的全局 `napcat.privateTempGroupId`。若玩家来自其他群且与 Bot
   非好友，后续普通私信可能需要显式 `tf_qq_send_private` 并传 `temporaryGroupId`，
   或后续补齐玩家来源群路由。
8. **工单板长轮询受 MCP client 超时约束**：默认 300 秒；若 DSH 自身请求超时更短，
   需要降低 `waitSeconds` 或调整 MCP client 超时。真实 DSH 长等待 smoke `NOT_RUN`。

## 4. 部署状态

- `simulation/dist` 仍是旧 jar；
- 当前已无 app 进程，`127.0.0.1:8765` 空闲；这不是本轮停止或替换造成的；
- 本轮未重启、未替换 `simulation/dist`、未发送任何真实 QQ 消息；
- 新 jar 只在 `iibot-app/target/`。

发布前需按用户协同流程停止旧 app、备份 runtime、替换 jar、重启并做真实只读 smoke；
自主入团和任意发送的真实外发仍需用户明确目标与验收内容。
