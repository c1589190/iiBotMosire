# LLBot / OneBot 11 迁移与兼容性记录

> 更新时间：2026-10-25（Asia/Shanghai）
> 当前 QQ 后端：LLOneBot 8.3.2，OneBot v11。
> 凭据只存在于本机环境文件，不写入仓库。

## 1. 传输配置

LLBot Docker 目录：

```text
/home/cna/LLBot-Docker
```

当前配置：

| 项 | 值 |
| --- | --- |
| WebSocket | `ws://127.0.0.1:3001/` |
| HTTP | `http://127.0.0.1:3000/` |
| OneBot 版本 | v11 |
| 消息格式 | `array` |
| 鉴权 | Bearer header 或 `access_token` 查询参数 |
| app 连接方式 | `NapCatAdapter`，HTTP 动作 + 正向 WS |

iibot 本机环境文件：

```text
~/.config/iibot/napcat.env
```

该文件权限 `0600`，只保存本机 token 与 URI，不进入 Git。

## 2. 已验证的只读能力

| 动作 | 结果 |
| --- | --- |
| `get_login_info` | HTTP 200 / retcode 0 |
| `get_group_list` | HTTP 200 / 15 个群 |
| `get_friend_list` | HTTP 200 / 12 个好友 |
| `get_group_member_list` | HTTP 200 / 当前测试群 3 人 |
| `get_group_msg_history` | HTTP 200 / messages |
| `get_friend_msg_history` | HTTP 200 / messages |
| `get_msg` | HTTP 200 / 群消息详情 |
| `get_status` | `online=true`, `good=true` |
| `get_version_info` | LLOneBot 8.3.2 / OneBot v11 |
| WebSocket 根路径 | Bearer header 与 query token 均握手 101 |
| 事件上报 | 实测 `meta_event` 与 `message.group` |

## 3. 已知差异与兜底

LLBot 8.3.2 当前没有：

```text
get_recent_contact
```

请求会返回 HTTP 404 / `get_recent_contact API 不存在`。

iibot 适配器的处理：

- `recentConversations` 检测到 `HTTP_404` 或 `ONEBOT_RETCODE_404` 时，
  回退到 `get_group_list`；
- 因此 `tf_qq_chat_list scope=recent` 在 LLBot 上返回已加入群目录，
  不是真正的“最近联系人”顺序；
- `scope=groups` / `scope=friends` / `scope=configured` 正常；
- 真实聊天历史使用 `get_group_msg_history` / `get_friend_msg_history`，正常。

## 4. 外发可行性

以下 HTTP action 已确认存在并做参数校验：

```text
send_private_msg
send_group_msg
send_msg
```

空请求会返回 `retcode=400`（缺少 `message`），说明路由存在；
本轮**没有执行真实 QQ 外发**，真实发送仍需用户明确目标与验收内容。

## 5. 配置与部署结果

- `IIBOT_NAPCAT_EVENT_WS_URI=ws://127.0.0.1:3001/`
- `IIBOT_NAPCAT_HTTP_BASE_URI=http://127.0.0.1:3000/`
- `IIBOT_NAPCAT_ACCESS_TOKEN` 已更新为本机 LLBot token；
- app 已重启并成功建立 LLBot WS 连接；
- `simulation/dist` 使用兼容兜底后的 jar：
  `484bebc2d486353c9cf9367838a9b415b7f0c60d7e486dd90da2c087c913d3cb`；
- `tf_qq_chat_list scope=recent`、`groups`、`configured` 均返回 OK；
- `tf_qq_history_read` 与 `tf_qq_message_get` 通过 LLBot 正常返回；
- DSH Connector 健康检查 PASS。

## 6. 风险

1. `scope=recent` 在 LLBot 上是群目录兜底，不具备真实的最近会话时间排序。
2. 真实群发/私聊尚未执行，三态回执与临时私聊路径仍需在明确目标下 smoke。
3. LLBot 当前监听 `0.0.0.0:3000/3001`，应确认宿主防火墙/网络边界；
   token 不能进入仓库、日志或模型上下文。

## 7. 主动群临时会话限制（2026-10-25 实测）

LLBot 8.3.2 **不支持 Bot 主动发起 QQ 群临时会话**。

本次失败链路：

- iibot 发出 `send_private_msg {user_id, group_id}`；
- LLBot 解析到目标不是好友，进入 `TempC2CFromGroup`；
- 但 LLBot 的 `sendMsg` 只有在本地 `temp_chat_info` 已存在时才保留临时会话类型；
- 没有 `temp_chat_info` 时会降级为普通 C2C，QQ 返回：
  `发送消息失败 (code=16): 发送失败，请先添加对方为好友`；
- HTTP 动作层把该错误编码成 `retcode=200`，旧适配器因此只显示
  `ONEBOT_RETCODE_200`。

LLBot 上游已明确表示不支持主动临时会话；只有目标先从官方客户端主动发起过一次
临时会话后，LLBot 才会缓存 `temp_chat_info`，之后同一目标的
`send_private_msg {user_id, group_id}` 才能成功：

- LLOneBot issue #725：主动临时会话不支持；
- LLOneBot issue #752：需要官方客户端先主动发起一次；
- LLOneBot issue #706：未添加好友时返回同一 code 16。

### iibot 侧修复

- 适配器现在保留服务端错误文本；
- 对 `temporaryGroupId != null` 且错误包含“先添加对方为好友”的拒绝，映射为稳定错误：

```text
LLBOT_TEMP_CHAT_REQUIRES_INBOUND
```

- 不再把它伪装成普通 `ONEBOT_RETCODE_200`；
- 不会自动重试，不会自动改发群消息。
- 修复后 full gate：619 tests；target jar `f9f2e448...` 尚未部署，
  当前活动局仍运行 `484beb...`；该错误映射只影响后续失败诊断，
  当前帖子的恢复仍按下面“先私聊、再显式重试”执行。

### 当前恢复流程

1. 让目标玩家在群内点 Bot 头像，进入临时会话并发送任意一句私聊；
2. LLBot 收到这条入站 `TempMessage` 后会写入 `temp_chat_info`；
3. GM 用 `tf_post_get` 读取当前失败帖 revision；
4. 调用 `tf_post_retry_delivery` 显式重试；
5. 若仍失败，改为添加好友后发送，或由真人 GM 在群内做一次不含私密内容的
   “请先私聊 Bot 领取内容”提示，不得把私密开局卡正文发到群里。
