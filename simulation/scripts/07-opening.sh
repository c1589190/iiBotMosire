#!/usr/bin/env bash
# 创建零玩家阶段的公共开场推文草稿；只调用 tf_publication_opening。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

if [ "$#" -ne 3 ]; then
    echo "用法: $0 \"标题\" \"正文\" \"幂等键\"" >&2
    exit 2
fi

title="$1"
body="$2"
idempotency_key="$3"
[ -n "$title" ] || { echo "ERROR: 标题不能为空" >&2; exit 2; }
[ -n "$body" ] || { echo "ERROR: 正文不能为空" >&2; exit 2; }
[ -n "$idempotency_key" ] || { echo "ERROR: 幂等键不能为空" >&2; exit 2; }

mkdir -p "$RUNTIME/init"
chmod 700 "$RUNTIME/init"
umask 077
args_file="$(mktemp "$RUNTIME/init/opening-args.XXXXXX.json")"
trap 'rm -f "$args_file"' EXIT
chmod 600 "$args_file"

# 参数经 0600 临时 JSON 文件传给 MCP；脚本不打印正文，也不把正文写入日志。
TITLE="$title" BODY="$body" IDEMPOTENCY_KEY="$idempotency_key" \
python3 - "$args_file" <<'PY'
import json
import os
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
payload = {
    "title": os.environ["TITLE"],
    "body": os.environ["BODY"],
    "idempotencyKey": os.environ["IDEMPOTENCY_KEY"],
}
with path.open("w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False)
PY
chmod 600 "$args_file"

echo "创建 PENDING_APPROVAL 公共开场草稿；本脚本不会审核、批准或发送 QQ。"
"$SIM_ROOT/scripts/06-mcp.sh" tf_publication_opening "@$args_file"

echo
echo "下一步（人工/DSH 审核后才允许外发）："
echo "  simulation/scripts/06-mcp.sh tf_post_list '{}'"
echo "  simulation/scripts/06-mcp.sh tf_post_get '{\"postId\":\"<returned publicPostId>\"}'"
echo "  simulation/scripts/06-mcp.sh tf_post_decide '{\"postId\":\"<...>\",\"decision\":\"APPROVE\",\"expectedRevision\":1,\"idempotencyKey\":\"<...>\"}'"
echo "  simulation/scripts/06-mcp.sh tf_post_publish '{\"postId\":\"<...>\",\"expectedRevision\":2,\"idempotencyKey\":\"<...>\"}'"
