# 共享 Skill 区与投诉闭环设计

> **状态**：有效方向（实施中）
> **日期**：2026-10-24
> **用户原话（逐字）**：
> 「能不能给秘书安排一个公用skill区，在其中放公共文档，不用出公开推文硬要一个一个复制到秘书的文档区，也可以放skill，指导秘书怎么响应玩家需求；秘书也可以承接玩家对推演的投诉，额外随时随地向GM提交投投诉，GM额外出应对之类的，专门做一个投诉提交，gm查看，gm回应工具来进行」

## 1. 共享 Skill / 公共文档区

### 1.1 存储布局

```text
dataDir/shared-skills/
  public/     公共资料，所有秘书只读
  skills/     秘书响应指南/技能文档，所有秘书只读
```

- 全局唯一，不按玩家复制。
- 使用 workspace-mosire + docs-mosire 的 FILE 挂载和路径解析。
- 只有 GM/DIRECTOR/SUPERVISOR 可以通过 MCP 写入；秘书/玩家不可写。
- 路径逃逸、绝对路径、`..`、软链、空段、非 `public/skills` 根 fail-closed。
- 单文档最大 64 KiB；读取给秘书时截断 16 KiB + 标记。
- 文档正文不进入事件 payload/日志；写入记录 path/hash/reason。

### 1.2 工具面

秘书（只读）：

- `secretary_shared_skill_list`：列 `public/`、`skills/` 或子目录；
- `secretary_shared_skill_read`：按相对路径读取，只读；
- 与 `secretary_workspace_*` 分开，避免把公共内容复制进每个玩家目录。

GM/MCP（读写）：

- `tf_shared_skill_list`
- `tf_shared_skill_get`
- `tf_shared_skill_write`：写入 `public/` 或 `skills/`；默认拒绝覆盖；`replace=true` 才允许覆盖；必须有 `idempotencyKey` 和 `reason`；标 destructive。
- GM 推演秘书增加内部只读工具 `gm_shared_skills` / `gm_shared_skill_read`。

### 1.3 权限

| 主体 | 列 | 读 | 写 |
|---|---|---|---|
| PLAYER | 否 | 否 | 否 |
| SECRETARY | 是 | 是 | 否 |
| GM/DIRECTOR/SUPERVISOR | 是 | 是 | 是 |
| GM AIDE | 是 | 是 | 否 |

## 2. 投诉闭环

### 2.1 状态

```text
SUBMITTED → RESPONDED
```

- 投诉不受回合状态限制；OPEN/LOCKED/ADJUDICATING/PUBLISHED 均可提交。
- 投诉人固定为秘书绑定玩家，不能由模型参数指定其他玩家。
- 投诉正文仅投诉玩家、其秘书、GM/SUPERVISOR 可见。
- GM 回应只能由 GM/DIRECTOR/SUPERVISOR 写入。
- 回应正文保存为私密记录；可选由宿主通过 outbox 发回玩家私聊，使用独立 deliveryState，UNKNOWN 不自动重发。

### 2.2 领域模型

- `Complaint`：complaintId、playerId、title、body、status、revision、createdAt、updatedAt、respondedBy、responseBody、respondedAt、deliveryState。
- `ComplaintService`：
  - `submit(Actor player, title, body, idempotencyKey)`
  - `listForSecretary(playerId)` / `getForSecretary(playerId, complaintId)`
  - `listForGm(status)` / `getForGm(complaintId)`
  - `respond(Actor gm, complaintId, expectedRevision, responseBody, idempotencyKey)`
  - `markDelivered(complaintId, DeliveryState)`
- `ComplaintStore` + `InMemoryComplaintStore`（taskflow）；app 实现 `JsonComplaintStore`（`dataDir/complaints.json`，严格 JSON、原子持久化、权限 600）。
- 每次写操作 copy-on-write；store.save 成功后才替换内存；失败零副作用。
- 幂等键重复返回首次结果。

### 2.3 工具面

秘书：

- `secretary_complaint_submit`（title/body/idempotencyKey；无 playerId 参数；随时可调用）
- `secretary_complaint_list`（只看自己的）
- `secretary_complaint_read`（只看自己的；含 GM 回应）
- 无回应、关闭、读取他人投诉工具。

GM/MCP：

- `tf_complaint_list`（status 可选；摘要，不含正文）
- `tf_complaint_get`（GM 可看正文与回应）
- `tf_complaint_respond`（complaintId/response/expectedRevision/idempotencyKey；仅 GM 侧；标 destructive）
- 不接受 playerId 伪造；投诉归属由 submit 时的认证 PLAYER actor 固定。
- full assembly MCP 工具数在共享 skill 3 个与投诉 3 个后从 28 → 34（以实际注册为准）。

### 2.4 回应投递

- `ComplaintDistributor` 扫描 `RESPONDED` 且 `deliveryState=PENDING` 的投诉，将 GM 回应发送到投诉玩家私聊。
- 发送相关键 `complaint:<id>:response`；SENT/FAILED/UNKNOWN 持久化；UNKNOWN 不自动重发。
- 只有 GM 的 `tf_complaint_respond` 会触发该分发，秘书/玩家不能触发。
- 如果用户不希望自动发 QQ，可只保存回应；当前设计默认发送，实际发送受 M-004 外部发送门约束。

## 3. 验收判据

### 共享 Skill

- [ ] 任意秘书可读 `public/`、`skills/`，不可写；
- [ ] 秘书工作区仍只含自己资料，公共文档无需复制；
- [ ] 玩家/其他秘书不可修改共享文档；
- [ ] GM MCP 可 list/get/write，路径逃逸/覆盖策略/幂等正确；
- [ ] GM aide 可只读全部共享文档；
- [ ] 真实 MCP/LLM 下秘书能读取一份 GM 写入的 skill 并据此回答。

### 投诉

- [ ] 回合冻结时仍可提交投诉；
- [ ] 秘书只能看到自己的投诉；A 的投诉对 B 不可见；
- [ ] GM 可列表/查看/回应；玩家/秘书不能回应；
- [ ] 回应 revision/角色/状态错误零副作用；重复 idempotencyKey 不重复写；
- [ ] `complaints.json` 重启恢复；回应 outbox 成功/UNKNOWN 语义正确；
- [ ] 真实 MCP/LLM 下完成 submit→GM list→GM respond（真实 QQ 发送需用户明确授权）。

### 文档与门禁

- [ ] 全量测试通过；
- [ ] 更新 STATUS/v2 设计索引；
- [ ] 提交并推送，密钥/真实 QQ 标识不入库。
