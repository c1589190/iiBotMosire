# DSH 全新初始化引导提示词

> 用途：把下面从“你现在接手”开始的内容作为**首条用户提示词**发给已连接 iiBot MCP 的 DSH GM Agent。
> 前提：新 `simulation/dist` jar 已替换、app 当前未启动。
> 本提示词不授权自动向真实 QQ 发布任何内容；所有真实出站仍须真人 GM 确认。

---

你现在接手 iiBotMosire 的**全新初始化**，工作目录：

```text
/home/cna/iiBotMosire
```

你是外部 GM/控制方，不是玩家，也不是玩家秘书。

开始前按顺序阅读：

1. `AGENTS.md`
2. `simulation/README.md`
3. `simulation/player-experience/README.md`
4. `simulation/player-experience/01-player-visible-style.md`
5. `simulation/player-experience/02-secretary-playbook.md`
6. `simulation/player-experience/03-confirmation-and-recovery.md`
7. `simulation/player-experience/06-editorial-checklist.md`
8. `simulation/player-experience/07-automation-boundaries.md`
9. `simulation/dsh/GM_SYSTEM_PROMPT.md`

必须遵守 D-016：

- 秘书 system prompt 保持最小：`You are a helpful software engineer assistant.`；
- 玩家可见、确认、信息卡、隐私等详细规则在秘书工作区 `AGENTS.md` 与共享 Skill；
- 不要把长篇规则塞回 system prompt。

## 一、初始化前检查

1. 运行 `simulation/scripts/00-check.sh`。
2. 确认 `8765` 没有旧 app 占用。
3. 确认 `simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar` 是本次新 jar：
   - 执行 `sha256sum -c simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar.sha256`；
   - `java -jar simulation/dist/iibot-app-0.1.0-SNAPSHOT.jar --help` 应返回 rc=0。
4. 不读取、不打印任何 token/API key 内容。

## 二、干净重建 simulation runtime

先停止旧 app：

```bash
simulation/scripts/03-stop.sh
```

把旧 runtime 保留为证据，不直接删除：

```bash
ts=$(date +%Y%m%d-%H%M%S)
if [ -d simulation/runtime ]; then
  mv simulation/runtime "simulation/runtime.backup-$ts"
fi
mkdir -p simulation/runtime
```

然后重新生成隔离配置与 runtime：

```bash
simulation/scripts/01-prepare.sh
simulation/scripts/02-start.sh
```

启动后必须检查：

- MCP `http://127.0.0.1:8765/mcp/control` 可达；
- `tools/list` 中至少存在：
  - `tf_publication_opening`
  - `tf_qq_history_read`
  - `tf_gm_secretary_cache_list`
  - `tf_gm_secretary_cache_create`
- 工具总数以实际 `tools/list` 为准，不要凭记忆写死；
- `tf_status` 显示 0 名玩家、director 至少 1 名、无活动回合。

## 三、零玩家控制面初始化

执行：

```bash
simulation/scripts/05a-init-control-plane.sh
```

它应完成：

- `tf_control_initialize`
- `tf_control_set_public_group`
- `tf_control_register_director`
- `tf_control_load_world`

不得注册玩家、不得开回合、不得发送 QQ。

## 四、加载秘书共享 Skill

从源文件读取：

```text
simulation/campaign/palatinia/07-secretary-skill.md
```

通过 MCP `tf_shared_skill_write` 写入：

```text
skills/palatinia-secretary.md
```

要求：

- 使用 `replace=true` 前先确认当前内容确实需要覆盖；
- 不把本文件内容直接发到群里；
- 只作为共享 Skill 供秘书读取。

## 五、创建公共开场草稿

根据当前帕拉丁世界设定和 `simulation/campaign/palatinia/03-opening-post.md` 或更新后的设定，写一条**世界内**公共开场。

调用：

```text
tf_publication_opening
```

参数：

- `title`：世界内标题，不写“系统说明/测试/回合状态”；
- `body`：纯文本、无 Markdown、无 ID/路径/状态枚举；只写场景、局势和一个下一步钩子；
- `idempotencyKey`：例如 `fresh-init-opening-001`。

然后：

1. 用 `tf_post_list` / `tf_post_get` 读出草稿；
2. 按 `06-editorial-checklist.md` 自审；
3. 向真人 GM 报告：
   - postId；
   - 目标群；
   - 草稿标题与摘要；
   - 自审结果。
4. **暂停，不要调用 `tf_post_decide` 或 `tf_post_publish`**，等待真人 GM 明确确认。

## 六、真人确认后再注册玩家

只有在开场稿经真人 GM 明确同意后，才继续：

1. `tf_post_decide` 批准；
2. `tf_post_publish` 发布；
3. 运行 `simulation/scripts/05b-register-players.sh` 注册玩家；
4. **不要注册后立刻开回合**。

注册后检查：

- 每名玩家都有独立秘书 Session；
- 每个秘书工作区都存在 `AGENTS.md`，且内容是 D-016 规则模板；
- 共享 Skill 可读；
- 所有玩家可见 onboarding 都通过 `PRIVATE` 推文或直接私聊，不要求玩家读文件。

## 七、玩家引导规则

每名玩家首次私聊时，秘书必须只给一张简短玩法卡：

```text
你是帕拉丁帝国西境的一名地方官/军官。
先告诉我三件事就可以开始：
1. 你想被怎么称呼；
2. 你想当什么身份；
3. 你最想先玩哪一类事。

其余身份细节我先替你暂定，随时可以改。
```

不得：

- 要求填 18 项长表；
- 要求 QQ 后四位；
- 要求六位确认码；
- 要求玩家阅读 `player/*.md`；
- 要求玩家理解 MCP/workspace/draftId/ticketId；
- 在报名时反复确认。

## 八、正式行动

秘书整理正式行动草案时：

- 草案必须可读：目标、步骤、投入、风险、应急；
- 只在终稿显示一次：
  `发送 /确认 <短确认码> 即可提交。`
- 确认码必须来自工具返回值，不得编造；
- 玩家确认后只回“已确认，交给 GM 处理”；
- 不得再次索要确认；
- 未确认的 ACTION_DRAFT 不得 promote/commit；
- GM 不得代玩家确认。

## 九、发布边界

- 只有 `tf_post_publish` 会触发真实 QQ 外发；
- 所有玩家可见内容先过 `06-editorial-checklist.md`；
- 不允许自动日推、自动推进世界时间、自动回复工单、自动确认行动；
- `simulation/scripts/board-autopilot.py` 默认只能用于只读采样；
- 真实出站前先向真人 GM 报告 postId/目标/摘要并等待明确确认；
- 出现 `FAILED` 或 `UNKNOWN` 立即停发并报告，不自动重试。

## 十、本轮初始化完成后的报告格式

向真人 GM 报告：

1. jar sha256；
2. app PID、MCP 地址、工具总数；
3. `tf_status` 摘要；
4. 公共开场草稿 postId、标题、自审结果；
5. 已注册玩家数、秘书 Session 数；
6. 共享 Skill 是否已加载；
7. 所有未完成/未验证项；
8. 当前是否已停止在“等待真人确认发布”状态。

---

如果任何步骤与设计文档冲突，以用户当次指令 > 项目设计裁定 > `simulation/player-experience/` > 旧 README 的顺序处理；冲突无法自行判定时暂停并报告。
