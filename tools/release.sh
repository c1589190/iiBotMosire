#!/usr/bin/env bash
# 打一个「全依赖内置」的 release jar。
#
#   bash tools/release.sh                 # 版本 = Alpha<今天 YYYYMMDD>
#   bash tools/release.sh -v Alpha20261103.2
#   bash tools/release.sh --with-tests    # 先跑 tools/gate.sh full 再打包
#   bash tools/release.sh --no-build      # 只改版本号并复用 target/ 里已有的 jar
#
# 版本号规则（2026-11-03 用户裁定）：一律 `Alpha<YYYYMMDD>`，同一天第二个 release 用
# `Alpha<YYYYMMDD>.2`、`.3`…，不再出现 `0.1.0-SNAPSHOT` 这类「不知道第几个 Alpha」的名字。
# 只改 iibot 各模块的 <parent>/自身 <version>；<inbox.version>（InboxMosire 依赖版本）保持不动。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION=""
WITH_TESTS=0
BUILD=1

while [ $# -gt 0 ]; do
  case "$1" in
    -v|--version) shift; VERSION="${1:-}"; [ -n "$VERSION" ] || { echo "✗ -v 需要版本号" >&2; exit 2; } ;;
    --with-tests) WITH_TESTS=1 ;;
    --no-build)   BUILD=0 ;;
    -h|--help)    sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "✗ 未知参数：$1" >&2; exit 2 ;;
  esac
  shift
done

[ -n "$VERSION" ] || VERSION="Alpha$(date +%Y%m%d)"

case "$VERSION" in
  Alpha[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]|Alpha[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9].[0-9]*) ;;
  *) echo "✗ 版本号必须是 Alpha<YYYYMMDD>[.N]，收到：$VERSION" >&2; exit 2 ;;
esac

echo "› 版本：$VERSION"

python3 - "$VERSION" <<'PY'
import re, sys, pathlib
new = sys.argv[1]
poms = ["pom.xml", "iibot-connect/pom.xml", "iibot-video-connect/pom.xml",
        "iibot-bot/pom.xml", "iibot-app/pom.xml"]
pat_parent = re.compile(r"(<parent>\s*\n(?:[^\n]*\n)*?\s*<version>)([^<]+)(</version>)")
pat_self = re.compile(r"(<artifactId>iibot-parent</artifactId>\s*\n\s*<version>)([^<]+)(</version>)")
for f in poms:
    p = pathlib.Path(f)
    s = p.read_text(encoding="utf-8")
    s2 = pat_parent.sub(lambda m: m.group(1) + new + m.group(3), s)
    s2 = pat_self.sub(lambda m: m.group(1) + new + m.group(3), s2)
    p.write_text(s2, encoding="utf-8")
    print("  pom:", f)
PY

if [ "$WITH_TESTS" = "1" ]; then
  echo "› 跑全量门禁 tools/gate.sh full"
  bash tools/gate.sh full
fi

JAR="iibot-app/target/iibot-app-${VERSION}.jar"

if [ "$BUILD" = "1" ]; then
  echo "› mvn -o -B -DskipTests package"
  mvn -o -B -q -DskipTests package
fi

[ -f "$JAR" ] || { echo "✗ 没找到 $JAR" >&2; exit 1; }

mkdir -p dist
cp -f "$JAR" "dist/iibot-app-${VERSION}.jar"
( cd dist && sha256sum "iibot-app-${VERSION}.jar" > SHA256SUMS )

echo
echo "✓ release 就绪"
printf '  文件  : %s/dist/iibot-app-%s.jar\n' "$ROOT" "$VERSION"
printf '  大小  : %s 字节\n' "$(stat -c %s "dist/iibot-app-${VERSION}.jar")"
printf '  sha256: %s\n' "$(cut -d' ' -f1 dist/SHA256SUMS)"
printf '  运行  : java -jar dist/iibot-app-%s.jar --config config/bot/bot.json\n' "$VERSION"
echo
echo "  提示：jar 是 shade 产物，依赖全部内置；部署时只需要这一个文件。"
