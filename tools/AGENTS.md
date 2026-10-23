# tools —— 文档与工程检查脚本

## 职责

- `check-docs.py`：文档体系结构、链接、STATUS 字段、设计/维护分账、历史快照、Spec 状态头、Skill 目录登记、worktree 规则。
- `skills-lint.py`：`.agents/skills/<name>/SKILL.md` 契约检查。
- `gate.sh`：统一入口：
  - `docs`：文档 + Skill 检查；
  - `selftest`：检查器自检；
  - `pre`：docs + Maven Wrapper/Java 工具链；
  - `focused <module>`：`./mvnw -pl <module> -am test`；
  - `full`：`./mvnw clean verify`（当前含 enforcer/tests/package/shade，尚无 Spotless/Checkstyle/SpotBugs）。

## 边界

- 检查器只做机械判定，不替代设计评审。
- `check-docs.py` 支持 pre-Maven 阶段，但当前已存在 `pom.xml`，会检查 4 个模块与局部 `AGENTS.md`。
- 修改检查器时同步更新 selftest；测试必须覆盖至少一个正例和若干负例。
- 报告必须输出真实 rc 与关键计数；不跑写 `NOT_RUN`。
- 不用 `-Dskip*`、`-q` 等削弱门禁的方式制造假绿。

## 运行

```bash
python3 tools/check-docs.py
python3 tools/check-docs.py --selftest
python3 tools/skills-lint.py
python3 tools/skills-lint.py --selftest
tools/gate.sh docs
tools/gate.sh selftest
tools/gate.sh pre
tools/gate.sh focused iibot-connect
tools/gate.sh full
```
