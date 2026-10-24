#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

mkdir -p "$SIM_ROOT/config" "$SIM_ROOT/init" "$RUNTIME" "$RUNTIME/logs" \
    "$RUNTIME/records" "$RUNTIME/secretaries" "$RUNTIME/gm-private"
chmod 700 "$RUNTIME" "$RUNTIME/logs" "$RUNTIME/records" "$RUNTIME/secretaries" "$RUNTIME/gm-private"

SOURCE_CONFIG="$ENV_DIR/iibot.local.json"
if [ ! -f "$SOURCE_CONFIG" ]; then
    echo "ERROR: 找不到真实基础配置 $SOURCE_CONFIG" >&2
    echo "请先把已跑通的真实配置放在 $ENV_DIR/iibot.local.json" >&2
    exit 2
fi

python3 - "$SOURCE_CONFIG" "$CONFIG" "$IDENTITIES" "$SIM_ROOT/config/world-facts.sim.json" <<'PY'
import json, os, pathlib, sys

src, out, ident_out, facts = map(pathlib.Path, sys.argv[1:])
data = json.loads(src.read_text(encoding="utf-8"))

# Dedicated dataDir: repo/simulation/runtime, never the existing real runtime.
sim = dict(data)
sim["dataDir"] = "../runtime"
# The control plane will set the real public group; avoid broadcasting before init.
sim["publicGroupId"] = "0"
# Players are registered through MCP to exercise the dynamic control path.
sim["players"] = []
sim["world"] = {
    "promptVersion": "sim-v1",
    "factsFile": "./world-facts.sim.json",
}
# Optional group for NapCat group-temporary private sessions. It must be a group
# shared by the target players; never a public group assumption.
temp_group = os.environ.get("IIBOT_PRIVATE_TEMP_GROUP_ID")
if temp_group:
    sim["napcat"] = dict(data.get("napcat", {}))
    sim["napcat"]["privateTempGroupId"] = temp_group
sim["scheduler"] = dict(data.get("scheduler", {}))
sim["scheduler"]["autoFreezeIntervalSeconds"] = 30
control = dict(data.get("control", {}))
control.update({
    "enabled": True,
    "host": "127.0.0.1",
    "port": 8765,
    "path": "/mcp/control",
    "bearerTokenEnv": "IIBOT_CONTROL_TOKEN",
    "maxRequestBytes": 16777216,
})
sim["control"] = control

out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(sim, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
os.chmod(out, 0o600)

identities = {
    "publicGroupId": data.get("publicGroupId", ""),
    "players": data.get("players", []),
    "directors": data.get("directors", []),
}
ident_out.parent.mkdir(parents=True, exist_ok=True)
ident_out.write_text(json.dumps(identities, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
os.chmod(ident_out, 0o600)

print(f"config={out}")
print(f"identities={ident_out}")
print(f"players_to_register={len(identities['players'])} directors={len(identities['directors'])}")
PY

chmod 600 "$CONFIG" "$IDENTITIES"
echo "prepare complete"
echo "NOTE: 真实 QQ 群/玩家只写入 simulation/init/identities.local.json；该文件及 runtime 均被 simulation/.gitignore 忽略。"
