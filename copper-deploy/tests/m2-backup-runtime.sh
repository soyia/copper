#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export COPPER_ENV_FILE="${COPPER_ENV_FILE:?COPPER_ENV_FILE is required}"
export COPPER_TEST_MODE="${COPPER_TEST_MODE:-1}"
export COPPER_ALLOW_MUTABLE_IMAGES="${COPPER_ALLOW_MUTABLE_IMAGES:-1}"
identity="${COPPER_BACKUP_TEST_IDENTITY:?COPPER_BACKUP_TEST_IDENTITY is required}"

source "$ROOT_DIR/scripts/lib.sh"

require_root
[[ "$COPPER_TEST_MODE" == "1" ]] || die "运行时备份测试只允许 COPPER_TEST_MODE=1"
validate_tools
validate_environment

etc_dir="$(required_env_value COPPER_ETC_DIR)"
var_dir="$(required_env_value COPPER_VAR_DIR)"
backup_dir="$(backup_dir_value)"
site_id="$(required_env_value COPPER_SITE_ID)"
proxy_port="$(required_env_value COPPER_PROXY_PORT)"
[[ "$etc_dir" == /tmp/copper-m2-02-* && "$var_dir" == /tmp/copper-m2-02-* ]] \
  || die "测试目录必须使用 /tmp/copper-m2-02-*"
[[ "$backup_dir" == /tmp/copper-m2-02-* || "$backup_dir" == /opt/copper-m2-02-* ]] \
  || die "测试备份目录必须使用隔离的 copper-m2-02 前缀"
[[ "$proxy_port" != "8191" ]] || die "运行时测试禁止占用正式 8191 端口"
[[ -s "$identity" ]] || die "测试 age 私钥不存在"

temporary="$(mktemp -d /tmp/copper-m2-02-acceptance-XXXXXX)"
trap 'rm -rf --one-file-system -- "$temporary"' EXIT

handover_record="$temporary/key-handover-record.txt"
printf 'Copper M2-02 isolated runtime key handover\n' > "$handover_record"
chmod 0600 "$handover_record"
handover_sha="$(sha256sum "$handover_record" | awk '{print $1}')"
"$ROOT_DIR/copperctl" backup key register \
  --handover-sha256 "$handover_sha" \
  --customer-confirmed-by "M2 test customer" \
  --witness-confirmed-by "M2 test witness" >/dev/null

require_healthy() {
  local service_name service_id state health
  for service_name in mysql backend web; do
    service_id="$(compose_cmd ps -q "$service_name")"
    [[ -n "$service_id" ]] || die "$service_name 容器不存在"
    state="$(docker inspect --format '{{.State.Status}}' "$service_id")"
    health="$(docker inspect --format '{{.State.Health.Status}}' "$service_id")"
    [[ "$state/$health" == "running/healthy" ]] || die "$service_name 未恢复健康：$state/$health"
  done
}

archive_count() {
  find "$backup_dir" -maxdepth 1 -type f -name '*.copperbak' -print | wc -l
}

last_success_digest() {
  sha256sum "$backup_dir/last-success.json" | awk '{print $1}'
}

expect_failure_without_publication() {
  local label="$1"
  shift
  local before_count before_last after_count after_last
  before_count="$(archive_count)"
  before_last="$(last_success_digest)"
  if env "$@" "$ROOT_DIR/copperctl" backup create >/dev/null 2>"$temporary/$label.err"; then
    die "$label 故障注入意外成功"
  fi
  require_healthy
  after_count="$(archive_count)"
  after_last="$(last_success_digest)"
  [[ "$after_count" == "$before_count" ]] || die "$label 失败后发布了新备份"
  [[ "$after_last" == "$before_last" ]] || die "$label 失败后改写了 last-success.json"
  printf '%s=passed\n' "$label"
}

require_healthy

attachment_relative="inbounds/999/m2-runtime-attachment.txt"
attachment="$var_dir/files/$attachment_relative"
install -d -o 10001 -g 10001 -m 0750 "$(dirname "$attachment")"
printf 'Copper M2-02 runtime attachment\n' > "$temporary/attachment"
install -o 10001 -g 10001 -m 0640 "$temporary/attachment" "$attachment"
attachment_size="$(stat -c '%s' "$attachment")"
attachment_sha="$(sha256sum "$attachment" | awk '{print $1}')"
compose_cmd exec -T mysql sh -c \
  'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" exec mysql -uroot copper_v2 --batch --raw' <<SQL
DELETE FROM inbound_attachment WHERE inbound_id = 999 AND file_name = 'm2-runtime-attachment.txt';
INSERT INTO inbound_attachment
  (inbound_id, file_name, content_type, file_size, sha256, storage_path, file_content, uploaded_by)
VALUES
  (999, 'm2-runtime-attachment.txt', 'text/plain', $attachment_size, '$attachment_sha', '$attachment_relative', NULL, 0);
SQL

"$ROOT_DIR/copperctl" backup create
archive_name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["archive"])' "$backup_dir/last-success.json")"
archive="$backup_dir/$archive_name"
"$ROOT_DIR/copperctl" backup verify "$archive"

install -d -m 0700 "$temporary/outer" "$temporary/payload"
tar -xf "$archive" -C "$temporary/outer"
age --decrypt --identity "$identity" "$temporary/outer/payload.tar.zst.age" \
  | zstd -q -d -c \
  | tar -xf - -C "$temporary/payload"
(cd "$temporary/payload" && sha256sum --strict -c checksums.sha256 >/dev/null)
zstd -q -t "$temporary/payload/database/copper.sql.zst"
cmp "$attachment" "$temporary/payload/attachments/$attachment_relative"

python3 - "$temporary/payload" "$etc_dir/secrets/copper_db_password" "$etc_dir/secrets/mysql_root_password" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
attachments = json.loads((root / "verification/attachments.json").read_text(encoding="utf-8"))
runtime = json.loads((root / "config/runtime-allowlist.json").read_text(encoding="utf-8"))
schema = json.loads((root / "verification/schema.json").read_text(encoding="utf-8"))
assert manifest["formatVersion"] == "copper-backup/v1"
assert manifest["consistency"]["mode"] == "graceful-write-quiesce"
assert manifest["consistency"]["maintenanceExit"] == "healthy"
assert manifest["database"]["version"].startswith("8.4.")
assert manifest["database"]["tableCount"] == schema["tableCount"]
assert attachments["databaseReferenceCount"] >= 1
assert any(item["path"] == "inbounds/999/m2-runtime-attachment.txt" for item in attachments["files"])
assert runtime["containsSecrets"] is False
secrets = [pathlib.Path(path).read_bytes().strip() for path in sys.argv[2:]]
for path in root.rglob("*"):
    if path.is_file():
        content = path.read_bytes()
        assert all(secret not in content for secret in secrets)
print(f"mysql_version={manifest['database']['version']}")
print(f"schema_version={manifest['database']['schemaVersion']}")
print(f"table_count={manifest['database']['tableCount']}")
print(f"attachment_reference_count={attachments['databaseReferenceCount']}")
print(f"archive_id={manifest['backupId']}")
PY

expect_failure_without_publication low_space COPPER_BACKUP_TEST_AVAILABLE_BYTES=0
expect_failure_without_publication stopped_recovery COPPER_BACKUP_TEST_FAIL_AT=after-stop
expect_failure_without_publication corrupt_package COPPER_BACKUP_TEST_CORRUPT_BEFORE_VERIFY=1
expect_failure_without_publication atomic_publish COPPER_BACKUP_TEST_FAIL_AT=during-publish

before_count="$(archive_count)"
before_last="$(last_success_digest)"
env COPPER_BACKUP_TEST_DELAY_AFTER_STOP=30 "$ROOT_DIR/copperctl" backup create \
  >"$temporary/interrupted.out" 2>"$temporary/interrupted.err" &
backup_pid=$!
stopped=0
for _ in $(seq 1 30); do
  if [[ -z "$(compose_cmd ps -q backend 2>/dev/null || true)" ]]; then
    stopped=1
    break
  fi
  sleep 1
done
[[ "$stopped" == "1" ]] || { kill -TERM "$backup_pid" 2>/dev/null || true; die "未观察到备份维护窗口"; }
kill -TERM "$backup_pid"
if wait "$backup_pid"; then
  die "中断测试意外成功"
fi
require_healthy
[[ "$(archive_count)" == "$before_count" ]] || die "中断后发布了新备份"
[[ "$(last_success_digest)" == "$before_last" ]] || die "中断后改写了 last-success.json"
echo "interrupt_recovery=passed"

"$ROOT_DIR/copperctl" backup prune --dry-run >/dev/null
require_healthy
echo "m2_backup_runtime=passed"
