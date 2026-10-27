#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/lib.sh"

echo "== simulation2 bot-first check =="
java -version 2>&1 | head -1
[ -x "$REPO_ROOT/mvnw" ] && echo "mvnw: present"
[ -f "$JAR" ] && echo "jar: present $JAR" || { echo "ERROR: 缺少 jar，先运行 00-build.sh" >&2; exit 2; }
[ -f "$BOT_CONFIG" ] && echo "config: present $BOT_CONFIG" || { echo "ERROR: 先运行 01-prepare.sh" >&2; exit 2; }

python3 - "$BOT_CONFIG" <<'PYCHECK'
import json, sys
cfg = json.load(open(sys.argv[1], encoding="utf-8"))
required = {"dataDir", "accountId", "napcat", "control", "inbox"}
missing = sorted(required - set(cfg))
if missing:
    raise SystemExit("ERROR: bot config 缺少字段: " + ",".join(missing))
forbidden = sorted(set(cfg) & {"players", "directors", "world", "llm", "llmRoutes",
                                "agentRoutes", "publicGroupId", "maxTurnMinutes",
                                "scheduler"})
if forbidden:
    raise SystemExit("ERROR: bot config 含 legacy 字段: " + ",".join(forbidden))
print("config_keys:", ",".join(sorted(cfg)))
print("inbox:", json.dumps(cfg["inbox"], ensure_ascii=False))
PYCHECK

source_iibot_env
require_env IIBOT_NAPCAT_ACCESS_TOKEN
require_env IIBOT_CONTROL_TOKEN
if port_open "$MCP_HOST" "$MCP_PORT"; then
    echo "ERROR: control port $MCP_PORT already in use; 先停止旧 bot/legacy app" >&2
    exit 2
else
    echo "control port $MCP_PORT: free"
fi
if port_open 127.0.0.1 3001; then echo "NapCat WS 3001: listening"; else echo "WARN: NapCat WS 3001 not listening" >&2; fi
if port_open 127.0.0.1 3000; then echo "NapCat HTTP 3000: listening"; else echo "WARN: NapCat HTTP 3000 not listening" >&2; fi
echo "check complete"
