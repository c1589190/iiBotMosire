#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

echo "== iiBotMosire P8 simulation check =="
java -version 2>&1 | head -1
[ -x "$REPO_ROOT/mvnw" ] && echo "mvnw: present"
[ -f "$JAR" ] && echo "jar: present $JAR" || { echo "ERROR: 先运行 tools/gate.sh full 或 ./mvnw package" >&2; exit 1; }
[ -f "$CONFIG" ] && echo "config: present" || echo "WARN: 尚未生成 $CONFIG（运行 01-prepare.sh）"
[ -f "$IDENTITIES" ] && echo "identities: present" || echo "WARN: 尚未生成 $IDENTITIES"
if [ -f "$DIST_JAR.sha256" ]; then
    (cd "$SIM_ROOT/dist" && sha256sum -c "$(basename "$DIST_JAR").sha256")
fi
if [ -f "$CONFIG" ]; then
    python3 - "$CONFIG" <<'PYCHECK'
import json, sys
cfg = json.load(open(sys.argv[1], encoding='utf-8'))
inbox = cfg.get('inbox') or {}
print('inbox:', json.dumps({
    'enabled': bool(inbox.get('enabled')),
    'inboxId': inbox.get('inboxId'),
    'dataDir': inbox.get('dataDir'),
    'channels': inbox.get('channels'),
}, ensure_ascii=False))
if not inbox.get('enabled'):
    raise SystemExit('ERROR: simulation config 未启用 inbox')
PYCHECK
fi

source_iibot_env
for name in IIBOT_LLM_API_KEY IIBOT_NAPCAT_ACCESS_TOKEN IIBOT_CONTROL_TOKEN; do
    if [ -n "${!name:-}" ]; then echo "env: $name present"; else echo "ERROR: env $name missing" >&2; exit 2; fi
done

if port_open 127.0.0.1 3001; then echo "NapCat WS 3001: listening"; else echo "ERROR: NapCat WS 3001 not listening" >&2; fi
if port_open 127.0.0.1 3000; then echo "NapCat HTTP 3000: listening"; else echo "WARN: NapCat HTTP 3000 not listening; 真实发送可能失败" >&2; fi
if port_open "$MCP_HOST" "$MCP_PORT"; then echo "WARN: control port $MCP_PORT already in use; 先停旧 app" >&2; else echo "control port $MCP_PORT: free"; fi
echo "check complete"
