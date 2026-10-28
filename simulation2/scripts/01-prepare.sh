#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/lib.sh"

mkdir -p "$SIM2_ROOT/config" "$RUNTIME" "$LOGS" "$RUNTIME/bot" "$RUNTIME/inbox"
chmod 700 "$RUNTIME" "$LOGS"

if [ -z "$SOURCE_CONFIG" ] || [ ! -f "$SOURCE_CONFIG" ]; then
    if [ -f "$BOT_CONFIG" ]; then
        chmod 600 "$BOT_CONFIG" 2>/dev/null || true
        echo "BOT_CONFIG=$BOT_CONFIG"
        echo "NOTE: 复用已有 bot.sim.local.json；未从旧配置重新生成。"
        exit 0
    fi
    fallback="$ENV_DIR/iibot.local.json"
    if [ -f "$fallback" ]; then
        SOURCE_CONFIG="$fallback"
    else
        cp "$SIM2_ROOT/config/bot.sim.example.json" "$BOT_CONFIG"
        chmod 600 "$BOT_CONFIG"
        echo "BOOTSTRAP_CONFIG=$BOT_CONFIG" >&2
        echo "ERROR: 未找到现有配置，已从示例生成 bot.sim.local.json。" >&2
        echo "请修改账号、OneBot 地址和 token 环境变量名，设置 IIBOT_NAPCAT_ACCESS_TOKEN/IIBOT_CONTROL_TOKEN 后重跑。" >&2
        exit 2
    fi
fi

python3 - "$SOURCE_CONFIG" "$BOT_CONFIG" <<'PY'
import json, os, pathlib, sys
src_path, out_path = map(pathlib.Path, sys.argv[1:])
data = json.loads(src_path.read_text(encoding="utf-8"))
napcat_src = data.get("napcat") or {}
account_id = str(napcat_src.get("accountId") or data.get("accountId") or "").strip()
if not account_id:
    raise SystemExit("来源配置缺少 napcat.accountId/accountId")
required_napcat = ("eventWebSocketUri", "httpBaseUri")
for key in required_napcat:
    if not napcat_src.get(key):
        raise SystemExit(f"来源配置缺少 napcat.{key}")
napcat = {
    key: napcat_src[key]
    for key in ("eventWebSocketUri", "httpBaseUri", "accessTokenEnv", "accountId",
                "privateTempGroupId", "suppressSelfMessages", "requestTimeoutSeconds",
                "maxTextLength")
    if key in napcat_src
}
napcat["accountId"] = account_id
control_src = data.get("control") or {}
control = {
    "host": control_src.get("host", "127.0.0.1"),
    "port": control_src.get("port", 8765),
    "path": "/mcp/bot",
    "bearerTokenEnv": control_src.get("bearerTokenEnv", "IIBOT_CONTROL_TOKEN"),
}
outbox_src = data.get("outbox") or {}
out = {
    "dataDir": "../runtime/bot",
    "accountId": account_id,
    "napcat": napcat,
    "control": control,
    "inbox": {
        "dataDir": "../runtime/inbox",
        "inboxId": "iibot-bot-sim",
        "bindingLabel": "dsh-bot-sim",
        "channels": ["qq"],
        "bindingTtlHours": 24,
        "apiHost": "127.0.0.1",
        "apiPort": 0,
        "mcpHost": "127.0.0.1",
        "mcpPort": 0,
        "mcpPath": "/mcp",
        "heartbeatMillis": 15000,
        "claimSweepIntervalMillis": 5000,
    },
    "summary": {"debounceMillis": 2000, "maxMessagesPerDigest": 100},
    "weighting": {
        "defaultPrivateSourceWeight": 300,
        "defaultGroupSourceWeight": 0,
        "defaultNotifyThreshold": 100,
        "signalWeights": {"DM": 200, "MENTION": 200, "REPLY_TO_BOT": 180,
                          "NAME": 120, "COMMAND": 250},
        "keywordWeights": {},
        "aliases": ["贾维洛", "洛洛"],
        "commandPrefixes": ["/", "!"],
        "maxMessagesPerDigest": 20,
        "quietMillis": 180000,
        "cooldownMillis": 60000,
    },
    "outbox": {
        "sendTimeoutSeconds": outbox_src.get("sendTimeoutSeconds", 15),
        "minSendIntervalMillis": outbox_src.get("minSendIntervalMillis", 150),
    },
}
out_path.parent.mkdir(parents=True, exist_ok=True)
out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
os.chmod(out_path, 0o600)
print(f"BOT_CONFIG={out_path}")
print(f"ACCOUNT_ID={account_id}")
print("INBOX_CHANNELS=qq")
PY

echo "prepare complete"
echo "NOTE: bot.sim.local.json 只保存环境变量名，不保存 raw token。"
