#!/usr/bin/env bash
# 只注册 players 并绑定秘书；不装载世界、不打开回合。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

[ -f "$IDENTITIES" ] || { echo "ERROR: 先运行 01-prepare.sh" >&2; exit 2; }
MCP="$SIM_ROOT/scripts/06-mcp.sh"
INIT_DIR="$RUNTIME/init"
mkdir -p "$INIT_DIR"
chmod 700 "$INIT_DIR"

python3 - "$MCP" "$IDENTITIES" "$INIT_DIR" <<'PY'
import json, os, pathlib, subprocess, sys

mcp, ident_path, init_dir = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
ident = json.loads(ident_path.read_text(encoding="utf-8"))
seq = 0

def argfile(value):
    global seq
    seq += 1
    path = init_dir / f"register-players-arg-{seq:02d}.json"
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    os.chmod(path, 0o600)
    return path

def call(tool, args):
    print(f"\n### {tool}", flush=True)
    subprocess.run([mcp, tool, "@" + str(argfile(args))], text=True, check=True)

players = ident.get("players", [])
if not players:
    print("NO_PLAYERS_IN_IDENTITIES：identities.local.json 中没有可注册玩家")
    raise SystemExit(0)
for item in players:
    call("tf_control_register_player", {
        "playerId": item["playerId"], "qqId": item["qqId"],
        "displayName": item["displayName"],
        "idempotencyKey": "sim-register-player-" + item["playerId"] + "-v1",
        "reason": "P8 simulation player registration",
    })
    call("tf_control_bind_secretary", {
        "playerId": item["playerId"],
        "idempotencyKey": "sim-bind-secretary-" + item["playerId"] + "-v1",
        "reason": "P8 simulation player registration",
    })
print("\nPLAYERS_REGISTERED count=" + str(len(players)))
PY

echo
echo "玩家注册完成；尚未自动开回合。"
echo "下一步可运行: simulation/scripts/05c-open-turn.sh"
