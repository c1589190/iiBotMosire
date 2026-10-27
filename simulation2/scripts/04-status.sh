#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/lib.sh"

if [ -f "$LOGS/app.pid" ]; then
    pid="$(cat "$LOGS/app.pid" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        echo "BOT_PID=$pid"
    else
        echo "BOT_PID=stale"
    fi
else
    echo "BOT_PID=absent"
fi
if port_open "$MCP_HOST" "$MCP_PORT"; then
    echo "MCP_PORT=open http://$MCP_HOST:$MCP_PORT$MCP_PATH"
else
    echo "MCP_PORT=closed"
fi
if [ -f "$INBOX_DESCRIPTOR_FILE" ]; then
    echo "INBOX_DESCRIPTOR=$INBOX_DESCRIPTOR_FILE"
else
    echo "INBOX_DESCRIPTOR=missing"
fi
echo "--- app.log tail ---"
tail -30 "$LOGS/app.log" 2>/dev/null || true
