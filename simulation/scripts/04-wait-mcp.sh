#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"
if wait_for_mcp; then echo "MCP_READY http://$MCP_HOST:$MCP_PORT$MCP_PATH"; else echo "MCP_TIMEOUT" >&2; exit 1; fi
