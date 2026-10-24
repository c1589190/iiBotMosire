#!/usr/bin/env bash
# Shared paths and conservative environment loading for the P8 simulation.
set -euo pipefail

SIM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$SIM_ROOT/.." && pwd)"
REPO_JAR="$REPO_ROOT/iibot-app/target/iibot-app-0.1.0-SNAPSHOT.jar"
DIST_JAR="$SIM_ROOT/dist/iibot-app-0.1.0-SNAPSHOT.jar"
JAR="${IIBOT_SIM_JAR:-}"
if [ -z "$JAR" ]; then
    if [ -f "$DIST_JAR" ]; then
        JAR="$DIST_JAR"
    else
        JAR="$REPO_JAR"
    fi
fi
ENV_DIR="${IIBOT_ENV_DIR:-$HOME/.config/iibot}"
CONFIG="$SIM_ROOT/config/iibot.sim.local.json"
IDENTITIES="$SIM_ROOT/init/identities.local.json"
RUNTIME="$SIM_ROOT/runtime"
LOGS="$SIM_ROOT/logs"
MCP_CLASSES="$SIM_ROOT/tools/classes"
MCP_CLIENT="$SIM_ROOT/tools/IibotMcpClient.java"
MCP_HOST="${IIBOT_MCP_HOST:-127.0.0.1}"
MCP_PORT="${IIBOT_MCP_PORT:-8765}"
MCP_PATH="${IIBOT_MCP_PATH:-/mcp/control}"

source_iibot_env() {
    local file
    for file in "$ENV_DIR/llm.env" "$ENV_DIR/napcat.env" "$ENV_DIR/control.env"; do
        if [ -f "$file" ]; then
            set -a
            # shellcheck disable=SC1090
            . "$file"
            set +a
        fi
    done
}

require_env() {
    local name="$1"
    if [ -z "${!name:-}" ]; then
        echo "ERROR: 缺少环境变量 $name；请先检查 $ENV_DIR/*.env" >&2
        exit 2
    fi
}

port_open() {
    local host="$1" port="$2"
    (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null
}

wait_for_mcp() {
    local i
    for i in $(seq 1 60); do
        if port_open "$MCP_HOST" "$MCP_PORT"; then
            return 0
        fi
        sleep 1
    done
    return 1
}
