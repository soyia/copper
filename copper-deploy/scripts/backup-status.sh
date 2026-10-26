#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

require_root
require_command python3

state_dir="$(required_env_value COPPER_VAR_DIR)/backup-jobs"
tool="$SCRIPT_DIR/backup-job.py"
[[ -x "$tool" ]] || die "缺少备份任务状态工具 $tool"
[[ -f "$state_dir/status.json" ]] || { echo "尚无备份任务状态；请先运行 copperctl backup create"; exit 0; }

case "${1:-}" in
  "") exec python3 "$tool" --state-dir "$state_dir" status --human ;;
  --json) [[ $# -eq 1 ]] || die "backup status --json 不接受额外参数"; exec python3 "$tool" --state-dir "$state_dir" status ;;
  *) die "backup status 只接受 --json" ;;
esac
