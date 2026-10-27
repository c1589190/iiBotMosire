# InboxMosire `inbox-embedded` 参数化初始化需求提示词

> **状态**：已实现（2026-10-27）· **目标仓库**：`/home/cna/InboxMosire`
> · **实现提交**：`6013cde` / `f93e7e2` · **本文不是当前 API 文档**
>
> InboxMosire 已完成 37 项测试并 `clean install`；本仓已接入这些参数。
> 本文保留为需求与验收记录，后续以实际 `inbox-embedded` API 为准。

---

你是 InboxMosire 的工程 Agent。目标是在 `/home/cna/InboxMosire` 中为
`inbox-embedded` 增加**完整、可验证的参数化初始化**，让调用方可以在启动 embedded
server 时显式指定 API/MCP 绑定和运行参数，同时保持现有安全边界、默认行为和
`start()` / `start(String)` 兼容。

## 1. 背景与现状

当前 iiBotMosire 使用：

```java
EmbeddedInboxServer.start(EmbeddedInboxOptions.builder()
    .dataDir(dataDir)
    .inboxId("iibot-bot-sim")
    .heartbeat(Duration.ofSeconds(15))
    .deleteDataOnClose(false)
    .build());
```

但 `inbox-embedded` 的 `EmbeddedInboxServer.start(options)` 目前把以下值写死：

```java
new ServerConfig(
    effectiveDataDir,
    "127.0.0.1", 0,
    "127.0.0.1", 0,
    "/mcp",
    options.heartbeat(),
    options.inboxId());
```

`InboxDaemon` 还把 claim sweeper 的初始延迟/周期写死为 5 秒。

`ServerConfig` 已支持这些字段，但 `EmbeddedInboxOptions` 没有把它们透传出来。

## 2. 目标 API

请保留现有 builder 风格，在 `EmbeddedInboxOptions.Builder` 中增加：

```java
dataDir(Path)                    // 已有
inboxId(String)                  // 已有
heartbeat(Duration)              // 已有，作为 SSE heartbeat
deleteDataOnClose(boolean)       // 已有

apiHost(String)                  // 默认 "127.0.0.1"
apiPort(int)                     // 默认 0（随机可用端口）
mcpHost(String)                  // 默认 "127.0.0.1"
mcpPort(int)                     // 默认 0（随机可用端口）
mcpPath(String)                  // 默认 "/mcp"
claimSweepInterval(Duration)     // 默认 5 秒
```

推荐的调用形状：

```java
EmbeddedInboxOptions options = EmbeddedInboxOptions.builder()
    .dataDir(dataDir)
    .inboxId("iibot-bot-sim")
    .apiHost("127.0.0.1")
    .apiPort(0)
    .mcpHost("127.0.0.1")
    .mcpPort(0)
    .mcpPath("/mcp")
    .heartbeat(Duration.ofSeconds(15))
    .claimSweepInterval(Duration.ofSeconds(5))
    .deleteDataOnClose(false)
    .build();
```

## 3. 实现要求

1. `EmbeddedInboxServer.start(options)` 必须把 `apiHost/apiPort/mcpHost/mcpPort/
   mcpPath/heartbeat/inboxId` 原样传给 `ServerConfig`。
2. `apiPort=0` / `mcpPort=0` 继续表示随机端口；非 0 时尝试绑定指定端口，
   绑定失败必须 fail-closed，不能静默退回随机端口。
3. `apiUrl()` 和 `mcpUrl()` 必须使用实际的配置 host、实际 bound port 和配置 path，
   不能继续硬编码 `127.0.0.1` 或 `/mcp`。
4. `ServerConfig` 的 loopback 校验继续生效：`apiHost/mcpHost` 只允许回环地址；
   不得为了让传参“更灵活”而放宽到 `0.0.0.0` 或公网地址。
5. `claimSweepInterval` 应传入 `InboxDaemon`；请增加不破坏旧调用的重载，例如：
   ```java
   InboxDaemon.start(ServerConfig config)
   InboxDaemon.start(ServerConfig config, Duration claimSweepInterval)
   ```
   旧调用保持默认 5 秒；日志中的 `sweeper=5s` 改为实际值。
6. 建议为 `EmbeddedInboxServer` 增加只读诊断访问器：
   ```java
   ServerConfig serverConfig();       // 有效请求配置
   Duration claimSweepInterval();
   int apiPort();                    // 实际 bound port
   int mcpPort();                    // 实际 bound port
   ```
   如果已有等价 accessor，可不重复添加；但调用方必须能在测试/诊断中读到有效配置。
7. `close()` 必须释放固定端口、停止 HTTP/MCP/sweeper/数据库，并继续遵守
   `deleteDataOnClose` 的原有语义。
8. 所有 builder 值必须校验：
   - port 在 `0..65535`；
   - `mcpPath` 必须以 `/` 开头且不含 query/fragment；
   - heartbeat 和 claimSweepInterval 必须为正；
   - host 非空，并由 `ServerConfig` 做 loopback 校验。
9. 不允许把 raw token、provisioner token、Authorization header 或完整 bindings
   写入日志/异常/诊断字符串。现有 token 只返回一次和 0600 文件的约束不变。
10. 不改变 `inbox-server` 的 HTTP/SSE/MCP 契约，不改变 item 状态机，不改变
    `inbox-client` 或 `inbox-mcp-tools-client` 的公开协议。

## 4. 兼容性要求

- 以下旧调用必须保持行为不变：
  ```java
  EmbeddedInboxServer.start();
  EmbeddedInboxServer.start("app-a");
  EmbeddedInboxServer.start(EmbeddedInboxOptions.builder()
      .dataDir(path).inboxId("app-b").build());
  ```
- 旧调用的默认值仍是 `127.0.0.1:0` API、`127.0.0.1:0` MCP、`/mcp`、
  15 秒 SSE heartbeat、5 秒 sweeper、临时目录 + 关闭删除。
- `EmbeddedInboxOptions` 的已有 getter 不得删除或改语义。

## 5. 必须补的测试

至少增加并通过：

1. 默认 start 仍是随机端口 + `/mcp`，且 `apiUrl().getPort()>0`、
   `mcpUrl().getPort()>0`。
2. 自定义 `mcpPath("/custom/mcp")` 后 `mcpUrl().getPath()` 等于该值。
3. 自定义固定端口（测试可用空闲端口探测）后 `apiPort()/mcpPort()`
   与 URL 端口一致；关闭后端口可再次绑定。
4. 自定义 `heartbeat` 与 `claimSweepInterval` 可通过诊断 accessor 读到。
5. 非 loopback host、非法端口、空 path、非正 Duration 均 fail-closed。
6. `deleteDataOnClose(false)` + 显式 dataDir 时关闭后 DB 保留；
   临时 dataDir 的默认清理语义不回退。
7. 旧 `start()` / `start(String)` 兼容测试仍通过。

## 6. 文档与交付

更新：

```text
docs/upstream/embedded-server.md
docs/agents/STATUS.md
```

在 `embedded-server.md` 写清：

- 新 builder 字段、默认值、0 端口语义；
- loopback-only 边界；
- 固定端口失败是 fail-closed；
- 旧 API 兼容；
- 自定义 mcp path 后 `mcpUrl()` 的实际形状。

完成后运行：

```bash
cd /home/cna/InboxMosire
scripts/gate.sh full
./mvnw -B -ntp clean install
```

最终报告必须包含：

- 实际改动文件；
- 新增 public API 的精确签名；
- 测试数量、失败/错误数；
- `inbox-embedded` / 相关模块 `0.1.0-SNAPSHOT` 已安装到本地 Maven 的证据；
- 是否已提交及 commit hash（如提交）；
- 任何未完成项，不得把未跑的测试写成通过。

不要修改 `/home/cna/iiBotMosire`；本仓会在新 artifact 安装后接入参数。
