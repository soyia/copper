#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

require_root
require_command python3

var_dir="$(required_env_value COPPER_VAR_DIR)"
install -d -o root -g 10001 -m 0750 "$var_dir/time-safety"
output="$var_dir/time-safety/time-safety.json"
exec python3 "$SCRIPT_DIR/time-safety.py" --output "$output" "$@"
