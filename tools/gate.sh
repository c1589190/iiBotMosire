#!/usr/bin/env bash
# iiBotMosire 工程门禁入口。
# docs/selftest 不需要 Maven；pre/focused/full 使用仓库内 Maven Wrapper。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
  cat <<'USAGE'
用法:
  tools/gate.sh docs                     # 文档 + Skill 结构检查
  tools/gate.sh selftest                 # 文档/Skill 检查器自检
  tools/gate.sh pre                      # 开工自检（docs + 工具链）
  tools/gate.sh focused <module> [args]  # 指定模块 + -am 的聚焦测试
  tools/gate.sh full                     # 全仓 clean verify（含测试与 shaded jar）
USAGE
}

run_docs() {
  python3 tools/check-docs.py
  python3 tools/skills-lint.py
}

require_wrapper() {
  if [[ ! -x ./mvnw ]]; then
    echo "[gate] 缺少可执行 ./mvnw" >&2
    exit 3
  fi
}

case "${1:-}" in
  docs)
    run_docs
    echo "[gate] docs 结论：PASS"
    ;;
  selftest)
    python3 tools/check-docs.py --selftest
    python3 tools/skills-lint.py --selftest
    echo "[gate] selftest 结论：PASS"
    ;;
  pre)
    run_docs
    require_wrapper
    ./mvnw -v
    echo "[gate] pre 结论：PASS (DOCS + TOOLCHAIN)"
    ;;
  focused)
    if [[ $# -lt 2 ]]; then
      echo "[gate] focused 缺少模块名" >&2
      usage >&2
      exit 2
    fi
    module="$2"
    shift 2
    require_wrapper
    ./mvnw -B -ntp -pl "$module" -am "$@"
    echo "[gate] focused 结论：FOCUSED TEST PASSED (NOT a full gate)"
    ;;
  full)
    require_wrapper
    ./mvnw -B -ntp clean verify
    echo "[gate] full 结论：PASS"
    ;;
  -h|--help|help|"")
    usage
    ;;
  *)
    echo "[gate] 未知命令: ${1}" >&2
    usage >&2
    exit 2
    ;;
esac
