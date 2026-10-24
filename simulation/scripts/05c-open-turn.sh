#!/usr/bin/env bash
# 当前无活动回合时打开回合；已存在活动回合则只报告状态。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

MCP="$SIM_ROOT/scripts/06-mcp.sh"
INIT_DIR="$RUNTIME/init"
mkdir -p "$INIT_DIR"
chmod 700 "$INIT_DIR"

python3 - "$MCP" "$INIT_DIR" <<'PY'
import json, os, pathlib, subprocess, sys

mcp, init_dir = sys.argv[1], pathlib.Path(sys.argv[2])
seq = 0

def call(tool, args=None, capture=False):
    global seq
    cmd = [mcp, tool]
    if args is not None:
        seq += 1
        path = init_dir / f"open-turn-arg-{seq:02d}.json"
        path.write_text(json.dumps(args, ensure_ascii=False), encoding="utf-8")
        os.chmod(path, 0o600)
        cmd.append("@" + str(path))
    print(f"\n### {tool}", flush=True)
    return subprocess.run(cmd, text=True, capture_output=capture, check=True).stdout

def status():
    out = call("tf_status", {}, capture=True)
    for line in reversed(out.splitlines()):
        line = line.strip()
        if line.startswith("{"):
            return json.loads(line)["data"]
    raise RuntimeError("tf_status 未返回 JSON")

state = status()
print("\n### status", json.dumps(state, ensure_ascii=False))
if state.get("hasActiveTurn", False):
    print("\nTURN_ALREADY_ACTIVE")
    raise SystemExit(0)
call("tf_open_turn", {
    "minutes": 240,
    "expectedRevision": state["engineRevision"],
    "idempotencyKey": "sim-open-turn-v1",
})
print("\nTURN_OPENED")
PY

echo "回合状态处理完成。"
