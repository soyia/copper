#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

python3 -m py_compile scripts/storage-target.py scripts/storage-capacity.py tests/backup-alert-receiver.py
bash -n scripts/storage-capacity.sh tests/run-m2-storage-local-database.sh tests/run-m2-key-lifecycle-local-database.sh
python3 tests/test_storage_target.py -v
echo "storage_contract=passed"
