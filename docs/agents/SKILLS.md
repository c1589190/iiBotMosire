# 工程 Skill 目录与维护规则

> **本文件属于项目维护。** 工程 Skill 面向维护本仓库的编码 Agent，不是产品内运行时 Skill。
> **当前 active Skill = 0**。没有经过真实任务验证的判断流程不进入 `.agents/skills/`。

## 一、什么该进 Skill

1. **项目设计硬约束** → `docs/superpowers/specs/**`；
2. **项目维护硬约束** → `docs/agents/MAINTENANCE_RULINGS.md` / `docs/AGENTS.md`；
3. **能机械判定的** → `tools/check-docs.py`、`tools/skills-lint.py`、`tools/gate.sh`、测试；
4. **只在某类重复任务中使用的判断流程** → `.agents/skills/<name>/SKILL.md`；
5. **当前状态/一次性证据** → `docs/agents/STATUS.md`、`docs/superpowers/status/**`、报告。

## 二、状态机

```text
proposed -> drafted -> pilot -> active -> deprecated -> retired
                         |          |
                         +----------+--(回归/宿主失配/越界)--> drafted / blocked
```

| 状态 | 位置 | 能否被发现 | 进入条件 |
|---|---|---|---|
| proposed | `.work/skill-candidates/<name>/` | 否 | 有重复任务/返工迹象，先登记候选 |
| drafted | `.work/skill-candidates/<name>/` | 否 | 已写 `SKILL.md` 草案，契约自检通过 |
| pilot | `.work/skill-candidates/<name>/` | 否 | 已在真实任务中对照验证，仍只显式使用 |
| **active** | **`.agents/skills/<name>/SKILL.md`** | **是** | pilot 通过；有 owner、复核日、退役条件；登记到本表 |
| deprecated | `docs/archive/skills/<name>/` | 否 | 失真、被替代、无 owner、维护成本大于收益 |
| retired | `docs/archive/skills/<name>/` | 否 | 归档/删除实现，只留历史说明 |

硬规则：

- 不跳过 pilot 直接 active；没有真实任务证据的 Skill 不进入 `.agents/skills/`。
- 任何导致误导执行的 Skill 先停用再修。
- 候选/归档 Skill 不得放进 `.agents/skills/`，否则宿主会误发现。

## 三、当前 active Skill

| name | 状态 | 触发场景 | 路径 | owner | 最近复核 | 下次复核 | 退役条件 |
|---|---|---|---|---|---|---|---|
| （当前无） | — | — | — | — | — | — | — |

## 四、候选与归档登记

| name | 状态 | 触发场景 | 位置 | 说明 |
|---|---|---|---|---|
| （当前无） | — | — | — | 新候选先放 `.work/skill-candidates/` |

## 五、Skill 契约

`SKILL.md` 最小 frontmatter：

```yaml
---
name: <目录名>
description: <一句话说明何时使用>
status: active
owner: repo-maintainers
last_reviewed: YYYY-MM-DD
---
```

必备章节：

1. 何时使用 / 何时不使用；
2. 前置条件（目录、工具、权限、是否联网/跑 Maven）；
3. 步骤（可执行、带工作目录）；
4. Gate（命令、退出码、必须核对的输出/证据）；
5. Report（任务、基线、实际执行、改动、未做/未验证、证据边界）；
6. 失败与停止条件；
7. 维护（owner、复核周期、退役条件）。

体量与安全：

- `SKILL.md` 建议 ≤ 200 行，单文件 ≤ 32 KiB；
- 不写本机临时目录、密钥、token、真实配置；
- 不用 `@` 导入长文；
- 不复制仓库级根规则；需要时放链接。

## 六、机械检查

```bash
python3 tools/skills-lint.py
python3 tools/skills-lint.py --selftest
# 或
tools/gate.sh docs
```

检查内容：只允许一层 active 目录、frontmatter/目录名一致、`status=active`、必备元数据、大小上限、
相对链接存在、目录和本表登记一致。CI：`.github/workflows/docs-skills.yml`。
