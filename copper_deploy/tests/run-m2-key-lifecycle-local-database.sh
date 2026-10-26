#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export COPPER_M2_06_MODE=1
exec "$ROOT_DIR/tests/run-m2-storage-local-database.sh"
