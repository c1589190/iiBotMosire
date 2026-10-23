# 环境维护手册

> 本机环境事实会变化；命令执行前重新核对版本与路径，不以本文代替 `java -version`/`./mvnw -v` 输出。

## 一、工具链现状

| 项 | 当前事实 |
|---|---|
| 工作目录 | `/home/cna/iiBotMosire` |
| Java | OpenJDK 21.0.12.1（`java -version` 于 2026-10-24 实跑） |
| Maven | 仓库内 `./mvnw`，Maven Wrapper 3.9.16；`./mvnw -v` 已实测 |
| Python | 文档检查脚本需要 Python 3；`python3` 可直接调用 |
| Git | 当前目录未初始化 Git |
| OS | Linux，当前时区 Asia/Shanghai |
| 内存 | 本机约 11 GiB；当前测试/打包不需要额外堆参数 |

## 二、参考仓与上游

| 路径 | 用途 | 当前事实 |
|---|---|---|
| `~/ProjectMosire` | AgentLib / Workspace / Docs 三库上游 | HEAD `79b349fb`，tag `v0.2.3`；工作树有 5 个未跟踪本地文件 |
| `~/DevMosire` | 旧工程实践参考 | 只读审计；GPL/无许可证仓不得直接复制代码，目标架构不继承 |
| `~/SimulatorMosire` | 文档系统格式参考、三库消费者参考 | 仅采用 2026-10-23 文档系统层级；不复制其业务设计 |

- 上游库坐标与发布页：`https://c1589190.github.io/ProjectMosire/`，当前版本 `0.2.3`。
- 消费三库前先核 POM 与本地/远程仓库解析；当前使用固定版 `0.2.3` 而不是 `0.2.3-SNAPSHOT`。

## 三、工作树与 Maven 规则

- 未来所有 git worktree 统一放 `<仓库根>/worktree/<name>/`，不再使用 `.claude/worktrees/`。
- `/worktree/` 已加入 `.gitignore`；忽略不等于删除，`git worktree list` 仍能看到。
- 当前单 checkout 使用 `./mvnw`；未来工作树应各自使用独立 Maven 锁/仓库，同树串行、跨树并行。
- 同一 checkout 开 Maven 前先看进程与锁；不得在 Maven 窗口改 Java；不覆盖正在运行的 jar。

## 四、运行时数据

- 临时证据放 `.work/`；长期运行数据放显式配置的数据目录，默认不写仓库。
- SQLite、日志、NapCat 配置、LLM key 不得提交。
- 真实 NapCat 测试使用隔离 QQ 小号/实例；真实模型测试使用独立 provider 配置与独立数据目录。
- 端口未固定；实现时由配置注入并先检测占用，不把临时端口写死在设计文档中。

## 五、换设备自检

1. `java -version`；
2. `python3 --version`；
3. `git status`（若已有 Git）；
4. `tools/gate.sh docs`；
5. 有 Maven 后：`tools/gate.sh pre`，然后聚焦测试/全量门禁；
6. 没跑的项按 `VERIFICATION.md` 写 `NOT_RUN`。
