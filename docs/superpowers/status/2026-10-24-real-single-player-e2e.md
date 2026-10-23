# 历史快照：2026-10-24 真实单人 E2E

> **历史快照**：本文件只记录该时点的事实，不是当前状态入口。
> **当前实时状态**：[`../../agents/STATUS.md`](../../agents/STATUS.md)
> **勿据此开工**：后续变更以新 STATUS 与设计文档为准。

## 范围

真实 NapCat / OneBot 11 + 真实 LLM + 本地 MCP client，单玩家、第 1 回合。

- Bot QQ：`<bot-qq>`
- 测试玩家 QQ：`<player-qq>`（`player-1`）
- 公共群：`<test-group>`
- MCP：`127.0.0.1:8765/mcp/control`，Bearer 鉴权，19 工具
- 数据目录：隔离目录 `/home/cna/.config/iibot/runtime`（不在仓库）
- 密钥：隔离 env 文件，仓库内无 token/API key

## 真实流程与结果

```text
玩家 QQ 私聊
  → NapCat WS 事件
  → 秘书 Agent（真实 LLM）
  → decision_draft_create
  → 玩家私聊 /确认 <draftId>
  → MCP tf_lock_turn
  → MCP tf_run_adjudication_agent（真实 LLM）
  → COMMITTED
  → MCP tf_publish_turn
  → afterMutation
  → 公共群公开叙事 + 玩家私聊 outcome
```

实际状态链：

```text
OPEN → LOCKED → ADJUDICATING → COMMITTED → PUBLISHED
```

- 草稿：`ecfc67d7-7d7a-460d-a1d7-5dab4ab174ae`
- 决策：`ba0d110e-b353-45e1-813b-3b2d353bec1f`
- 硬确认者：`player-1`
- 冻结 snapshotHash：`8c1713375d2249c1a3eddbdb0fe1a5c174ed793b304c044e58de06c82bf1a516`
- 中央推演 LLM 调用：约 14s，工具调用 1 次
- `worldVersion`：1 → 2
- 最终 `eventSeq=14`
- 公共群消息 id：`1536303685`
- 玩家私聊 outcome 消息 id：`138548704`

## 已验证

- 真实 NapCat 私聊入站、事件 WS 连接、HTTP Action 出站；
- 真实 LLM 秘书回复与决策草稿工具调用；
- 玩家 `/确认 <draftId>` 硬确认，其他路径不能代替；
- MCP `tf_lock_turn` / `tf_run_adjudication_agent` / `tf_publish_turn`；
- 中央推演 Agent 真实 LLM 调用并成功提交结构化提案；
- 发布后公开群与玩家私聊两个真实出站消息均成功；
- 进程重启恢复：同一 dataDir 第二次启动 `recovery=true`，恢复当前回合/世界/决策/快照；
- MCP client 需长于 20s 的请求超时；已在本机隔离 client 中设为 5 分钟。

## 未验证 / 限制

- 仅单玩家、单回合；多玩家/多回合/长跑未跑；
- 真实 DSH host 会话未接入；当前由本地 Java MCP client 驱动；
- 第一次中央推演尝试在 MCP client 默认 20s 超时处中断，回合停在 `ADJUDICATING`；随后显式 `failAdjudication` → `retry` 后第二次 14s 成功。真实客户端必须配置足够长的 requestTimeout。
- 逐事件回放未实现，重启恢复依赖完整快照；Secretary 的 ContextWindow 聊天历史不恢复；
- 公共群入站玩法未开放。

## 证据边界

- 消息 ID、状态、worldVersion、eventSeq 由真实 NapCat `get_msg` 与 MCP `tf_status`/`tf_turn_get` 返回；
- 真实聊天内容不写入本文件，仅保留流程与标识；
- 没有 Git 提交。
