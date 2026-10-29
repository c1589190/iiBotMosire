# 验证维护手册

> **本文件属于项目维护。** 只记录当前统一 QQ Agent 客户端的验证入口、证据规则与已知边界。
> 最新实际门禁摘要见 [`STATUS.md`](STATUS.md) §3；没跑的命令必须写 `NOT_RUN`。

## 一、门禁入口

```bash
tools/gate.sh pre
tools/gate.sh docs
tools/gate.sh selftest
tools/gate.sh focused <module> [args...]
tools/gate.sh full
```

- `docs`：文档相对链接、目录结构、STATUS 字段与 Skill 登记。
- `selftest`：`check-docs.py` / `skills-lint.py` 的自测；不跑 Maven。
- `pre`：docs + Maven/Java 环境自检。
- `focused`：`./mvnw -B -ntp -pl <module> -am <args...>`，只能写 `FOCUSED TEST PASSED`。
- `full`：`./mvnw -B -ntp clean verify`，全 reactor 测试、打包与 shade。

当前 `verify` 未接入 SpotBugs/Checkstyle 静态门禁；`full` 只代表构建与测试通过。

## 二、当前基线

- 2026-10-29 P8.7 基线：`tools/gate.sh full` PASS，**240 tests / 0 failures / 0 errors /
  0 skipped**（connect 61、bot 109、app 70）；`tools/gate.sh docs` PASS
  （modules=3 files=41 links=103）；`tools/gate.sh selftest` PASS
  （check-docs 11/11、skills-lint 6/6）。
- 依赖：Jackson 2.22.4、SLF4J 2.0.20、JUnit 6.1.3、Java-WebSocket 1.6.0；
  `iibot-app` shaded jar 构建成功。
- Inbox 依赖刷新后 `./mvnw -B -ntp clean verify` PASS：37 tests / 0 failures / 0 errors。
- 干净空 Maven 仓库：`PUBLICATION_GAP` / `NOT_RUN`。公开 AgentLib 0.2.4 尚未发布，
  空仓全量下载也在 300 秒超时；不得用本机 `~/.m2` 冒充公开可复现。
- 真实 QQ/NapCat/DSH/OpenCode/MCP/Inbox 外部闭环、日志真实轮转与崩溃恢复：
  `NOT_RUN`。

## 三、证据规则

- PASS = 命令实际执行 + 退出码符合预期 + Surefire 汇总/XML 与关键输出核对。
- 管道取 `${PIPESTATUS[0]}`，不得拿 `tail` 的退出码。
- 关键证据保留 source SHA、命令、时间、测试数、报告路径与 run 边界。
- 旧报告、Mock、进程内回环测试不得混写成真实外部验收。
- 外部副作用测试（QQ 发送、好友审批、MCP/Inbox 外部连接）必须使用隔离实例或 fake；
  真实操作需用户当次授权。

## 四、当前测试分层

| 层 | 目的 | 当前状态 |
|---|---|---|
| 领域单测 | 消息板、thread、备注、好友申请、来源权重、consumer 游标 | 已有 |
| 契约测试 | OneBot 事件/动作 JSON、MCP schema、SQLite 迁移与重开 | 已有 |
| 应用集成 | BotRuntime + fake Adapter + embedded loopback Inbox/MCP | 已有 |
| 真实 NapCat E2E | 真实私聊/群聊入站与发送 | `NOT_RUN` |
| 真实 DSH/OpenCode wake | 真实 wake/ack 闭环 | `NOT_RUN` |
| 长跑/崩溃恢复 | 多账号、日志轮转、进程崩溃与重启 | `NOT_RUN` |

## 五、当前负向判据

1. 重复 QQ `message_id` ⇒ 只入库/通知一次。
2. 入站 accountId 与绑定账号不一致 ⇒ `inbound_account_mismatch` fail-closed。
3. 出站 thread 的 accountId 无对应 Adapter ⇒ 不得改投其他账号。
4. 发送超时/`UNKNOWN` ⇒ 不得自动重试。
5. `qq_conversation_read` 不得推进 `readSeq`；重复读取返回同一 delivered 批次。
6. 未显式 ack 前新到消息不得被上一批 ack 吃掉。
7. ack 必须先成功同步 Inbox，再推进 consumer `readSeq`。
8. 多账号下 recent/history/friend request/user note 未指定 `accountId` ⇒ `ACCOUNT_REQUIRED`。
9. 重复 `idempotencyKey` ⇒ 不重复发送；省略时服务端生成并回传。
10. 日志/MCP 结果不得出现 raw token、Authorization、完整协议 JSON 或聊天正文。
11. 非回环监听、错误 Bearer、未知工具/参数 ⇒ fail-closed。
12. 配置 JSON 错误必须给来源文件与 JSON pointer；`--print-effective-config` 必须脱敏。

## 六、未验证表达

- “第三方文档说可以” ⇒ `文档结论（未实测）`。
- “本机源码存在 API” ⇒ `源码核实（未编译）`。
- “Mock/回环通过” ≠ “真实 NapCat/DSH/OpenCode 通过”。
- “构建通过” ≠ “长跑稳定”或“真实外部副作用已验证”。
