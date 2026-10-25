#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

[ -f "$JAR" ] || { echo "ERROR: 缺少 $JAR，先部署 simulation/dist" >&2; exit 2; }
source_iibot_env
require_env IIBOT_CONTROL_TOKEN
mkdir -p "$RUNTIME"
chmod 700 "$RUNTIME"

RAW="$RUNTIME/.inbox-descriptor.raw.$$"
ERR="$RUNTIME/.inbox-descriptor.err.$$"
cleanup() { rm -f "$RAW" "$ERR"; }
trap cleanup EXIT
: > "$RAW"
: > "$ERR"
chmod 600 "$RAW" "$ERR"

if ! "$SIM_ROOT/scripts/06-mcp.sh" tf_inbox_binding_descriptor '{}' >"$RAW" 2>"$ERR"; then
    echo "ERROR: tf_inbox_binding_descriptor 调用失败" >&2
    tail -20 "$ERR" >&2 || true
    exit 1
fi

python3 - "$RAW" "$INBOX_DESCRIPTOR_FILE" "$RUNTIME/inbox" <<'PYDESC'
import json
import os
import pathlib
import sys

raw_path = pathlib.Path(sys.argv[1])
out_path = pathlib.Path(sys.argv[2])
inbox_dir = pathlib.Path(sys.argv[3])

text = raw_path.read_text(encoding="utf-8")
if "IS_ERROR=true" in text:
    raise SystemExit("ERROR: MCP 返回 IS_ERROR=true")
lines = [line.strip() for line in text.splitlines() if line.strip().startswith("{")]
if not lines:
    raise SystemExit("ERROR: tf_inbox_binding_descriptor 未返回 JSON")
payload = json.loads(lines[-1])
if set(payload) != {"descriptor", "descriptorFile"}:
    raise SystemExit(f"ERROR: descriptor 工具输出字段异常: {sorted(payload)}")
descriptor = payload["descriptor"]
expected = {"inboxId", "apiBaseUrl", "adapterTokenRef", "channels", "label"}
if not isinstance(descriptor, dict) or set(descriptor) != expected:
    raise SystemExit(f"ERROR: descriptor 字段异常: {sorted(descriptor or {})}")

serialized = json.dumps(descriptor, ensure_ascii=False, indent=2) + "\n"
for token_name in ("program-mcp.token", "dsh-wake.token"):
    token_path = inbox_dir / token_name
    if token_path.is_file():
        token = token_path.read_text(encoding="utf-8").strip()
        if token and token in serialized:
            raise SystemExit(f"ERROR: descriptor 含 raw token ({token_name})")

out_path.parent.mkdir(parents=True, exist_ok=True)
out_path.write_text(serialized, encoding="utf-8")
os.chmod(out_path, 0o600)

print(f"INBOX_DESCRIPTOR_FILE={out_path}")
print(f"INBOX_ID={descriptor['inboxId']}")
print(f"INBOX_API_BASE_URL={descriptor['apiBaseUrl']}")
print(f"INBOX_ADAPTER_TOKEN_REF={descriptor['adapterTokenRef']}")
print(f"INBOX_CHANNELS={','.join(descriptor['channels'])}")
print(f"INBOX_LABEL={descriptor['label']}")
print(f"APP_DESCRIPTOR_FILE={payload['descriptorFile']}")
PYDESC
