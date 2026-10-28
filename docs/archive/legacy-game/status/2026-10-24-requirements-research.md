# 历史快照：2026-10-24 需求调研与文档体系

> **历史快照**：本文件只记录该时点的事实，不是当前状态入口。
> **当前实时状态**：[`../../agents/STATUS.md`](../../agents/STATUS.md)
> **勿据此开工**：后续有变更时以新 STATUS 与设计文档为准。

## 本轮完成

- 建立与 SimulatorMosire 最新文档系统对齐的层级；
- 核实 ProjectMosire 三库 0.2.3 的发布坐标、源码能力和集成边界；
- 核实 NapCat 正向/反向 WS、HTTP Action、消息事件与发送动作契约；
- 调查旧 `iBotMosire` 的适配器/测试可参考部分；
- 形成需求基线、目标运行设计、外部 MCP 控制面、实施计划与调查报告；
- 尚未初始化 Maven 工程与 Git。

## 证据边界

- 三库/协议结论来自源码、发布页与官方文档；
- 没有 Java 构建、真实 NapCat、真实 LLM、MCP E2E 证据；
- 所有“可用/兼容/正确”最终必须由实现阶段的实际命令证明。

## 本轮机械检查（仅文档结构）

```text
python3 tools/check-docs.py
DOCS CHECK PASSED (phase=pre-maven modules=0 files=30 links=62)

python3 tools/check-docs.py --selftest
SELFTEST PASSED check-docs.py (11/11 cases)

python3 tools/skills-lint.py
SKILLS LINT PASSED (active skills=0)

tools/gate.sh docs
[gate] docs 结论：PASS
```

> 这不是 Java/Maven/NapCat/LLM/MCP 运行证据。

## 下一步

见当前 [`STATUS.md`](../../agents/STATUS.md) §7。
