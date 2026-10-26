#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib.sh"

require_root
[[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || die "Copper 环境文件不存在或不安全：$ENV_FILE"
exec "$SCRIPT_DIR/egress-audit.py" --policy "$ROOT_DIR/config/egress-policy.json" --env-file "$ENV_FILE" "$@"
