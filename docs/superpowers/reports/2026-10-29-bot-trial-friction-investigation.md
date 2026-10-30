# Bot 试运行摩擦报告调查与分诊

> **状态**：第一、二批与 D-031 3A–3D 已实施并通过本机门禁（2026-10-30，Asia/Shanghai）
> **用户原话**：
> > 「定位到iibot项目本地，bot文件夹下的试运行Agent报告了数个问题，找到调查报告和其中的对应问题，告诉我你对此的分析和看法，不要急着写代码」
>
> > 「有需要我做的裁决吗，没有的话直接记文档开工」
> **证据快照**：`/home/cna/DevMosire-iiBot/bot/FRICTION.md`，读取时 sha256
> `0ea7d1919f46f72a4561cd26488d17e311b6630a8076502e69ae8a29f1dcc6f1`，
> mtime `2026-10-29 21:53:47 +08:00`。该文件由 Bot 工作区维护，可能继续变化；
> 本文记录的是上述快照及 iiBot 源码 `e1defa7` 的静态核对结果。
> **本文性质**：事实调查、问题分诊和第一批实施边界，不是新的用户设计裁定。

## 一、结论先行

1. FRICTION 中的 20 条不是 20 个同层缺陷。可归为入站段丢失、出站能力缺口、
   宿主/wake 集成、工作区媒体运维和产品边界五类。
2. 第一批无需用户新裁定即可实施的是 **F-001 卡片正文不可读**与
   **F-002 群文件元数据不落库**。两者共根：`OneBot11Codec` 把未显式支持的
   segment 一律压缩成只有类型标签的 `OTHER`。
3. 第一批只实现入站保真和安全可读文本，不顺手扩成任意 OneBot action 或任意
   原始 JSON 工具面。群文件下载工具、转发媒体句柄、出站富消息、撤回、@全体、
   主动私聊、ASR、定时调度仍单独评估。
4. FRICTION 对 F-002 的验收写“读出来就有文件名和 file_id”。`file_id` 属于
   项目红线中的内部媒体定位符，直接返回模型会突破“URL/fileId 不进入 MCP 返回”；
   本轮改为：**消息板保存 file_id，MCP 只返回文件名、大小和 `hasMedia`**。
   后续下载由专用语义工具在服务端解析 file_id，不把原始定位符交给模型。

## 二、对应问题与分诊

| 编号 | 摩擦 | 当前判断 | 落点/本轮状态 |
|---|---|---|---|
| F-001 | 卡片只有 `OTHER/json`，正文为空 | 已由源码确认：`OneBot11Codec.appendSegment()` 对 `json/xml` 落入 `addOther()`，未保留卡片可读字段 | iiBot 适配器；**本轮修复** |
| F-002 | 群文件只有 `OTHER`，无文件名/file_id | 已确认：`file` 未进入映射分支；`MessageSegment.FILE` 已存在但未被 codec 生产 | iiBot 适配器；**本轮修复元数据** |
| F-003 | 转发里的图片没有可下载句柄 | `qq_forward_read` 返回媒体摘要，但安全句柄/取图工具未贯通 | 富媒体契约；后续责任区 |
| F-004 | 大 GIF 预览爆栈 | `Maximum call stack size exceeded` 是宿主/图片传输形状信号，先不判为 Java 适配器缺陷 | DSH/OpenCode 或媒体工具隔离；后续 |
| F-005 | 扩展名与实际格式不符 | Bot 工作区脚本已按文件头修正 | 工作区；已完成 |
| F-006 | 语音无转写 | D-031：先做音频提取/audioRef，ASR 后置 | 提取已实现；ASR `NOT_RUN` |
| F-007 | 不能 @全体 | D-031：普通 Agent 随时可用 | `mentionAll` 已实现；真实外发 `NOT_RUN` |
| F-008 | 不能拉群/同意群邀请 | 可选 OneBot 扩展能力，标准核心不保证 | 产品评估；暂缓 |
| F-009 | 沙箱不能写 `~/.npm` | Bot 工作区已把 npm cache 固定到 `run/npm-cache` | 工作区；已完成 |
| F-010 | token 泄露风险 | 工作区纪律问题，不是代码功能 | Bot 工作区；持续遵守 |
| F-011 | 单条外发 2000 字上限 | 源码已有文本分片路径；报告未说明是否绕过 MCP/适配器，当前不能直接下结论 | 需目标路径复现后再定 |
| F-012 | 没有撤回动作 | D-031：实现且 Agent 自行决定目标，不要求 confirm | `qq_message_recall` 已实现；真实撤回 `NOT_RUN` |
| F-013 | 外发无回执核对 | 源码核对：MCP `qq_conversation_send` 已经把 OUT/PENDING 写入消息板并回写三态；`suppressSelfMessages` 只过滤回显。报告中的无记录路径应是绕过 MCP 的 Raw LLBot 发送 | 先按 MCP 路径复验，暂不改 |
| F-014 | wake 提示 programMcp 与实际不符 | 已确认 DSH `schemas()` 省略 scope 会查全局视图；改为 `schemas(agent)` | DSH 已修；运行态 reload `NOT_RUN` |
| F-015 | partial ack 后不能立即续处理 | 不放宽宿主 backoff；在 ack 结果和 iibot view-ack profile 中明确同轮续读 | iiBot/iiBot-DSH 已修；新会话验证 `NOT_RUN` |
| F-016 | 群文件下载要猜 busid | 已由 typed `GroupFileCapability` + `qq_group_files` / `qq_group_file_get` 封装；上传走 `qq_group_file_upload`；busid 仅在适配器内部解析 | 已实现；真实 LLBot `NOT_RUN` |
| F-017 | 无定时/延迟动作 | D-031：由宿主或系统调度器负责，iibot 不内置 scheduler | 产品边界已定；无代码 |
| F-018 | 不能主动发起私聊 | D-031：允许主动私聊任意 QQ，不设白名单 | `qq_private_send` 已实现；真实外发 `NOT_RUN` |
| F-019 | 外发不支持图片/表情 | D-031：MCP 必须支持图片/表情/文件 | image/sticker + `qq_group_file_upload` 已实现；真实外发 `NOT_RUN` |
| F-020 | jar 随 DSH 重启被带走 | Bot 工作区已用 `setsid` 修复进程组问题；稳定端口/自启仍未完成 | 工作区运维；后续 |

## 三、第一批实施边界

### 目标

1. 卡片 `json`/`xml` 段在入站时提取有界、可读的标题/描述/链接，写入
   `MessageSegment.text` 与消息的非文本摘要，不再只剩 `[卡片×1]`。
2. 群文件 `file` 段映射为 `MessageSegment.Type.FILE`，保存文件名、内部 file id
   和大小；MCP 输出文件名/大小/hasMedia，不输出 file id、URL 或原始协议 JSON。
3. 数组消息段和 CQ 字符串两条解析路径行为一致。
4. 普通消息段语义保持不变；图片、表情、转发等现有摘要测试不回归。

### 非目标

- 不新增 `qq_group_files` / `qq_group_file_url` / `qq_forward_media_get` 工具；
- 不把完整卡片 JSON 或原始 OneBot data 写入 MCP 结果或日志；
- 不返回 `file_id`、媒体 URL、本地路径；
- 不实现出站图片、@全体、撤回、主动私聊、ASR、调度；
- 不改动 Bot 工作区中的运行配置、token、数据库或 FRICTION.md。

## 四、验收判据

1. 卡片数组段测试：segment 类型为 `OTHER`、`rawType=json`，`text` 含标题和链接，
   不含完整原始 JSON；消息非文本摘要含可读卡片信息。
2. 卡片 CQ 字符串测试：`&#44;` 反转义后可解析嵌入 JSON，得到相同可读信息。
3. 群文件数组段测试：segment 类型为 `FILE`、`text=文件名`、内部 `fileId` 已保存、
   `byteSize` 正确、`media()=true`。
4. 群文件 CQ 字符串测试：同一语义；不得退化为 `OTHER`。
5. SQLite segment 回读测试覆盖 `FILE` 元数据。
6. MCP `segmentMap` 输出不包含 `fileId`、`url`、本地路径或 raw data，
   但能看到文件名与大小。
7. `tools/gate.sh focused iibot-connect -am test`、
   `tools/gate.sh focused iibot-bot -am test`、`tools/gate.sh docs` 实际运行；
   未跑全量时不得写 full PASS。

## 五、后续责任区

| 顺序 | 责任区 | 条目 | 前置 |
|---|---|---|---|
| 1 | 入站段保真 | F-001/F-002 | 本文 |
| 2 | 群文件工具 | F-016 及文件下载 | **已实现 code-level**；真实样本待验 |
| 3 | 转发媒体句柄 | F-003 | **已实现顶层媒体**；嵌套转发待评估 |
| 4 | OUT 审计 | F-013 | 源码已证明 MCP 路径落库；先复验 Raw LLBot 路径 |
| 5 | 宿主 wake 一致性 | F-014/F-015 | **已实现 code-level**；DSH reload / 新会话 `NOT_RUN` |
| 6 | 出站富消息/副作用工具 | F-007/F-012/F-019 | 独立设计，不接受任意 action 暴露 |
| 7 | 产品能力评估 | F-006/F-008/F-017/F-018 | 用户后续目标和安全边界 |

## 六、当前未验证

- 本轮尚未发送真实 QQ 卡片或群文件；没有真实 LLBot 回归，只有后续提交的
  codec/存储/MCP 单测。
- 卡片厂商 JSON 的字段组合可能存在变体；实现必须提供安全 fallback，不能假设
  `meta.detail_1` 永远存在。
- F-011 的真实截断路径未复现。
- F-014/F-015 的宿主侧根因未在本报告内完成源码核对。

## 七、第一批实施结果（2026-10-29）

### 7.1 源码改动

| 文件 | 改动 |
|---|---|
| `iibot-connect/.../OneBot11Codec.java` | 解析 `json/xml` 卡片安全可读文本；解析数组/CQ 字符串 `file` 段为 `FILE` 元数据；增加有界摘要，不保留原始 JSON 输出 |
| `iibot-connect/.../MessageSegment.java` | 新增 `file(index, fileName, fileId, byteSize)` 工厂，file id 仅内部使用 |
| `iibot-bot/.../BotToolSupport.java` | MCP segment 输出增加 `rawType`，仍不输出 fileId/url/路径 |
| codec/store/MCP 测试 | 覆盖卡片数组/CQ/XML、文件数组/CQ、SQLite 回读和 MCP 脱敏 |

### 7.2 实际门禁

| 命令 | 结果 | 证据 |
|---|---|---|
| `tools/gate.sh focused iibot-bot -am test` | PASS：connect 66 + bot 110，0 failures/errors/skipped | `/tmp/iibot-bot-test2.log`（本地证据） |
| `tools/gate.sh full` | PASS：**246 tests / 0 failures / 0 errors / 0 skipped**（connect 66、bot 110、app 70）；jar 构建成功 | `.work/bot-friction-f001-f002-full.log` |
| `tools/gate.sh docs` | PASS：modules=3 files=43 links=109 | 文档检查输出 |

### 7.3 没做的事

- 未做真实 QQ/LLBot 卡片或群文件回归；真实入站样本仍是 `NOT_RUN`。
- 未新增下载 file id 的 MCP 工具；`qq_group_files` / `qq_group_file_url` 属后续 P1。
- 未改 Bot 工作区运行 jar、配置、数据库、token 或 `FRICTION.md`。
- 未处理 F-003/F-004/F-006/F-007/F-008/F-011/F-012/F-013/F-014/F-015/F-016/F-017/F-018/F-019。

## 八、第二批实施结果（2026-10-30）

### 8.1 F-003 转发内图片

- 新增 `qq_forward_media_get(messageKey, segmentIndex?, nodeIndex?, limit?)`；
- 只扫描顶层转发页，最多 4 张图片，经 `MediaCapability` 下载后存入
  `BotMediaStore`，以 MCP `ImageContent` 返回；
- 文本结果只含 `nodeIndex/segmentIndex/senderName/mediaType/size` 和稳定错误；
  不返回 URL、file id、路径；嵌套转发不展开，明确标 `nestedUnsupported`；
- 单测覆盖两张转发图片、asset 读取和文本脱敏。

### 8.2 F-016 群文件

- `iibot-connect` 新增 `GroupFileCapability` / `QqGroupFile`；
- `NapCatAdapter` 实现 `get_group_files_by_folder`、`get_group_file_url` 和受控
  下载（scheme、超时、10 MiB 上限）；
- 新增 `qq_group_files`、`qq_group_file_get`：
  - 列表只返回名称/大小/上传者/时间 + 当前进程短期 `fileRef`；
  - fileRef 是随机盐哈希，不携带 fileId/busid，10 分钟过期、容量 1024；
  - 文本类文件返回最多 64 KiB UTF-8 正文；
  - 图片走 ImageContent；
  - 其他二进制只返回元数据并标 `contentOmitted=true`；
- 单测覆盖目录 payload、下载 payload、文件引用脱敏、JSON/图片/二进制三条读取路径、
  多账号 `ACCOUNT_REQUIRED` 和未知 fileRef。

### 8.3 F-014 programMcp 误报

- 根因：DSH tool registry 的 `schemas(scope)` 省略 scope 时返回全局视图；旧实现
  `schemas()` 因而可能看不到会话注入的 MCP 工具。
- 修复：改为 `schemas(agent)`；wake-mode/profile 过滤不变。
- 验证：新增 scope-sensitive registry stub，旧实现会失败；DSH `npm test` PASS。
- 当前运行中的 DSH 未 reload，真实 `missing → available` 转变 `NOT_RUN`。

### 8.4 F-015 partial ack 同轮续处理

- 不在 DSH/OpenCode 放宽 no-progress backoff：`available` 是 item 数，不能区分
  partial ack 与 claim/release，放宽会破坏既有退避语义。
- 在 iiBot 语义工具层修复：`qq_conversation_ack` 返回
  `remaining=true` 时同时返回 `nextAction=QQ_CONVERSATION_READ`，description 明确
  要求立即再次 `qq_conversation_read(source)`；
- `iiBotMosire-DSH` 的 iibot view-ack profile 同步加入同轮续处理提示；
  `simulation2/dsh/HANDOFF_PROMPT.md` 去掉“剩余等下一次 wake”的旧规则；
- 单测覆盖 partial ack 的 `nextAction` 与完整 ack 的 `NONE`；
- 当前 Bot 会话仍在使用旧提示/旧 jar，真实同轮续处理 `NOT_RUN`。

### 8.5 实际门禁

| 命令/范围 | 结果 |
|---|---|
| iiBotMosire `tools/gate.sh full` | PASS：**251 tests / 0 failures / 0 errors / 0 skipped**（connect 67、bot 110、app 74） |
| iiBotMosire `tools/gate.sh docs` | PASS：modules=3 files=43 links=110 |
| InboxMosire-DSH `npm test` | PASS：**147 tests / 144 pass / 3 live skipped / 0 fail** |
| iiBotMosire-DSH wake profile `npm test` | PASS：2/2 |
| 真实 QQ/LLBot/DSH reload 后闭环 | `NOT_RUN` |

## 九、D-031 高风险能力拍板（2026-10-30）

用户已对剩余需要产品/安全裁决的项给出选择，正本见
[`2026-10-30-qq-outbound-actions-and-media-rulings.md`](../specs/2026-10-30-qq-outbound-actions-and-media-rulings.md)：

1. Raw LLBot HTTP：允许 Agent 常规兜底使用；
2. @全体：普通 Agent 随时可用；
3. 主动私聊：允许任意对象，不设白名单；
4. 撤回：实现，Agent 可自行决定撤回目标，不要求逐次确认；
5. 外发富媒体：MCP 必须支持图片/表情/文件；不禁止 Raw 兜底；
6. 语音：先做音频提取，ASR 模型配置后补；
7. 定时/延迟：由宿主或系统调度器负责，iibot 不内置 scheduler。

上述裁定未取消 token 零泄露、账号路由、默认回环、`UNKNOWN` 不自动重试和结果审计。
本轮只完成裁定落文档；具体实现尚未开始，相关能力仍为 `NOT_RUN`。

## 十、D-031 3A–3D 实施结果（2026-10-30）

### 10.1 已实现

- **3A**：
  - `OutboundSegment.MentionAll`；
  - `qq_conversation_send(mentionAll=true)`，群聊映射 `at/qq=all`，私聊 fail-closed；
  - `RecallCapability` + `delete_msg` + `qq_message_recall`；不要求 confirm；
- **3B**：
  - `BotReplyService.sendPrivate` 自动创建/复用 private thread；
  - `qq_private_send(accountId?, targetQqId, text, idempotencyKey?)`；
  - 先写 OUT/PENDING，再发送并回写三态；
- **3C**：
  - `qq_media_upload`（base64 → 受控 assetRef）；
  - `qq_conversation_send(attachments=[image/sticker])`；
  - `OutboundSegment.Image/Sticker`；
  - `qq_group_file_upload`（group file 走独立 upload action，先落 OUT/PENDING）；
  - 不暴露 URL、本地路径或 base64 到结果；
- **3D**：
  - `MessageSegment.Type.AUDIO` 与 record 数组/CQ 映射；
  - `AudioCapability` + `qq_audio_get`；
  - 返回 `audioRef`、MIME、size；`asrConfigured=false`、无伪造转写。

### 10.2 门禁

| 命令 | 结果 |
|---|---|
| `tools/gate.sh focused iibot-app -am test` | PASS：connect 73 + bot 115 + app 84，0/0/0 |
| `tools/gate.sh full` | PASS：**272 tests / 0 failures / 0 errors / 0 skipped**（connect 73、bot 115、app 84） |
| `tools/gate.sh docs` | PASS：modules=3 files=44 links=119 |
| 真实 QQ/LLBot 外发、上传、撤回、主动私聊、语音下载 | `NOT_RUN` |
| ASR 转写 | `NOT_RUN`（未配置模型） |

### 10.3 未完成/风险

- Raw LLBot 常规兜底是 D-031 允许的，但不经过 iibot 消息板；其外部动作仍依赖 Agent
  如实报告 messageId/retcode，不能与 MCP 三态混淆。
- 群文件上传通过本地临时文件交给 OneBot provider；远程 LLBot/NapCat 是否可读取该路径
  尚未真实联调，失败会稳定返回，不伪造成功。
- 图片发送使用 `base64://` 消息段；真实 LLBot 对图片段的接受度和大小上限待实测。
