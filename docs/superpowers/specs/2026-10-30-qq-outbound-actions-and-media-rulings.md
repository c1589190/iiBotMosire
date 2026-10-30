# QQ 出站动作、富媒体与高风险能力裁定设计

> **状态**：D-031 已由用户拍板；3A–3D code-level 已实现（2026-10-30），真实外部副作用 `NOT_RUN`。
> **用户原话/选择**：
> > 「允许 Agent 常规使用」
> > 「普通 Agent 随时可用」
> > 「允许主动私聊任意人」
> > 「先做一个音频提取，后续写模型识别配置等，主要是现在我手头上没有」
> > 「宿主或系统调度器负责（推荐）」
> > 「不用禁止绕过MCP，但是MCP层面的发图片/表情/文件必须做」
> > 「实现，并且Agent想撤啥撤啥」
> **上游裁定**：[D-031](2026-10-24-user-requirements-source.md)。
> **本文取代**：富消息 spec 中“At 不接受模型任意目标/图片发送后续再说”的保守实现边界，
> 但保留 token 零泄露、账号路由、三态和 UNKNOWN 不自动重试等安全不变量。

## 1. 目标与非目标

### 目标

1. 允许 Agent 在 MCP 未覆盖时常规使用 Raw LLBot HTTP，但外发结果必须如实报告。
2. MCP `qq_conversation_send` 支持 `mentionAll`。
3. MCP 支持主动私聊任意 QQ，不设白名单。
4. MCP 支持撤回，Agent 可自行决定目标，不要求逐次主人确认。
5. MCP 支持外发图片、表情、文件；富媒体走消息板、幂等、三态和审计。
6. iibot 提供音频提取/落库/稳定媒体引用；ASR 后置。
7. 定时/延迟动作不进入 iibot，由宿主或系统调度器负责。

### 非目标

- 不把 Raw LLBot HTTP 包装成任意 MCP action；
- 不把 raw token、URL、本地路径或完整协议 JSON 暴露给模型；
- 不在 iibot 内运行 LLM/ASR 模型；
- 不实现持久化 schedule daemon；
- 不因 D-031 放宽回环监听、账号隔离、token 零泄露和 UNKNOWN 不重试。

## 2. Raw LLBot 兜底

- Agent 可以直接使用 Bot 工作区提供的 LLBot HTTP 接口；
- **不是** iibot MCP 契约的一部分，不得把 token 放入命令回显、日志、聊天或报告；
- 外发成功/失败必须记录真实 `messageId`、retcode 或稳定错误；不得用 Raw HTTP 的成功
  冒充 MCP 工具调用成功；
- 账号路由仍由 Agent 选择的账号/接口决定，禁止串号；
- 遇到 `UNKNOWN`/超时不得自动重发；
- MCP 能力缺失时，Agent 可以先报告“MCP 未封装”，再由自己按上述纪律使用 Raw 兜底。

## 3. @全体成员

MCP：

```text
qq_conversation_send(source|threadId, text, mentionAll?, mentionSender?,
                     replyToMessageId?, idempotencyKey?)
```

契约：

- `mentionAll=true` 仅允许群聊；
- `mentionAll` 与 `mentionSender` 同时为 true ⇒ `INVALID_ARGUMENTS`；
- 生成显式 `MentionAll` 出站段，适配器映射为标准 `at` 段 `{ "qq": "all" }`；
- 顺序：reply → mentionAll/mentionSender → text；
- 长文本分片时 mention 段只出现在首片；
- 不要求主人逐次确认，但仍走消息板、幂等、三态和审计。

## 4. 主动私聊

新增 MCP：

```text
qq_private_send(accountId?, targetQqId, text, idempotencyKey?)
```

契约：

- `accountId` 缺省规则：单账号自动选择；多账号必须显式指定；
- 先创建/复用 `qq_private/{accountId}/{targetQqId}` 出站消息，状态 PENDING；
- 经该账号 Adapter 发送，回写 SENT/FAILED_BEFORE_SEND/UNKNOWN；
- 不设好友白名单；Adapter/NapCat 的真实失败原样映射稳定错误码；
- 复用现有账号级发送限速；不新增模型级“审批”门槛；
- `UNKNOWN` 不自动重发；重复 idempotencyKey 不重复发送；
- 消息板是唯一事实源，返回 `source/messageId/deliveryState/idempotencyKey`。

## 5. 撤回

新增 MCP：

```text
qq_message_recall(messageId, source?, accountId?)
```

契约：

- `source` 优先按其 accountId 路由；无 source 时用显式 accountId；单账号可省略；
- 多账号且无法确定账号 ⇒ `ACCOUNT_REQUIRED`；
- 不要求 confirm；Agent 可自行决定撤回哪条可定位消息；
- 新增 typed `RecallCapability`，NapCat 使用标准 `delete_msg`；
- 不删除/篡改消息板历史；只记录 Ops 事件（messageId、accountId、结果）；
- 失败不得伪报成功；`UNKNOWN` 不自动重试。

## 6. 外发富消息

### 6.1 MCP 面

```text
qq_media_upload(fileName, mediaType, dataBase64) -> assetRef
qq_conversation_send(..., attachments?)       # image / sticker
qq_group_file_upload(source, assetRef, fileName, mediaType?, idempotencyKey?)
```

`attachments` 只接受：

```text
{ "type": "image", "assetRef": "..." }
{ "type": "sticker", "faceId": "..." }
```

群文件是独立的 `upload_group_file` 外部动作，不与普通消息段混发；它仍先写消息板
OUT/PENDING，再回写三态。

- 不允许模型直接传任意 URL 或本地路径；
- `assetRef` 来自 `qq_media_upload`，进入受控 `BotMediaStore`；
- 单文件上限沿用 10 MiB；MIME/扩展名/大小校验；
- 上传资产有容量上限，不返回本地路径或可下载 URL；
- 文字可空但 attachments 不能为空；至少一个文本或富消息段。

### 6.2 中立出站段

`OutboundSegment` 扩展：

```text
Image(assetRef, fileName, mediaType, bytes)   # bytes 由 BotMediaStore 在发送前解析
Sticker(faceId)
MentionAll()
```

- 群文件由 `GroupFileUploadCapability` 走 `upload_group_file`，使用受控临时文件和
  上传动作，不把 local path 暴露给模型；
- 适配器负责把 Image 资产映射为 OneBot `image` 段；
- 富消息仍按原顺序发送；长文本分片不重复 reply/mention；
- 发送意图先写消息板，不允许 MCP 直接调 Adapter；
- 文件上传若 provider 不支持，返回 `UNSUPPORTED_FILE_SEND`，不静默改发文本链接。

## 7. 语音提取

- 新增 `MessageSegment.Type.AUDIO`，保留内部 `fileId/url/mediaType/byteSize`；
- 新增 `AudioCapability` 与受控 `qq_audio_get(messageKey, segmentIndex?, limit?)`；
- 音频存入 `BotMediaStore`，返回稳定 `audioRef`、MIME、大小和时长（若 provider 提供）；
- 当前不返回转写；ASR provider/模型配置后补；
- 不向模型暴露音频 URL、本地路径或 base64。
- 真实 LLBot `get_record`/音频下载能力需 provider 探测，失败返回
  `AUDIO_UNAVAILABLE`，不得伪造转写。

## 8. 定时/延迟

- iibot 不新增持久化 scheduler、异步任务表或延迟 MCP 工具；
- DSH/OpenCode 或系统 cron 负责定时唤醒和重入；
- iibot 只暴露即时发送/读取/ack 语义。

## 9. 安全不变量

1. 默认回环监听不变；
2. raw token/Bearer/QQ 凭据不进入日志、state、MCP 结果、模型上下文、仓库或答复；
3. 账号路由不串号；富消息和撤回按 source/accountId 解析；
4. `UNKNOWN` 外发不自动重试；
5. 真实副作用和工具结果必须可审计；
6. 富媒体不通过任意 URL/path 参数进入 MCP。

## 10. 实施批次

| 批次 | 内容 | 前置 |
|---|---|---|
| 3A | MentionAll + qq_message_recall + 测试 | **已实现** |
| 3B | qq_private_send（消息板落库+账号路由） | **已实现** |
| 3C | qq_media_upload + image/sticker attachments + qq_group_file_upload | **已实现** |
| 3D | AUDIO segment + qq_audio_get 提取；ASR `NOT_RUN` | **已实现** |
| 运维 | Raw LLBot 兜底纪律、宿主调度说明 | 文档已记录 |

每批必须分别跑 focused + docs；全仓 full 在批次收口时跑。

## 11. 验收判据

### 11.1 MentionAll

- 群聊 `qq_conversation_send(mentionAll=true)` 的出站段顺序为 reply → at(all) → text；
- 私聊使用 `mentionAll` 返回 `MENTION_ALL_REQUIRES_GROUP`；
- `mentionAll` 与 `mentionSender` 同时为 true 返回 `INVALID_ARGUMENTS`；
- 长文本分片只首个片段带 at(all)。

### 11.2 主动私聊

- 单账号省略 `accountId` 可发送；多账号省略返回 `ACCOUNT_REQUIRED`；
- 发送前消息板存在 PENDING OUT，结果回写 SENT/FAILED_BEFORE_SEND/UNKNOWN；
- 相同 idempotencyKey 不产生第二次外部发送；
- 不串用其他账号的 Adapter。

### 11.3 撤回

- 指定 source/accountId 按该账号调用 `delete_msg`；
- 多账号无法确定账号返回 `ACCOUNT_REQUIRED`；
- 失败返回稳定错误码，不写成功；消息板历史不被删除；
- Ops 事件包含 messageId、accountId、结果，不包含 token 或正文。

### 11.4 富消息

- 上传资产、segment schema、消息板写入、适配器发送 mapping 均有契约测试；
- 图片/文件只接受 `assetRef`，任意 URL/path 入参被 schema 拒绝；
- 文件超 10 MiB、MIME 非法或资产过期 fail-closed；
- 发送意图先落 PENDING，再回写三态；富消息幂等和 UNKNOWN 语义与文本一致。

### 11.5 音频

- `record` 段映射为 AUDIO，保存内部媒体定位信息；
- `qq_audio_get` 返回 audioRef/MIME/size，文本结果不含 URL/路径/base64；
- 无 provider 能力时返回 `AUDIO_UNAVAILABLE`；
- 未接 ASR 时任何工具结果都不得出现伪造转写。

### 11.6 文档与门禁

- `tools/gate.sh docs` PASS；
- 每个批次 focused test PASS；
- 批次收口时 `tools/gate.sh full` PASS；
- 真实 QQ/LLBot 外部副作用未跑时写 `NOT_RUN`。
