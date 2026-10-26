#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$EUID" -eq 0 ]] || { echo "错误：M4-06 x86_64 验收必须使用 sudo" >&2; exit 1; }
[[ "$(uname -m)" == "x86_64" ]] || { echo "错误：M4-06 正式验收只支持 x86_64" >&2; exit 1; }
. /etc/os-release
[[ "$ID/$VERSION_ID" == "ubuntu/24.04" ]] || { echo "错误：M4-06 正式验收只支持 Ubuntu 24.04" >&2; exit 1; }
[[ "${COPPER_M4_ALLOW_HOST_TIME_CHANGE:-0}" == "1" ]] \
  || { echo "错误：真实时间故障必须显式设置 COPPER_M4_ALLOW_HOST_TIME_CHANGE=1" >&2; exit 1; }

web_image="${COPPER_M4_FAULT_WEB_IMAGE:?COPPER_M4_FAULT_WEB_IMAGE is required}"
backend_image="${COPPER_M4_FAULT_BACKEND_IMAGE:?COPPER_M4_FAULT_BACKEND_IMAGE is required}"
mysql_image="${COPPER_M4_FAULT_MYSQL_IMAGE:-sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af}"
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
base="${COPPER_M4_FAULT_TEST_ROOT:-/opt/copper-m4-06}"
runtime="$base/runtime-$run_id"
evidence="$base/evidence-$run_id"
offline="$runtime/offline-key"
etc_dir="$runtime/etc"
var_dir="$runtime/var"
backup_dir="$runtime/backups"
restore_root="$runtime/restores"
env_file="$etc_dir/copper.env"
data_image="$runtime/data-filesystem.img"
backup_image="$runtime/backup-filesystem.img"
exhaust_image="$runtime/exhaust-filesystem.img"
exhaust_mount="$runtime/exhaust"
project="copper-m406-$run_id"
domain="m406.test.internal"
proxy_port="${COPPER_M4_FAULT_PROXY_PORT:-18118}"
journal="$evidence/fault-events.jsonl"
cookie="$runtime/admin-cookie.txt"
data_mounted=0
backup_mounted=0
backup_read_only=0
exhaust_mounted=0
clock_shifted=0
filler="$var_dir/.m406-capacity-filler"

compose() {
  docker compose --env-file "$env_file" -f "$ROOT_DIR/compose.yaml" -f "$ROOT_DIR/compose.external-proxy.yaml" "$@"
}

restore_clock() {
  [[ "$clock_shifted" == "1" ]] || return 0
  chronyc online >/dev/null 2>&1 || true
  local attempt offset
  for attempt in $(seq 1 3); do
    chronyc burst 4/4 >/dev/null 2>&1 || true
    sleep 5
    chronyc makestep >/dev/null 2>&1 || true
    for _ in $(seq 1 10); do
      if [[ "$(timedatectl show -p NTPSynchronized --value 2>/dev/null || true)" == "yes" ]]; then
        offset="$(chronyc tracking 2>/dev/null | awk '/^(System time|Last offset)/ {print $4; exit}' || true)"
        if python3 - "$offset" <<'PY'
import sys
try:
    raise SystemExit(0 if abs(float(sys.argv[1])) < 1 else 1)
except ValueError:
    raise SystemExit(1)
PY
        then
          clock_shifted=0
          return 0
        fi
      fi
      sleep 1
    done
  done
  return 1
}

cleanup() {
  local status=$?
  set +e
  restore_clock
  if [[ "$backup_read_only" == "1" ]]; then mount -o remount,rw "$backup_dir" >/dev/null 2>&1; fi
  if [[ -f "$env_file" ]]; then
    compose ps -a > "$evidence/cleanup-compose-ps.txt" 2>&1
    compose logs --no-color --timestamps > "$evidence/cleanup-compose-logs.txt" 2>&1
    env COPPER_TEST_MODE=1 COPPER_ALLOW_MUTABLE_IMAGES=1 "$ROOT_DIR/copperctl" --env-file "$env_file" down >/dev/null 2>&1
  fi
  if [[ "$exhaust_mounted" == "1" ]]; then umount "$exhaust_mount" >/dev/null 2>&1; fi
  if [[ "$backup_mounted" == "1" ]]; then umount "$backup_dir" >/dev/null 2>&1; fi
  if [[ "$data_mounted" == "1" ]]; then umount "$var_dir" >/dev/null 2>&1; fi
  rm -rf --one-file-system -- "$runtime"
  trap - EXIT
  exit "$status"
}
trap cleanup EXIT

for command in docker openssl age age-keygen zstd python3 curl chronyc timedatectl mkfs.ext4 mount umount fallocate; do
  command -v "$command" >/dev/null 2>&1 || { echo "错误：缺少 $command" >&2; exit 1; }
done
for image in "$web_image" "$backend_image" "$mysql_image"; do docker image inspect "$image" >/dev/null; done
[[ "$(docker version --format '{{.Server.Version}}')" == 29.* ]]
[[ "$(docker compose version --short)" == 5.* ]]
[[ "$proxy_port" != "8191" ]]
if ss -lnt | awk '{print $4}' | grep -Eq '(^|:|\])8191$'; then
  echo "错误：M4-06 验收禁止占用宿主机 8191" >&2
  exit 1
fi
[[ ! -e "$evidence" ]] || { echo "错误：证据目录已存在" >&2; exit 1; }

install -d -o root -g root -m 0700 "$base" "$runtime" "$evidence" "$var_dir" "$backup_dir" "$restore_root" "$exhaust_mount"
fallocate -l 10G "$data_image"
mkfs.ext4 -q -F -m 0 -L COPPER_M406_DATA "$data_image"
mount -o loop,noatime "$data_image" "$var_dir"
data_mounted=1
fallocate -l 4G "$backup_image"
mkfs.ext4 -q -F -m 0 -L COPPER_M406_BACKUP "$backup_image"
mount -o loop,noatime "$backup_image" "$backup_dir"
backup_mounted=1

"$ROOT_DIR/copper-key-offline" generate --output-dir "$offline" \
  --site-id copper-m406-test --customer-reference m4-06-x86-acceptance > "$evidence/key-generation.json"
identity="$offline/copper-age-identity.txt"
recipient="$(tr -d '\r\n' < "$offline/recipient.txt")"
handover_sha="$(sha256sum "$offline/handover-record.json" | awk '{print $1}')"

install -d -o root -g root -m 0700 "$etc_dir" "$etc_dir/tls" "$etc_dir/notification-credentials"
install -o root -g root -m 0600 "$ROOT_DIR/config/copper.env.example" "$env_file"
openssl req -x509 -newkey rsa:2048 -nodes -days 90 \
  -keyout "$etc_dir/tls/test-private-key.pem" -out "$etc_dir/tls/external-fullchain.pem" \
  -subj "/CN=$domain" -addext "subjectAltName=DNS:$domain" >/dev/null 2>&1
chmod 0600 "$etc_dir/tls/test-private-key.pem"
chmod 0644 "$etc_dir/tls/external-fullchain.pem"

python3 - "$env_file" "$project" "$web_image" "$backend_image" "$mysql_image" \
  "$etc_dir" "$var_dir" "$backup_dir" "$restore_root" "$proxy_port" "$recipient" "$domain" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
values = {
    "COPPER_SITE_ID": "copper-m406-test",
    "COPPER_PROJECT_NAME": sys.argv[2],
    "COPPER_RELEASE_VERSION": "0.4.0-rc.1-m406-test",
    "COPPER_DEPLOY_MODE": "external-proxy",
    "COPPER_DOMAIN": sys.argv[12],
    "COPPER_TLS_MODE": "domain",
    "COPPER_PROXY_PORT": sys.argv[10],
    "COPPER_IMAGE_DELIVERY": "offline",
    "COPPER_WEB_IMAGE": sys.argv[3],
    "COPPER_BACKEND_IMAGE": sys.argv[4],
    "COPPER_MYSQL_IMAGE": sys.argv[5],
    "COPPER_ETC_DIR": sys.argv[6],
    "COPPER_VAR_DIR": sys.argv[7],
    "COPPER_BACKUP_DIR": sys.argv[8],
    "COPPER_RESTORE_ROOT": sys.argv[9],
    "COPPER_RESTORE_PROXY_PORT": "18119",
    "COPPER_BACKUP_AGE_RECIPIENT": sys.argv[11],
    "COPPER_BACKUP_TARGETS": "local",
    "COPPER_MONITOR_ALERT_MODE": "local",
    "COPPER_MONITOR_TLS_CERT_FILE": f"{sys.argv[6]}/tls/external-fullchain.pem",
}
result, seen = [], set()
for line in path.read_text(encoding="utf-8").splitlines():
    key = line.split("=", 1)[0] if "=" in line and not line.lstrip().startswith("#") else ""
    if key in values:
        result.append(f"{key}={values[key]}")
        seen.add(key)
    else:
        result.append(line)
for key, value in values.items():
    if key not in seen:
        result.append(f"{key}={value}")
path.write_text("\n".join(result) + "\n", encoding="utf-8")
PY
chmod 0600 "$env_file"

export COPPER_TEST_MODE=1
export COPPER_ALLOW_MUTABLE_IMAGES=1
export COPPER_ENV_FILE="$env_file"
source "$ROOT_DIR/scripts/lib.sh"
source "$ROOT_DIR/scripts/verification-sql.sh"

event() {
  python3 "$ROOT_DIR/scripts/fault-evidence.py" append --journal "$journal" --run-id "$run_id" \
    --fault "$1" --phase "$2" --result "$3" --message "$4" \
    --data-impact "${5:-none}" --manual-step "${6:-automated-acceptance}" --remaining-risk "${7:-none}" >/dev/null
}

mysql_cli() {
  compose exec -T mysql sh -c \
    'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" exec mysql -uroot -D copper_v2 --batch --raw --skip-column-names'
}

mysql_query() {
  printf '%s\n' "$1" | mysql_cli
}

check_all_tables() {
  local output="$1" table
  : > "$output"
  while IFS= read -r table; do
    [[ "$table" =~ ^[A-Za-z0-9_]+$ ]] || { echo "错误：数据库表名不安全：$table" >&2; return 1; }
    mysql_query "CHECK TABLE \`$table\`;" >> "$output"
  done < <(mysql_query "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;")
  awk -F '\t' 'NF != 4 || $3 != "status" || $4 != "OK" {bad=1} END {exit bad ? 1 : 0}' "$output"
}

write_business_reference_integrity_sql() {
  cat <<'SQL'
SELECT 'acceptance_bill.customer',COUNT(*) FROM acceptance_bill x LEFT JOIN partner p ON p.id=x.customer_id WHERE p.id IS NULL
UNION ALL SELECT 'acceptance_bill.sales_order',COUNT(*) FROM acceptance_bill x LEFT JOIN sales_order p ON p.id=x.sales_order_id WHERE p.id IS NULL
UNION ALL SELECT 'acceptance_bill_event.bill',COUNT(*) FROM acceptance_bill_event x LEFT JOIN acceptance_bill p ON p.id=x.bill_id WHERE p.id IS NULL
UNION ALL SELECT 'inbound_attachment.inbound',COUNT(*) FROM inbound_attachment x LEFT JOIN inbound_order p ON p.id=x.inbound_id WHERE p.id IS NULL
UNION ALL SELECT 'inbound_audit_log.inbound',COUNT(*) FROM inbound_audit_log x LEFT JOIN inbound_order p ON p.id=x.inbound_id WHERE p.id IS NULL
UNION ALL SELECT 'inbound_item.inbound',COUNT(*) FROM inbound_item x LEFT JOIN inbound_order p ON p.id=x.inbound_id WHERE p.id IS NULL
UNION ALL SELECT 'inbound_item.product',COUNT(*) FROM inbound_item x LEFT JOIN product p ON p.id=x.product_id WHERE p.id IS NULL
UNION ALL SELECT 'inbound_order.purchase_order',COUNT(*) FROM inbound_order x LEFT JOIN purchase_order p ON p.id=x.purchase_order_id WHERE x.purchase_order_id<>0 AND p.id IS NULL
UNION ALL SELECT 'inbound_order.supplier',COUNT(*) FROM inbound_order x LEFT JOIN partner p ON p.id=x.supplier_id WHERE p.id IS NULL
UNION ALL SELECT 'inbound_order.warehouse',COUNT(*) FROM inbound_order x LEFT JOIN warehouse p ON p.id=x.warehouse_id WHERE p.id IS NULL
UNION ALL SELECT 'inventory_batch.product',COUNT(*) FROM inventory_batch x LEFT JOIN product p ON p.id=x.product_id WHERE p.id IS NULL
UNION ALL SELECT 'inventory_batch.warehouse',COUNT(*) FROM inventory_batch x LEFT JOIN warehouse p ON p.id=x.warehouse_id WHERE p.id IS NULL
UNION ALL SELECT 'inventory_movement.batch',COUNT(*) FROM inventory_movement x LEFT JOIN inventory_batch p ON p.id=x.batch_id WHERE p.id IS NULL
UNION ALL SELECT 'inventory_movement.product',COUNT(*) FROM inventory_movement x LEFT JOIN product p ON p.id=x.product_id WHERE p.id IS NULL
UNION ALL SELECT 'inventory_movement.warehouse',COUNT(*) FROM inventory_movement x LEFT JOIN warehouse p ON p.id=x.warehouse_id WHERE p.id IS NULL
UNION ALL SELECT 'outbound_item.batch',COUNT(*) FROM outbound_item x LEFT JOIN inventory_batch p ON p.id=x.batch_id WHERE p.id IS NULL
UNION ALL SELECT 'outbound_item.outbound',COUNT(*) FROM outbound_item x LEFT JOIN outbound_order p ON p.id=x.outbound_id WHERE p.id IS NULL
UNION ALL SELECT 'outbound_item.product',COUNT(*) FROM outbound_item x LEFT JOIN product p ON p.id=x.product_id WHERE p.id IS NULL
UNION ALL SELECT 'outbound_order.customer',COUNT(*) FROM outbound_order x LEFT JOIN partner p ON p.id=x.customer_id WHERE p.id IS NULL
UNION ALL SELECT 'outbound_order.sales_order',COUNT(*) FROM outbound_order x LEFT JOIN sales_order p ON p.id=x.sales_order_id WHERE x.sales_order_id<>0 AND p.id IS NULL
UNION ALL SELECT 'outbound_order.warehouse',COUNT(*) FROM outbound_order x LEFT JOIN warehouse p ON p.id=x.warehouse_id WHERE p.id IS NULL
UNION ALL SELECT 'partner_account.partner',COUNT(*) FROM partner_account x LEFT JOIN partner p ON p.id=x.partner_id WHERE p.id IS NULL
UNION ALL SELECT 'partner_account_action.partner',COUNT(*) FROM partner_account_action x LEFT JOIN partner p ON p.id=x.partner_id WHERE x.partner_id<>0 AND p.id IS NULL
UNION ALL SELECT 'partner_account_log.partner',COUNT(*) FROM partner_account_log x LEFT JOIN partner p ON p.id=x.partner_id WHERE p.id IS NULL
UNION ALL SELECT 'payable_movement.supplier',COUNT(*) FROM payable_movement x LEFT JOIN partner p ON p.id=x.supplier_id WHERE p.id IS NULL
UNION ALL SELECT 'payment.customer',COUNT(*) FROM payment x LEFT JOIN partner p ON p.id=x.customer_id WHERE p.id IS NULL
UNION ALL SELECT 'payment.outbound',COUNT(*) FROM payment x LEFT JOIN outbound_order p ON p.id=x.outbound_id WHERE x.outbound_id<>0 AND p.id IS NULL
UNION ALL SELECT 'payment.sales_order',COUNT(*) FROM payment x LEFT JOIN sales_order p ON p.id=x.sales_order_id WHERE x.sales_order_id<>0 AND p.id IS NULL
UNION ALL SELECT 'product.category',COUNT(*) FROM product x LEFT JOIN product_category p ON p.id=x.category_id WHERE p.id IS NULL
UNION ALL SELECT 'product.series',COUNT(*) FROM product x LEFT JOIN product_series p ON p.id=x.product_series_id WHERE x.product_series_id<>0 AND p.id IS NULL
UNION ALL SELECT 'purchase_order.supplier',COUNT(*) FROM purchase_order x LEFT JOIN partner p ON p.id=x.supplier_id WHERE p.id IS NULL
UNION ALL SELECT 'receivable_adjustment.customer',COUNT(*) FROM receivable_adjustment x LEFT JOIN partner p ON p.id=x.customer_id WHERE p.id IS NULL
UNION ALL SELECT 'receivable_adjustment.sales_order',COUNT(*) FROM receivable_adjustment x LEFT JOIN sales_order p ON p.id=x.sales_order_id WHERE p.id IS NULL
UNION ALL SELECT 'receivable_movement.customer',COUNT(*) FROM receivable_movement x LEFT JOIN partner p ON p.id=x.customer_id WHERE p.id IS NULL
UNION ALL SELECT 'sales_order.customer',COUNT(*) FROM sales_order x LEFT JOIN partner p ON p.id=x.customer_id WHERE p.id IS NULL
UNION ALL SELECT 'stock_balance.product',COUNT(*) FROM stock_balance x LEFT JOIN product p ON p.id=x.product_id WHERE p.id IS NULL
UNION ALL SELECT 'stock_balance.warehouse',COUNT(*) FROM stock_balance x LEFT JOIN warehouse p ON p.id=x.warehouse_id WHERE p.id IS NULL
UNION ALL SELECT 'supplier_payment.inbound',COUNT(*) FROM supplier_payment x LEFT JOIN inbound_order p ON p.id=x.inbound_id WHERE x.inbound_id<>0 AND p.id IS NULL
UNION ALL SELECT 'supplier_payment.purchase_order',COUNT(*) FROM supplier_payment x LEFT JOIN purchase_order p ON p.id=x.purchase_order_id WHERE x.purchase_order_id<>0 AND p.id IS NULL
UNION ALL SELECT 'supplier_payment.supplier',COUNT(*) FROM supplier_payment x LEFT JOIN partner p ON p.id=x.supplier_id WHERE p.id IS NULL
ORDER BY 1;
SQL
}

require_healthy() {
  compose up -d --remove-orphans --wait --wait-timeout 240 >/dev/null
  local service id state
  for service in mysql backend web; do
    id="$(compose ps -q "$service")"
    [[ -n "$id" ]] || { echo "错误：$service 容器不存在" >&2; return 1; }
    state="$(docker inspect --format '{{.State.Status}}/{{.State.Health.Status}}' "$id")"
    [[ "$state" == "running/healthy" ]] || { echo "错误：$service 未健康：$state" >&2; return 1; }
  done
  curl --fail --silent --show-error --max-time 8 "http://127.0.0.1:$proxy_port/readyz" >/dev/null
}

api_post() {
  local endpoint="$1" payload="$2" output="$3"
  set +e
  API_CODE="$(curl --silent --show-error --max-time 50 --output "$output" --write-out '%{http_code}' \
    -b "$cookie" -H 'Content-Type: application/json' --data "$payload" \
    "http://127.0.0.1:$proxy_port/api$endpoint")"
  API_EXIT=$?
  set -e
}

assert_api_failure() {
  local code="$1" response="$2"
  [[ "$API_EXIT" -eq 0 && "$code" =~ ^(400|403|409|500|503)$ ]] || return 1
  python3 - "$response" <<'PY'
import json, sys
document = json.load(open(sys.argv[1], encoding="utf-8"))
assert document["code"] == 1 and document["data"] is None
encoded = json.dumps(document, ensure_ascii=False).lower()
assert "password" not in encoded and "cookie" not in encoded and "token" not in encoded
PY
}

capture_attachment_files() {
  local output="$1" file relative size digest
  : > "$output"
  while IFS= read -r -d '' file; do
    relative="${file#"$var_dir/files/"}"
    size="$(stat -c '%s' "$file")"
    digest="$(sha256sum "$file" | awk '{print $1}')"
    printf '%s\t%s\t%s\n' "$relative" "$size" "$digest" >> "$output"
  done < <(find "$var_dir/files" -type f -print0 | LC_ALL=C sort -z)
}

capture_invariants() {
  local output="$1" work database_version schema_version
  install -d -o root -g root -m 0700 "$output"
  work="$(mktemp -d "$output/.capture-XXXXXX")"
  database_version="$(mysql_query 'SELECT VERSION();' | tr -d '[:space:]')"
  schema_version="$(mysql_query "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;" | tr -d '[:space:]')"
  mysql_query "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;" > "$work/table-names.tsv"
  write_table_counts_sql "$work/counts.sql"
  mysql_cli < "$work/counts.sql" > "$work/record-counts.tsv"
  write_business_totals_sql "$work/totals.sql"
  mysql_cli < "$work/totals.sql" > "$work/business-totals.tsv"
  write_attachment_references_sql "$work/references.sql"
  mysql_cli < "$work/references.sql" > "$work/attachment-references.tsv"
  capture_attachment_files "$work/attachment-files.tsv"
  python3 "$ROOT_DIR/scripts/backup-metadata.py" schema "$output/schema.json" "$database_version" "$schema_version" "$work/table-names.tsv"
  python3 "$ROOT_DIR/scripts/backup-metadata.py" record-counts "$work/record-counts.tsv" "$output/record-counts.json"
  python3 "$ROOT_DIR/scripts/backup-metadata.py" business-totals "$work/business-totals.tsv" "$output/business-totals.json"
  python3 "$ROOT_DIR/scripts/backup-metadata.py" attachments "$work/attachment-files.tsv" "$work/attachment-references.tsv" "$output/attachments.json"
  mysql_query "SELECT k.CONSTRAINT_NAME,k.TABLE_NAME,k.COLUMN_NAME,k.REFERENCED_TABLE_NAME,k.REFERENCED_COLUMN_NAME,k.ORDINAL_POSITION,r.DELETE_RULE,r.UPDATE_RULE FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.TABLE_NAME=k.TABLE_NAME AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME WHERE k.CONSTRAINT_SCHEMA=DATABASE() AND k.REFERENCED_TABLE_NAME IS NOT NULL ORDER BY k.TABLE_NAME,k.CONSTRAINT_NAME,k.ORDINAL_POSITION;" > "$work/foreign-key-metadata.tsv"
  python3 "$ROOT_DIR/scripts/restore-metadata.py" assert-core-foreign-keys "$schema_version" "$work/foreign-key-metadata.tsv"
  python3 "$ROOT_DIR/scripts/restore-metadata.py" foreign-key-sql "$work/foreign-key-metadata.tsv" "$work/foreign-key-checks.sql"
  mysql_cli < "$work/foreign-key-checks.sql" > "$output/foreign-keys.tsv"
  mysql_query "SELECT installed_rank,COALESCE(version,''),description,type,script,COALESCE(CAST(checksum AS CHAR),''),success FROM flyway_schema_history ORDER BY installed_rank;" > "$output/flyway-history.tsv"
  write_business_reference_integrity_sql | mysql_cli > "$output/business-reference-integrity.tsv"
  awk -F '\t' 'NF != 2 || $2 != 0 {bad=1} END {exit bad ? 1 : 0}' "$output/business-reference-integrity.tsv"
  mysql_query "SELECT 'partner',id,name,status,type_mask FROM partner WHERE name LIKE 'M4-06%' UNION ALL SELECT 'product',id,sku,status,product_series_id FROM product WHERE id=990006 UNION ALL SELECT 'warehouse',id,name,status,0 FROM warehouse WHERE id=990006 UNION ALL SELECT 'inventory_batch',id,batch_no,status,quantity_available FROM inventory_batch WHERE id=990006 UNION ALL SELECT 'purchase_order',id,order_no,status,total_amount FROM purchase_order WHERE id=990006 UNION ALL SELECT 'inbound_order',id,order_no,status,total_amount FROM inbound_order WHERE id=990006 UNION ALL SELECT 'sales_order',id,order_no,status,total_amount FROM sales_order WHERE id=990006 UNION ALL SELECT 'outbound_order',id,order_no,status,total_amount FROM outbound_order WHERE id=990006 ORDER BY 1,2;" > "$output/domain-details.tsv"
  mysql_query "SELECT COUNT(*),COUNT(DISTINCT event_id),SUM(event_id NOT REGEXP '^[0-9a-f]{32}$'),SUM(trace_id NOT REGEXP '^[0-9a-f]{32}$'),SUM(occurred_at IS NULL) FROM security_audit_log;" > "$output/audit-integrity.tsv"
  mysql_query "SELECT event_id,action_code,result_code,failure_code,target_type,target_id,request_method,request_path,occurred_at FROM security_audit_log ORDER BY id;" > "$output/audit-events.tsv"
  rm -rf --one-file-system -- "$work"
  chmod 0600 "$output"/*
}

compare_invariants() {
  local expected="$1" actual="$2" result="$3"
  python3 "$ROOT_DIR/scripts/restore-metadata.py" compare-verification \
    "$expected" "$actual" "$actual/foreign-keys.tsv" "$result"
  cmp "$expected/flyway-history.tsv" "$actual/flyway-history.tsv"
  cmp "$expected/business-reference-integrity.tsv" "$actual/business-reference-integrity.tsv"
  cmp "$expected/domain-details.tsv" "$actual/domain-details.tsv"
  cmp "$expected/audit-integrity.tsv" "$actual/audit-integrity.tsv"
  cmp "$expected/audit-events.tsv" "$actual/audit-events.tsv"
}

fill_to_available() {
  local target="$1" current size delta
  current="$(df -B1 --output=avail "$var_dir" | tail -1 | tr -d ' ')"
  size=0
  [[ -f "$filler" ]] && size="$(stat -c '%s' "$filler")"
  delta=$((current - target))
  [[ "$delta" -gt 0 ]] || { echo "错误：容量目标无有效增量" >&2; return 1; }
  fallocate -l $((size + delta)) "$filler"
  sync
}

"$ROOT_DIR/copperctl" --env-file "$env_file" install > "$evidence/install.txt"
require_healthy
{
  stat -c 'directory=%u:%g %a' "$var_dir/time-safety"
  stat -c 'snapshot=%u:%g %a' "$var_dir/time-safety/time-safety.json"
  docker inspect --format '{{range .Mounts}}{{if eq .Destination "/run/copper/time-safety"}}mount={{.Source}}|rw={{.RW}}{{end}}{{end}}' "$(compose ps -q backend)"
} > "$evidence/time-safety-integrity.txt"
grep -Fxq 'directory=0:10001 750' "$evidence/time-safety-integrity.txt"
grep -Fxq 'snapshot=0:0 644' "$evidence/time-safety-integrity.txt"
grep -Fxq "mount=$var_dir/time-safety|rw=false" "$evidence/time-safety-integrity.txt"
if compose exec -T backend sh -c ': > /run/copper/time-safety/application-write-must-fail' \
  > "$evidence/time-safety-application-write.out" 2> "$evidence/time-safety-application-write.err"; then
  echo "错误：Backend 意外获得可信时间目录写权限" >&2
  exit 1
fi
[[ ! -e "$var_dir/time-safety/application-write-must-fail" ]]
credential_file="$etc_dir/credentials/initial-admin.txt"
username="$(awk -F= '$1=="username" {print $2}' "$credential_file")"
initial_password="$(awk -F= '$1=="password" {print $2}' "$credential_file")"
new_password="M406!$(openssl rand -hex 18)aZ9"
curl --fail --silent --show-error -c "$cookie" -H 'Content-Type: application/json' \
  --data "{\"username\":\"$username\",\"password\":\"$initial_password\"}" \
  "http://127.0.0.1:$proxy_port/api/auth/login" >/dev/null
curl --fail --silent --show-error -b "$cookie" -H 'Content-Type: application/json' \
  --data "{\"currentPassword\":\"$initial_password\",\"newPassword\":\"$new_password\"}" \
  "http://127.0.0.1:$proxy_port/api/auth/change-password" >/dev/null
rm -f -- "$cookie"
curl --fail --silent --show-error -c "$cookie" -H 'Content-Type: application/json' \
  --data "{\"username\":\"$username\",\"password\":\"$new_password\"}" \
  "http://127.0.0.1:$proxy_port/api/auth/login" >/dev/null
unset initial_password new_password
[[ ! -e "$credential_file" ]]

fixture_payload="{\"typeMask\":3,\"entityType\":\"COMPANY\",\"name\":\"M4-06 Fixture $run_id\",\"status\":\"ACTIVE\",\"customerStatus\":\"ACTIVE\",\"supplierStatus\":\"ACTIVE\"}"
api_post /partners "$fixture_payload" "$runtime/fixture-partner.json"
[[ "$API_EXIT" -eq 0 && "$API_CODE" == "200" ]]
fixture_partner="$(python3 - "$runtime/fixture-partner.json" <<'PY'
import json, sys
document=json.load(open(sys.argv[1], encoding="utf-8")); assert document["code"]==0
print(document["data"]["id"])
PY
)"

attachment_relative="inbounds/990006/m406-fixture.txt"
install -d -o 10001 -g 10001 -m 0750 "$(dirname "$var_dir/files/$attachment_relative")"
printf 'Copper M4-06 durable attachment\n' > "$runtime/attachment"
install -o 10001 -g 10001 -m 0640 "$runtime/attachment" "$var_dir/files/$attachment_relative"
attachment_size="$(stat -c '%s' "$var_dir/files/$attachment_relative")"
attachment_sha="$(sha256sum "$var_dir/files/$attachment_relative" | awk '{print $1}')"
mysql_cli <<SQL
INSERT INTO product (id,category_id,product_series_id,sku,name,unit,default_price,status) VALUES (990006,101,2001,'M406-SKU','M4-06 紫铜大盘管','kg',88.6600,'ENABLED');
INSERT INTO warehouse (id,name,location,status) VALUES (990006,'M4-06隔离仓','fault-test','ENABLED');
INSERT INTO inventory_batch (id,product_id,warehouse_id,batch_no,source_type,source_id,base_unit,quantity_in,quantity_available,unit_cost,amount_cost,status,received_at) VALUES (990006,990006,990006,'M406-BATCH','INBOUND',990006,'kg',12.500,12.500,66.6600,833.25,'AVAILABLE',CURRENT_DATE);
INSERT INTO stock_balance (product_id,warehouse_id,quantity_available,amount_cost) VALUES (990006,990006,12.500,833.25);
INSERT INTO inventory_movement (movement_no,movement_type,product_id,warehouse_id,batch_id,quantity,unit_price,amount,ref_type,ref_id,memo) VALUES ('M406-MOVE','INBOUND',990006,990006,990006,12.500,66.6600,833.25,'INBOUND',990006,'M4-06 fixture');
INSERT INTO purchase_order (id,order_no,supplier_id,business_date,total_quantity,total_amount,paid_amount,payment_status,status,memo) VALUES (990006,'M406-PO',$fixture_partner,CURRENT_DATE,12.500,833.25,111.11,'PARTIAL','ORDERED','M4-06 fixture');
INSERT INTO inbound_order (id,order_no,purchase_order_id,supplier_id,warehouse_id,business_date,status,total_quantity,total_amount,payable_amount,paid_amount,payment_status,memo) VALUES (990006,'M406-IN',990006,$fixture_partner,990006,CURRENT_DATE,'STOCKED',12.500,833.25,833.25,111.11,'PARTIAL','M4-06 fixture');
INSERT INTO sales_order (id,order_no,title,customer_id,business_date,total_amount,receivable_amount,received_amount,payment_status,status,memo) VALUES (990006,'M406-SO','M4-06 fixture',$fixture_partner,CURRENT_DATE,999.99,999.99,222.22,'PARTIAL','ORDERED','M4-06 fixture');
INSERT INTO outbound_order (id,order_no,sales_order_id,customer_id,warehouse_id,business_date,status,total_quantity,total_amount,receivable_amount,paid_amount,payment_status,memo) VALUES (990006,'M406-OUT',990006,$fixture_partner,990006,CURRENT_DATE,'SHIPPED',2.500,199.99,199.99,22.22,'PARTIAL','M4-06 fixture');
INSERT INTO partner_account (partner_id,account_type,balance,frozen_amount,opposite_balance) VALUES ($fixture_partner,'TRADE',123.45,6.78,9.10);
INSERT INTO payment (payment_no,customer_id,sales_order_id,outbound_id,amount,payment_method,account_type,received_date,verification_status,memo) VALUES ('M406-PAY',$fixture_partner,990006,990006,88.66,'BANK','TRADE',CURRENT_DATE,'VERIFIED','M4-06 fixture');
INSERT INTO supplier_payment (payment_no,supplier_id,purchase_order_id,inbound_id,amount,payment_method,account_type,paid_date,verification_status,memo) VALUES ('M406-SPAY',$fixture_partner,990006,990006,44.33,'BANK','TRADE',CURRENT_DATE,'VERIFIED','M4-06 fixture');
INSERT INTO receivable_movement (movement_no,customer_id,ref_type,ref_id,direction,amount,balance_after,memo) VALUES ('M406-AR',$fixture_partner,'SALES_ORDER',990006,'DEBIT',999.99,999.99,'M4-06 fixture');
INSERT INTO payable_movement (movement_no,supplier_id,ref_type,ref_id,direction,amount,balance_after,memo) VALUES ('M406-AP',$fixture_partner,'PURCHASE_ORDER',990006,'INCREASE',833.25,833.25,'M4-06 fixture');
INSERT INTO receivable_adjustment (adjustment_no,customer_id,sales_order_id,adjustment_type,amount,occurred_date,status,reason) VALUES ('M406-ADJUST',$fixture_partner,990006,'SMALL_WAIVER',1.23,CURRENT_DATE,'CONFIRMED','M4-06 fixture');
INSERT INTO acceptance_bill (id,bill_no,instrument_no,bill_type,customer_id,sales_order_id,face_amount,issue_date,due_date,received_date,status,memo) VALUES (990006,'M406-BILL','M406-INSTRUMENT','BANK_ACCEPTANCE',$fixture_partner,990006,66.66,CURRENT_DATE,DATE_ADD(CURRENT_DATE,INTERVAL 30 DAY),CURRENT_DATE,'HELD','M4-06 fixture');
INSERT INTO acceptance_bill_event (event_no,bill_id,event_type,occurred_date,cash_amount,fee_amount,memo) VALUES ('M406-BILL-EVENT',990006,'RECEIVE',CURRENT_DATE,0,0,'M4-06 fixture');
INSERT INTO inbound_attachment (inbound_id,file_name,content_type,file_size,sha256,storage_path,file_content,uploaded_by) VALUES (990006,'m406-fixture.txt','text/plain',$attachment_size,'$attachment_sha','$attachment_relative',NULL,0);
INSERT INTO inbound_audit_log (inbound_id,action,reason,operator_id,operator_name) VALUES (990006,'AUDIT','M4-06 fixture',0,'M4 test');
SQL

"$ROOT_DIR/copperctl" backup key register --handover-sha256 "$handover_sha" \
  --customer-confirmed-by "M4 test customer" --witness-confirmed-by "M4 test witness" > "$evidence/key-register.json"
"$ROOT_DIR/copperctl" backup create --purpose manual > "$evidence/initial-backup.txt"
initial_archive="$(find "$backup_dir" -maxdepth 1 -type f -name '*.copperbak' -print -quit)"
[[ -s "$initial_archive" ]]
"$ROOT_DIR/copperctl" backup verify "$initial_archive" > "$evidence/initial-backup-verify.json"
receipt="$offline/restore-receipt.json"
"$ROOT_DIR/copper-key-offline" verify-backup --identity "$identity" --archive "$initial_archive" --receipt "$receipt" > "$evidence/initial-offline-verify.json"
"$ROOT_DIR/copperctl" backup key record-restore-test "$receipt" > "$evidence/restore-proof-register.json"
require_healthy

# Database unavailable: API must return explicit failure and the retry must create exactly one row.
event database INJECTED IN_PROGRESS "mysql container stopped without graceful application shutdown"
mysql_id="$(compose ps -q mysql)"
docker stop -t 1 "$mysql_id" >/dev/null
set +e
"$ROOT_DIR/copperctl" monitor check --json > "$evidence/database-detected.json"
db_monitor_exit=$?
set -e
[[ "$db_monitor_exit" -eq 1 ]]
event database DETECTED PASSED "monitor blocked readiness and writable layers"
db_name="M4-06 DB Outcome $run_id"
db_payload="{\"typeMask\":1,\"entityType\":\"COMPANY\",\"name\":\"$db_name\",\"status\":\"ACTIVE\",\"customerStatus\":\"ACTIVE\"}"
api_post /partners "$db_payload" "$evidence/database-write-response.json"
assert_api_failure "$API_CODE" "$evidence/database-write-response.json"
event database WRITE_ATTEMPT PASSED "write returned explicit non-2xx failure"
event database RECOVERY_STARTED IN_PROGRESS "mysql restarted through compose health gates"
require_healthy
[[ "$(mysql_query "SELECT COUNT(*) FROM partner WHERE name='$db_name';" | tr -d '[:space:]')" == "0" ]]
api_post /partners "$db_payload" "$evidence/database-retry-response.json"
[[ "$API_EXIT" -eq 0 && "$API_CODE" == "200" ]]
[[ "$(mysql_query "SELECT COUNT(*) FROM partner WHERE name='$db_name';" | tr -d '[:space:]')" == "1" ]]
event database RECOVERED PASSED "mysql and all application layers returned healthy"
event database VERIFIED PASSED "failed write absent and single retry committed" "failed attempt wrote no business row; retry wrote one row" "restart mysql and wait for compose health" "connection-pool timeout still determines failure latency"

# Disk: real warning/critical/blocked levels plus a disposable filesystem reaching ENOSPC.
event disk INJECTED IN_PROGRESS "real isolated data filesystem filled through all protection levels"
fill_to_available $((4 * 1024**3))
"$ROOT_DIR/copperctl" monitor check --json > "$evidence/disk-warning.json"
python3 - "$evidence/disk-warning.json" <<'PY'
import json,sys
s=json.load(open(sys.argv[1],encoding="utf-8")); assert any(x["id"]=="storage.dataFilesystem" and x["level"]=="WARNING" for x in s["signals"])
PY
fill_to_available $((1536 * 1024**2))
set +e; "$ROOT_DIR/copperctl" monitor check --json > "$evidence/disk-critical.json"; critical_exit=$?; set -e
[[ "$critical_exit" -eq 1 ]]
python3 - "$evidence/disk-critical.json" <<'PY'
import json,sys
s=json.load(open(sys.argv[1],encoding="utf-8")); assert any(x["id"]=="storage.dataFilesystem" and x["level"]=="CRITICAL" for x in s["signals"])
PY
fill_to_available $((768 * 1024**2))
set +e; "$ROOT_DIR/copperctl" monitor check --json > "$evidence/disk-blocked.json"; blocked_exit=$?; set -e
[[ "$blocked_exit" -eq 1 ]]
event disk DETECTED PASSED "real data filesystem reached WARNING CRITICAL and BLOCKED"
disk_name="M4-06 Disk Outcome $run_id"
disk_payload="{\"typeMask\":1,\"entityType\":\"COMPANY\",\"name\":\"$disk_name\",\"status\":\"ACTIVE\",\"customerStatus\":\"ACTIVE\"}"
api_post /partners "$disk_payload" "$evidence/disk-write-response.json"
assert_api_failure "$API_CODE" "$evidence/disk-write-response.json"
[[ "$(mysql_query "SELECT COUNT(*) FROM partner WHERE name='$disk_name';" | tr -d '[:space:]')" == "0" ]]
event disk WRITE_ATTEMPT PASSED "business write rejected before ENOSPC"
fallocate -l 64M "$exhaust_image"
mkfs.ext4 -q -F -m 0 -L COPPER_M406_FULL "$exhaust_image"
mount -o loop,noatime "$exhaust_image" "$exhaust_mount"
exhaust_mounted=1
set +e
dd if=/dev/zero of="$exhaust_mount/full.bin" bs=1M status=none 2> "$evidence/disk-enospc-error.txt"
enospc_exit=$?
set -e
[[ "$enospc_exit" -ne 0 ]]
df -B1 "$exhaust_mount" > "$evidence/disk-enospc-filesystem.txt"
grep -qi 'No space left on device' "$evidence/disk-enospc-error.txt"
umount "$exhaust_mount"
exhaust_mounted=0
event disk RECOVERY_STARTED IN_PROGRESS "capacity filler removed and data filesystem resynchronized"
rm -f -- "$filler"
sync
"$ROOT_DIR/copperctl" monitor check --json > "$evidence/disk-recovered.json"
api_post /partners "$disk_payload" "$evidence/disk-retry-response.json"
[[ "$API_EXIT" -eq 0 && "$API_CODE" == "200" ]]
[[ "$(mysql_query "SELECT COUNT(*) FROM partner WHERE name='$disk_name';" | tr -d '[:space:]')" == "1" ]]
event disk RECOVERED PASSED "data filesystem returned normal and business write gate reopened"
event disk VERIFIED PASSED "blocked write absent; one post-recovery retry committed; ENOSPC observed separately" "no database or attachment damage" "remove only the controlled filler or expand storage, then rerun monitor" "production cleanup must never delete business, audit, attachment or only-good-backup data"

# Backup: make the actual target read-only, preserve the old package, then recover and verify a new package.
event backup INJECTED IN_PROGRESS "backup filesystem remounted read-only"
before_backup_count="$(find "$backup_dir" -maxdepth 1 -type f -name '*.copperbak' | wc -l | tr -d ' ')"
before_last_sha="$(sha256sum "$backup_dir/last-success.json" | awk '{print $1}')"
before_initial_sha="$(sha256sum "$initial_archive" | awk '{print $1}')"
mount -o remount,ro "$backup_dir"
backup_read_only=1
set +e
"$ROOT_DIR/copperctl" backup create --purpose manual > "$evidence/backup-failed.out" 2> "$evidence/backup-failed.err"
backup_exit=$?
set -e
[[ "$backup_exit" -ne 0 ]]
event backup DETECTED PASSED "backup command failed on real read-only target"
require_healthy
[[ "$(find "$backup_dir" -maxdepth 1 -type f -name '*.copperbak' | wc -l | tr -d ' ')" == "$before_backup_count" ]]
[[ "$(sha256sum "$backup_dir/last-success.json" | awk '{print $1}')" == "$before_last_sha" ]]
[[ "$(sha256sum "$initial_archive" | awk '{print $1}')" == "$before_initial_sha" ]]
event backup WRITE_ATTEMPT PASSED "failed backup published no package and did not rewrite last-success"
event backup RECOVERY_STARTED IN_PROGRESS "backup filesystem remounted read-write"
mount -o remount,rw "$backup_dir"
backup_read_only=0
"$ROOT_DIR/copperctl" backup create --purpose manual > "$evidence/backup-retry.out"
archive_value="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1],encoding="utf-8"))["archive"])' "$backup_dir/last-success.json")"
if [[ "$archive_value" == /* ]]; then
  new_archive="$archive_value"
else
  new_archive="$backup_dir/$archive_value"
fi
"$ROOT_DIR/copperctl" backup verify "$new_archive" > "$evidence/backup-retry-verify.json"
if [[ -e "$initial_archive" ]]; then
  [[ "$(sha256sum "$initial_archive" | awk '{print $1}')" == "$before_initial_sha" ]]
else
  grep -Fq "已按保留策略删除旧备份：$(basename "$initial_archive")" "$evidence/backup-retry.out"
fi
event backup RECOVERED PASSED "new verified backup completed after target repair"
event backup VERIFIED PASSED "failed attempt preserved the old package; successful retry published and verified a new package before retention" "no business change; recovery point advanced only after success" "repair target, verify old package, retry once" "same-period old packages may be removed only after a newer package is committed and verified; local-only backup still cannot cover whole-host loss"

# Certificate: install a genuinely expired leaf certificate; external-proxy mode must not expose public HTTP.
event certificate INJECTED IN_PROGRESS "monitor certificate replaced with a real expired certificate"
openssl req -newkey rsa:2048 -nodes -keyout "$runtime/expired.key" -out "$runtime/expired.csr" -subj "/CN=$domain" >/dev/null 2>&1
openssl x509 -req -in "$runtime/expired.csr" -signkey "$runtime/expired.key" -days -1 -out "$etc_dir/tls/external-fullchain.pem" >/dev/null 2>&1
set +e; "$ROOT_DIR/copperctl" monitor check --json > "$evidence/certificate-expired.json"; cert_exit=$?; set -e
[[ "$cert_exit" -eq 1 ]]
python3 - "$evidence/certificate-expired.json" <<'PY'
import json,sys
s=json.load(open(sys.argv[1],encoding="utf-8")); x=next(i for i in s["signals"] if i["id"]=="tls.certificate"); assert x["level"]=="BLOCKED"
PY
event certificate DETECTED PASSED "expired certificate produced BLOCKED signal"
if ss -lnt | awk '{print $4}' | grep -Eq '(^|:|\])(80|443)$'; then
  echo "错误：外部代理模式不应自动暴露公网HTTP/HTTPS" >&2
  exit 1
fi
event certificate WRITE_ATTEMPT PASSED "no insecure public HTTP fallback appeared"
event certificate RECOVERY_STARTED IN_PROGRESS "valid certificate reissued and installed"
openssl req -x509 -newkey rsa:2048 -nodes -days 90 -keyout "$etc_dir/tls/test-private-key.pem" \
  -out "$etc_dir/tls/external-fullchain.pem" -subj "/CN=$domain" -addext "subjectAltName=DNS:$domain" >/dev/null 2>&1
chmod 0600 "$etc_dir/tls/test-private-key.pem"; chmod 0644 "$etc_dir/tls/external-fullchain.pem"
"$ROOT_DIR/copperctl" monitor check --json > "$evidence/certificate-recovered.json"
event certificate RECOVERED PASSED "valid chain and hostname returned normal"
event certificate VERIFIED PASSED "expired certificate never downgraded to public plaintext" "public ingress remained absent; loopback acceptance endpoint only" "renew certificate and rerun local chain/hostname monitor" "external proxy availability still depends on customer DNS and proxy"

# Time: take chrony offline, shift the real host clock, publish BLOCKED safety state, and prove finance guard.
event time INJECTED IN_PROGRESS "chrony taken offline and host realtime shifted forward 120 seconds"
chronyc offline >/dev/null
date -u --set="@$(($(date +%s) + 120))" > "$evidence/time-shift-command.txt"
clock_shifted=1
sleep 2
chronyc tracking > "$evidence/time-chrony-shifted.txt"
"$ROOT_DIR/scripts/time-safety.sh" --json > "$evidence/time-safety-blocked.json"
python3 - "$evidence/time-safety-blocked.json" <<'PY'
import json,sys
s=json.load(open(sys.argv[1],encoding="utf-8")); assert s["level"]=="BLOCKED" and not s["highRiskWritesAllowed"] and abs(s["clockDiscontinuityMs"]) >= 60000
PY
set +e; "$ROOT_DIR/copperctl" monitor check --json > "$evidence/time-blocked.json"; time_exit=$?; set -e
[[ "$time_exit" -eq 1 ]]
python3 - "$evidence/time-blocked.json" <<'PY'
import json,sys
s=json.load(open(sys.argv[1],encoding="utf-8")); x=next(i for i in s["signals"] if i["id"]=="time.synchronization"); assert x["level"]=="BLOCKED" and abs(x["details"]["clockDiscontinuityMs"]) >= 60000
PY
event time DETECTED PASSED "real clock offset exceeded 60 seconds and safety snapshot blocked"
finance_memo="M406-TIME-$run_id"
finance_payload="{\"partnerId\":$fixture_partner,\"accountType\":\"CUSTOMER_ADVANCE\",\"actionType\":\"INCREASE\",\"amount\":1.23,\"memo\":\"$finance_memo\"}"
api_post /partner-account-actions "$finance_payload" "$evidence/time-finance-response.json"
assert_api_failure "$API_CODE" "$evidence/time-finance-response.json"
[[ "$(mysql_query "SELECT COUNT(*) FROM partner_account_action WHERE memo='$finance_memo';" | tr -d '[:space:]')" == "0" ]]
event time WRITE_ATTEMPT PASSED "high-risk finance write returned explicit failure and wrote no ledger row"
event time RECOVERY_STARTED IN_PROGRESS "chrony returned online and makestep restored trusted time"
restore_clock
"$ROOT_DIR/scripts/time-safety.sh" --json > "$evidence/time-safety-recovery-jump.json"
"$ROOT_DIR/scripts/time-safety.sh" --json > "$evidence/time-safety-recovered.json"
python3 - "$evidence/time-safety-recovered.json" <<'PY'
import json,sys
s=json.load(open(sys.argv[1],encoding="utf-8")); assert s["level"]=="NORMAL" and s["highRiskWritesAllowed"]
PY
"$ROOT_DIR/copperctl" monitor check --json > "$evidence/time-recovered.json"
api_post /partner-account-actions "$finance_payload" "$evidence/time-finance-retry-response.json"
[[ "$API_EXIT" -eq 0 && "$API_CODE" == "200" ]]
[[ "$(mysql_query "SELECT COUNT(*) FROM partner_account_action WHERE memo='$finance_memo';" | tr -d '[:space:]')" == "1" ]]
event time RECOVERED PASSED "NTP and offset returned normal; high-risk gate reopened"
event time VERIFIED PASSED "blocked finance attempt absent and one post-recovery action plus ledger entry committed" "no finance mutation during untrusted time" "restore NTP, refresh safety snapshot, verify audit, then retry explicitly" "wall-clock timestamps inside the fault window remain untrusted; hash-chain monotonic order is authoritative"

# Establish the exact expected state immediately before sudden termination.
expected="$evidence/invariants-before-power"
capture_invariants "$expected"
[[ "$(mysql_query "SELECT COUNT(*)=COUNT(DISTINCT event_id) AND SUM(occurred_at IS NULL)=0 FROM security_audit_log;" | tr -d '[:space:]')" == "1" ]]

# Power equivalent: kill all three site processes while a financial transaction is uncommitted.
event power INJECTED IN_PROGRESS "all site containers receive SIGKILL during an open financial transaction"
mysql_cli > "$runtime/power-transaction.out" 2> "$runtime/power-transaction.err" <<SQL &
START TRANSACTION;
INSERT INTO partner_account_action (partner_id,category,amount,action_type,occurred_at,memo) VALUES ($fixture_partner,'CUSTOMER_ADVANCE',77.77,'INCREASE',CURRENT_TIMESTAMP,'M406-POWER-UNCOMMITTED');
SELECT /* M406_POWER_SLEEP */ SLEEP(120);
COMMIT;
SQL
transaction_pid=$!
transaction_seen=0
for _ in $(seq 1 60); do
  if [[ "$(mysql_query "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID <> CONNECTION_ID() AND INFO LIKE '%M406_POWER_SLEEP%';" 2>/dev/null | tr -d '[:space:]' || true)" == "1" ]]; then
    transaction_seen=1
    break
  fi
  sleep 1
done
[[ "$transaction_seen" == "1" ]]
container_ids=("$(compose ps -q mysql)" "$(compose ps -q backend)" "$(compose ps -q web)")
docker kill --signal KILL "${container_ids[@]}" > "$evidence/power-killed-containers.txt"
set +e; wait "$transaction_pid"; transaction_exit=$?; set -e
[[ "$transaction_exit" -ne 0 ]]
event power DETECTED PASSED "in-flight client lost connection and all three containers stopped abruptly"
event power WRITE_ATTEMPT PASSED "transaction had no COMMIT acknowledgement"
event power RECOVERY_STARTED IN_PROGRESS "compose startup invoked MySQL crash recovery and health gates"
require_healthy
event power RECOVERED PASSED "MySQL backend and web returned healthy after SIGKILL"
[[ "$(mysql_query "SELECT COUNT(*) FROM partner_account_action WHERE memo='M406-POWER-UNCOMMITTED';" | tr -d '[:space:]')" == "0" ]]
mysql_container="$(compose ps -q mysql)"
docker logs "$mysql_container" 2>&1 | tail -200 > "$evidence/power-mysql-recovery.log"
check_all_tables "$evidence/power-check-table.tsv"
actual="$evidence/invariants-after-power"
capture_invariants "$actual"
compare_invariants "$expected" "$actual" "$evidence/power-invariant-comparison.json"
[[ "$(sha256sum "$var_dir/files/$attachment_relative" | awk '{print $1}')" == "$attachment_sha" ]]
event power VERIFIED PASSED "uncommitted finance row rolled back and all business financial attachment audit invariants matched" "no committed row or attachment lost; no uncommitted row appeared" "wait for MySQL crash recovery and compare full invariants before reopening writes" "container SIGKILL is the accepted host-power equivalent; M4-07 still performs final blank-host exit acceptance"

python3 "$ROOT_DIR/scripts/fault-evidence.py" verify --journal "$journal" --output "$evidence/fault-summary.json" > "$evidence/fault-summary-verify.json"
python3 - "$evidence/fault-summary.json" <<'PY'
import json,sys
s=json.load(open(sys.argv[1],encoding="utf-8")); assert s["result"]=="PASSED" and len(s["faults"])==6
PY

{
  printf 'run_id=%s\n' "$run_id"
  printf 'completed_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'host_arch=%s\n' "$(uname -m)"
  printf 'os=%s/%s\n' "$ID" "$VERSION_ID"
  printf 'docker=%s\n' "$(docker version --format '{{.Server.Version}}')"
  printf 'compose=%s\n' "$(docker compose version --short)"
  printf 'schema=%s\n' "$(mysql_query "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;" | tr -d '[:space:]')"
  printf 'table_count=%s\n' "$(mysql_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE';" | tr -d '[:space:]')"
  printf 'host_8191=unused\n'
  printf 'faults=database,disk,backup,certificate,time,power\n'
  printf 'result=PASSED\n'
} > "$evidence/summary.env"

compose ps -a > "$evidence/compose-ps-final.txt"
find "$evidence" -type f ! -name SHA256SUMS -printf '%P\0' | LC_ALL=C sort -z \
  | xargs -0 -r -I{} sha256sum "$evidence/{}" | sed "s#  $evidence/#  #" > "$evidence/SHA256SUMS"
find "$evidence" -type d -exec chmod 0700 {} +
find "$evidence" -type f -exec chmod 0600 {} +
sha256sum "$evidence/SHA256SUMS" > "$evidence-manifest.sha256"
chmod 0600 "$evidence-manifest.sha256"

echo "m4_06_x86_acceptance=passed"
echo "evidence_dir=$evidence"
echo "evidence_manifest_sha256=$(awk '{print $1}' "$evidence-manifest.sha256")"
