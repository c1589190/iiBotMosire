#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

[ -f "$CONFIG" ] || { echo "ERROR: 先运行 simulation/scripts/01-prepare.sh" >&2; exit 2; }
[ -f "$JAR" ] || { echo "ERROR: 缺少 $JAR，先构建" >&2; exit 2; }
source_iibot_env
require_env IIBOT_LLM_API_KEY
require_env IIBOT_NAPCAT_ACCESS_TOKEN
require_env IIBOT_CONTROL_TOKEN
mkdir -p "$LOGS" "$RUNTIME"
chmod 700 "$LOGS" "$RUNTIME"

if [ -f "$LOGS/app.pid" ]; then
    old="$(cat "$LOGS/app.pid" 2>/dev/null || true)"
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
        echo "app already running pid=$old"
        exit 0
    fi
    rm -f "$LOGS/app.pid"
fi
if port_open "$MCP_HOST" "$MCP_PORT"; then
    echo "ERROR: control port $MCP_PORT already in use; 先运行 03-stop.sh" >&2
    exit 2
fi

: > "$LOGS/app.log"
chmod 600 "$LOGS/app.log"
nohup java -jar "$JAR" --config "$CONFIG" >> "$LOGS/app.log" 2>&1 &
pid=$!
echo "$pid" > "$LOGS/app.pid"
chmod 600 "$LOGS/app.pid"
echo "starting pid=$pid; waiting for MCP $MCP_HOST:$MCP_PORT ..."
if ! wait_for_mcp; then
    echo "ERROR: app/MCP did not become ready; log tail:" >&2
    tail -60 "$LOGS/app.log" >&2 || true
    exit 1
fi
echo "APP_STARTED pid=$pid"
tail -20 "$LOGS/app.log" || true
