#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/lib.sh"

temporary="$(mktemp -d /tmp/copper-backup-contract-XXXXXX)"
trap 'rm -rf "$temporary"' EXIT

printf 'copper-backup-test\n' > "$temporary/archive.tar.gz"
(cd "$temporary" && sha256sum archive.tar.gz > archive.tar.gz.sha256)
verify_sha256_sidecar "$temporary/archive.tar.gz" "$temporary/archive.tar.gz.sha256" archive.tar.gz >/dev/null

cp "$temporary/archive.tar.gz.sha256" "$temporary/wrong-name.sha256"
sed -i.bak 's/archive.tar.gz/wrong.tar.gz/' "$temporary/wrong-name.sha256"
rm -f "$temporary/wrong-name.sha256.bak"
if (verify_sha256_sidecar "$temporary/archive.tar.gz" "$temporary/wrong-name.sha256" archive.tar.gz >/dev/null 2>&1); then
  echo "错误：错误文件名的校验文件被接受" >&2
  exit 1
fi

printf '0%.0s' {1..64} > "$temporary/wrong-hash.sha256"
printf '  archive.tar.gz\n' >> "$temporary/wrong-hash.sha256"
if (verify_sha256_sidecar "$temporary/archive.tar.gz" "$temporary/wrong-hash.sha256" archive.tar.gz >/dev/null 2>&1); then
  echo "错误：错误哈希被接受" >&2
  exit 1
fi

echo "backup_contract=passed"

grep -q 'require_no_pending_initial_admin_credential' scripts/backup.sh
grep -q 'require_current_admin_handover_complete' scripts/restore.sh
grep -q 'stage_var/backend/runtime' scripts/restore.sh
grep -q 'capture_stage_failure_diagnostics' scripts/restore.sh
grep -q '首次管理员一次性凭据已经完成交付' docs/M2_BACKUP.md
grep -q '空白目标站也必须先' docs/M2_RESTORE.md
echo "bootstrap_credential_backup_restore_contract=passed"

python3 tests/test_backup_metadata.py -v
python3 tests/test_backup_chain.py -v
python3 tests/test_backup_key_lifecycle.py -v
python3 tests/test_backup_job.py -v
python3 tests/test_restore_metadata.py -v
python3 tests/test_erp_backup_restore_coverage.py -v
