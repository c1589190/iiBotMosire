# P3：控制初始化与 GM 私有文档

> **状态**：有效（P3 实施设计；依据 [`2026-10-24-ideal-gm-control-workflow-and-events-db.md`](2026-10-24-ideal-gm-control-workflow-and-events-db.md) §3.1/§5.1/§5.2 与 [`../plans/2026-10-24-ideal-workflow-implementation-plan.md`](../plans/2026-10-24-ideal-workflow-implementation-plan.md) P3）
> **日期**：2026-10-24
> **用户原话**：见理想工作流设计文件头。
> **范围**：外部控制 MCP 初始化公开群、玩家/推演 QQ 映射、秘书绑定、世界装载；GM 私有文档区。工单/发布编排不在本阶段。

## 1. 目标

- DSH/Hermes 通过 Bearer MCP 在运行时完成控制初始化；
- 已恢复的 TaskflowEngine 身份映射是玩家/推演身份的事实源；
- 动态注册的玩家立即获得独立秘书 Session/workspace，旧私聊路由继续工作；
- 公开群 ID 可动态设置并持久化到 events.db；
- GM 私有文档 `dataDir/gm-private/{world,plot,settings,notes}/` 只经 GM MCP 访问；
- 初始化/注册/绑定幂等，非法参数 fail-closed，重启可恢复。

## 2. 运行期控制状态

新增 app `ControlBootstrapService`（组合根适配器；taskflow 保持领域模型）：

```text
ControlStateSnapshot(revision, publicGroupId, initializedAt)
```

- 身份映射不复制成第二事实源：玩家/推演身份以 `TaskflowEngine` 快照/事件为准；
- `ControlBootstrapService` 启动时从 `engine.playerProfiles()` / `engine.directorProfiles()` 重建
  QQ→`MappedIdentity` 映射与 QQ→playerId 目录；
- 所有 engine 玩家重建 `SecretarySession`（workspace 幂等 ensure）；因此动态注册的玩家
  重启后仍可私聊进入秘书；
- `publicGroupId` 从最新 `CONTROL_STATE` 事件读取，缺失时回退启动配置；
- `revision` 为控制面本地单调版本，仅用于摘要/幂等，不替代 engineRevision。

## 3. MCP 工具

### 3.1 控制初始化

```text
tf_control_initialize
tf_control_set_public_group
tf_control_register_player
tf_control_register_director
tf_control_bind_secretary
tf_control_load_world
```

- 全部 roles=supervisor，经 `ToolCallAuthorizer`；无 SYSTEM/`*`；
- `initialize`：幂等返回当前公开群、玩家/推演/秘书摘要、engineRevision；
- `set_public_group`：先追加 `CONTROL_STATE` 完整 `ControlStateSnapshot` 事件，
  append 成功后才替换内存值；失败不改变状态；
- `register_player`：校验 playerId/QQ/displayName、唯一性；调用
  `TaskflowEngine.registerPlayer`（expectedRevision 从当前 engine 读取）；成功后更新
  运行期 QQ 目录、workspace 和 SecretarySession；事件 `CONTROL_PLAYER_REGISTERED` 为
  审计/重建辅助，engine 快照是身份恢复事实源；
- `register_director` 同理，事件 `CONTROL_DIRECTOR_REGISTERED`；
- `bind_secretary` 幂等：玩家必须已注册；缺失会话时创建，已存在则返回既有摘要；
  事件 `CONTROL_SECRETARY_BOUND`；
- `load_world`：接受 fact 数组（factId/content/visibility），worldVersion 固定为当前
  worldVersion+1，调用 `engine.loadWorldFacts`；成功后刷新 WorldDocs；事件
  `CONTROL_WORLD_LOADED` 为摘要，完整状态由 `TASKFLOW_SNAPSHOT` 保存。

### 3.2 GM 私有文档

```text
tf_gm_doc_list
tf_gm_doc_get
tf_gm_doc_write
tf_control_load_gm_doc
```

- 根目录固定 `dataDir/gm-private`；逻辑根仅 `world/plot/settings/notes`；
- 拒绝绝对路径、`..`、反斜杠、控制字符、软链逃逸；只接受 UTF-8 文本；
- `list` 返回逻辑路径/大小/更新时间，不返回物理路径；
- `get` 返回正文与内容 hash；
- `write` 支持 `replace=false` 默认拒绝覆盖；写临时文件 + fsync + 原子 move，权限 600；
- 写入成功后 best-effort 追加 `GM_DOC_CREATED/UPDATED` 完整正文事件；事件失败不回滚
  文件，也不把已成功文件写伪装成失败（P3 文件区仍为 GM 文档可读事实源，P7 再投影对账）；
- 不注册到秘书/玩家工具面。

## 4. 动态路由

- `InteractionTaskflowService` 使用 `ControlBootstrapService.resolve(qqId)`，不能继续持有
  启动时 map 快照；
- `TurnResultDistributor` / `PostDistributor` / `ComplaintDistributor` 增加动态端口：
  `Supplier<String> publicGroupId`、`Function<String,String> playerQqById`；
  旧固定 map 构造器保留给测试；
- `AckPolicy` 增加动态 supplier 形式；新注册玩家立即进入 ACK 策略；
- `SecretarySessionRegistry` 增加幂等 ensure；`SecretaryWorkspaceStore` 增加
  `ensurePlayer`；
- 新注册玩家不需要重启即可接收私聊、ACK、回合结果和私密推文/投诉回应。

## 5. 失败与重启语义

- 控制 MCP 非回环/无 token 的既有 fail-closed 语义不变；
- 动态状态目录不可安全建立、QQ/playerId 冲突、非法路径 => 零身份变更；
- 公开群事件 append 失败 => 内存值不变并返回稳定失败；
- engine 注册成功后的控制审计事件失败 => 记录 ERROR，不回滚身份（engine 快照/事件
  已是身份事实源），返回成功并在摘要中保留 engine revision；
- 重启后从 engine 恢复动态身份、从 workspace 恢复会话、从最新 `CONTROL_STATE` 恢复
  公开群；
- 旧配置玩家/推演仍是启动输入；控制面只增不改已有身份，不删除映射。

## 6. 验收

- real/loopback MCP 调用 `tf_control_register_player` 后，新 QQ 私聊进入其秘书；
- 新玩家 workspace 只包含自己的目录；秘书/玩家工具无法列/读 gm-private；
- `tf_control_set_public_group` 后重启仍使用新群；非法群/QQ/路径 fail-closed；
- 重复 initialize/register/bind 幂等；
- GM 私有文档 list/get/write 可用，`replace=false` 拒绝覆盖，路径逃逸拒绝；
- 现有 48 工具升级为预期新工具数；旧回合/投递/迁移测试不回归；
- full gate 与阶段评审通过。

## 7. 非目标 / 已知限制

- 不做工单/消息板（P4）；
- 不做 DecisionPackage/Batch 持久化（P5）；
- 不删除玩家/推演，不做多世界；
- `CONTROL_*` 审计事件不替代 engine 身份快照；
- GM 文档事件是文件写后的 best-effort 审计，P7 才做 events.db 单一投影；
- 真实 DSH/Hermes 连接仍需用户协同，P3 只做本地 loopback MCP 契约。
