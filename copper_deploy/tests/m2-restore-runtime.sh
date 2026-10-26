#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export COPPER_ENV_FILE="${COPPER_ENV_FILE:?COPPER_ENV_FILE is required}"
export COPPER_TEST_MODE="${COPPER_TEST_MODE:-1}"
export COPPER_ALLOW_MUTABLE_IMAGES="${COPPER_ALLOW_MUTABLE_IMAGES:-1}"
export COPPER_RESTORE_TEST_CONFIRM=1
identity="${COPPER_BACKUP_TEST_IDENTITY:?COPPER_BACKUP_TEST_IDENTITY is required}"
other_web_image="${COPPER_RESTORE_TEST_OTHER_WEB_IMAGE:?COPPER_RESTORE_TEST_OTHER_WEB_IMAGE is required}"
v8_v24_table_contract="$ROOT_DIR/config/erp-v8-v24-tables.txt"
erp18_opening_migration_table_contract="$ROOT_DIR/config/erp18-opening-migration-tables.txt"

source "$ROOT_DIR/scripts/lib.sh"
require_root
[[ "$COPPER_TEST_MODE" == "1" ]] || die "运行时恢复测试只允许 COPPER_TEST_MODE=1"
validate_tools
validate_environment

etc_dir="$(required_env_value COPPER_ETC_DIR)"
var_dir="$(required_env_value COPPER_VAR_DIR)"
backup_dir="$(backup_dir_value)"
restore_root="$(restore_root_value)"
source_port="$(required_env_value COPPER_PROXY_PORT)"
restore_port="$(required_env_value COPPER_RESTORE_PROXY_PORT)"
[[ "$etc_dir" == /tmp/copper-m2-03-* && "$var_dir" == /tmp/copper-m2-03-* && "$restore_root" == /tmp/copper-m2-03-* ]] \
  || die "恢复验收必须使用 /tmp/copper-m2-03-* 的完全隔离目录"
[[ "$source_port" != "8191" && "$restore_port" != "8191" ]] || die "恢复验收禁止占用正式 8191"
[[ -s "$identity" ]] || die "测试 age 私钥不存在"

temporary="$(mktemp -d /tmp/copper-m2-03-acceptance-XXXXXX)"
trap 'rm -rf --one-file-system -- "$temporary"' EXIT

handover_record="$temporary/key-handover-record.txt"
printf 'Copper M2-03 isolated runtime key handover\n' > "$handover_record"
chmod 0600 "$handover_record"
handover_sha="$(sha256sum "$handover_record" | awk '{print $1}')"
"$ROOT_DIR/copperctl" backup key register \
  --handover-sha256 "$handover_sha" \
  --customer-confirmed-by "M2 test customer" \
  --witness-confirmed-by "M2 test witness" >/dev/null

mysql_cli() {
  compose_cmd exec -T mysql sh -c \
    'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" exec mysql -uroot -D copper_v2 --batch --raw --skip-column-names'
}

mysql_query() {
  printf '%s\n' "$1" | mysql_cli
}

assert_v8_v24_tables_present() {
  local stage="$1" table present count=0
  [[ -s "$v8_v24_table_contract" ]] || die "V8-V24 备份恢复表合同不存在"
  while IFS= read -r table; do
    [[ -n "$table" && "$table" != \#* ]] || continue
    [[ "$table" =~ ^[a-z0-9_]+$ ]] || die "V8-V24 表合同包含非法表名：$table"
    present="$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND table_name='$table';" | tr -d '[:space:]')"
    [[ "$present" == "1" ]] || die "$stage 缺少 V8-V24 业务表：$table"
    count=$((count + 1))
  done < "$v8_v24_table_contract"
  [[ "$count" == "56" ]] || die "V8-V24 备份恢复表合同数量错误：$count"
}

assert_erp18_opening_migration_tables_present() {
  local stage="$1" table present count=0
  [[ -s "$erp18_opening_migration_table_contract" ]] || die "ERP-18 期初迁移备份恢复表合同不存在"
  while IFS= read -r table; do
    [[ -n "$table" && "$table" != \#* ]] || continue
    [[ "$table" =~ ^[a-z0-9_]+$ ]] || die "ERP-18 期初迁移表合同包含非法表名：$table"
    present="$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND table_name='$table';" | tr -d '[:space:]')"
    [[ "$present" == "1" ]] || die "$stage 缺少 ERP-18 期初迁移表：$table"
    count=$((count + 1))
  done < "$erp18_opening_migration_table_contract"
  [[ "$count" == "6" ]] || die "ERP-18 期初迁移备份恢复表合同数量错误：$count"
}

assert_erp18_opening_migration_anchor_rows() {
  local batch_status row_count event_count check_count issue_count fund_count
  batch_status="$(mysql_query "SELECT status FROM data_migration_batch WHERE id=990180;" | tr -d '[:space:]')"
  row_count="$(mysql_query "SELECT COUNT(*) FROM data_migration_row WHERE batch_id=990180;" | tr -d '[:space:]')"
  event_count="$(mysql_query "SELECT COUNT(*) FROM data_migration_event WHERE batch_id=990180;" | tr -d '[:space:]')"
  check_count="$(mysql_query "SELECT COUNT(*) FROM data_migration_check_run WHERE batch_id=990180;" | tr -d '[:space:]')"
  issue_count="$(mysql_query "SELECT COUNT(*) FROM data_migration_check_issue WHERE check_run_id=990180;" | tr -d '[:space:]')"
  fund_count="$(mysql_query "SELECT COUNT(*) FROM data_migration_fund_opening WHERE batch_id=990180 AND status='ACTIVE';" | tr -d '[:space:]')"
  [[ "$batch_status" == "CUTOVER" ]] || die "ERP-18 恢复后迁移批次切换状态丢失：$batch_status"
  [[ "$row_count/$event_count/$check_count/$issue_count/$fund_count" == "2/1/2/1/1" ]] \
    || die "ERP-18 恢复后逐行事实或审计链丢失：$row_count/$event_count/$check_count/$issue_count/$fund_count"
}

assert_v8_v24_anchor_rows() {
  local table count
  for table in inventory_transfer_order inventory_stocktake_order purchase_return_order \
    inventory_cost_adjustment business_posting_batch business_posting_entry accounting_voucher \
    accounting_ledger_entry tax_invoice bank_reconciliation_session bank_reconciliation_match \
    accounting_period_close_run crm_opportunity crm_quote; do
    count="$(mysql_query "SELECT COUNT(*) FROM \`$table\` WHERE id=990100;" | tr -d '[:space:]')"
    [[ "$count" == "1" ]] || die "恢复后 V8-V24 锚点记录丢失：$table#990100"
  done
}

require_current_healthy() {
  local service id state health
  for service in mysql backend web; do
    id="$(compose_cmd ps -q "$service")"
    [[ -n "$id" ]] || die "current/$service 不存在"
    state="$(docker inspect --format '{{.State.Status}}' "$id")"
    health="$(docker inspect --format '{{.State.Health.Status}}' "$id")"
    [[ "$state/$health" == "running/healthy" ]] || die "current/$service 未健康：$state/$health"
  done
  curl --fail --silent --show-error --max-time 8 "http://127.0.0.1:$source_port/readyz" >/dev/null
}

state_value() {
  python3 - "$restore_root/$1/state.json" "$2" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1], encoding="utf-8"))[sys.argv[2]])
PY
}

diagnostic_failure_count=0

assert_failure_diagnostics_if_present() {
  local restore_id="$1" diagnostics="$restore_root/$1/diagnostics" file
  [[ -d "$diagnostics" ]] || return 0
  [[ "$(stat -c '%u:%g %a' "$diagnostics")" == "0:0 700" ]] || die "$restore_id 失败诊断目录权限不安全"
  [[ -f "$diagnostics/compose-ps.txt" && -f "$diagnostics/backend.log" ]] || die "$restore_id 缺少失败诊断文件"
  while IFS= read -r -d '' file; do
    [[ "$(stat -c '%a' "$file")" == "600" ]] || die "$restore_id 失败诊断文件权限不安全：$file"
  done < <(find "$diagnostics" -type f -print0)
  diagnostic_failure_count=$((diagnostic_failure_count + 1))
}

discard() {
  "$ROOT_DIR/copperctl" backup restore discard "$1" >/dev/null
}

assert_post_backup_marker() {
  [[ "$(mysql_query "SELECT COUNT(*) FROM inbound_attachment WHERE storage_path = 'inbounds/990004/post-backup.txt';" | tr -d '[:space:]')" == "1" ]] \
    || die "当前数据库的切换后标记丢失"
  [[ -f "$var_dir/files/inbounds/990004/post-backup.txt" ]] || die "当前附件的切换后标记丢失"
}

expect_failed_stage() {
  local restore_id="$1"
  shift
  if "$@"; then
    die "$restore_id 故障场景意外成功"
  fi
  [[ "$(state_value "$restore_id" status)" == "FAILED" ]] || die "$restore_id 未进入 FAILED"
  require_current_healthy
  assert_post_backup_marker
  assert_failure_diagnostics_if_present "$restore_id"
  discard "$restore_id"
  printf '%s=passed\n' "$restore_id"
}

require_current_healthy
assert_v8_v24_tables_present "备份源"
assert_erp18_opening_migration_tables_present "备份源"

credential_file="$etc_dir/credentials/initial-admin.txt"
if [[ -f "$credential_file" ]]; then
  if "$ROOT_DIR/copperctl" backup create \
    >"$temporary/pending-source-backup.out" 2>"$temporary/pending-source-backup.err"; then
    die "首次管理员凭据未改密时不应允许创建备份"
  fi
  grep -q '首次管理员一次性凭据尚未完成交付和改密' "$temporary/pending-source-backup.err" \
    || die "待交付凭据备份拒绝原因不明确"
  require_current_healthy
  echo "pending_source_credential_backup_guard=passed"
  username="$(awk -F= '$1 == "username" {print $2; exit}' "$credential_file")"
  initial_password="$(awk -F= '$1 == "password" {print $2; exit}' "$credential_file")"
  new_password="M203Runtime$(openssl rand -hex 12)"
  source_cookie="$temporary/source-cookie.txt"
  curl --fail --silent --show-error -c "$source_cookie" -H 'Content-Type: application/json' \
    --data "{\"username\":\"$username\",\"password\":\"$initial_password\"}" \
    "http://127.0.0.1:$source_port/api/auth/login" >/dev/null
  curl --fail --silent --show-error -b "$source_cookie" -H 'Content-Type: application/json' \
    --data "{\"currentPassword\":\"$initial_password\",\"newPassword\":\"$new_password\"}" \
    "http://127.0.0.1:$source_port/api/auth/change-password" >/dev/null
  [[ ! -e "$credential_file" ]] || die "首次管理员改密后凭据文件未销毁"
  curl --fail --silent --show-error -c "$source_cookie" -H 'Content-Type: application/json' \
    --data "{\"username\":\"$username\",\"password\":\"$new_password\"}" \
    "http://127.0.0.1:$source_port/api/auth/login" >/dev/null
  unset initial_password new_password
fi
echo "source_initial_admin_handover=passed"

seed_relative="inbounds/990003/backup-snapshot.txt"
seed_file="$var_dir/files/$seed_relative"
install -d -o 10001 -g 10001 -m 0750 "$(dirname "$seed_file")"
printf 'Copper M2-03 backup snapshot attachment\n' > "$temporary/seed"
install -o 10001 -g 10001 -m 0640 "$temporary/seed" "$seed_file"
seed_size="$(stat -c '%s' "$seed_file")"
seed_sha="$(sha256sum "$seed_file" | awk '{print $1}')"

business_evidence_relative="evidence/m203/signed-receipt.txt"
tax_import_relative="tax-imports/m203/tax-invoices.csv"
bank_import_relative="bank-imports/m203/bank-statement.csv"
accounting_archive_relative="accounting-archives/m203/period-preview.zip"
for relative in "$business_evidence_relative" "$tax_import_relative" "$bank_import_relative" "$accounting_archive_relative"; do
  install -d -o 10001 -g 10001 -m 0750 "$(dirname "$var_dir/files/$relative")"
  printf 'Copper M2-03 V8-V24 evidence: %s\n' "$relative" > "$temporary/module-evidence"
  install -o 10001 -g 10001 -m 0640 "$temporary/module-evidence" "$var_dir/files/$relative"
done
business_evidence_size="$(stat -c '%s' "$var_dir/files/$business_evidence_relative")"
business_evidence_sha="$(sha256sum "$var_dir/files/$business_evidence_relative" | awk '{print $1}')"
tax_import_size="$(stat -c '%s' "$var_dir/files/$tax_import_relative")"
tax_import_sha="$(sha256sum "$var_dir/files/$tax_import_relative" | awk '{print $1}')"
bank_import_size="$(stat -c '%s' "$var_dir/files/$bank_import_relative")"
bank_import_sha="$(sha256sum "$var_dir/files/$bank_import_relative" | awk '{print $1}')"
accounting_archive_size="$(stat -c '%s' "$var_dir/files/$accounting_archive_relative")"
accounting_archive_sha="$(sha256sum "$var_dir/files/$accounting_archive_relative" | awk '{print $1}')"
mysql_cli <<SQL
DELETE FROM inbound_attachment WHERE storage_path IN ('$seed_relative', 'inbounds/990004/post-backup.txt');
INSERT INTO inbound_attachment
  (inbound_id, file_name, content_type, file_size, sha256, storage_path, file_content, uploaded_by)
VALUES
  (990003, 'backup-snapshot.txt', 'text/plain', $seed_size, '$seed_sha', '$seed_relative', NULL, 0);
INSERT INTO business_evidence_attachment
  (id,document_type,document_id,evidence_type,source_channel,original_file_name,archive_file_name,content_type,file_size,sha256,storage_path,status,note,uploaded_by_name)
VALUES (990100,'INBOUND',990003,'SIGNED_RECEIPT','UPLOAD','signed-receipt.txt','signed-receipt.txt','text/plain',$business_evidence_size,'$business_evidence_sha','$business_evidence_relative','ACTIVE','V8-V24 restore','restore test');
INSERT INTO partner_account
  (partner_id, account_type, balance, frozen_amount, credit_limit, opposite_balance, opposite_credit_limit)
VALUES (990003, 'TRADE', 123.45, 6.78, 0, 9.10, 0);
INSERT INTO payment
  (payment_no, customer_id, sales_order_id, amount, payment_method, account_type, received_date, verification_status, memo)
VALUES ('M203-CUSTOMER-PAYMENT', 990003, 0, 88.66, 'BANK', 'TRADE', CURRENT_DATE, 'VERIFIED', 'M2-03 runtime');
INSERT INTO supplier_payment
  (payment_no, supplier_id, purchase_order_id, inbound_id, amount, payment_method, account_type, paid_date, verification_status, memo)
VALUES ('M203-SUPPLIER-PAYMENT', 990004, 0, 0, 44.33, 'BANK', 'TRADE', CURRENT_DATE, 'VERIFIED', 'M2-03 runtime');
INSERT INTO sales_order_advance_allocation
  (sales_order_id, customer_id, source_payment_id, amount, status, applied_payment_id, memo)
VALUES (990003, 990003, 0, 20.00, 'RESERVED', 0, 'M2-03 runtime');
INSERT INTO outbound_advance_allocation
  (outbound_id, customer_id, amount, status, applied_payment_id, memo)
VALUES (990003, 990003, 5.00, 'RESERVED', 0, 'M2-03 runtime');
INSERT INTO customer_advance_refund
  (refund_no, customer_id, amount, refund_method, refunded_date, memo)
VALUES ('M203-ADVANCE-REFUND', 990003, 3.21, 'BANK', CURRENT_DATE, 'M2-03 runtime');
INSERT INTO partner_account_action
  (partner_id, category, amount, action_type, occurred_at, memo)
VALUES (990003, 'CUSTOMER_RECEIPT', 10.00, 'RECEIVE', CURRENT_TIMESTAMP, 'M2-03 runtime');
INSERT INTO partner_account_log
  (partner_id, account_type, source_type, source_id, amount, amount_before, amount_after)
VALUES (990003, 'TRADE', 'PAYMENT', 990003, 7.00, 116.45, 123.45);
INSERT INTO receivable_adjustment
  (adjustment_no, customer_id, sales_order_id, adjustment_type, amount, occurred_date, status, reason)
VALUES ('M203-AR-ADJUST', 990003, 990003, 'SMALL_WAIVER', 1.23, CURRENT_DATE, 'CONFIRMED', 'M2-03 runtime');

SET @erp18_entity_id=(SELECT id FROM accounting_entity WHERE entity_code='DEFAULT');
INSERT INTO data_migration_batch
  (id,entity_id,batch_no,opening_date,source_file_name,source_file_sha256,source_file_size,status,total_rows,valid_rows,error_rows,applied_rows,opening_voucher_id,imported_by_name,applied_by_name,cutover_by_name,applied_at,cutover_at,last_checked_at)
VALUES
  (990180,@erp18_entity_id,'M203-ERP18-OPENING',CURRENT_DATE,'erp18-opening.xlsx',REPEAT('a',64),1024,'CUTOVER',2,2,0,2,990100,'restore test','restore test','restore test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO data_migration_row
  (id,batch_id,domain_code,source_sheet,source_row_no,source_key,payload_json,row_hash,validation_status,target_type,target_id,target_created,rollback_context_json,applied_at)
VALUES
  (990180,990180,'PARTNER','往来单位',2,'M203-PARTNER','{"partnerNo":"990003"}',REPEAT('b',64),'VALID','PARTNER',990003,0,'{"before":"kept"}',CURRENT_TIMESTAMP),
  (990181,990180,'FUND_OPENING','期初资金',2,'M203-CASH','{"accountCode":"1001","openingBalance":"66.88"}',REPEAT('c',64),'VALID','FUND_OPENING',990180,1,'{"before":"none"}',CURRENT_TIMESTAMP);
INSERT INTO data_migration_event
  (id,batch_id,action,from_status,to_status,reason,operator_name)
VALUES
  (990180,990180,'CUTOVER','APPLIED','CUTOVER','M2-03 restore contract','restore test');
INSERT INTO data_migration_check_run
  (id,batch_id,trigger_type,status,checked_count,issue_count,summary_json,checked_by_name)
VALUES
  (990180,990180,'APPLY','FAILED',6,1,'{"result":"historical blocking issue retained"}','restore test'),
  (990181,990180,'CUTOVER','PASSED',6,0,'{"result":"cutover reconciled"}','restore test');
INSERT INTO data_migration_check_issue
  (id,check_run_id,check_code,severity,domain_code,expected_value,actual_value,message)
VALUES
  (990180,990180,'OPENING_RECONCILIATION','BLOCKING','FUND_OPENING','66.88','0.00','Historical issue retained before correction');

INSERT INTO inventory_transfer_order
  (id,order_no,from_warehouse_id,to_warehouse_id,business_date,status,total_quantity,total_cost,memo,dispatched_at,received_at)
VALUES (990100,'M203-TRANSFER',990001,990002,CURRENT_DATE,'COMPLETED',1.250,125.00,'V8-V24 restore',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO inventory_stocktake_order
  (id,order_no,warehouse_id,business_date,status,total_gain_quantity,total_loss_quantity,total_cost_difference,memo,confirmed_at)
VALUES (990100,'M203-STOCKTAKE',990001,CURRENT_DATE,'CONFIRMED',0.100,0.000,10.00,'V8-V24 restore',CURRENT_TIMESTAMP);
INSERT INTO purchase_return_order
  (id,order_no,inbound_id,purchase_order_id,supplier_id,warehouse_id,business_date,status,total_quantity,total_amount,payable_reduction_amount,supplier_credit_amount,reason,confirmed_at)
VALUES (990100,'M203-PURCHASE-RETURN',990001,990001,990004,990001,CURRENT_DATE,'CONFIRMED',0.500,50.00,50.00,0.00,'V8-V24 restore',CURRENT_TIMESTAMP);
INSERT INTO inventory_cost_adjustment
  (id,adjustment_no,business_date,status,total_difference_amount,reason,memo,confirmed_at)
VALUES (990100,'M203-COST-ADJUST',CURRENT_DATE,'CONFIRMED',6.00,'V8-V24 restore','V8-V24 restore',CURRENT_TIMESTAMP);

SET @posting_rule_id=(SELECT id FROM business_posting_rule_version WHERE rule_code='INVENTORY_TRANSFER_RECEIVE' ORDER BY version_no DESC LIMIT 1);
INSERT INTO business_posting_batch
  (id,posting_no,source_type,source_id,source_no,posting_action,rule_version_id,rule_code,business_date,idempotency_key,status,entry_hash,reason,operator_name,posted_at)
VALUES (990100,'M203-POSTING','INVENTORY_TRANSFER',990100,'M203-TRANSFER','RECEIVE',@posting_rule_id,'INVENTORY_TRANSFER_RECEIVE',CURRENT_DATE,'M203-POSTING-IDEMPOTENCY','POSTED',REPEAT('c',64),'V8-V24 restore','restore test',CURRENT_TIMESTAMP);
INSERT INTO business_posting_entry
  (id,posting_batch_id,line_no,fact_type,direction,product_id,warehouse_id,inventory_batch_id,quantity,quantity_unit,amount,memo)
VALUES (990100,990100,1,'INVENTORY_ON_HAND','INCREASE',990001,990002,990001,1.250,'kg',125.00,'V8-V24 restore');

SET @entity_id=(SELECT id FROM accounting_entity WHERE entity_code='DEFAULT');
SET @period_id=(SELECT id FROM accounting_period WHERE entity_id=@entity_id AND CURRENT_DATE BETWEEN start_date AND end_date LIMIT 1);
SET @cash_account_id=(SELECT id FROM accounting_account WHERE entity_id=@entity_id AND account_code='1001');
INSERT INTO data_migration_fund_opening
  (id,migration_row_id,batch_id,fund_type,account_code,account_name,currency,ledger_account_id,opening_date,opening_balance,status)
VALUES
  (990180,990181,990180,'CASH','1001','库存现金','CNY',@cash_account_id,CURRENT_DATE,66.88,'ACTIVE');
INSERT INTO accounting_voucher
  (id,entity_id,period_id,voucher_no,voucher_type,voucher_date,summary,source_type,source_id,source_no,business_posting_batch_id,status,debit_total,credit_total,entry_hash,maker_name,reviewer_name,poster_name,reviewed_at,posted_at)
VALUES (990100,@entity_id,@period_id,'M203-VOUCHER','AUTO',CURRENT_DATE,'V8-V24 restore','INVENTORY_TRANSFER',990100,'M203-TRANSFER',990100,'POSTED',77.77,77.77,REPEAT('d',64),'restore test','restore test','restore test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO accounting_voucher_line
  (id,voucher_id,line_no,account_id,account_code_snapshot,account_name_snapshot,summary,debit_amount,credit_amount,source_posting_entry_id)
VALUES (990100,990100,1,@cash_account_id,'1001','库存现金','V8-V24 restore',77.77,0.00,990100);
INSERT INTO accounting_ledger_entry
  (id,entity_id,period_id,voucher_id,voucher_line_id,voucher_no,voucher_date,account_id,account_code,account_name,summary,debit_amount,credit_amount)
VALUES (990100,@entity_id,@period_id,990100,990100,'M203-VOUCHER',CURRENT_DATE,@cash_account_id,'1001','库存现金','V8-V24 restore',77.77,0.00);
INSERT INTO accounting_archive_batch
  (id,entity_id,period_id,archive_no,archive_mode,status,file_name,storage_path,archive_bytes,archive_sha256,voucher_count,source_attachment_count,generated_by_name,completed_at)
VALUES (990100,@entity_id,@period_id,'M203-ACCOUNTING-ARCHIVE','PREVIEW','COMPLETED','period-preview.zip','$accounting_archive_relative',$accounting_archive_size,'$accounting_archive_sha',1,1,'restore test',CURRENT_TIMESTAMP);

INSERT INTO tax_invoice
  (id,entity_id,internal_no,direction,invoice_color,invoice_type,invoice_number,partner_id,partner_name_snapshot,issuer_name,recipient_name,invoice_date,received_or_issued_date,source_channel,amount_excluding_tax,tax_amount,total_amount,status,verification_status,booking_status,usage_status,memo)
VALUES (990100,@entity_id,'M203-TAX','OUTPUT','BLUE','DIGITAL_NORMAL','M203000001',990003,'M2-03 customer','Copper','M2-03 customer',CURRENT_DATE,CURRENT_DATE,'IMPORT',100.00,13.00,113.00,'CONFIRMED','VERIFIED','CONFIRMED','NOT_APPLICABLE','V8-V24 restore');
INSERT INTO tax_invoice_import_batch
  (id,batch_no,source_file_name,source_file_sha256,source_file_size,source_storage_path,status,imported_invoice_count,imported_line_count,imported_by_name,completed_at)
VALUES (990100,'M203-TAX-IMPORT','tax-invoices.csv','$tax_import_sha',$tax_import_size,'$tax_import_relative','COMPLETED',1,1,'restore test',CURRENT_TIMESTAMP);

SET @bank_account_id=(SELECT id FROM business_bank_account WHERE entity_id=@entity_id ORDER BY id LIMIT 1);
INSERT INTO bank_reconciliation_session
  (id,entity_id,bank_account_id,period_id,reconciliation_no,statement_from,statement_to,opening_balance,statement_closing_balance,ledger_closing_balance,adjusted_statement_balance,adjusted_ledger_balance,difference,status,close_reason,confirmed_by_name,confirmed_at)
VALUES (990100,@entity_id,@bank_account_id,@period_id,'M203-BANK-REC',DATE_FORMAT(CURRENT_DATE,'%Y-%m-01'),LAST_DAY(CURRENT_DATE),0.00,88.66,88.66,88.66,88.66,0.00,'CONFIRMED','V8-V24 restore','restore test',CURRENT_TIMESTAMP);
INSERT INTO bank_statement_line
  (id,session_id,line_no,transaction_date,value_date,bank_serial_no,direction,amount,balance,counterparty_name,purpose,source_hash,status)
VALUES (990100,990100,1,CURRENT_DATE,CURRENT_DATE,'M203-BANK-SERIAL','CREDIT',88.66,88.66,'M2-03 customer','V8-V24 restore',REPEAT('e',64),'MATCHED');
INSERT INTO bank_reconciliation_match
  (id,session_id,statement_line_id,source_type,source_id,source_no,source_date,source_direction,matched_amount,cash_flow_category,matching_method,status,reason,matched_by_name)
VALUES (990100,990100,990100,'PAYMENT',990100,'M203-CUSTOMER-PAYMENT',CURRENT_DATE,'CREDIT',88.66,'OPERATING_INFLOW','MANUAL','ACTIVE','V8-V24 restore','restore test');
INSERT INTO bank_statement_import_batch
  (id,session_id,batch_no,template_version,source_file_name,source_file_format,source_file_sha256,source_file_size,source_storage_path,status,total_rows,valid_rows,auto_match_rows,imported_rows,applied_auto_match_rows,imported_by_name,completed_at)
VALUES (990100,990100,'M203-BANK-IMPORT','copper-bank-statement-csv/v1','bank-statement.csv','CSV','$bank_import_sha',$bank_import_size,'$bank_import_relative','COMPLETED',1,1,1,1,1,'restore test',CURRENT_TIMESTAMP);
INSERT INTO accounting_period_close_run
  (id,entity_id,period_id,attempt_no,status,close_reason,balance_sheet_assets,balance_sheet_liabilities_equity,income_net_profit,cash_net_increase,trial_difference,snapshot_hash,operator_name)
VALUES (990100,@entity_id,@period_id,990100,'CHECKED','V8-V24 restore',77.77,77.77,12.34,88.66,0.00,REPEAT('f',64),'restore test');

INSERT INTO crm_opportunity
  (id,opportunity_no,partner_id,title,stage,probability,estimated_amount,expected_close_date,owner_user_id,owner_name,demand,created_by,updated_by)
VALUES (990100,'M203-OPPORTUNITY',990003,'V8-V24 restore','PROPOSAL',60.00,2000.00,DATE_ADD(CURRENT_DATE,INTERVAL 15 DAY),0,'restore test','V8-V24 restore',0,0);
INSERT INTO crm_quote
  (id,quote_no,opportunity_id,partner_id,title,quote_date,valid_until,price_includes_tax,tax_rate,tax_rule_snapshot,original_amount,amount_excluding_tax,tax_amount,total_amount,status,created_by,updated_by)
VALUES (990100,'M203-QUOTE',990100,990003,'V8-V24 restore',CURRENT_DATE,DATE_ADD(CURRENT_DATE,INTERVAL 30 DAY),1,13.0000,'VAT_GOODS_13',113.00,100.00,13.00,113.00,'ACTIVE',0,0);
SQL

assert_erp18_opening_migration_anchor_rows
"$ROOT_DIR/copperctl" backup create >/dev/null
archive_name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["archive"])' "$backup_dir/last-success.json")"
archive="$backup_dir/$archive_name"
"$ROOT_DIR/copperctl" backup verify "$archive" >/dev/null

printf 'pending target credential\n' > "$temporary/pending-initial-admin.txt"
install -o 10001 -g 10001 -m 0600 "$temporary/pending-initial-admin.txt" "$credential_file"
if "$ROOT_DIR/copperctl" backup restore stage "$archive" --identity "$identity" \
  --restore-id m203-pending-credential --port "$restore_port" \
  >"$temporary/pending-credential.out" 2>"$temporary/pending-credential.err"; then
  die "待交付一次性凭据存在时旁路恢复不应启动"
fi
grep -q '当前空白站仍有待交付的一次性管理员凭据' "$temporary/pending-credential.err" \
  || die "待交付凭据恢复拒绝原因不明确"
rm -f "$credential_file"
[[ ! -e "$restore_root/m203-pending-credential" ]] || die "凭据门槛失败后不应创建旁路恢复目录"
require_current_healthy
echo "pending_target_credential_restore_guard=passed"

post_relative="inbounds/990004/post-backup.txt"
post_file="$var_dir/files/$post_relative"
install -d -o 10001 -g 10001 -m 0750 "$(dirname "$post_file")"
printf 'Copper M2-03 data created after backup\n' > "$temporary/post"
install -o 10001 -g 10001 -m 0640 "$temporary/post" "$post_file"
post_size="$(stat -c '%s' "$post_file")"
post_sha="$(sha256sum "$post_file" | awk '{print $1}')"
mysql_cli <<SQL
INSERT INTO inbound_attachment
  (inbound_id, file_name, content_type, file_size, sha256, storage_path, file_content, uploaded_by)
VALUES
  (990004, 'post-backup.txt', 'text/plain', $post_size, '$post_sha', '$post_relative', NULL, 0);
SQL
assert_post_backup_marker

"$ROOT_DIR/copperctl" backup restore stage "$archive" --identity "$identity" --restore-id m203-login --port "$restore_port" >/dev/null
[[ "$(state_value m203-login status)" == "VALIDATED" ]] || die "正常旁路恢复未进入 VALIDATED"
python3 - "$restore_root/m203-login/contract/expected/business-totals.json" <<'PY'
import json
import sys

metrics = {item["metric"]: item["value"] for item in json.load(open(sys.argv[1], encoding="utf-8"))["metrics"]}
expected = {
    "partner_account.balance.trade": "123.45",
    "customer_payment.amount.verified": "88.66",
    "supplier_payment.amount.verified": "44.33",
    "sales_order_advance_allocation.amount.reserved": "20.00",
    "outbound_advance_allocation.amount.reserved": "5.00",
    "customer_advance_refund.amount.total": "3.21",
    "partner_account_action.amount.total": "10.00",
    "partner_account_log.amount.total": "7.00",
    "receivable_adjustment.amount.small_waiver.confirmed": "1.23",
    "inventory_transfer.total_quantity.completed": "1.250000",
    "inventory_stocktake.cost_difference.confirmed": "10.00",
    "purchase_return.payable_reduction.confirmed": "50.00",
    "inventory_cost_adjustment.difference_amount.confirmed": "6.00",
    "business_posting_entry.amount.inventory_on_hand.increase": "125.00",
    "accounting_voucher.debit.posted": "77.77",
    "accounting_ledger.debit.total": "77.77",
    "tax_invoice.total_amount.output.blue.confirmed": "113.00",
    "bank_match.amount.active": "88.66",
    "period_close.net_profit.checked": "12.34",
    "crm_opportunity.estimated_amount.proposal": "2000.00",
    "crm_quote.total_amount.active": "113.00",
}
for name, value in expected.items():
    if metrics.get(name) != value:
        raise SystemExit(f"financial metric mismatch: {name}={metrics.get(name)!r}, expected {value}")
print("financial_totals=passed")
PY
credential_file="$etc_dir/credentials/initial-admin.txt"
if [[ -f "$credential_file" ]]; then
  python3 - "$credential_file" "$temporary/login.json" <<'PY'
import json
import pathlib
import sys

values = {}
for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    if "=" in line:
        key, value = line.split("=", 1)
        values[key] = value
pathlib.Path(sys.argv[2]).write_text(json.dumps({"username": values["username"], "password": values["password"]}), encoding="utf-8")
PY
  login_code="$(curl --silent --show-error --output "$temporary/login-response.json" --write-out '%{http_code}' \
    -H 'Content-Type: application/json' --data-binary "@$temporary/login.json" "http://127.0.0.1:$restore_port/api/auth/login")"
  [[ "$login_code" == "200" ]] || die "旁路恢复管理员登录接口返回 HTTP $login_code"
  rm -f "$temporary/login.json" "$temporary/login-response.json"
  echo "sidecar_login=passed"
  if env COPPER_RESTORE_TEST_CONFIRM=0 "$ROOT_DIR/copperctl" backup restore switch m203-login >/dev/null 2>"$temporary/dirty-switch.err"; then
    die "发生登录写入后的旁路恢复不应允许切换"
  fi
  [[ "$(state_value m203-login status)" == "DIRTY" ]] || die "登录写入后未进入 DIRTY"
  require_current_healthy
  assert_post_backup_marker
  echo "dirty_sidecar_switch_block=passed"
fi
discard m203-login
assert_post_backup_marker

age-keygen -o "$temporary/wrong-identity.txt" >/dev/null 2>&1
chmod 0600 "$temporary/wrong-identity.txt"
expect_failed_stage m203-wrong-key \
  "$ROOT_DIR/copperctl" backup restore stage "$archive" --identity "$temporary/wrong-identity.txt" --restore-id m203-wrong-key --port "$restore_port"

corrupt="$temporary/corrupt.copperbak"
cp "$archive" "$corrupt"
original_hash="$(sha256sum "$corrupt" | awk '{print $1}')"
printf 'corrupt' >> "$corrupt"
printf '%s  %s\n' "$original_hash" "$(basename "$corrupt")" > "$corrupt.sha256"
expect_failed_stage m203-corrupt \
  "$ROOT_DIR/copperctl" backup restore stage "$corrupt" --identity "$identity" --restore-id m203-corrupt --port "$restore_port"

expect_failed_stage m203-low-space \
  env COPPER_RESTORE_TEST_AVAILABLE_BYTES=0 "$ROOT_DIR/copperctl" backup restore stage "$archive" --identity "$identity" --restore-id m203-low-space --port "$restore_port"

incompatible_env="$etc_dir/incompatible.env"
cp "$COPPER_ENV_FILE" "$incompatible_env"
chmod 0600 "$incompatible_env"
python3 - "$incompatible_env" "$other_web_image" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
lines = path.read_text(encoding="utf-8").splitlines()
path.write_text("\n".join(f"COPPER_WEB_IMAGE={sys.argv[2]}" if line.startswith("COPPER_WEB_IMAGE=") else line for line in lines) + "\n", encoding="utf-8")
PY
expect_failed_stage m203-incompatible \
  "$ROOT_DIR/copperctl" --env-file "$incompatible_env" backup restore stage "$archive" --identity "$identity" --restore-id m203-incompatible --port "$restore_port"
rm -f "$incompatible_env"

expect_failed_stage m203-attachment-missing \
  env COPPER_RESTORE_TEST_DELETE_ATTACHMENT=1 "$ROOT_DIR/copperctl" backup restore stage "$archive" --identity "$identity" --restore-id m203-attachment-missing --port "$restore_port"
[[ "$diagnostic_failure_count" -ge 1 ]] || die "容器启动后的恢复失败未保留 root-only 诊断日志"
echo "restore_failure_diagnostics=passed"

env COPPER_RESTORE_TEST_DELAY_AFTER_IMPORT=30 "$ROOT_DIR/copperctl" backup restore stage "$archive" --identity "$identity" --restore-id m203-interrupted --port "$restore_port" \
  >"$temporary/interrupted.out" 2>"$temporary/interrupted.err" &
restore_pid=$!
import_seen=0
for _ in $(seq 1 180); do
  stage_mysql="$(docker ps --filter 'name=copper-restore-m203-interrupted-mysql' --format '{{.ID}}' | head -1)"
  if [[ -n "$stage_mysql" ]]; then
    table_count="$(docker exec "$stage_mysql" sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" mysql -uroot -Nse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=\"copper_v2\";"' 2>/dev/null || true)"
    if [[ "$table_count" =~ ^[1-9][0-9]*$ ]]; then
      import_seen=1
      break
    fi
  fi
  sleep 1
done
[[ "$import_seen" == "1" ]] || { kill -TERM "$restore_pid" 2>/dev/null || true; die "未观察到中断测试的数据库导入"; }
kill -TERM "$restore_pid"
if wait "$restore_pid"; then
  die "恢复中断测试意外成功"
fi
[[ "$(state_value m203-interrupted status)" == "FAILED" ]] || die "中断后未进入 FAILED"
require_current_healthy
assert_post_backup_marker
discard m203-interrupted
echo "m203-interrupted=passed"

"$ROOT_DIR/copperctl" backup restore stage "$archive" --identity "$identity" --restore-id m203-switch-failure --port "$restore_port" >/dev/null
if env COPPER_RESTORE_TEST_FAIL_AT=after-switch-swap "$ROOT_DIR/copperctl" backup restore switch m203-switch-failure; then
  die "切换故障注入意外成功"
fi
[[ "$(state_value m203-switch-failure status)" == "VALIDATED" ]] || die "切换失败后未恢复 VALIDATED"
require_current_healthy
assert_post_backup_marker
discard m203-switch-failure
echo "switch_failure_recovery=passed"

"$ROOT_DIR/copperctl" backup restore stage "$archive" --identity "$identity" --restore-id m203-final --port "$restore_port" >/dev/null
"$ROOT_DIR/copperctl" backup restore switch m203-final >/dev/null
[[ "$(state_value m203-final status)" == "SWITCHED" ]] || die "正常切换未进入 SWITCHED"
require_current_healthy
assert_v8_v24_tables_present "恢复切换后"
assert_v8_v24_anchor_rows
assert_erp18_opening_migration_tables_present "恢复切换后"
assert_erp18_opening_migration_anchor_rows
for evidence_spec in \
  "$business_evidence_relative:$business_evidence_sha" \
  "$tax_import_relative:$tax_import_sha" \
  "$bank_import_relative:$bank_import_sha" \
  "$accounting_archive_relative:$accounting_archive_sha"; do
  evidence_relative="${evidence_spec%%:*}"
  evidence_sha="${evidence_spec#*:}"
  [[ -f "$var_dir/files/$evidence_relative" ]] || die "恢复后新增模块原始文件丢失：$evidence_relative"
  [[ "$(sha256sum "$var_dir/files/$evidence_relative" | awk '{print $1}')" == "$evidence_sha" ]] \
    || die "恢复后新增模块原始文件摘要不一致：$evidence_relative"
done
echo "v8_v24_restore_modules=passed"
echo "erp18_opening_migration_restore=passed"
[[ "$(mysql_query "SELECT COUNT(*) FROM inbound_attachment WHERE storage_path = '$post_relative';" | tr -d '[:space:]')" == "0" ]] || die "切换后仍存在备份时间点之后的数据"
[[ ! -e "$post_file" ]] || die "切换后仍存在备份时间点之后的附件"
"$ROOT_DIR/copperctl" backup restore rollback m203-final >/dev/null
[[ "$(state_value m203-final status)" == "ROLLED_BACK" ]] || die "正常回滚未进入 ROLLED_BACK"
require_current_healthy
assert_post_backup_marker
[[ "$(sha256sum "$post_file" | awk '{print $1}')" == "$post_sha" ]] || die "回滚后的原附件 SHA-256 不一致"
discard m203-final

[[ -z "$(find "$restore_root" -mindepth 1 -maxdepth 1 -print -quit)" ]] || die "验收结束仍有旁路恢复数据"
echo "restored_table_count=$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE';" | tr -d '[:space:]')"
echo "archive_bytes=$(stat -c '%s' "$archive")"
echo "m2_restore_runtime=passed"
