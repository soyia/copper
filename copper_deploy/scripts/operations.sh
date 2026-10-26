#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

require_root
require_command python3

tool="$SCRIPT_DIR/operations.py"
[[ -x "$tool" ]] || die "缺少可执行运维目标工具 $tool"

case "${1:-}" in
  status)
    shift
    if [[ $# -eq 0 ]]; then
      exec python3 "$tool" --root "$ROOT_DIR" --env-file "$ENV_FILE" status
    elif [[ $# -eq 1 && "$1" == "--json" ]]; then
      exec python3 "$tool" --root "$ROOT_DIR" --env-file "$ENV_FILE" status --json
    fi
    die "operations status 只接受 --json"
    ;;
  checklist)
    [[ -n "${2:-}" ]] || die "operations checklist 需要故障类型"
    fault="$2"
    shift 2
    if [[ $# -eq 0 ]]; then
      exec python3 "$tool" --root "$ROOT_DIR" --env-file "$ENV_FILE" checklist "$fault"
    elif [[ $# -eq 1 && "$1" == "--json" ]]; then
      exec python3 "$tool" --root "$ROOT_DIR" --env-file "$ENV_FILE" checklist "$fault" --json
    fi
    die "operations checklist 只接受故障类型和 --json"
    ;;
  *) die "operations 支持 status 或 checklist" ;;
esac
