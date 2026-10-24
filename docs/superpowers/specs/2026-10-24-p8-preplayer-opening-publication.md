# P8-A：零玩家启动与 GM 独立开场推文

> **状态**：有效（P8 真实模拟前置修复）
> **日期**：2026-10-24
> **用户裁定**：允许 `players=[]` 启动；推演/GM Agent 可先把公共开场推文写入公共群，再通过 MCP 载入玩家。
> **范围**：配置契约、零玩家启动、GM 独立 PUBLIC 开场推文、records/openings 投影；不改变既有批次发布语义。

## 1. 配置契约

允许：

```text
directors >= 1
players = []
publicGroupId 非空
```

规则：

- `directors` 仍必须至少一个，保证 bootstrap director、自动冻结和控制面权威锚点；
- `players=[]` 允许启动；
- `players=[]` 且 `control.enabled=false` 只记录 WARN，不拒绝启动；该配置没有运行期加人入口；
- `players=[]` 且 `control.enabled=true` 是标准“先公共开场、后注册玩家”流程；
- 仍要求 `players` 字段显式存在，即使是 `[]`。

## 2. 独立开场推文

新增 GM 工具：

```text
tf_publication_opening
```

参数：

```json
{
  "title": "雾灯港·序章",
  "body": "潮水退去后……",
  "idempotencyKey": "opening-001"
}
```

语义：

- 仅 GM/DIRECTOR/SUPERVISOR；
- 只创建 `PUBLIC` 推文；
- author 固定 `system:publication`；
- 状态 `PENDING_APPROVAL`；
- 不直接发 QQ，仍必须经 `tf_post_decide` + `tf_post_publish`；
- 不要求 ActionBatch、不要求玩家、不创建假回合；
- 同一 `(gm actorId, idempotencyKey)` 确定性 publicationId；重复调用返回首次 record，不重复建 post；
- publication event content 为完整 `PublicationRecord`，summary 不含正文；
- `PublicationRecord.kind=OPENING`，`batchId=null`，`turnId=null`，`privatePostIds/privateOutcomes` 为空；
- 旧 BATCH record 兼容：反序列化缺失 kind 视为 `BATCH`，batchId 仍必须非空。

## 3. 发布与记录

发布仍使用：

```text
tf_post_list / tf_post_get
tf_post_decide
tf_post_publish
```

Records：

```text
records/openings/<publicationId>/
  index.md
  public-narrative.md
  posts/<postId>-PUBLIC.md
```

- OPENING 不进入 `records/turns/*`，不伪造 turnNo；
- `tf_record_query` 支持 `kind=opening`，返回 publicationId/postId/hash 摘要，不返回正文；
- 既有 turn 记录结构不变。

## 3.1 私聊失败重试

- NapCat 私聊可配置 `napcat.privateTempGroupId`：当目标不是好友时，使用
  `send_private_msg {user_id, group_id}` 走群临时会话；目标必须在来源群中且 QQ 允许临时会话。
- `tf_post_retry_delivery`：只允许把 PUBLISHED 且 `deliveryState=FAILED` 的推文重置为 PENDING，
  由 afterMutation 的 PostDistributor 再尝试一次；UNKNOWN/SENT 不得自动重发。
- `OutboxSender` 的普通 ACK、私聊推文、投诉回应、回合私聊结果统一使用同一临时会话群配置。

## 4. 失败语义

- 配置 `players=[]` + directors 非空 => 加载成功；
- directors 空 => 仍启动失败；
- opening 输入空/超长/标题或正文非法 => 零 post；
- opening 事件 append 失败 => 重试按 publicationId 回查，不重复建 post；
- opening 仍保持 PENDING_APPROVAL，任何自动发送都禁止；
- 玩家注册/秘书/ACK/私聊路由沿用现有动态控制路径，不新增第二套身份表。

## 5. 验收

- 零玩家启动，MCP 65→67 工具；
- 零玩家时 `tf_publication_opening` 创建 PUBLIC PENDING_APPROVAL 草稿；
- GM approve/publish 后只发公共群，不需要玩家 QQ 映射；
- 重复 opening 调用/重启不重复 post/event；
- 发布后可 MCP 注册玩家、绑定秘书、开回合；
- 动态玩家重启后恢复 workspace/session/ACK/路由；
- records/openings 幂等投影，hash 可对账；
- full gate 与阶段评审 PASS。
