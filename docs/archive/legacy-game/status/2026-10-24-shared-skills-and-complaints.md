# 历史快照：2026-10-24 共享 Skill 与投诉闭环

> **历史快照**：本文件只记录该时点事实，不是当前状态入口。
> **当前实时状态**：[`../../agents/STATUS.md`](../../agents/STATUS.md)

## 交付

### 全局共享 Skill 区

```text
dataDir/shared-skills/
  public/     公共资料，所有秘书只读
  skills/     秘书响应指南/Skill，所有秘书只读
```

- `SharedSkillStore` 基于 workspace-mosire + docs-mosire，单个全局 FILE 挂载；
- 秘书工具：`secretary_shared_skill_list`、`secretary_shared_skill_read`；
- GM MCP：`tf_shared_skill_list`、`tf_shared_skill_get`、`tf_shared_skill_write`；
- GM 推演秘书只读：`gm_shared_skills`、`gm_shared_skill_read`；
- GM write 默认拒绝覆盖，`replace=true` 才覆盖；幂等键、64 KiB 上限、路径逃逸/软链 fail-closed。

### 投诉闭环

```text
SUBMITTED → RESPONDED
```

- 秘书工具：`secretary_complaint_submit`、`secretary_complaint_list`、`secretary_complaint_read`；
- GM MCP：`tf_complaint_list`、`tf_complaint_get`、`tf_complaint_respond`；
- GM 推演秘书只读：`gm_complaints`、`gm_complaint_read`；
- 投诉提交不依赖回合状态；归属固定为认证 PLAYER actor；
- GM 回应仅 GM/DIRECTOR/SUPERVISOR；成功 revision+1、deliveryState=PENDING；
- `ComplaintDistributor` 将 GM 回应私聊发回投诉玩家；UNKNOWN 不自动重发；未知玩家安全失败；
- `JsonComplaintStore`：`dataDir/complaints.json`，严格 JSON、schemaVersion、原子持久化、权限 600。

## 自动化验证

```text
./mvnw -B -ntp -pl iibot-taskflow -am test
  connect 29 + connect-agent 21 + taskflow 172 = 222 PASS

./mvnw -B -ntp -pl iibot-app -am test
  app 123；reactor 345 tests PASS

tools/gate.sh full
  345 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS
```

## 真实 MCP/LLM 验证

- MCP 全量装配 `tools/list=34`；
- GM 写入 `shared-skills/skills/secretary-response-guide.md`；
- 真实秘书 LLM 通过共享 Skill 工具读取并复述响应/投诉规则；
- 真实玩家 QQ 私信提交投诉（`complaintId=0f43536a-8868-48c1-949a-16c8b3515372`）；
- 真实秘书成功提交投诉，GM MCP `tf_complaint_get` 读取全文；
- GM `tf_complaint_respond` 回应，`ComplaintDistributor` 真实私聊发送成功，消息 id `1282368080`，最终 `deliveryState=SENT`；
- 秘书拒绝 GM 代替玩家提交投诉，要求玩家本人私聊授意；权限边界实际成立。

## 未验证 / 限制

- 公共推文真实群发未跑；
- 多玩家投诉隔离仅自动化测试覆盖，真实多玩家未跑；
- DSH 外部会话未接入；
- 模型热切换未实现（配置+重启）；
- 投诉只支持 `SUBMITTED → RESPONDED`，没有关闭/升级/多次回复状态。
