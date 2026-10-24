#!/usr/bin/env bash
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
    path = init_dir / f"arg-{seq:02d}.json"
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    os.chmod(path, 0o600)
    return path

def call(tool, args=None, capture=False):
    cmd = [mcp, tool]
    if args is not None:
        cmd.append("@" + str(argfile(args)))
    print(f"\n### {tool}", flush=True)
    result = subprocess.run(cmd, text=True, capture_output=capture, check=True)
    if capture:
        print(result.stdout, end="")
        return result.stdout
    return ""

def status():
    out = call("tf_status", {}, capture=True)
    for line in reversed(out.splitlines()):
        line = line.strip()
        if line.startswith("{"):
            return json.loads(line)["data"]
    raise RuntimeError("tf_status 未返回 JSON")

call("tf_control_initialize", {})
if not ident.get("publicGroupId"):
    raise RuntimeError("identities.local.json 缺少 publicGroupId")
call("tf_control_set_public_group", {
    "publicGroupId": ident["publicGroupId"],
    "idempotencyKey": "sim-set-public-group-v1",
    "reason": "P8 simulation init",
})
for item in ident.get("directors", []):
    key = "sim-register-director-" + item["playerId"] + "-v1"
    call("tf_control_register_director", {
        "playerId": item["playerId"], "qqId": item["qqId"],
        "displayName": item["displayName"], "idempotencyKey": key,
        "reason": "P8 simulation init",
    })
for item in ident.get("players", []):
    key = "sim-register-player-" + item["playerId"] + "-v1"
    call("tf_control_register_player", {
        "playerId": item["playerId"], "qqId": item["qqId"],
        "displayName": item["displayName"], "idempotencyKey": key,
        "reason": "P8 simulation init",
    })
    call("tf_control_bind_secretary", {
        "playerId": item["playerId"],
        "idempotencyKey": "sim-bind-secretary-" + item["playerId"] + "-v1",
        "reason": "P8 simulation init",
    })
call("tf_control_load_world", {
    "facts": [{
        "factId": "sim-control-initialized",
        "content": "真实模拟控制面已完成初始化；这是由 MCP 动态装载的公开事实。",
        "visibility": "PUBLIC",
    }],
    "idempotencyKey": "sim-load-world-v1",
    "reason": "P8 simulation init",
})
state = status()
print("\n### status", json.dumps(state, ensure_ascii=False))
if not state.get("hasActiveTurn", False):
    call("tf_open_turn", {
        "minutes": 240,
        "expectedRevision": state["engineRevision"],
        "idempotencyKey": "sim-open-turn-v1",
    })
    state = status()
    print("\n### status_after_open", json.dumps(state, ensure_ascii=False))
print("\nINIT_COMPLETE")
PY

echo
echo "MCP 初始化完成。下一步："
echo "  $SIM_ROOT/scripts/06-mcp.sh --list"
echo "  $SIM_ROOT/scripts/06-mcp.sh tf_status '{}'"
