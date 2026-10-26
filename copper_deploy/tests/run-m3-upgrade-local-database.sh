#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export COPPER_M3_03_RUNTIME=1
exec "$ROOT_DIR/tests/run-m3-upgrade-preflight-local-database.sh"
