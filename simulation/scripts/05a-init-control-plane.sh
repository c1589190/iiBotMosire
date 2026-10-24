#!/usr/bin/env bash
# 只初始化控制面：公开群、director、世界事实；不注册玩家、不打开回合。
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
    path = init_dir / f"control-plane-arg-{seq:02d}.json"
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    os.chmod(path, 0o600)
    return path

def call(tool, args=None):
    cmd = [mcp, tool]
    if args is not None:
        cmd.append("@" + str(argfile(args)))
    print(f"\n### {tool}", flush=True)
    subprocess.run(cmd, text=True, check=True)

call("tf_control_initialize", {})
if not ident.get("publicGroupId"):
    raise RuntimeError("identities.local.json 缺少 publicGroupId")
call("tf_control_set_public_group", {
    "publicGroupId": ident["publicGroupId"],
    "idempotencyKey": "sim-set-public-group-v1",
    "reason": "P8 simulation control plane init",
})
for item in ident.get("directors", []):
    call("tf_control_register_director", {
        "playerId": item["playerId"], "qqId": item["qqId"],
        "displayName": item["displayName"],
        "idempotencyKey": "sim-register-director-" + item["playerId"] + "-v1",
        "reason": "P8 simulation control plane init",
    })
call("tf_control_load_world", {
    "facts": [{
        "factId": "sim-control-initialized",
        "content": "真实模拟控制面已完成初始化；这是由 MCP 动态装载的公开事实。",
        "visibility": "PUBLIC",
    }],
    "idempotencyKey": "sim-load-world-v1",
    "reason": "P8 simulation control plane init",
})
print("\nCONTROL_PLANE_READY")
PY

echo
echo "控制面已初始化；尚未注册玩家、未开回合。"
echo "下一步可运行: simulation/scripts/07-opening.sh \"标题\" \"正文\" \"幂等键\""
