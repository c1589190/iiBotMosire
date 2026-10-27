#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/lib.sh"

pidfile="$LOGS/app.pid"
if [ ! -f "$pidfile" ]; then echo "no pidfile; bot not started by simulation2"; exit 0; fi
pid="$(cat "$pidfile" 2>/dev/null || true)"
if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
    echo "bot not running"; rm -f "$pidfile"; exit 0
fi
kill "$pid" 2>/dev/null || true
for _ in $(seq 1 30); do
    if ! kill -0 "$pid" 2>/dev/null; then
        echo "BOT_STOPPED pid=$pid"; rm -f "$pidfile"; exit 0
    fi
    sleep 1
done
kill -9 "$pid" 2>/dev/null || true
rm -f "$pidfile"
echo "BOT_KILLED pid=$pid"
