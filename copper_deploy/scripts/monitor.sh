#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

require_root
require_command python3

var_dir="$(required_env_value COPPER_VAR_DIR)"
state_dir="$var_dir/monitor"
tool="$SCRIPT_DIR/monitor.py"
[[ -x "$tool" ]] || die "缺少可执行监控工具 $tool"

case "${1:-}" in
  check|status)
    command="$1"
    shift
    if [[ "$command" == "check" ]]; then
      require_command docker
      require_command openssl
      "$SCRIPT_DIR/time-safety.sh" >/dev/null
    fi
    if [[ "${1:-}" == "--json" && $# -eq 1 ]]; then
      exec python3 "$tool" --root "$ROOT_DIR" --env-file "$ENV_FILE" --state-dir "$state_dir" "$command" --json
    elif [[ $# -eq 0 ]]; then
      exec python3 "$tool" --root "$ROOT_DIR" --env-file "$ENV_FILE" --state-dir "$state_dir" "$command"
    fi
    die "monitor $command 只接受 --json"
    ;;
  history)
    shift
    args=()
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --json) args+=(--json); shift ;;
        --limit)
          [[ -n "${2:-}" ]] || die "monitor history --limit 缺少数量"
          args+=(--limit "$2")
          shift 2
          ;;
        *) die "monitor history 只接受 --json 和 --limit 1-1000" ;;
      esac
    done
    exec python3 "$tool" --root "$ROOT_DIR" --env-file "$ENV_FILE" --state-dir "$state_dir" history "${args[@]}"
    ;;
  *)
    die "monitor 支持 check、status、history 或 schedule"
    ;;
esac
