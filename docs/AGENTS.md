# docs/AGENTS.md —— 文档系统规则（项目维护）

> **本文件属于项目维护，不属于项目设计。**
> 本项目文档格式采用相邻 `SimulatorMosire` 在 **2026-10-23 文档系统重写**后的分层方式：
> 薄根索引、目录级 AGENTS、唯一实时 STATUS、项目设计/项目维护分账、工程 Skill 目录、机械检查。
>
> **当前状态唯一入口**：[`agents/STATUS.md`](agents/STATUS.md)。
> 本文件只规定“文档放哪、谁说了算、什么时候写、怎么被检查”。

## 一、两条文档轴：项目设计 ≠ 项目维护

| 轴 | 收什么 | 正本位置 | 不放在哪里 |
|---|---|---|---|
| **项目设计** | 代码结构、模块边界、数据所有权、事件/API/载荷、运行时阶段、QQ/Agent/MCP 交互、提示词与可见性语义、数值公式 | `docs/superpowers/specs/**`；索引在 `docs/superpowers/DESIGN_RULINGS.md` | 不写进维护裁定；不把项目维护流程塞进设计稿 |
| **项目维护** | 文档系统、Skill、门禁、验证、工作流、协作、环境、提交/归档 | `docs/agents/MAINTENANCE_RULINGS.md`、`docs/AGENTS.md`、`docs/agents/*.md` | 不写进设计原稿；不冒充用户设计裁定 |

**分账规则**：

1. 一条规则只能有一个主正本；另一侧只放链接，不复制全文。
2. 同一条用户原话影响两边时拆开记录：设计影响进设计文档，维护影响进 `MAINTENANCE_RULINGS.md`。
3. 代码“为什么这样”属于设计；代码“怎么验证/派单/提交”属于维护。
4. 分不清时先停手补充分类，不默认塞进根 `AGENTS.md`。

## 二、文档分层

| 层 | 路径 | 放什么 | 何时读 |
|---|---|---|---|
| 常驻路由 | `AGENTS.md` | 项目简介、冲突顺序、常驻红线、模块与文档索引 | 总是 |
| 目录局部 | `docs/AGENTS.md`、`docs/agents/AGENTS.md`、`docs/superpowers/AGENTS.md`、未来各模块 `AGENTS.md` | 本目录特有规则与局部索引 | 触达该目录后 |
| 实时状态 | `docs/agents/STATUS.md` | 当前目标、源码基线、最近门禁、未验证、下一步 | 每次开工/收工、压缩前 |
| 维护裁定 | `docs/agents/MAINTENANCE_RULINGS.md` | 文档、Skill、门禁、协作、环境等维护裁定的逐字原话 + 落点 | 改维护机制前 |
| 维护手册 | `docs/agents/WORKFLOW.md`、`VERIFICATION.md`、`ENVIRONMENT.md` | 当前可执行流程、命令、证据规则 | 对应任务开始时 |
| 工程 Skill | `.agents/skills/<name>/SKILL.md` | 宿主可发现的可复用判断流程；目录见 `docs/agents/SKILLS.md` | 任务匹配时 |
| 项目设计 | `docs/superpowers/specs/**` | 需求原话、专题设计、目标运行序列、契约、不变量、验收判据 | 设计/实现前 |
| 设计索引 | `docs/superpowers/AGENTS.md`、`DESIGN_RULINGS.md` | 设计目录规则、D-xxx 与专题入口 | 找设计依据时 |
| 计划 | `docs/superpowers/plans/**` | 按设计拆出的实施顺序；不是设计本身 | 派单/执行前 |
| 调查/报告 | `docs/superpowers/reports/**` | 调查、审计、复现与证据摘要 | 追溯事实时 |
| 评审 | `docs/superpowers/reviews/**` | 独立评审与裁决 | 评审/关账时 |
| 历史快照 | `docs/superpowers/status/**` | 某时点状态、交接、证据摘要；**不是当前状态入口** | 只作追溯 |
| 上游契约 | `docs/upstream/**` | 本仓消费的三库、NapCat/OneBot、MCP 等外部契约摘要与出处 | 接线前 |
| 决策解释 | `.agents/notes/{proposed,implemented,archived}/**` | 为什么这样定、被谁取代；不替代设计/维护正本 | 需要背景时 |
| 机械强制 | `tools/check-docs.py`、`tools/skills-lint.py`、`tools/gate.sh`、CI | 能退出码判定的检查 | 直接运行 |

## 三、权威顺序与取代关系

### 3.1 冲突顺序

1. 用户当次明确指令；
2. 用户项目设计裁定（需求原话 / 当前有效设计）；
3. 项目维护裁定（本文件 / `MAINTENANCE_RULINGS.md`）；
4. 当前有效目标运行设计或契约；
5. 根 `AGENTS.md` 的路由与常驻红线；
6. `STATUS.md` 当前事实（只覆盖事实，不覆盖设计/维护规则）；
7. 旧文档、历史 status、调查报告与外部资料。

### 3.2 取代怎么写

- 被取代的文档不删，在文件头加：
  `> **状态**：历史（YYYY-MM-DD）· **取代者**：<路径> · **勿据此开工**。`
- 取代者一侧在标题下写：`> **本文取代**：<旧路径>（YYYY-MM-DD）。`
- 局部修正只标范围和取代者，不把整篇历史文档改写成“当前事实”。
- **不得按文件名日期推断有效期**；以正文状态头、用户原话日期和实际代码/测试为准。

## 四、记录频率

| 触发 | 必须记录 |
|---|---|
| 用户给出新裁定 | 先分类：项目设计写设计原稿/专题 spec，项目维护写 `MAINTENANCE_RULINGS.md`；都保留原话与日期 |
| 开始一个责任区 | 在 `STATUS.md` 写当前目标、范围、预期产物；计划落 `docs/superpowers/plans/` |
| 完成一个可验证步骤 | 更新 `STATUS.md`：改动、命令、rc、测试数、证据路径、未验证项 |
| 门禁/真模型/长跑完成 | 在报告或 dated status 留真实输出；`STATUS.md` 只留摘要和链接 |
| 发现文档矛盾 | 记录双方路径/日期/事实；能判定的在旧文加取代头；不能判定的列入待裁定 |
| 子 Agent 交接/上下文压缩前 | 更新 `STATUS.md` 的下一步第一条命令、当前 dirty、未验证项 |
| 发现可复用判断流程 | 先登记 Skill 候选；经真实任务验证后再进 `.agents/skills/`，不批量造空 Skill |
| 变更维护机制 | 先写 `MAINTENANCE_RULINGS.md` 条目，再改脚本/CI/目录 |

## 五、STATUS 与交接模板

`docs/agents/STATUS.md` 至少包含：

```text
更新时间（绝对日期 + 时区）
当前目标 / 进行中范围
源码基线（分支、HEAD、upstream、工作树状态；无 Git 时写“未初始化”）
最近功能门禁（命令、rc、测试数、证据路径、是否 full；没跑写 NOT_RUN）
当前有效设计/维护入口
未验证 / 风险 / blocker
下一步（可执行的第一条命令）
```

交接时另写 dated 文件也可以，但必须：

- 放在 `docs/superpowers/status/`；
- 文件头标 `历史快照` + 指向当前 `docs/agents/STATUS.md`；
- 不把 dated 文件叫作“最新/唯一入口”。

## 六、目录级 AGENTS 规则

- 每个有独立工作规则的目录可以有 `AGENTS.md`；根到 cwd 的每级会被宿主按祖先链加载。
- 局部文件必须短：本目录职责、局部边界、关键入口、验证命令、当前文档入口。
- 局部文件不得复制根常驻红线、不得另立用户裁定、不得把历史状态写成当前事实。
- Maven 模块落地时必须同批补 `AGENTS.md`；模板以本仓未来首个模块为准。
- 局部规则只在本目录范围生效；与设计/维护正本冲突 ⇒ 停手上报。

## 七、Skill 与规则的边界

| 规则形态 | 去处 |
|---|---|
| 项目设计硬约束（模块边界、数据所有权、事件/工具/公式） | `docs/superpowers/specs/**` |
| 项目维护硬约束（文档格式、门禁、提交、工作树） | `MAINTENANCE_RULINGS.md` / `docs/agents/**` |
| 能机械判定的内容 | `tools/check-docs.py`、`tools/skills-lint.py`、`tools/gate.sh`、测试 |
| 只在某类重复任务里使用的判断流程 | `.agents/skills/<name>/SKILL.md` |
| 当前事实和一次性证据 | `STATUS.md` / `docs/superpowers/status/**` / 报告 |

工程 Skill 的目录、状态机和契约见 [`agents/SKILLS.md`](agents/SKILLS.md)。

## 八、机械检查

在仓库根执行：

```bash
python3 tools/check-docs.py
python3 tools/check-docs.py --selftest
python3 tools/skills-lint.py
python3 tools/skills-lint.py --selftest
# 或
tools/gate.sh docs
```

CI：`.github/workflows/docs-skills.yml`。当前已有 4 个 Maven 模块 `iibot-connect` / `iibot-video-connect` / `iibot-bot` / `iibot-app`；检查脚本同时校验模块 `AGENTS.md`。
历史文游文档归档在 `docs/archive/legacy-game/`，不进入当前链接门禁和导航。

检查目标包括：根入口是薄索引、当前文档相对链接存在、`STATUS.md` 存在且有关键字段、设计/维护分账标志存在、
dated status 标明历史快照、目录与 Skill 登记一致、无 `@` 导入长文。

## 九、引用与日期

- 引用用户原话必须逐字，日期取用户实际说出的日期。
- 现状事实给 `file:line` 或 URL；跑过的结论给命令、rc、关键输出与证据路径。
- 没有跑过的结论写 `NOT_RUN` / `未验证` / `BLOCKED`；不写“通过”。
- 外部资料分为“一手文档”与“二次转述”；NapCat/OneBot/AgentLib 的 API 结论优先给官方 URL 或本地源码行号。
- 长文用普通 Markdown 链接，不用 `@` 导入。
