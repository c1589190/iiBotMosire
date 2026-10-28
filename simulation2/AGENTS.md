# simulation2 —— bot-first 独立运行区

> 本目录只用于 bot-first 运行脚本、配置和证据；旧 `simulation/` 文游运行区已从工作树删除。

## 职责

- 独立 `simulation2/runtime`、`simulation2/logs`、`simulation2/dist`；
- 生成 bot-only `bot.sim.local.json`；
- 默认启动 bot-first，暴露 Bot MCP + Inbox descriptor；
- DSH 绑定固定使用 `wakeMode=view-ack`，不 claim；
- 提供 DSH 主 Agent 的绑定/读取/回复引导。

## 边界

- 旧 `simulation/` 历史由 Git 保留，不在当前工作树恢复；
- 不保存 raw token、QQ 凭据或真实玩家隐私到仓库；
- 运行时文件全部 gitignore；
- 真实外发前必须由用户明确授权，遵守根 AGENTS 的 M-004 协同门。

## 验证

```bash
bash -n scripts/*.sh
scripts/00-build.sh
scripts/01-prepare.sh
```
