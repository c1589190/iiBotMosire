# Chromium CDP sidecar（V5 目标部署）

本目录提供服务器侧 Chromium + ffmpeg 的最小镜像定义。Java 主进程使用
`video.browser.mode=remote-cdp` 连接同一个网络命名空间内的
`http://127.0.0.1:9222`；**不要把 9222 发布到公网**。

## 约束

- 非 root（uid 10001）运行，profile 在 `/data/chromium`；
- CDP 仅绑定 `127.0.0.1`，不启用 `--no-sandbox`；
- 容器需要与 iibot Java 进程共享 network namespace：
  - Compose: sidecar 使用 `network_mode: "service:iibot"`；
  - Kubernetes: 放在同一 Pod，或同容器运行；
- Chromium 的 H.264/专有 codec 支持取决于基础镜像；视频抽帧默认仍走 ffmpeg，
  浏览器只是失败补帧；
- `video.browser.maxConcurrentSessions`、`timeoutSeconds` 必须小于容器 CPU/内存
  配额，避免浏览器崩溃拖垮 Java。

## 构建与运行

```bash
docker build -t iibot-browser-cdp deploy/browser
docker volume create iibot-browser-data
docker run --rm --network container:iibot \
  --memory=1g --cpus=1 --shm-size=512m \
  --read-only --tmpfs /tmp \
  --volume iibot-browser-data:/data \
  iibot-browser-cdp
```

健康检查：

```bash
curl -fsS http://127.0.0.1:9222/json/version
```

## iibot 配置

```json
{
  "video": {
    "browser": {
      "enabled": true,
      "mode": "remote-cdp",
      "cdpEndpoint": "http://127.0.0.1:9222",
      "headless": true,
      "maxConcurrentSessions": 2,
      "timeoutSeconds": 60,
      "profileRoot": ""
    }
  }
}
```

`cdpEndpoint` 只接受回环 http/https/ws/wss；非回环、userinfo 或非 http(s)/ws(s)
scheme 会在配置加载阶段 fail-closed。`browser.enabled=false` 时不启动或连接浏览器。

## 验收状态

本机已验证：Playwright `remote-cdp` 连接本地 `--remote-debugging-port=0` Chromium，
对 fake 页面截取 640x360 JPEG PASS（`RemoteCdpBrowserBackendTest`）。真实服务器
镜像构建、真实 B 站页面、3 并发、断线重连、崩溃恢复和资源限制仍为 `NOT_RUN`。

相关索引：[当前状态](../../docs/agents/STATUS.md) ·
[环境事实](../../docs/agents/ENVIRONMENT.md) ·
[D-035 设计](../../docs/superpowers/specs/2026-10-31-video-site-connect-and-browser-backend.md)
