# NapCat / OneBot 11 对接契约（调研稿）

> **状态**：调查结论（官方文档转述；未连接真实 NapCat）
> **日期**：2026-10-24
> **主文档**：<https://doc.napneko.icu/config/basic>、<https://mintlify.wiki/NapNeko/NapCatQQ/api/network/websocket>、
> <https://mintlify.wiki/NapNeko/NapCatQQ/api/network/http>、<https://mintlify.wiki/NapNeko/NapCatQQ/api/onebot/overview>

## 1. 不变结论

- NapCat 通过 OneBot 11 约定暴露 QQ 的 HTTP/WebSocket 接口（来源：NapCat 文档）。
- 网络角色有四种：
  - HTTP 服务端：Bot 框架/NapCat 接受动作请求；
  - HTTP 客户端：NapCat 主动推事件到应用；
  - WebSocket 服务端（正向 WS）：NapCat 监听，应用主动连，双向收事件/发动作；
  - WebSocket 客户端（反向 WS）：NapCat 主动连应用，双向。
- 官方建议实时场景优先 WebSocket。
- 所有动作响应遵循 OneBot 11 格式：`status`、`retcode`、`data`、`message`/`wording`，WS 另有 `echo`。

## 2. 配置事实（官方文档）

### 2.1 正向 WebSocket Server

```json
{
  "network": {
    "websocketServers": [
      {
        "name": "WsServer",
        "enable": true,
        "host": "127.0.0.1",
        "port": 3001,
        "messagePostFormat": "array",
        "reportSelfMessage": false,
        "token": "",
        "enableForcePushEvent": true,
        "debug": false,
        "heartInterval": 30000
      }
    ]
  }
}
```

来源：<https://doc.napneko.icu/config/basic#通过-文件-配置OneBot服务>。

要点：

- `host=127.0.0.1` 是本项目推荐生产默认；公网部署必须启用 token。
- `messagePostFormat` 建议 `array`，比 CQ Code 字符串更易解析。
- `reportSelfMessage` 对本项目默认 `false`，避免 Bot 自己的消息再次触发回复循环；如果未来需要审计自己发出的消息再显式打开。
- NapCat 官方 WebSocket 文档把“事件 WS”和“API WS”分开展示：
  - 事件 WS：`ws://127.0.0.1:3001/?access_token=...`
  - API WS：`ws://127.0.0.1:3001/api?access_token=...`
- 因此 Adapter 不应把“同一个连接既能收事件又能发动作”写成不可替换的假设；传输策略可配置。

### 2.2 HTTP API

```json
{
  "network": {
    "httpServers": [
      {
        "name": "http-server",
        "enable": true,
        "port": 3000,
        "host": "127.0.0.1",
        "token": "your_secret_token",
        "messagePostFormat": "array"
      }
    ]
  }
}
```

动作：`POST http://127.0.0.1:3000/<action>`，响应为 OneBot 11 标准格式。
认证：`Authorization: Bearer <token>` 或 `?access_token=` 查询参数。
来源：<https://mintlify.wiki/NapNeko/NapCatQQ/api/network/http>。

### 2.3 鉴权

- 官方推荐 `Authorization: Bearer <token>`；也支持 `access_token` 查询参数。
- 本项目 Adapter 必须支持两种配置写法，但日志中绝不打印 token 或完整 URL 查询串。

## 3. 消息事件（入站）

通用字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `time` | number | Unix 秒时间戳 |
| `self_id` | number | 收到事件的 Bot QQ 号 |
| `post_type` | string | `message` / `notice` / `request` / `meta_event` |
| `message_type` | string | `private` / `group` |
| `sub_type` | string | `friend` / `group` / `normal` 等 |
| `message_id` | number | 消息唯一 ID |
| `message_seq` | number | 消息序号 |
| `user_id` | number | 发送者 QQ |
| `group_id` | number | 群号（群消息） |
| `message` | array \| string | 消息段数组（推荐）或 CQ Code 字符串 |
| `raw_message` | string | 原始文本/CQ Code |
| `sender` | object | `user_id`/`nickname`/`card`/`role` 等 |

来源：<https://mintlify.wiki/NapNeko/NapCatQQ/api/events/message-events>。

文字段示例：

```json
{
  "type": "text",
  "data": { "text": "Hello, bot!" }
}
```

- 本项目 v1 只处理 `message_type=private` 的文字段（若 Q1 确认私聊）；
- 图片/语音/文件/合并转发等非文字段：记录“收到不支持类型”，不自动下载/解析；
- 群消息若未来启用，需处理 `at` 段与 `group_id`，且必须区分“对 Bot 的呼叫”与普通群聊。

## 4. 发送动作

### 4.1 `send_private_msg`

请求：

```json
{
  "user_id": "123456",
  "message": "Hello!"
}
```

响应：

```json
{
  "status": "ok",
  "retcode": 0,
  "data": { "message_id": 12345678 }
}
```

### 4.2 `send_group_msg`

请求：

```json
{
  "group_id": "123456",
  "message": [
    { "type": "text", "data": { "text": "Hello " } },
    { "type": "at", "data": { "qq": "987654" } }
  ]
}
```

### 4.3 `send_msg`

- `message_type` = `private` 或 `group`；
- 分别使用 `user_id` / `group_id`；
- `message` 可为 string/CQ/array/single segment；
- `auto_escape` 仅对字符串消息有效；本项目用 array 文本段，避免 CQ 注入。

来源：<https://mintlify.wiki/NapNeko/NapCatQQ/api/onebot/message-actions>。

### 4.4 `get_login_info`

- 无参数；读取 Bot 自身 QQ 号与昵称，用于启动自检与账号映射。
- 来源：NapCat User Actions 文档；动作名与响应结构以实际 NapCat 版本为准，实施时先做 probe。

## 5. 传输策略建议

### 5.1 v1 首选：事件 WS + HTTP 动作

- 一条 WebSocket 只负责接收事件（根路径 `/`）。
- 动作走 HTTP POST（`send_msg`/`get_login_info` 等），避免维护第二条 WS API 与 `echo` 关联。
- 优点：
  - 事件流简单；
  - 动作请求/响应天然一一对应；
  - JDK `HttpClient` 直接支持；
  - 与旧参考实现相比少一个 echo 关联器。
- 代价：
  - 两个 NapCat network adapter 要同时启用（WS server + HTTP server）；
  - 事件与动作的 token 可分别配置。

### 5.2 备选：事件 WS + API WS

- 事件 WS 根路径；API WS `/api`。
- 需要强 `echo` 关联器、pending map、超时清扫、断线时全部失败。
- 若用户已经使用单一 WS 连接发送动作，也可作为 `apiMode=websocket-same` 适配策略；
  但必须在真实 NapCat 上验证根 WS 是否接受动作。
- **不把传输策略写进 core**；`NapCatTransport` 内部可替换。

### 5.3 Adapter 端口

```java
public interface MessagingAdapter extends AutoCloseable {
    String adapterId();
    void start(InboundHandler handler);
    CompletionStage<SendReceipt> send(OutboundMessage message);
    AdapterStatus status();
}
```

约束：

- `InboundMessage`：adapterId、selfId、messageId、userId、sessionType、text、receivedAt、dedupeKey；
- `OutboundMessage`：targetType、targetId、textSegments、idempotencyKey；
- `SendReceipt`：`SENT(messageId, at)` / `FAILED_BEFORE_SEND(reason)` / `UNKNOWN(reason)`；
- 领域层不得看到 `post_type`、`raw_message`、CQ Code、`echo`、NapCat 端口；
- `send` 必须做分段、限速与最大长度控制；超限文本在 Adapter/渲染层切段，不交给模型。

## 6. 可靠性要求

1. **去重键**：`selfId + messageId + subType`；群消息加 `groupId`。同一事件重投只处理一次。
2. **心跳**：NapCat 默认 `heartInterval=30000ms`；Adapter 应处理 meta_event heartbeat，并有自己的连接超时检测。
3. **重连**：指数退避 + jitter（参考旧实现 500ms → 30s；具体参数待实测），关闭时 fail 所有 pending 动作。
4. **消息顺序**：同一会话串行；不同会话可并发。Adapter 只负责投递，不保证跨会话顺序。
5. **发送结果**：HTTP 超时/连接中断 → `UNKNOWN`，默认不自动重试；`retcode != 0` → `FAILED` 并记录错误码（脱敏）。
6. **自消息抑制**：默认 `reportSelfMessage=false`；若打开，Adapter 按 `self_id` 与消息来源过滤，避免回环。
7. **速率限制**：按账号 + 目标（私聊/群）限速；发送路径只在 Adapter 一处实现，Agent/工具无法绕过。
8. **消息大小**：QQ 文本消息长度上限以实测为准；渲染层按字符/字节安全切段，优先在段落/换行处分段。

## 7. 与旧参考实现的关系

`~/DevMosire/iBotMosire` 已有一套可参考的：
- 正向 WS + `echo` 关联器：`ibotmosire-napcat/.../OneBotConnection.java`；
- 账号注册/保留账号、发送限速、事件模型、MockNapCatServer 测试；
- 但该仓的目标是“Agent 通过 MCP 操作 QQ/服务器”，与本项目“QQ 进入回合制世界”不同。

本项目可参考其协议处理与测试思路，不继承其进程拓扑、权限模型或 Agent 架构；不复制其配置/密钥文件。

## 8. 未验证清单

- [ ] 真实 NapCat 正向 WS 根路径能否同时接收事件和动作（决定是否需要 API WS）。
- [ ] HTTP 动作与 WS 事件混用时的 token 配置差异。
- [ ] `messagePostFormat=array` 对图片/表情/回复段的具体形状。
- [ ] 私聊消息长度分段阈值与速率限制真实值。
- [ ] 连接断开原因、重连与心跳超时行为。

> 以上均 `NOT_RUN`；官方文档只证明协议“支持”，不证明本项目接线正确。
