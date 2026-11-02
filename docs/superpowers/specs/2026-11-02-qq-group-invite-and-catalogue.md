# 群邀请、入群申请与群目录检索（D-037）

> **状态**：已实施（code-level，2026-11-02）· 真实 QQ 联调 `NOT_RUN`。
> **本文属于项目设计**（工具面与协议事实）；维护流程见 [`../../agents/STATUS.md`](../../agents/STATUS.md)。
> **本文取代**：无（新增专题）。

## 一、用户裁定与原话

用户裁定（2026-11-02）：

> 「F008，不行，Agent理想情况下不但要有能力加自己想加的群聊，还要有能力自己搜。
> 其它的不用，先把这个可操作性性问题解决掉。」

配套边界（同次会话）：

> 「把已有的F开头bug修了，注意要明确模块间边界，不要越界。」

因此本专题范围严格限定在 `iiBotMosire` 仓；不改 LLOneBot 上游、不改 DSH 宿主、
不动 `bot/` 运行工作区以外的其他仓。政治敏感话题毒化问题由用户明确排除，不在本专题。

## 二、协议事实（探针实测，2026-11-02/03）

这些事实是本设计的硬约束，不要在实现里"再试一次"：

1. **LLOneBot 8.3.2 没有「按群号申请加群」和「搜群」的 OneBot action。**
   `join_group`、`join_group_v2`、`leave_group`、`search_group`、`search_friend`、
   `search_user`、`get_group_add_request`、`get_group_notice` 等全部 HTTP 404。
2. **内核层同样不存在。** `src/ntqqapi/api/group.ts` 只有
   `getGroupNotifications`/`setGroupRequest`/`quitGroup`/`kickGroupMember` 等；
   `src/ntqqapi/api/webapi.ts` 只有 qun.qq.com 公告/荣誉/相册/签到接口。
   全仓 grep `GroupSearch`/`joinGroup`/`invite_join`/`10c5` 无实现。
3. **NapCat / Lagrange 也没有。** NapCat 的 `SetGroupSearch` 是"设置群搜索开关"，
   Lagrange 只有 `GroupJoinProcessor`（处理入群通知）。三大 OneBot 实现一致缺失。
4. **QQ 网页版群搜索接口不可用。**
   `https://qun.qq.com/cgi-bin/group_search/group_search` 端点存在，但只要带
   `keyword` 就确定性返回 `{"ec":3,"errcode":258}`（验证码/风控门）；
   2020 年的 `pc_group_search` 已下线（`errcode:0`，参数被忽略）；
   `qunjoin/*`、`group_join/join`、`member/join_group` 全 404。
5. **唯一可用的入群路径是「接受邀请」或「审批入群申请」。**
   `set_group_add_request` 的 `flag` 在纯数字时被当作通知 sequence；
   找不到就抛 `flag 不存在`。此时可以用内核后门直接发 OIDB：
   `llonebot_debug{apiClass:"qqProtocol", method:"handleGroupRequest",
   args:[sequence, eventType, groupCode, 1, "", false]}`，其中
   `src/main/qqProtocol/mixins/group.ts:346` 的方法签名是
   `handleGroupRequest(sequence, eventType, groupCode, operation, message, filtered)`，
   `operation` 1=accept / 2=reject / 3=ignore。
6. **邀请卡片本身带得动接受所需的全部数据。**
   卡片是 `app=com.tencent.tuwen.lua`、`bizsrc=qun.invite`，
   `meta.news.jumpUrl` 形如
   `mqqapi://group/invite_join?...&groupcode=938269591&msgseq=1793612836122334`。
   `msgseq` 就是 OIDB 需要的 `sequence`（< 2^53，JS number 不丢精度）。

## 三、设计

### 3.1 能力契约（`iibot-connect`）

- `GroupSystemRequest`：`(sequence, kind, eventType, groupId, groupName, inviterId,
  inviterName, requesterId, requesterName, message, handled)`；`pending()` =
  `!handled && sequence > 0`；常量 `EVENT_TYPE_INVITE=2`、`EVENT_TYPE_JOIN=1`。
- `GroupRequestCapability`：`joinedGroups()`、`listGroupRequests(limit)`、
  `decideGroupRequest(sequence, approve, reason)`、`acceptGroupInvitation(groupId, sequence, eventType)`。
- `NapCatAdapter` 实现：`stop_group_system_msg` 归一化 `invited_requests`/`join_requests`；
  决定用 `set_group_add_request{flag:"<sequence>", approve, reason}`；
  接受邀请先走 `set_group_add_request`，只在**服务端明确拒绝**（`FailureKind.REJECTED`，
  典型是 `flag 不存在`）时回退 OIDB 后门；传输失败一律 `UNKNOWN`。
- `OneBot11Codec` 识别群邀请卡片：从 `mqqapi://group/invite_join` 提取
  `groupcode`/`msgseq` 渲染成 `群邀请｜标题｜群号 X｜序列 Y｜描述`；
  **不输出 token，不输出原始 `mqqapi:` 链接**。

### 3.2 工具面（`iibot-bot`）

| 工具 | 作用 | 关键错误码 |
|---|---|---|
| `qq_group_request_list` | 列群邀请/入群申请（默认只列未处理） | `ACCOUNT_REQUIRED`、`GROUP_REQUEST_UNAVAILABLE` |
| `qq_group_request_decide` | 审批入群申请（`requestId`+`approve`+`reason`） | `INVALID_ARGUMENTS`、`GROUP_REQUEST_UNKNOWN` |
| `qq_group_invite_accept` | 接受邀请把 bot 加进群（`groupId` 必填，`sequence` 可省） | `NO_INVITATION`、`GROUP_INVITE_UNKNOWN`、`GROUP_INVITE_REJECTED` |
| `qq_group_search` | 按群号/群名检索 bot 知道的群 | `NO_MATCH`、`GROUP_SEARCH_UNAVAILABLE` |

`qq_group_search` 的检索面是"bot 自己的关系面"：已加入的群（live `get_group_list`）、
待处理邀请/申请（live `get_group_system_msg`）与本地 SQLite 群目录
（`group_catalogue`：`group_id` 主键、`group_name`、`source`
`joined|invite|join_request`、`first_seen_at`、`last_seen_at`）。
没有命中时返回 `NO_MATCH` 并给出可执行提示：先联网搜到群号，再让群内成员邀请本账号。

### 3.3 装配

`BotRuntime` 创建 `SqliteGroupCatalogueStore(dataDir/bot.db)`，用
`capabilityMap(bindings, GroupRequestCapability.class)` 生成按账号能力映射，
经 `BotToolContext` → `QqToolPack` → `BotMcpTools.allForAccounts(...)` 注入；
能力不存在时四个工具根本不注册。

## 四、不变量

1. `llonebot_debug` **不暴露给模型**：只在 `NapCatAdapter` 内部以固定
   `apiClass/method/args` 形状调用，模型无法传任意 `apiClass`/`method`。
2. 邀请卡片里的 `config.token`、原始 `mqqapi:` 链接不进入消息正文、日志或工具结果。
3. 外部副作用不自动重试：`UNKNOWN` 原样返回，由 Agent/人确认后另行处理。
4. 账号不串号：一律经 `BotAccountScope` 按 `accountId` 选能力。
5. 群目录写入是辅助记忆，写失败不得让读取失败。

## 五、验收判据

- `iibot-connect`：`NapCatAdapterGroupRequestTest`（10 项）覆盖归一化、排序、limit、
  `flag` 十进制编码、`UNKNOWN` 不重试、快路径接受邀请、`flag 不存在` 回退 OIDB、
  内核拒绝透出、传输失败、非数字群号；
  `OneBot11CodecTest` 新增 2 项覆盖 JSON/CQ 两种邀请卡片渲染且不泄露 token。
- `iibot-bot`：`BotGroupToolsTest`（14 项）覆盖列请求/含已处理/账号选择、决定三态、
  接受邀请快慢路径与 `NO_INVITATION`、搜索合并三来源与 `NO_MATCH`、只保留群聊；
  `SqliteGroupCatalogueStoreTest`（3 项）覆盖 upsert 合并、搜索/limit、关键词匹配；
  `BotMcpToolsTest` 新增 1 项覆盖"有能力才注册工具"。
- 门禁：`tools/gate.sh full` PASS，**373 tests**（connect 85、video 26、bot 146、app 116）。
- 未验证（`NOT_RUN`）：真实 QQ 群邀请卡片接受入群、真实 `llonebot_debug` OIDB 回退、
  真实群目录长期写入；需要用户当次明确授权才能碰真实协议端。
