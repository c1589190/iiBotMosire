#!/bin/sh
# 容器的「主程序」：等内嵌 Inbox 就绪，再把 gateway 交出去。
# 宿主镜像的约定是 CMD 就是主程序（docker/s6-rc.d/main-hermes/run 的注释），
# 所以 gateway 不放进 s6，它的退出即容器退出，交给 restart 策略接管。
set -e

URL="${INBOX_HEALTH_URL:-http://127.0.0.1:46500/healthz}"
WAIT="${INBOX_WAIT_SECONDS:-60}"

i=0
while [ "$i" -lt "$WAIT" ]; do
    if curl -fsS --max-time 2 "$URL" >/dev/null 2>&1; then
        echo "[cmd] inbox 就绪（${i}s），启动 gateway"
        break
    fi
    i=$((i + 1))
    sleep 1
done

if [ "$i" -ge "$WAIT" ]; then
    echo "[cmd] 警告：${WAIT}s 内没等到 $URL，仍然启动 gateway（工具会在后续回合补进来）" >&2
fi

exec hermes gateway run "$@"
