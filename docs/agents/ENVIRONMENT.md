# 环境维护手册

> 本机环境事实会变化；命令执行前重新核对版本与路径，不以本文代替 `java -version`/`./mvnw -v` 输出。

## 一、工具链现状

| 项 | 当前事实 |
|---|---|
| 工作目录 | `/home/cna/DevMosire-iiBot/iiBotMosire`（2026-10-27 迁移后） |
| Java | OpenJDK 21.0.12.1（`java -version` 于 2026-10-24 实跑） |
| Maven | 仓库内 `./mvnw`，Maven Wrapper 3.9.16；`./mvnw -v` 已实测 |
| Python | 文档检查脚本需要 Python 3；`python3` 可直接调用 |
| ffmpeg | 2026-10-31 实测 `/usr/bin/ffmpeg` 6.1.1；`bili_video_frames` 默认从 PATH 读取，可用 `IIBOT_FFMPEG` 覆盖 |
| Chromium / Playwright | 2026-10-31 snap 安装 Chromium `156.0.8078.47`（`/snap/bin/chromium`）；`chromium --headless=new --disable-gpu --dump-dom about:blank` PASS。Playwright Java 尚未加入，B 站页面/截图/remote-cdp `NOT_RUN` |
| Git | 独立 Git 仓库；当前在 `refactor/bot-first-main-agent` 分支 |
| Maven reactor | 4 模块：`iibot-connect` / `iibot-video-connect` / `iibot-bot` / `iibot-app`；模块局部 `AGENTS.md` 由 docs gate 校验 |
| OS | Linux，当前时区 Asia/Shanghai |
| 内存 | 本机约 11 GiB；当前测试/打包不需要额外堆参数 |

## 二、参考仓与上游

| 路径 | 用途 | 当前事实 |
|---|---|---|
| `~/ProjectMosire` | AgentLib / Workspace / Docs 三库上游 | 2026-10-28 核对：HEAD `6d2a1a8`（`release: 0.2.4`）；最新公开 tag 仍为 `v0.2.3`；工作树有未跟踪本地文件 |
| `~/DevMosire` | 旧工程实践参考 | 只读审计；GPL/无许可证仓不得直接复制代码，目标架构不继承 |
| `~/SimulatorMosire` | 文档系统格式参考、三库消费者参考 | 仅采用 2026-10-23 文档系统层级；不复制其业务设计 |

- 上游库坐标与发布页：`https://c1589190.github.io/ProjectMosire/`。2026-10-28 核对公开 Pages metadata 最新为 `0.2.3`；`0.2.4` 只在源码主线/本机 Maven 仓，公开 POM 为 404。
- iiBot/Inbox 当前 POM 固定 `agentlib-mosire:0.2.4`；干净 Maven 仓库复现前必须先完成上游 0.2.4 发布。不得用 `~/.m2` 手工安装冒充可复现发布。用户已裁定不降级到 0.2.3。
- 2026-10-28 依赖基线：Jackson `2.22.4`（BOM）、SLF4J `2.0.20`、JUnit `6.1.3`、Java-WebSocket `1.6.0`、SQLite JDBC `3.53.4.0`。

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
