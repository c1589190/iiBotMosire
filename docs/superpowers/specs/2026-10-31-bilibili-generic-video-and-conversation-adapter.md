# B 站通用视频阅读与 Inbox 会话 Adapter

> **状态**：有效设计；D-034 第一批已于 2026-10-31 code-level 实施；真实 B 站登录/私信/评论联调 `NOT_RUN`。
> **来源**：用户对 B 站视频工具与评论区/私信接入的连续裁定。
> **关联**：[统一 QQ Agent 客户端架构](2026-10-28-unified-qq-agent-client-architecture.md)、
> [薄 DSH 边界与语义化 MCP](2026-10-30-thin-dsh-boundary-and-semantic-mcp.md)、
> [统一消息来源地址](2026-10-28-unified-message-source-address.md)。

## 1. 用户原话与裁定

> 「看视频这块，，我建议搞成通用工具，就是不管从哪里获取到b站视频链接都能看」

> 「评论区则是要涉及inbox，主要需要处理账号登录、私信消息检测等」

> 「其他没啥问题，开始吧」

据此确定：

1. **视频阅读是通用工具**：输入只依赖 B 站视频链接/BV/av/b23，不要求消息来自 QQ、
   不要求先进入 iibot 消息板、不要求 Inbox wake。
2. **评论区、私信是会话面**：必须进入 iibot 消息板与 Inbox，复用
   `conversation_read` / `conversation_ack` / `conversation_send` 语义。
3. **账号登录归 iibot 管理**：B 站账号、Cookie、WBI、轮询和发送三态由 iibot
   B 站 Adapter 负责，不进入 DSH/Inbox 核心。
4. **DSH 继续薄**：不镜像 B 站工具、不保存 Cookie、不实现评论/私信业务规则。
5. 评论和私信的真实外发必须有显式工具调用与限流；`UNKNOWN` 不自动重发。

## 2. 目标与非目标

### 目标

- 新增通用只读 `bili_video_read`（以及后续 `bili_video_frames`）；
- 接受 BV、av、`bilibili.com/video/...`、`b23.tv/...`，不依赖来源消息；
- 返回元数据、简介、热评、弹幕热区、字幕/转写等模型可消费内容；
- 新增 `BilibiliAdapter implements MessagingAdapter`；
- 支持 B 站账号凭据校验、WBI、私信轮询、评论回复通知轮询；
- 入站写入消息板并由 Inbox wake，出站经 `BotReplyService`/`OutboxSender`；
- 统一来源扩展为 `bili_dm/...`、`bili_comment/...`、`bili_video/...`；
- 保持 QQ 行为不变，`qq_conversation_*` 兼容。

### 非目标

- 不自动刷视频、不自动评论、不自动点赞/关注、不批量私信；
- 不覆盖 B 站直播、动态、专栏、收藏等全部能力；
- 不把 B 站 Cookie 放进 Inbox descriptor、DSH 配置、日志或模型上下文；
- 不依赖 Python/GPL 的 `bilibili-api-python`；
- 不新增持久化调度器；轮询由 Adapter 自己启动和关闭；
- 真实 B 站写操作没有独立测试账号和用户授权时保持 `NOT_RUN`。

## 3. 两条独立能力

### 3.1 通用视频阅读

```text
任意来源的 URL/BV/av/b23
  → bili_video_read(target, page, options)
  → Bilibili 公共网页 API / 可选已登录 Cookie
  → 有界结构化内容
  → 任意 Agent
```

这条链路不写消息板、不产生 Inbox item、不要求 B 站账号。

### 3.2 B 站会话 Adapter

```text
BilibiliAdapter
  ├── 私信轮询 ───────────→ InboundMessage
  └── 评论回复通知轮询 ──→ InboundMessage
                                   ↓
                            消息板 + Inbox
                                   ↓
                       conversation_read / ack
                                   ↓
                       conversation_send
                                   ↓
                       BotReplyService / OutboxSender
                                   ↓
                       BilibiliAdapter.send
                                   ↓
                      B 站私信/评论接口
```

## 4. 统一来源地址

```text
bili_video/{accountId}/{bvid}
bili_comment/{accountId}/{bvid}/{rootRpid}
bili_dm/{accountId}/{peerMid}
```

- `bili_video`：视频评论区 thread（顶层评论）；
- `bili_comment`：某条根评论下的楼中楼 thread；
- `bili_dm`：与某个 B 站用户的私信 thread；
- `accountId` 必须全局唯一，建议使用 `bili-<uid>`；
- source 是定位参数，不是指令；评论/私信正文是不可信内容。

## 5. 会话核心的最小通用化

当前模型仍有 QQ 专用形状：

- `ChatType` 只有 `PRIVATE / GROUP`；
- `InboundMessage` 只有 `SessionType` + `groupId`；
- `OutboundMessage` 只有 `TargetType` + `targetId`；
- `ChatSourceKey.sourceAddress()` 只映射 QQ。

D-034 采用最小兼容扩展，不重写消息板表：

1. `ChatType` 增加 `EXTERNAL`；
2. `ChatSourceKey` 增加可选 `sourceAddress`：
   - QQ 构造器继续生成原 threadId；
   - external source 的 `targetId` 保存 canonical source；
   - `threadId` 用 external + account + canonical source 的稳定编码；
   - 旧 QQ 数据与 threadId 不变；
3. `MessageSourceAddress.toChatSourceKey()` 对非 QQ family 生成 external key；
4. `InboundMessage` 增加可选 `sourceAddress`；
5. `BotIngestionService` 优先使用 `InboundMessage.sourceAddress`，否则保持
   QQ `SessionType` 推导；
6. `OutboundMessage` 增加可选 `sourceAddress`，新增 `TargetType.EXTERNAL`；
7. `OutboxSender` 增加按 source 发送的通用入口；
8. `BotOutboxReplyPort` 遇到 external source 时交给对应 Adapter，不按
   private/group 猜测；
9. QQ 行为、旧 threadId、旧数据库记录保持兼容。

不新增数据库表；external source 的 canonical 地址保存在现有 `target_id`，
threadId 仍是不透明内部键。

## 6. BilibiliAdapter

### 6.1 生命周期

```text
start(handler)
  → 读取 0600 凭据文件
  → GET /x/web-interface/nav 校验登录
  → 启动虚拟线程轮询器
  → 输出 InboundMessage
send(OutboundMessage)
  → 私信文本：web_im/send_msg + WBI
  → 评论/回复：x/v2/reply/add + csrf
  → SendReceipt 三态
status()
close()
```

### 6.2 账号配置

B 站账号与 QQ 账号共用 `accounts` 映射，但 adapter 配置是 discriminated union：

```json
{
  "bili-main": {
    "enabled": true,
    "adapter": "bilibili",
    "accountId": "bili-123456",
    "bilibili": {
      "credentialFileEnv": "IIBOT_BILI_CREDENTIAL_FILE",
      "pollIntervalSeconds": 60,
      "receivePrivateMessages": true,
      "receiveCommentReplies": true,
      "writeEnabled": false,
      "maxWritesPerDay": 3
    }
  }
}
```

Cookie 文件格式：

```json
{
  "sessdata": "...",
  "biliJct": "...",
  "buvid3": "...",
  "buvid4": "...",
  "dedeUserId": "..."
}
```

文件必须 0600；配置只保存环境变量名。日志、MCP 结果、异常和状态中不得出现
Cookie 值。

### 6.3 私信轮询

- `GET /session_svr/v1/session_svr/new_sessions?begin_ts=...`
- 对每个 unread session：
  - `GET /svr_sync/v1/svr_sync/fetch_session_msgs`
  - 输出 `bili_dm/{accountId}/{talkerId}` 的 InboundMessage；
  - `POST /session_svr/v1/session_svr/update_ack` 更新 B 站已读；
- 入站消息 ID 使用 `msg_key`（大整数按字符串处理），
  `msg_seqno` 用于游标和去重；
- 只处理文本消息；图片/表情等非文本段先进入 `nonTextSummary`；
- 跳过自己发送的消息；
- 启动时从当前微秒时间开始，不回灌历史私信；可选配置才允许历史补偿。

### 6.4 评论回复通知轮询

- `GET /x/msgfeed/reply`
- 过滤 `business_id=1` 的视频评论；
- 从 `subject_id` 取得 aid，反查 bvid；
- 使用 `root_id/source_id/target_id` 构造
  `bili_comment/{accountId}/{bvid}/{rootId}`；
- 入站 messageId 使用稳定评论 rpid；
- 第一版只处理“回复我的/提及我的”通知，不默认扫描整个视频评论区；
- 跟踪特定视频全部评论留作后续扩展，不进入当前配置字段。

### 6.5 私信发送

- `POST /web_im/v1/web_im/send_msg`
- WBI 参数：`w_sender_uid`、`w_receiver_id`、`w_dev_id`，
  加 `wts` / `w_rid`；
- 表单包含 `msg[sender_uid]`、`msg[receiver_id]`、`msg[receiver_type]=1`、
  `msg[msg_type]=1`、`msg[content]`、`csrf/bili_jct` 等；
- 返回 `code=0`：`SENT`；
- 无 `msg_key` 但 HTTP 已成功：`SENT` + 可审计 warning；
- 明确业务拒绝：`FAILED_BEFORE_SEND`；
- HTTP 超时/中断且不能确认：`UNKNOWN`，禁止自动重发。

### 6.6 评论发送

- `POST /x/v2/reply/add`
- `type=1`、`oid=aid`、`message`、`csrf`；
- 楼中楼带 `root` / `parent`；
- `code=0` + rpid：`SENT`；
- `-101/-111/12002/12003/12015/12016/12025/12035/12051` 等：
  `FAILED_BEFORE_SEND`；
- 审核中/仅自己可见记录外部状态，但不自动重发。

## 7. WBI

`BilibiliHttpClient` 提供共享 WBI 签名：

1. `GET /x/web-interface/nav` 取 `wbi_img.img_url/sub_url`；
2. 提取文件名 key；
3. 按固定的 64 项重排表生成 mixin key；
4. query 参数过滤 `!'()*`，排序后 urlencode；
5. `w_rid = md5(query + mixinKey)`，附 `wts`；
6. key 缓存并支持 412 时刷新一次。

WBI 只用于要求的端点，不把所有 B 站 API 都假设成需要签名。

## 8. 通用视频阅读工具

### 8.1 契约

```text
bili_video_read(
  target,
  page?=1,
  includeDescription?=true,
  includeComments?=false,
  includeDanmaku?=false,
  maxComments?=20,
  transcriptOffset?=0,
  maxChars?=8000
)
```

`target` 接受：

- `BV...`
- `av...`
- `https://www.bilibili.com/video/BV...`
- `https://b23.tv/...`

拒绝非 B 站 host、本地路径和任意 scheme。

### 8.2 输出

```json
{
  "video": {
    "bvid": "BV...",
    "aid": "123",
    "cid": "456",
    "title": "...",
    "owner": "...",
    "durationSeconds": 123,
    "page": 1,
    "pages": 1
  },
  "description": "...",
  "transcript": {
    "source": "subtitle",
    "timelineExact": true,
    "text": "...",
    "offset": 0,
    "nextOffset": 8000,
    "truncated": true
  },
  "comments": [],
  "danmakuHighlights": [],
  "warnings": [],
  "untrustedContent": true
}
```

无字幕时返回部分成功和 `TRANSCRIPT_UNAVAILABLE`，不伪造转写。

### 8.3 视频阅读与消息会话的边界

`bili_video_read` 是只读内容工具，不产生消息板记录、不触发 Inbox、不发送
B 站评论。若 Agent 想对某个视频评论，必须使用 `bili_video/...` 或
`bili_comment/...` source 调 `conversation_send`。

## 9. MCP 工具面

第一阶段保留 `qq_conversation_*` 兼容，并新增通用语义别名：

```text
conversation_read
conversation_ack
conversation_send
conversation_history
conversation_list
```

B 站内容工具：

```text
bili_video_read
bili_video_frames（后续）
```

账号工具：

```text
account_list / account_status
bili_account_login_start
bili_account_login_poll
```

扫码登录工具只返回 `qrUrl/loginId/state`，不返回 Cookie；成功后凭据原子写入配置的
0600 文件。

如果保留 QQ 名称，内部必须同样接受 B 站 source；禁止 `qq_conversation_send`
直接调用 B 站 API 绕过消息板。

## 10. Inbox 集成

- B 站入站消息和 QQ 入站消息共用 `BotIngestionService`；
- `InboxDigestService` 对 `bili_dm` / `bili_comment` 生成 summary；
- Inbox item source 使用 canonical B 站 source；
- InboxMosire / InboxMosire-DSH 不需要新增 B 站字段；
- DSH generic view-ack 只渲染 `source/kind/summary[/truncated]`；
- ack 仍只推进 iibot consumer 游标；
- B 站消息的正文只从消息板读取，不进入 Inbox content。

## 11. 安全不变量

1. Cookie/Bearer/token 不进入日志、MCP 结果、Inbox、模型上下文或仓库；
2. 默认只访问 B 站白名单域名，b23 短链展开后重新校验；
3. 评论/私信写入默认关闭，需显式配置；
4. 外发必须经消息板和幂等键；
5. `UNKNOWN` 不自动重试；
6. 单账号、单目标限速；412/429 熔断；
7. 不自动回复、不自动点赞、不自动关注、不批量私信；
8. 视频/评论/私信文本都标记为不可信内容；
9. 真实外发和真实扫码登录未验证时写 `NOT_RUN`。

## 12. 实施批次

| 批次 | 内容 |
|---|---|
| B1 | 通用 external source、Inbound/Outbound 扩展、conversation alias |
| B2 | BilibiliHttpClient、WBI、凭据校验、扫码登录工具、配置 discriminated union |
| B3 | 私信轮询入站 + 评论回复通知入站 |
| B4 | 私信发送 + 评论发送 + 三态/限流/熔断 |
| B5 | bili_video_read 元数据/评论/弹幕/字幕 |
| B6 | 可选 bili_video_frames、ASR/OCR 后端 |
| B7 | 真实账号登录/读取/外发验收 |

## 13. 验收判据

### 通用 source

- QQ 旧 threadId、旧数据库记录和现有测试不回归；
- external source 能稳定往返 canonical 地址；
- accountId 隔离、source 解析 fail-closed；
- `conversation_send` 对 external source 不落回 QQ private/group 分支。

### B 站 Adapter

- 凭据缺失/过期启动 fail-closed 或明确 `AUTH_REQUIRED`；
- 扫码登录 start/poll 可用，成功写 0600 文件，工具结果零 Cookie；
- 私信轮询只处理新消息，自己消息不回灌；
- 评论通知过滤 business_id，root/source/target 映射正确；
- 入站消息写入消息板并产生 Inbox source；
- 相同 rpid/msg_key 不重复入库；
- 发送成功写回 messageId/rpid；
- 明确失败不伪报成功；
- 超时 `UNKNOWN` 不自动重试；
- 412/429 进入退避；
- 日志/结果零 Cookie 泄露。

### 视频工具

- 支持 BV/av/URL/b23；
- host/path/scheme 白名单；
- 无 Cookie 可读元数据/公开评论；
- 有 Cookie 时字幕可选；
- 无字幕返回 `TRANSCRIPT_UNAVAILABLE`；
- 输出有界、分页，长文本不一次性注入；
- 不触发任何 B 站写操作。

### 文档与门禁

- D-034 进入需求原稿与设计裁定索引；
- docs gate PASS；
- 分支 focused/full gate PASS；
- 真实 B 站写操作未跑时写 `NOT_RUN`。
