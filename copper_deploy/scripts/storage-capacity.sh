#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

require_root
require_command python3

var_dir="$(required_env_value COPPER_VAR_DIR)"
backup_dir="$(backup_dir_value)"
target_tool="$SCRIPT_DIR/storage-target.py"
capacity_tool="$SCRIPT_DIR/storage-capacity.py"
[[ -x "$target_tool" && -x "$capacity_tool" ]] || die "缺少可执行存储容量工具"

"$SCRIPT_DIR/time-safety.sh" >/dev/null

temporary="$(mktemp -d "${TMPDIR:-/tmp}/copper-storage-status-XXXXXX")"
trap 'rm -rf --one-file-system -- "$temporary"' EXIT

"$target_tool" --env-file "$ENV_FILE" status > "$temporary/targets.json"
output="$var_dir/backend/runtime/storage-capacity.json"
python3 "$capacity_tool" \
  --var-dir "$var_dir" \
  --backup-dir "$backup_dir" \
  --targets-json "$temporary/targets.json" \
  --output "$output"
