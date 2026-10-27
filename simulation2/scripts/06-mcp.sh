#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/lib.sh"
[ -f "$JAR" ] || { echo "ERROR: 缺少 $JAR" >&2; exit 2; }
source_iibot_env
require_env IIBOT_CONTROL_TOKEN
export IIBOT_MCP_HOST="$MCP_HOST" IIBOT_MCP_PORT="$MCP_PORT" IIBOT_MCP_PATH="$MCP_PATH"
mkdir -p "$MCP_CLASSES"
if [ ! -f "$MCP_CLASSES/IibotMcpClient.class" ] || [ "$MCP_CLIENT" -nt "$MCP_CLASSES/IibotMcpClient.class" ]; then
    javac -encoding UTF-8 -cp "$JAR" -d "$MCP_CLASSES" "$MCP_CLIENT"
fi
exec java -cp "$JAR:$MCP_CLASSES" IibotMcpClient "$@"
