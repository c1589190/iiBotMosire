# P6：发布编排、回合 Markdown 投影与记录查询

> **状态**：有效（P6 实施设计；依据 ideal workflow §3.5/§5.5 与 implementation plan P6）
> **日期**：2026-10-24
> **用户原话**：「推文推送到公开推演群和各个玩家（看需求，最好公开推文和私密推文的内容不重合、私密推文有发送必要，不过这就是推文协作skill的内容了），允许秘书Agent自由查看能查看的推文范围并作解读」。完整原话见 `2026-10-24-user-requirements-source.md`。
> **范围**：已 commit ActionBatch → 中央 LLM 结构化发布草稿 → GM 审核/发布；records Markdown 投影；记录查询 MCP。P7 跳过，不做全领域事件回放。

## 1. 目标

- GM 选择已 COMMITTED 的 ActionBatch 触发生成 Public/Private 推文草稿；
- 发布草稿只进入 `PENDING_APPROVAL`，GM 审核前绝不发 QQ；
- 现有 `tf_post_decide` / `tf_post_publish` 负责最终审核发布；
- 每回合多个批次都能进入同一 Turn 记录；
- `records/turns/<turnNo>-<turnId>/` 是 events.db/领域快照的幂等投影，不是第二事实源；
- 查询 MCP 提供 turn 列表/详情/筛选，不返回其他玩家私密内容给秘书。

## 2. 发布编排

新增 app `PublicationOrchestrator`（依赖 PostService、ActionPackageService、LlmClient、EventContentStore、DomainEventJournal、Clock）：

- `generate(gm, batchId, idempotencyKey)`：
  1. 找 ActionPackageService 中 COMMITTED ActionBatch；不存在/未 commit fail-closed；
  2. 若已有 `PUBLICATION_GENERATED` 记录，冪等返回；
  3. 用中央 LLM 将 batch.result + world facts + batch行动生成严格 JSON：
     `{"publicNarrative": "...", "privateOutcomes": {"playerId": "..."} }`；
  4. privateOutcomes key 必须是该 batch 的 playerIds 子集；public/private 均非空的才建 post；
  5. PostService 增加 GM 专用 `createPublicationDraft`：public 的 authorPlayerId 固定 publication 作者，
     private 的 authorPlayerId 必须是 recipient player；统一进入 PENDING_APPROVAL；
  6. 追加 `PUBLICATION_GENERATED` 事件，content 保存完整 PublicationRecord（batchId/turnId/
     publicPostId/privatePostIds/public/private正文/hash）；summary 只放索引；
  7. 事件 append 失败 => POST 已创建但 publication mapping 未落库，返回
     `PUBLICATION_EVENT_UNAVAILABLE`；重试同 batch 必须复用 PostService 幂等键，不重复建 post。
- 结构化输出必须严格 schema、最大长度、无 markdown code fence；失败不建 post，不发送。
- `LlmActionBatchExecutor` 在 commit 结果中填当前 turnId（若存在），供批次投影。

## 3. Markdown 回合投影

新增 app `TurnRecordProjector`：

```text
dataDir/records/turns/<turnNo>-<turnId>/
  index.md
  public-narrative.md
  players/<playerId>.md
  actions/<actionId>.md
  packages/<packageId>.md
  batches/<batchId>.md
  posts/<postId>-<visibility>.md
```

- 从 engine.exportState().turns + ActionPackageService.batches/packages + PublicationOrchestrator records + PostService 投影；
- 写文件使用安全逻辑 ID、同父 staging + 备份替换/失败恢复、600 best-effort；`records`/`records/turns` 父级软链与 realPath 逃逸 fail-closed；
- 同一输入重复投影结果不变；投影失败只记录/返回错误，不回滚领域事件；
- public-narrative 只来自 public publication；players/<playerId> 只来自该玩家的 private outcome；
- `index.md` 含 turnId/worldVersion/status/batchIds/packageIds/postIds/content hashes。

## 4. 记录查询 MCP

新增 3 个 supervisor 工具：

```text
tf_record_turn_list
tf_record_turn_get
tf_record_query
```

- turn_list 返回 turnNo/turnId/status/batch/post 计数；
- turn_get 可按 file 读取 index/public/players/actions/packages/batches/posts 的 Markdown，只允许 turn 目录内安全相对路径；读取前校验 index manifest 文件集合/逐文件 hash，不一致返回 RECORD_INCONSISTENT；
- record_query 按 kind/turnId/playerId/postId/batchId 过滤摘要；
- 不返回物理路径、不返回其他玩家私密正文。

## 5. 工具数与验收

- 完整 MCP：61 + `tf_publication_generate` + 3 record 工具 = 65。
- PostService 所有写命令使用 actor/command/request fingerprint：同 key 不同命令/请求必须 IDEMPOTENCY_KEY_CONFLICT，禁止历史成功回执跨命令复用。
- 验收：
  - COMMITTED batch 可生成 PENDING_APPROVAL public/private posts；
  - GM 审核前无任何发送；approve/publish 后 PostDistributor 才出站；
  - 同一回合两批次均进 records，public/private 不混；
  - 秘书只能读已发布推文（既有 PostService 权限保持不变）；
  - records 幂等重建，Markdown 与领域内容 hash 可对账；
  - events.db/领域服务仍是 canonical，Markdown 不手工写入；
  - full gate 与阶段评审通过。

## 6. 非目标

- P7 全领域回放跳过；仍以 taskflow-state.json + events.db 混合恢复；
- 不做推文内容重合硬校验（按用户原话属于推文协作 skill / GM 审核）；
- 不做真实 QQ/LLM 联调；P8 再做。
