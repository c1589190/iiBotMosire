#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/lib.sh"

[ -f "$BOT_CONFIG" ] || { echo "ERROR: 先运行 simulation2/scripts/01-prepare.sh" >&2; exit 2; }
[ -f "$JAR" ] || { echo "ERROR: 缺少 $JAR，先运行 simulation2/scripts/00-build.sh" >&2; exit 2; }
source_iibot_env
require_env IIBOT_NAPCAT_ACCESS_TOKEN
require_env IIBOT_CONTROL_TOKEN
mkdir -p "$LOGS" "$RUNTIME"
chmod 700 "$LOGS" "$RUNTIME"

if [ -f "$LOGS/app.pid" ]; then
    old="$(cat "$LOGS/app.pid" 2>/dev/null || true)"
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
        echo "bot already running pid=$old"
        exit 0
    fi
    rm -f "$LOGS/app.pid"
fi
if port_open "$MCP_HOST" "$MCP_PORT"; then
    echo "ERROR: control port $MCP_PORT already in use; 先停止旧 bot" >&2
    exit 2
fi

: > "$LOGS/app.log"
chmod 600 "$LOGS/app.log"
nohup java -jar "$JAR" --config "$BOT_CONFIG" >> "$LOGS/app.log" 2>&1 &
pid=$!
echo "$pid" > "$LOGS/app.pid"
chmod 600 "$LOGS/app.pid"
echo "starting bot pid=$pid; waiting for MCP $MCP_HOST:$MCP_PORT ..."
if ! wait_for_mcp; then
    echo "ERROR: bot MCP did not become ready; log tail:" >&2
    tail -80 "$LOGS/app.log" >&2 || true
    exit 1
fi
echo "BOT_STARTED pid=$pid"
echo "BOT_MCP_URL=http://$MCP_HOST:$MCP_PORT$MCP_PATH"
echo "INBOX_WAKE_MODE=view-ack"
tail -25 "$LOGS/app.log" || true

if "$SCRIPT_DIR/07-inbox-descriptor.sh"; then
    echo "INBOX_HANDOFF_READY=$INBOX_DESCRIPTOR_FILE"
else
    echo "WARN: inbox descriptor 获取失败；检查 runtime/logs/app.log" >&2
fi
