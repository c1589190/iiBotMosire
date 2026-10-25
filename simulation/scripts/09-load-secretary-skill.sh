#!/usr/bin/env bash
# 将通用秘书 Agent 行为 Skill 加载到 iibot 共享 Skill 区。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

SKILL_FILE="$SIM_ROOT/.agents/skills/iibot-secretary-behavior/SKILL.md"
TARGET_PATH="skills/iibot-secretary-behavior.md"
MCP="$SIM_ROOT/scripts/06-mcp.sh"
INIT_DIR="$RUNTIME/init"

[ -f "$SKILL_FILE" ] || { echo "ERROR: 缺少 $SKILL_FILE" >&2; exit 2; }
source_iibot_env
require_env IIBOT_CONTROL_TOKEN
mkdir -p "$INIT_DIR"
chmod 700 "$INIT_DIR"

ARG_FILE="$(mktemp "$INIT_DIR/skill-load-arg.XXXXXX")"
trap 'rm -f "$ARG_FILE"' EXIT
chmod 600 "$ARG_FILE"

python3 - "$SKILL_FILE" "$ARG_FILE" "$TARGET_PATH" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
text = source.read_text(encoding='utf-8')
if text.startswith('---'):
    parts = text.split('---', 2)
    if len(parts) != 3:
        raise SystemExit('SKILL frontmatter 不完整')
    text = parts[2].lstrip('\n')
args = {
    'path': sys.argv[3],
    'content': text,
    'replace': True,
    'idempotencyKey': 'load-iibot-secretary-behavior-v1',
    'reason': '加载通用秘书 Agent 行为 Skill',
}
target.write_text(json.dumps(args, ensure_ascii=False), encoding='utf-8')
PY

"$MCP" tf_shared_skill_write "@$ARG_FILE"
echo "SECRETARY_SKILL_LOADED=$TARGET_PATH"
