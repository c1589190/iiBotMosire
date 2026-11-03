# 发布（release）手册

> **本文件属于项目维护。** 规定 iiBot release 的版本号与产物形态，不涉及运行序列。

## 一、版本号

**规则（2026-11-03 用户裁定）**：release 版本号一律 `Alpha<YYYYMMDD>`；同一天出第二个
release 用 `Alpha<YYYYMMDD>.2`、`.3`…。不再新增 `0.1.0-SNAPSHOT`、`0.2.0-Alpha1` 这类
「不知道是第几个 Alpha」的名字。

- 需要覆盖的 pom 只有 `<parent>` 与项目自身 `<version>`；
  **`<inbox.version>`（InboxMosire 依赖版本）不是本项目版本，保持 `0.1.0-SNAPSHOT` 不动。**
- 版本写在 5 个 pom 里：`pom.xml`、`iibot-connect/pom.xml`、`iibot-video-connect/pom.xml`、
  `iibot-bot/pom.xml`、`iibot-app/pom.xml`。
- `tools/release.sh` 会做这个改写并校验格式，不需要手工 sed。

## 二、产物形态

`iibot-app` 用 `maven-shade-plugin`（`iibot-app/pom.xml:99-124`）把**全部依赖打进一个 jar**：

- 类在 jar 根路径（`io/mosire/...`），不是 Spring Boot 的 `BOOT-INF/classes/`；
  用 `unzip -l` 查类时别套 `BOOT-INF/` 前缀。
- 入口 `io.mosire.iibot.app.Main`（manifest 由 shade 的 `ManifestResourceTransformer` 写入）。
- 单文件部署：目标机只需要 JDK 21 + 这一个 jar + `config/bot/bot.json`，
  不需要 `~/.m2`、不需要 `target/`、不需要网络。

## 三、怎么发

```bash
bash tools/release.sh                 # 版本 = Alpha<今天>，跳测试打包
bash tools/release.sh --with-tests    # 先跑 tools/gate.sh full 再打包（正式发布用这个）
bash tools/release.sh -v Alpha20261103.2
bash tools/release.sh --no-build      # 只改版本号，复用 target/ 里的 jar
```

产物：

```
dist/iibot-app-<版本>.jar     # 全依赖内置
dist/SHA256SUMS               # 校验
```

`dist/` 不进仓库（见 `.gitignore`），随发布单独投递。

## 四、部署与回滚

```bash
cp dist/iibot-app-<版本>.jar <运行目录>/
# 运行目录里的启动脚本引用固定文件名，需要同步改名：
#   run/boot-env.sh  : exec /usr/bin/java -jar iibot-app-<版本>.jar --config config/bot/bot.json
#   start-bot.sh     : pkill -f 'iibot-app-<版本>.jar'
./start-bot.sh restart
```

回滚 = 换回上一个 jar（保留 `<上一个jar>.bak-<日期>`）+ 把上面两行改回旧文件名 + `restart`。
`config/bot/bot.json` 与它无关，不用回滚。

## 五、发布后核查

| # | 检查 | 命令 | 期望 |
|---|---|---|---|
| 1 | 版本 | `unzip -p dist/iibot-app-<版本>.jar META-INF/MANIFEST.MF` | 有 `Main-Class: io.mosire.iibot.app.Main` |
| 2 | 校验 | `cd dist && sha256sum -c SHA256SUMS` | `OK` |
| 3 | 配置 | `java -jar dist/iibot-app-<版本>.jar --config config/bot/bot.json --print-effective-config` | 打印脱敏 JSON，`messageBoard` 三项在 |
| 4 | 类内容 | `unzip -p <jar> io/mosire/iibot/bot/summary/MessageSummary.class \| strings \| grep -c messageKey` | ≥1 |
| 5 | 起服务 | `./start-bot.sh start` 后 `ss -lnt` | 8765 / 46500 / 46501 在听 |
