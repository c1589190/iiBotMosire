# simulation/AGENTS.md —— 推演工作区局部规则

> 更新时间：2026-10-25 23:31（Asia/Shanghai）
> 本文件按上级 `../docs/AGENTS.md` 的项目文档规范落地，保留 simulation 范围内的最小可执行副本；不复制上级 `AGENTS.md` 的常驻红线。
> 上级项目入口：`../AGENTS.md`；上级文档规范：`../docs/AGENTS.md`；完整规则以该上级文件为准。

## 0. 冲突顺序

1. 用户当次明确指令。
2. 本文与 `docs/AGENTS.md`、`docs/world/AGENTS.md` 的局部文档规则。
3. `.agents/skills/` 中 active Skill 的 Agent 行为规则。
4. `docs/world/` 中的世界设定与公开/秘密数据。
5. `docs/STATUS.md` 的当前事实。
6. `campaign/` 中的流程、历史快照和归档材料。
7. `runtime/`、`logs/`、`runtime.backup-*` 中的运行数据与历史证据。

发生冲突时先停手并记录冲突路径；不得用旧文档或备份反推当前运行事实。

## 1. 三条文档轴

| 轴 | 收什么 | 正本位置 |
| --- | --- | --- |
| Agent 行为 | 推演/GM 与秘书的可复用判断流程 | `.agents/skills/<name>/SKILL.md` |
| 世界设定 | 世界事实、公开材料、秘密数据、角色与机构 | `docs/world/` |
| 运行事实 | app 当前状态、世界版本、玩家、回合、事件 | `runtime/`；摘要见 `docs/STATUS.md` |

`campaign/` 只保留报名、开本、秘书工作区模板、历史复盘与旧材料；世界设定不再长期留在 campaign。

## 2. 目录路由

```text
simulation/
  AGENTS.md                 # 本文件：局部规则与路由
  docs/
    AGENTS.md               # docs 目录规则
    STATUS.md               # 当前运行事实摘要
    agents/
      SKILLS.md             # active Skill 目录
    world/
      AGENTS.md             # 世界文档规则
      README.md             # 公开/秘密索引
      public/               # 所有秘书可读的公开材料与推文
      secret/               # GM-only 的秘密数据
    archive/                # 历史快照（如启用）
  .agents/skills/
    iibot-referee-behavior/SKILL.md    # 通用推演/GM 行为
    iibot-secretary-behavior/SKILL.md  # 通用秘书行为
    iibot-live-simulation-gm/SKILL.md  # 真实联调工程操作
  campaign/                 # 报名、开本、模板、历史与旧材料
  runtime/                  # app 当前运行数据（不手改）
  logs/                     # app 日志
  scripts/                  # 启动、停止、MCP smoke、Skill 加载
  config/                   # 模拟配置与初始事实
  dist/                     # 部署 jar
```

## 3. 文档撰写规则

1. 每个有独立规则的目录可以有 `AGENTS.md`，但必须是薄索引：职责、边界、入口、验证方式，不复制长篇规则。
2. 当前事实只写在 `docs/STATUS.md`；历史快照必须标 `历史快照`，并指向当前状态入口。
3. 任何结论都要给证据：命令、rc、关键输出、时间、文件路径；没有跑过的写 `NOT_RUN`，不写“通过”。
4. 引用用户原话必须逐字，日期取用户实际说出的日期；不按文件名日期推断有效期。
5. 使用普通 Markdown 相对链接；禁止 `@` 导入长文。
6. 世界专有名词、角色、机构、资源只能出现在 `docs/world/` 与 `campaign/` 的世界材料中；不得写进通用 Agent Skill。
7. 公开内容必须能被所有秘书阅读；秘密内容只能放在 `docs/world/secret/`，不得自动加载到任何秘书共享 Skill 或玩家工作区。
8. 运行态 `runtime/` 是事实数据，不是文档；不要用 Markdown 反推当前世界、回合或投递状态。
9. 文档修改后，至少检查链接是否存在、是否泄漏 token/QQ/私密正文、是否需要同步 `docs/STATUS.md`。

## 4. Agent Skill 规则

- 每个 Skill 一个文件夹：`.agents/skills/<name>/SKILL.md`。
- 必须带 frontmatter：`name`、`description`、`status`、`owner`、`last_reviewed`、`next_review`。
- active Skill 登记在 `docs/agents/SKILLS.md`。
- `iibot-secretary-behavior` 是通用秘书行为，必须能单独加载到 iibot 共享 Skill；加载脚本为 `scripts/09-load-secretary-skill.sh`。
- `iibot-referee-behavior` 是通用推演/GM 行为，供 DSH GM 使用；不加载给玩家秘书。
- `iibot-live-simulation-gm` 是真实联调工程操作，不是玩家可见行为规则。
- `runtime/secretaries/player-*/AGENTS.md` 是每个秘书自己的工作区规则；保留原样，不用通用 Skill 覆盖。

## 5. 世界文档规则

- `docs/world/public/`：所有秘书可读的公开世界常识、报名说明、公开推文和玩家可见材料。
- `docs/world/secret/`：GM-only 的完整设定审计、隐藏事实、秘密角色资料、压力池和可装载秘密事实。
- 世界设定正本不等于运行事实；如需改变运行世界，必须通过 `tf_control_load_world` 或对应 `tf_*` 工具，并更新 `docs/STATUS.md`。
- 公开/秘密目录只是逻辑隔离，不是操作系统 ACL；GM 必须确保秘密文件不被加载到秘书共享 Skill 或玩家工作区。

## 6. 启动与停止

- app 使用 `simulation/runtime/` 作为 `dataDir`。
- 启动：`scripts/02-start.sh`；随后启动本机 MCP 凭据代理，并按需运行 `scripts/09-load-secretary-skill.sh`。
- 停止：`scripts/03-stop.sh`；同时停止代理和残留 watcher。
- app 重启后 embedded Inbox 随机端口会变化，必须重新获取 descriptor、重新绑定 adapter。

## 7. 当前入口

- 当前状态：`docs/STATUS.md`
- 推演行为：`.agents/skills/iibot-referee-behavior/SKILL.md`
- 秘书行为：`.agents/skills/iibot-secretary-behavior/SKILL.md`
- 世界公开：`docs/world/public/`
- 世界秘密：`docs/world/secret/`
- 运行事实：`runtime/taskflow-state.json`、`runtime/events.db`
