#!/usr/bin/env bash
# 静态自检：不需要 docker daemon，CI 与本地都能跑。
#   1) versions.env 与 Dockerfile / compose 里钉的版本必须一致
#   2) shell / JSON / YAML 语法 + 模板占位是否都被 seed 覆盖
#   3) 镜像里不许出现密钥字面量
#   4) 兄弟仓库存在时，校验 PLUGINS_REF 能解析、两个插件都在
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
fail=0
ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; fail=1; }

# shellcheck disable=SC1091
. ./versions.env

echo "› 1. 版本一致性"
check_pair() {
    local name="$1" value="$2" file="$3"
    if grep -q -- "$value" "$file"; then ok "$name = $value（$file）"; else bad "$name = $value 没出现在 $file"; fi
}
check_pair IIBOT_VERSION "$IIBOT_VERSION" Dockerfile
check_pair IIBOT_SHA256  "$IIBOT_SHA256"  Dockerfile
check_pair IIBOT_SHA256  "$IIBOT_SHA256"  compose.yaml
check_pair PLUGINS_REF   "$PLUGINS_REF"   Dockerfile
check_pair PLUGINS_REF   "$PLUGINS_REF"   compose.yaml
check_pair HERMES_BASE   "$HERMES_BASE"   Dockerfile
check_pair HERMES_BASE   "$HERMES_BASE"   compose.yaml

echo "› 2. 语法"
for f in iibot/run cmd.sh cont-init.d/05-iibot-seed verify.sh; do
    if sh -n "$f" 2>/dev/null; then ok "$f"; else bad "$f 语法错误"; fi
done
if python3 -c 'import json,sys; json.load(open("defaults/iibot-bot.json"))' 2>/dev/null; then
    ok "defaults/iibot-bot.json"
else
    bad "defaults/iibot-bot.json"
fi
if python3 -c '
import re, yaml
yaml.safe_load(open("compose.yaml"))
tpl = open("defaults/hermes-config.yaml.tmpl").read()
yaml.safe_load(re.sub(r"@@[A-Z_]+@@", "placeholder", tpl))
' 2>/dev/null; then
    ok "compose.yaml + defaults/hermes-config.yaml.tmpl"
else
    bad "YAML 解析失败"
fi

echo "› 3. 模板占位"
for ph in $(grep -oE '@@[A-Z_]+@@' defaults/hermes-config.yaml.tmpl | sort -u); do
    if grep -q -- "$ph" cont-init.d/05-iibot-seed; then ok "seed 覆盖 $ph"; else bad "seed 没处理 $ph"; fi
done
# 运行期插值必须原样留在配置里，不能被 seed 提前替换掉
if grep -q '\${MCP_' defaults/hermes-config.yaml.tmpl; then ok "\${MCP_*} 原样保留（宿主运行期插值）"; else bad "模板里丢了 \${MCP_*} 占位"; fi

echo "› 4. 泄露扫描"
if grep -rnE 'Bearer [A-Za-z0-9._-]{20,}|sk-[A-Za-z0-9]{16,}|IIBOT_CONTROL_TOKEN=[^ ]' \
      --include='*.yaml' --include='*.tmpl' --include='*.json' --include='*.sh' --include='Dockerfile' \
      --exclude=verify.sh . 2>/dev/null \
      | grep -v '\${' >/dev/null; then
    bad "疑似把 token/密钥写进了镜像文件"
else
    ok "没有密钥字面量（只允许 \$ENV 占位）"
fi

echo "› 5. 插件 tag 可解析"
PLUGIN_REPO="${PLUGIN_REPO:-$ROOT/../../../InboxMosire-Hermes}"
if git -C "$PLUGIN_REPO" rev-parse --git-dir >/dev/null 2>&1; then
    if git -C "$PLUGIN_REPO" rev-parse -q --verify "refs/tags/$PLUGINS_REF" >/dev/null; then
        ok "$PLUGINS_REF 存在（$PLUGIN_REPO）"
        for id in persona-mode inbox-wake; do
            if git -C "$PLUGIN_REPO" cat-file -e "$PLUGINS_REF:plugins/$id/plugin.yaml" 2>/dev/null; then
                ver="$(git -C "$PLUGIN_REPO" show "$PLUGINS_REF:plugins/$id/plugin.yaml" | sed -n 's/^version:[[:space:]]*"\?\([^"]*\)"\?/\1/p')"
                ok "tag 里有 plugins/$id（$ver）"
            else
                bad "tag 里没有 plugins/$id"
            fi
        done
    else
        bad "$PLUGINS_REF 不是 $PLUGIN_REPO 里的 tag"
    fi
else
    echo "  − 跳过：找不到插件仓库 $PLUGIN_REPO（远端构建时正常）"
fi

echo
if [ "$fail" -eq 0 ]; then echo "✓ 全部通过"; else echo "✗ 有失败项"; fi
exit "$fail"
