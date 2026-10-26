#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_id="${COPPER_INCREMENTAL_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
[[ "$run_id" =~ ^[0-9A-Za-z._-]{8,64}$ ]] || { echo "invalid run id" >&2; exit 2; }
work="/tmp/copper-incremental-runtime-$run_id"
source_name="copper-inc-source-$run_id"
target_name="copper-inc-target-$run_id"
mysql_image="${COPPER_INCREMENTAL_MYSQL_IMAGE:?COPPER_INCREMENTAL_MYSQL_IMAGE must reference the built Copper MySQL image with mysqlbinlog}"
password="$(openssl rand -hex 24)"

cleanup() {
  docker rm -f "$source_name" "$target_name" >/dev/null 2>&1 || true
  rm -rf --one-file-system -- "$work"
}
trap cleanup EXIT INT TERM
trap 'echo "incremental runtime failure at line $LINENO" >&2' ERR
install -d -m 0700 "$work" "$work/base-files/docs" "$work/current-files/docs" "$work/delta-files" "$work/materialized/docs"

for command_name in docker openssl python3 sha256sum; do
  command -v "$command_name" >/dev/null || { echo "missing command: $command_name" >&2; exit 1; }
done

docker run -d --name "$source_name" \
  -e MYSQL_ROOT_PASSWORD="$password" \
  "$mysql_image" \
  --log-bin=/var/lib/mysql/binlog --binlog-format=ROW --binlog-row-image=FULL --binlog-expire-logs-seconds=2592000 \
  >/dev/null
docker run -d --name "$target_name" -e MYSQL_ROOT_PASSWORD="$password" "$mysql_image" --skip-log-bin >/dev/null

wait_mysql() {
  local container="$1"
  for _ in $(seq 1 90); do
    if docker logs "$container" 2>&1 | grep -q 'MySQL init process done. Ready for start up.' \
      && docker exec -e MYSQL_PWD="$password" "$container" mysqladmin -uroot ping --silent >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  echo "mysql did not become ready: $container" >&2
  docker logs "$container" >&2 || true
  exit 1
}
wait_mysql "$source_name"
wait_mysql "$target_name"

mysql_source() {
  docker exec -i -e MYSQL_PWD="$password" "$source_name" mysql -uroot --batch --raw --skip-column-names "$@"
}
mysql_target() {
  docker exec -i -e MYSQL_PWD="$password" "$target_name" mysql -uroot --batch --raw --skip-column-names "$@"
}

mysql_source <<'SQL'
CREATE DATABASE copper_v2 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE copper_v2;
CREATE TABLE stock_balance (
  id BIGINT PRIMARY KEY,
  warehouse_id BIGINT NOT NULL,
  quantity DECIMAL(18,6) NOT NULL,
  amount_cost DECIMAL(18,6) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE inbound_attachment (
  id BIGINT PRIMARY KEY,
  storage_path VARCHAR(255) NOT NULL,
  file_size BIGINT NOT NULL,
  sha256 CHAR(64) NOT NULL
) ENGINE=InnoDB;
INSERT INTO stock_balance VALUES (1, 10, 20.345000, 1424.150000);
SQL

mysql_source -e 'FLUSH BINARY LOGS;'
read -r from_file from_position _ < <(mysql_source -e 'SHOW BINARY LOG STATUS;')
[[ "$from_file" =~ ^binlog\.[0-9]{6,}$ && "$from_position" =~ ^[0-9]+$ ]] || { echo "invalid base boundary" >&2; exit 1; }
docker exec -e MYSQL_PWD="$password" "$source_name" \
  mysqldump -uroot --databases copper_v2 --single-transaction --quick --hex-blob --no-tablespaces --set-gtid-purged=OFF \
  > "$work/full.sql"

printf 'signed-receipt-v1\n' > "$work/base-files/docs/receipt.pdf"
printf 'obsolete-signature\n' > "$work/base-files/docs/obsolete-signature.txt"
cp "$work/base-files/docs/receipt.pdf" "$work/materialized/docs/receipt.pdf"
cp "$work/base-files/docs/obsolete-signature.txt" "$work/materialized/docs/obsolete-signature.txt"
printf 'signed-receipt-v2\n' > "$work/current-files/docs/receipt.pdf"
printf 'invoice-image\n' > "$work/current-files/docs/invoice.jpg"

receipt_size="$(stat -c '%s' "$work/current-files/docs/receipt.pdf")"
receipt_sha="$(sha256sum "$work/current-files/docs/receipt.pdf" | awk '{print $1}')"
invoice_size="$(stat -c '%s' "$work/current-files/docs/invoice.jpg")"
invoice_sha="$(sha256sum "$work/current-files/docs/invoice.jpg" | awk '{print $1}')"
mysql_source copper_v2 <<SQL
UPDATE stock_balance SET quantity = 18.345000, amount_cost = 1284.150000 WHERE id = 1;
INSERT INTO stock_balance VALUES (2, 11, 2.000000, 140.000000);
INSERT INTO inbound_attachment VALUES (1, 'docs/receipt.pdf', $receipt_size, '$receipt_sha');
INSERT INTO inbound_attachment VALUES (2, 'docs/invoice.jpg', $invoice_size, '$invoice_sha');
SQL

mysql_source -e 'FLUSH BINARY LOGS;'
read -r to_file to_position _ < <(mysql_source -e 'SHOW BINARY LOG STATUS;')
[[ "$to_file" =~ ^binlog\.[0-9]{6,}$ && "$to_file" != "$from_file" ]] || { echo "invalid increment boundary" >&2; exit 1; }
docker cp "$source_name:/var/lib/mysql/$from_file" "$work/$from_file" >/dev/null

mysql_target < "$work/full.sql"
docker cp "$work/$from_file" "$target_name:/tmp/$from_file" >/dev/null
docker exec "$target_name" mysqlbinlog --disable-log-bin --start-position="$from_position" "/tmp/$from_file" \
  | mysql_target

source_rows="$(mysql_source copper_v2 -e 'SELECT id, warehouse_id, quantity, amount_cost FROM stock_balance ORDER BY id; SELECT id, storage_path, file_size, sha256 FROM inbound_attachment ORDER BY id;')"
target_rows="$(mysql_target copper_v2 -e 'SELECT id, warehouse_id, quantity, amount_cost FROM stock_balance ORDER BY id; SELECT id, storage_path, file_size, sha256 FROM inbound_attachment ORDER BY id;')"
[[ "$source_rows" == "$target_rows" ]] || { echo "binlog replay differs from source" >&2; exit 1; }

python3 - "$work" <<'PY'
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])

def snapshot(directory):
    files = []
    for path in sorted(directory.rglob("*")):
        if not path.is_file():
            continue
        content = path.read_bytes()
        files.append({
            "path": path.relative_to(directory).as_posix(),
            "size": len(content),
            "sha256": hashlib.sha256(content).hexdigest(),
            "referencedBy": [],
        })
    return {
        "fileCount": len(files),
        "totalBytes": sum(item["size"] for item in files),
        "databaseReferenceCount": 0,
        "orphanFileCount": len(files),
        "files": files,
    }

base = snapshot(root / "base-files")
current = snapshot(root / "current-files")
(root / "state.json").write_text(json.dumps({
    "formatVersion": "copper-backup-chain-state/v1",
    "attachmentSnapshot": base,
}, sort_keys=True) + "\n")
(root / "current.json").write_text(json.dumps(current, sort_keys=True) + "\n")
PY

python3 "$ROOT_DIR/scripts/backup-chain.py" attachment-delta \
  "$work/state.json" "$work/current.json" "$work/current-files" "$work/delta-files" "$work/delta.json"
install -d -m 0700 "$work/delta-payload/verification" "$work/delta-payload/attachments"
cp "$work/delta.json" "$work/delta-payload/verification/attachment-delta.json"
cp -a "$work/delta-files/." "$work/delta-payload/attachments/"
python3 "$ROOT_DIR/scripts/backup-chain.py" apply-delta "$work/delta-payload" "$work/materialized"

current_manifest="$(find "$work/current-files" -type f -print0 | sort -z | xargs -0 sha256sum | sed "s#$work/current-files/##")"
restored_manifest="$(find "$work/materialized" -type f -print0 | sort -z | xargs -0 sha256sum | sed "s#$work/materialized/##")"
[[ "$current_manifest" == "$restored_manifest" ]] || { echo "attachment delta restore differs from source" >&2; exit 1; }

printf 'incremental_mysql_runtime=passed\n'
printf 'from=%s:%s to=%s:%s\n' "$from_file" "$from_position" "$to_file" "$to_position"
