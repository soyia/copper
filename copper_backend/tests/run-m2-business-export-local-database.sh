#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_id="$(date -u +%Y%m%d%H%M%S)-$$"
suffix="$(printf '%s' "$run_id" | tr -cd '0-9' | tail -c 12)"
database="copper_m204_${suffix}"
db_user="m204_${suffix}"
runtime="/tmp/copper-m2-04-${run_id}"
cookie="$runtime/cookie.txt"
pid=""
started_at="$(date +%s)"
mysql_before="$(docker inspect --format '{{.Id}}' vm-mysql)"
valkey_before="$(docker inspect --format '{{.Id}}' vm-valkey)"

die() {
  echo "ERROR: $*" >&2
  if [[ -f "$runtime/stdout.log" ]]; then tail -100 "$runtime/stdout.log" >&2; fi
  exit 1
}

mysql_root() {
  docker exec -i vm-mysql sh -lc '
    p="$MYSQL_ROOT_PASSWORD"
    if [ -n "$MYSQL_ROOT_PASSWORD_FILE" ]; then p="$(cat "$MYSQL_ROOT_PASSWORD_FILE")"; fi
    exec mysql --default-character-set=utf8mb4 -uroot -p"$p" "$@"
  ' sh "$@"
}

cleanup() {
  set +e
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then kill "$pid"; wait "$pid" 2>/dev/null; fi
  printf 'DROP DATABASE IF EXISTS `%s`; DROP USER IF EXISTS '\''%s'\''@'\''%%'\'';\n' "$database" "$db_user" | mysql_root >/dev/null 2>&1
  rm -rf "$runtime"
}
trap cleanup EXIT INT TERM

[[ "$(uname -n)" == "local-database" || "$(hostname)" == "local-database" \
   || "$(uname -n)" == "local-ubuntu" || "$(hostname)" == "local-ubuntu" \
   || "$(uname -n)" == "localhost.localdomain" || "$(hostname)" == "localhost.localdomain" ]] \
  || die "该验收脚本只能在 local-database 执行"
if [[ -x /usr/lib/jvm/java-21-openjdk/bin/java ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
  export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v java >/dev/null || die "缺少 Java"
command -v mvn >/dev/null || die "缺少 Maven"
command -v curl >/dev/null || die "缺少 curl"
command -v python3 >/dev/null || die "缺少 python3"
[[ "$ROOT_DIR" != /tmp/* && "$ROOT_DIR" != /var/tmp/* ]] \
  || die "源码目录不能位于 /tmp；生产持久目录校验用例会按设计拒绝临时路径"
ss -ltn | grep -q ':8193 ' && die "8193 已被占用，禁止误停现有进程"

mkdir -p "$runtime/data/files/inbounds/910001" "$runtime/data/exports" "$runtime/data/tmp" "$runtime/data/logs" "$runtime/data/secrets"
chmod 700 "$runtime" "$runtime/data" "$runtime/data/secrets"
db_password="$(openssl rand -hex 24)"
printf '%s' "$db_password" > "$runtime/data/secrets/db-password"
chmod 600 "$runtime/data/secrets/db-password"

{
  printf 'DROP DATABASE IF EXISTS `%s`;\n' "$database"
  printf 'CREATE DATABASE `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n' "$database"
  printf 'DROP USER IF EXISTS '\''%s'\''@'\''%%'\'';\n' "$db_user"
  printf 'CREATE USER '\''%s'\''@'\''%%'\'' IDENTIFIED BY '\''%s'\'';\n' "$db_user" "$db_password"
  printf 'GRANT ALL PRIVILEGES ON `%s`.* TO '\''%s'\''@'\''%%'\''; FLUSH PRIVILEGES;\n' "$database" "$db_user"
} | mysql_root >/dev/null

cd "$ROOT_DIR"
mvn -q test
mvn -q -DskipTests package
jar_file="$(find target -maxdepth 1 -name 'copper-v2-backend-*.jar' ! -name '*.original' | head -1)"
[[ -f "$jar_file" ]] || die "未生成后端 JAR"

env \
  COPPER_PORT=8193 \
  COPPER_TIME_ZONE=Asia/Shanghai \
  COPPER_DB_URL="jdbc:mysql://127.0.0.1:3306/${database}?useSSL=false&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
  COPPER_DB_USERNAME="$db_user" \
  COPPER_DB_PASSWORD_FILE="$runtime/data/secrets/db-password" \
  COPPER_FLYWAY_ENABLED=true \
  COPPER_FLYWAY_BASELINE_ON_MIGRATE=false \
  COPPER_DATA_ROOT="$runtime/data" \
  COPPER_FILE_STORAGE_PATH="$runtime/data/files" \
  COPPER_EXPORT_PATH="$runtime/data/exports" \
  COPPER_UPLOAD_TEMP_PATH="$runtime/data/tmp" \
  COPPER_LOG_FILE="$runtime/data/logs/backend.log" \
  COPPER_LOG_LEVEL=INFO \
  COPPER_BOOTSTRAP_USERNAME=admin \
  COPPER_BOOTSTRAP_CREDENTIAL_FILE="$runtime/data/secrets/initial-admin.txt" \
  COPPER_ALLOWED_ORIGINS=http://127.0.0.1:18080 \
  COPPER_COOKIE_SECURE=false \
  COPPER_COOKIE_SAME_SITE=Lax \
  nohup java -jar "$jar_file" >"$runtime/stdout.log" 2>&1 &
pid=$!

for _ in $(seq 1 120); do
  if curl -fsS http://127.0.0.1:8193/api/actuator/health/readiness >/dev/null 2>&1 \
     && [[ -f "$runtime/data/secrets/initial-admin.txt" ]]; then break; fi
  kill -0 "$pid" 2>/dev/null || { tail -100 "$runtime/stdout.log" >&2; die "后端提前退出"; }
  sleep 1
done
curl -fsS http://127.0.0.1:8193/api/actuator/health/readiness >/dev/null || die "8193 未进入就绪"

initial_password="$(sed -n 's/^password=//p' "$runtime/data/secrets/initial-admin.txt")"
new_password="M204Runtime$(openssl rand -hex 8)"
curl -fsS -c "$cookie" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$initial_password\"}" \
  http://127.0.0.1:8193/api/auth/login >/dev/null
curl -fsS -b "$cookie" -H 'Content-Type: application/json' \
  -d "{\"currentPassword\":\"$initial_password\",\"newPassword\":\"$new_password\"}" \
  http://127.0.0.1:8193/api/auth/change-password >/dev/null
[[ ! -e "$runtime/data/secrets/initial-admin.txt" ]] || die "首次管理员凭据未销毁"
curl -fsS -c "$cookie" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$new_password\"}" \
  http://127.0.0.1:8193/api/auth/login >/dev/null
unset initial_password

attachment="$runtime/data/files/inbounds/910001/m204-receipt.txt"
printf 'Copper M2-04 synthetic attachment\n' > "$attachment"
attachment_size="$(stat -c '%s' "$attachment")"
attachment_sha="$(sha256sum "$attachment" | awk '{print $1}')"

mkdir -p "$runtime/data/files/evidence/m204" "$runtime/data/files/tax-imports/m204" \
  "$runtime/data/files/bank-imports/m204" "$runtime/data/files/accounting-archives/m204"
evidence_attachment="$runtime/data/files/evidence/m204/m204-business-evidence.txt"
tax_import_attachment="$runtime/data/files/tax-imports/m204/m204-tax-import.csv"
bank_import_attachment="$runtime/data/files/bank-imports/m204/m204-bank-import.csv"
accounting_archive_attachment="$runtime/data/files/accounting-archives/m204/m204-accounting-archive.zip"
printf 'Copper M2-04 signed business evidence\n' > "$evidence_attachment"
printf 'invoice_number,total_amount\nM204000001,113.00\n' > "$tax_import_attachment"
printf 'transaction_date,amount\n2026-07-22,88.66\n' > "$bank_import_attachment"
printf 'Copper M2-04 synthetic accounting archive\n' > "$accounting_archive_attachment"
evidence_size="$(stat -c '%s' "$evidence_attachment")"
evidence_sha="$(sha256sum "$evidence_attachment" | awk '{print $1}')"
tax_import_size="$(stat -c '%s' "$tax_import_attachment")"
tax_import_sha="$(sha256sum "$tax_import_attachment" | awk '{print $1}')"
bank_import_size="$(stat -c '%s' "$bank_import_attachment")"
bank_import_sha="$(sha256sum "$bank_import_attachment" | awk '{print $1}')"
accounting_archive_size="$(stat -c '%s' "$accounting_archive_attachment")"
accounting_archive_sha="$(sha256sum "$accounting_archive_attachment" | awk '{print $1}')"

{
cat <<SQL
INSERT INTO partner (id,business_no,type,type_mask,name,contact_person,phone,status,memo)
VALUES (910001,'910001','CUSTOMER',1,'M2-04脱敏客户','张某','TEST-PHONE-CUSTOMER','ENABLED','仅用于导出验收'),
       (910002,'910002','SUPPLIER',2,'M2-04脱敏供应商','李某','TEST-PHONE-SUPPLIER','ENABLED','仅用于导出验收');
INSERT INTO warehouse (id,name,location,status) VALUES
  (910001,'M2-04脱敏仓','测试区','ENABLED'),
  (910002,'M2-04脱敏调入仓','测试区','ENABLED');
INSERT INTO product (id,category_id,product_series_id,sku,name,grade,spec,unit,default_price,status)
VALUES (910001,101,1,'M204-SKU-001','脱敏紫铜管','T2','φ10×1','kg',12.3400,'ENABLED');
INSERT INTO purchase_order
  (id,order_no,supplier_id,business_date,total_quantity,received_quantity,total_amount,paid_amount,payment_status,status,memo)
VALUES (910001,'M204-PO-001',910002,CURRENT_DATE,10.000,10.000,1000.00,400.00,'PARTIAL','ORDERED','脱敏采购订单');
INSERT INTO purchase_order_item
  (id,purchase_order_id,product_id,product_sku_snapshot,product_name_snapshot,requested_quantity,requested_unit,
   quantity,base_unit,received_quantity,received_requested_quantity,price_unit,pricing_quantity,package_unit,
   packaging_plan_json,unit_price,amount,status)
VALUES (910001,910001,910001,'M204-SKU-001','脱敏紫铜管',10.000000,'kg',10.000,'kg',10.000,10.000000,
        'kg',10.000000,'', '{}',100.0000,1000.00,'ENABLED');
INSERT INTO inbound_order
  (id,order_no,purchase_order_id,supplier_id,warehouse_id,source_type,business_date,status,total_quantity,total_amount,
   payable_amount,paid_amount,payment_status,memo,stocked_at)
VALUES (910001,'M204-IN-001',910001,910002,910001,'PURCHASE_ORDER',CURRENT_DATE,'STOCKED',10.000,1000.00,
        1000.00,400.00,'PARTIAL','脱敏入库单',CURRENT_TIMESTAMP);
INSERT INTO inbound_item
  (id,inbound_id,product_id,product_sku_snapshot,product_name_snapshot,purchase_order_item_id,requested_quantity,
   requested_unit,inventory_batch_id,batch_no,quantity,base_unit,price_unit,pricing_quantity,package_unit,
   packaging_plan_json,unit_cost,amount)
VALUES (910001,910001,910001,'M204-SKU-001','脱敏紫铜管',910001,10.000000,'kg',910001,'M204-BATCH-001',
        10.000,'kg','kg',10.000000,'','{}',100.0000,1000.00);
INSERT INTO inventory_batch
  (id,product_id,warehouse_id,batch_no,source_type,source_id,base_unit,quantity_in,quantity_available,
   unit_cost,amount_cost,status,received_at)
VALUES (910001,910001,910001,'M204-BATCH-001','INBOUND',910001,'kg',10.000,6.000,100.0000,600.00,'AVAILABLE',CURRENT_DATE);
INSERT INTO inventory_movement
  (id,movement_no,movement_type,product_id,warehouse_id,batch_id,quantity,unit_price,amount,ref_type,ref_id,memo)
VALUES (910001,'M204-MOVE-IN','INBOUND',910001,910001,910001,10.000,100.0000,1000.00,'INBOUND',910001,'脱敏入库流水'),
       (910002,'M204-MOVE-OUT','OUTBOUND',910001,910001,910001,-4.000,100.0000,-400.00,'OUTBOUND',910001,'脱敏出库流水');
INSERT INTO stock_balance (product_id,warehouse_id,quantity_available,amount_cost)
VALUES (910001,910001,6.000,600.00);
INSERT INTO sales_order
  (id,order_no,title,customer_id,business_date,original_amount,total_amount,receivable_amount,received_amount,
   payment_status,status,memo)
VALUES (910001,'M204-SO-001','脱敏销售订单',910001,CURRENT_DATE,600.00,600.00,600.00,200.00,'PARTIAL','ORDERED','脱敏销售订单');
INSERT INTO sales_order_item
  (id,sales_order_id,product_id,product_sku_snapshot,product_name_snapshot,requested_quantity,requested_unit,
   quantity,shipped_quantity,shipped_requested_quantity,base_unit,price_unit,pricing_quantity,package_unit,
   packaging_plan_json,unit_price,amount,status)
VALUES (910001,910001,910001,'M204-SKU-001','脱敏紫铜管',4.000000,'kg',4.000,4.000000,4.000000,
        'kg','kg',4.000000,'','{}',150.0000,600.00,'ENABLED');
INSERT INTO outbound_order
  (id,order_no,sales_order_id,source_type,customer_id,warehouse_id,business_date,status,total_quantity,
   original_amount,total_amount,receivable_amount,paid_amount,payment_status,settlement_status,memo,shipped_at)
VALUES (910001,'M204-OUT-001',910001,'SALES_ORDER',910001,910001,CURRENT_DATE,'SHIPPED',4.000,
        600.00,600.00,600.00,200.00,'PARTIAL','PARTIAL','脱敏出库单',CURRENT_TIMESTAMP);
INSERT INTO outbound_item
  (id,outbound_id,sales_order_item_id,product_id,product_sku_snapshot,product_name_snapshot,requested_quantity,
   requested_unit,batch_id,quantity,base_unit,package_unit,packaging_plan_json,price_unit,pricing_quantity,unit_price,amount)
VALUES (910001,910001,910001,910001,'M204-SKU-001','脱敏紫铜管',4.000000,'kg',910001,4.000,'kg','',
        '{}','kg',4.000000,150.0000,600.00);
INSERT INTO outbound_allocation
  (outbound_id,outbound_item_id,sales_order_item_id,product_id,inventory_batch_id,quantity,status)
VALUES (910001,910001,910001,910001,910001,4.000,'ACTIVE');
INSERT INTO receivable_movement
  (movement_no,customer_id,ref_type,ref_id,direction,amount,balance_after,memo)
VALUES ('M204-AR-001',910001,'OUTBOUND',910001,'DEBIT',600.00,600.00,'脱敏应收'),
       ('M204-AR-002',910001,'PAYMENT',910001,'CREDIT',200.00,400.00,'脱敏回款核销');
INSERT INTO payable_movement
  (movement_no,supplier_id,ref_type,ref_id,direction,amount,balance_after,memo)
VALUES ('M204-AP-001',910002,'INBOUND',910001,'INCREASE',1000.00,1000.00,'脱敏应付'),
       ('M204-AP-002',910002,'SUPPLIER_PAYMENT',910001,'DECREASE',400.00,600.00,'脱敏付款核销');
INSERT INTO payment
  (id,payment_no,customer_id,sales_order_id,outbound_id,amount,payment_method,account_type,received_date,verification_status,memo)
VALUES (910001,'M204-RECEIPT-001',910001,910001,910001,200.00,'BANK','TRADE',CURRENT_DATE,'VERIFIED','脱敏回款');
INSERT INTO supplier_payment
  (id,payment_no,supplier_id,purchase_order_id,inbound_id,amount,payment_method,account_type,paid_date,verification_status,memo)
VALUES (910001,'M204-PAYMENT-001',910002,910001,910001,400.00,'BANK','TRADE',CURRENT_DATE,'VERIFIED','脱敏付款');
INSERT INTO partner_account (partner_id,account_type,balance,frozen_amount,credit_limit,opposite_balance,opposite_credit_limit)
VALUES (910001,'TRADE',400.00,0,0,0,0),(910002,'TRADE',600.00,0,0,0,0);
INSERT INTO inbound_attachment
  (id,inbound_id,file_name,content_type,file_size,sha256,storage_path,file_content,uploaded_by)
VALUES (910001,910001,'脱敏回单.txt','text/plain',$attachment_size,'$attachment_sha','inbounds/910001/m204-receipt.txt',NULL,0);
INSERT INTO business_evidence_attachment
  (id,document_type,document_id,evidence_type,source_channel,original_file_name,archive_file_name,content_type,file_size,sha256,storage_path,status,note,uploaded_by_name)
VALUES (910001,'INBOUND',910001,'SIGNED_RECEIPT','UPLOAD','m204-business-evidence.txt','m204-business-evidence.txt','text/plain',$evidence_size,'$evidence_sha','evidence/m204/m204-business-evidence.txt','ACTIVE','V8-V24导出验收','验收脚本');
INSERT INTO scan_log (code,scan_type,scan_count,dealt,result)
SELECT CONCAT('M204-SCAN-',a.n,b.n,c.n,d.n), 'INVENTORY', 1, 1, '脱敏批量记录'
FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d;

INSERT INTO inventory_transfer_order
  (id,order_no,from_warehouse_id,to_warehouse_id,business_date,status,total_quantity,total_cost,memo,dispatched_at,received_at)
VALUES (910001,'M204-TRANSFER-001',910001,910002,CURRENT_DATE,'COMPLETED',1.250,125.00,'V8-V24导出验收',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO inventory_transfer_item
  (id,transfer_id,product_id,source_batch_id,target_batch_id,quantity,base_unit,unit_cost,amount_cost,packaging_plan_json,selected_package_instance_ids_json)
VALUES (910001,910001,910001,910001,0,1.250,'kg',100.0000,125.00,'{}','[]');
INSERT INTO inventory_stocktake_order
  (id,order_no,warehouse_id,business_date,status,total_gain_quantity,total_loss_quantity,total_cost_difference,memo,confirmed_at)
VALUES (910001,'M204-STOCKTAKE-001',910001,CURRENT_DATE,'CONFIRMED',0.100,0.000,10.00,'V8-V24导出验收',CURRENT_TIMESTAMP);
INSERT INTO inventory_stocktake_item
  (id,stocktake_id,product_id,batch_id,system_quantity,counted_quantity,difference_quantity,base_unit,unit_cost,system_amount_cost,counted_amount_cost,cost_difference,difference_reason)
VALUES (910001,910001,910001,910001,6.000,6.100,0.100,'kg',100.0000,600.00,610.00,10.00,'脱敏盘盈');
INSERT INTO purchase_return_order
  (id,order_no,inbound_id,purchase_order_id,supplier_id,warehouse_id,business_date,status,total_quantity,total_amount,payable_reduction_amount,supplier_credit_amount,reason,confirmed_at)
VALUES (910001,'M204-PURCHASE-RETURN-001',910001,910001,910002,910001,CURRENT_DATE,'CONFIRMED',0.500,50.00,50.00,0.00,'V8-V24导出验收',CURRENT_TIMESTAMP);
INSERT INTO purchase_return_item
  (id,return_id,inbound_item_id,product_id,batch_id,quantity,base_unit,inventory_unit_cost,inventory_amount_cost,payable_unit_cost,amount,packaging_plan_json,selected_package_instance_ids_json)
VALUES (910001,910001,910001,910001,910001,0.500,'kg',100.0000,50.00,100.0000,50.00,'{}','[]');
INSERT INTO inventory_cost_adjustment
  (id,adjustment_no,business_date,status,total_difference_amount,reason,memo,confirmed_at)
VALUES (910001,'M204-COST-ADJUST-001',CURRENT_DATE,'CONFIRMED',6.00,'V8-V24导出验收','脱敏成本调整',CURRENT_TIMESTAMP);
INSERT INTO inventory_cost_adjustment_item
  (id,adjustment_id,product_id,warehouse_id,batch_id,quantity_snapshot,old_unit_cost,new_unit_cost,old_amount_cost,new_amount_cost,difference_amount)
VALUES (910001,910001,910001,910001,910001,6.000,100.0000,101.0000,600.00,606.00,6.00);
INSERT INTO inventory_document_event
  (id,document_type,document_id,action,reason,operator_name)
VALUES (910001,'INVENTORY_TRANSFER',910001,'CONFIRM','V8-V24导出验收','验收脚本');

SET @posting_rule_id=(SELECT id FROM business_posting_rule_version WHERE rule_code='INVENTORY_TRANSFER_RECEIVE' ORDER BY version_no DESC LIMIT 1);
INSERT INTO business_posting_batch
  (id,posting_no,source_type,source_id,source_no,posting_action,rule_version_id,rule_code,business_date,idempotency_key,status,entry_hash,reason,operator_name,posted_at)
VALUES (910001,'M204-POSTING-001','INVENTORY_TRANSFER',910001,'M204-TRANSFER-001','RECEIVE',@posting_rule_id,'INVENTORY_TRANSFER_RECEIVE',CURRENT_DATE,'M204-POSTING-IDEMPOTENCY','POSTED',REPEAT('c',64),'V8-V24导出验收','验收脚本',CURRENT_TIMESTAMP);
INSERT INTO business_posting_entry
  (id,posting_batch_id,line_no,fact_type,direction,product_id,warehouse_id,inventory_batch_id,quantity,quantity_unit,amount,memo)
VALUES (910001,910001,1,'INVENTORY_ON_HAND','INCREASE',910001,910002,910001,1.250,'kg',125.00,'V8-V24导出验收');
INSERT INTO business_posting_event
  (id,posting_batch_id,action,from_status,to_status,reason,operator_name)
VALUES (910001,910001,'POST','','POSTED','V8-V24导出验收','验收脚本');

SET @entity_id=(SELECT id FROM accounting_entity WHERE entity_code='DEFAULT');
SET @period_id=(SELECT id FROM accounting_period WHERE entity_id=@entity_id AND fiscal_year=YEAR(CURRENT_DATE) AND period_no=MONTH(CURRENT_DATE));
SET @cash_account_id=(SELECT id FROM accounting_account WHERE entity_id=@entity_id AND account_code='1001');
SET @bank_account_ledger_id=(SELECT id FROM accounting_account WHERE entity_id=@entity_id AND account_code='1002');
INSERT INTO accounting_voucher
  (id,entity_id,period_id,voucher_no,voucher_type,voucher_date,summary,source_type,source_id,source_no,business_posting_batch_id,status,debit_total,credit_total,entry_hash,maker_name,reviewer_name,poster_name,reviewed_at,posted_at)
VALUES (910001,@entity_id,@period_id,'M204-VOUCHER-001','AUTO',CURRENT_DATE,'V8-V24导出验收','INVENTORY_TRANSFER',910001,'M204-TRANSFER-001',910001,'POSTED',77.77,77.77,REPEAT('d',64),'验收脚本','验收脚本','验收脚本',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO accounting_voucher_line
  (id,voucher_id,line_no,account_id,account_code_snapshot,account_name_snapshot,summary,debit_amount,credit_amount,source_posting_entry_id)
VALUES (910001,910001,1,@cash_account_id,'1001','库存现金','V8-V24导出验收',77.77,0.00,910001),
       (910002,910001,2,@bank_account_ledger_id,'1002','银行存款','V8-V24导出验收',0.00,77.77,910001);
INSERT INTO accounting_voucher_event
  (id,voucher_id,action,from_status,to_status,reason,operator_name)
VALUES (910001,910001,'POST','REVIEWED','POSTED','V8-V24导出验收','验收脚本');
INSERT INTO accounting_ledger_entry
  (id,entity_id,period_id,voucher_id,voucher_line_id,voucher_no,voucher_date,account_id,account_code,account_name,summary,debit_amount,credit_amount)
VALUES (910001,@entity_id,@period_id,910001,910001,'M204-VOUCHER-001',CURRENT_DATE,@cash_account_id,'1001','库存现金','V8-V24导出验收',77.77,0.00),
       (910002,@entity_id,@period_id,910001,910002,'M204-VOUCHER-001',CURRENT_DATE,@bank_account_ledger_id,'1002','银行存款','V8-V24导出验收',0.00,77.77);
INSERT INTO accounting_generation_result
  (id,entity_id,business_posting_batch_id,outcome,voucher_count,detail)
VALUES (910001,@entity_id,910001,'VOUCHER_CREATED',1,'V8-V24导出验收');
INSERT INTO accounting_archive_batch
  (id,entity_id,period_id,archive_no,archive_mode,status,file_name,storage_path,archive_bytes,archive_sha256,voucher_count,source_attachment_count,generated_by_name,completed_at)
VALUES (910001,@entity_id,@period_id,'M204-ACCOUNTING-ARCHIVE','PREVIEW','COMPLETED','m204-accounting-archive.zip','accounting-archives/m204/m204-accounting-archive.zip',$accounting_archive_size,'$accounting_archive_sha',1,1,'验收脚本',CURRENT_TIMESTAMP);

SET @tax_rule_id=(SELECT id FROM tax_rate_rule WHERE rule_code='VAT_GOODS_13' ORDER BY version_no DESC LIMIT 1);
INSERT INTO tax_invoice
  (id,entity_id,internal_no,direction,invoice_color,invoice_type,invoice_number,partner_id,partner_name_snapshot,issuer_name,recipient_name,invoice_date,received_or_issued_date,source_channel,amount_excluding_tax,tax_amount,total_amount,status,verification_status,booking_status,usage_status,accounting_voucher_id,memo)
VALUES (910001,@entity_id,'M204-TAX-001','OUTPUT','BLUE','DIGITAL_NORMAL','M204000001',910001,'M2-04脱敏客户','本企业','M2-04脱敏客户',CURRENT_DATE,CURRENT_DATE,'IMPORT',100.00,13.00,113.00,'CONFIRMED','VERIFIED','CONFIRMED','NOT_APPLICABLE',910001,'V8-V24导出验收');
INSERT INTO tax_invoice_line
  (id,invoice_id,line_no,product_id,product_sku_snapshot,product_name_snapshot,unit_snapshot,quantity,unit_price_excluding_tax,amount_excluding_tax,tax_rate_rule_id,tax_rule_code_snapshot,tax_rule_name_snapshot,tax_rate,calculation_method_snapshot,tax_amount,total_amount)
VALUES (910001,910001,1,910001,'M204-SKU-001','脱敏紫铜管','kg',1.000000,100.000000,100.00,@tax_rule_id,'VAT_GOODS_13','货物及铜材一般计税13%',13.0000,'GENERAL',13.00,113.00);
INSERT INTO tax_invoice_relation
  (id,invoice_id,relation_layer,source_type,source_id,source_no,source_business_date,partner_id,amount_excluding_tax,tax_amount,total_amount,status,matching_basis,created_by_name)
VALUES (910001,910001,'FUNDS','PAYMENT',910001,'M204-RECEIPT-001',CURRENT_DATE,910001,100.00,13.00,113.00,'ACTIVE','V8-V24导出验收','验收脚本');
INSERT INTO tax_invoice_event
  (id,invoice_id,action,from_status,to_status,reason,operator_name)
VALUES (910001,910001,'CONFIRM','DRAFT','CONFIRMED','V8-V24导出验收','验收脚本');
INSERT INTO tax_invoice_use_event
  (id,invoice_id,action,from_usage_status,to_usage_status,tax_period,tax_amount,accounting_voucher_id,evidence_reference,reason,operator_name)
VALUES (910001,910001,'BOOKING_CONFIRM','UNCONFIRMED','NOT_APPLICABLE',DATE_FORMAT(CURRENT_DATE,'%Y-%m'),13.00,910001,'M204-TAX-EVIDENCE','V8-V24导出验收','验收脚本');
INSERT INTO tax_invoice_import_batch
  (id,batch_no,source_file_name,source_file_sha256,source_file_size,source_storage_path,status,imported_invoice_count,imported_line_count,imported_by_name,completed_at)
VALUES (910001,'M204-TAX-IMPORT','m204-tax-import.csv','$tax_import_sha',$tax_import_size,'tax-imports/m204/m204-tax-import.csv','COMPLETED',1,1,'验收脚本',CURRENT_TIMESTAMP);

SET @business_bank_account_id=(SELECT id FROM business_bank_account WHERE entity_id=@entity_id ORDER BY id LIMIT 1);
INSERT INTO bank_reconciliation_session
  (id,entity_id,bank_account_id,period_id,reconciliation_no,statement_from,statement_to,opening_balance,statement_closing_balance,ledger_closing_balance,adjusted_statement_balance,adjusted_ledger_balance,difference,status,close_reason,confirmed_by_name,confirmed_at)
VALUES (910001,@entity_id,@business_bank_account_id,@period_id,'M204-BANK-REC-001',DATE_FORMAT(CURRENT_DATE,'%Y-%m-01'),LAST_DAY(CURRENT_DATE),0.00,88.66,88.66,88.66,88.66,0.00,'CONFIRMED','V8-V24导出验收','验收脚本',CURRENT_TIMESTAMP);
INSERT INTO bank_statement_line
  (id,session_id,line_no,transaction_date,value_date,bank_serial_no,direction,amount,balance,counterparty_name,purpose,source_hash,status)
VALUES (910001,910001,1,CURRENT_DATE,CURRENT_DATE,'M204-BANK-SERIAL','CREDIT',88.66,88.66,'M2-04脱敏客户','V8-V24导出验收',REPEAT('e',64),'MATCHED');
INSERT INTO bank_reconciliation_match
  (id,session_id,statement_line_id,source_type,source_id,source_no,source_date,source_direction,matched_amount,cash_flow_category,matching_method,status,reason,matched_by_name)
VALUES (910001,910001,910001,'PAYMENT',910001,'M204-RECEIPT-001',CURRENT_DATE,'CREDIT',88.66,'OPERATING_INFLOW','MANUAL','ACTIVE','V8-V24导出验收','验收脚本');
INSERT INTO bank_reconciliation_difference
  (id,session_id,statement_line_id,difference_type,direction,amount,cash_flow_category,reason,accounting_voucher_id,status,created_by_name,resolved_by_name,resolved_at)
VALUES (910001,910001,910001,'BANK_FEE','DEBIT',1.23,'OPERATING_OUTFLOW','V8-V24导出验收',910001,'RESOLVED','验收脚本','验收脚本',CURRENT_TIMESTAMP);
INSERT INTO bank_reconciliation_pending_system
  (id,session_id,source_type,source_id,source_no,source_date,direction,amount,cash_flow_category,reason,status,created_by_name,resolved_at)
VALUES (910001,910001,'SUPPLIER_PAYMENT',910001,'M204-PAYMENT-001',CURRENT_DATE,'DEBIT',44.33,'OPERATING_OUTFLOW','V8-V24导出验收','MATCHED','验收脚本',CURRENT_TIMESTAMP);
INSERT INTO bank_reconciliation_event
  (id,session_id,action,from_status,to_status,reason,operator_name)
VALUES (910001,910001,'CONFIRM','DRAFT','CONFIRMED','V8-V24导出验收','验收脚本');
INSERT INTO bank_statement_import_batch
  (id,session_id,batch_no,template_version,source_file_name,source_file_format,source_file_sha256,source_file_size,source_storage_path,status,total_rows,valid_rows,auto_match_rows,imported_rows,applied_auto_match_rows,imported_by_name,completed_at)
VALUES (910001,910001,'M204-BANK-IMPORT','copper-bank-statement-csv/v1','m204-bank-import.csv','CSV','$bank_import_sha',$bank_import_size,'bank-imports/m204/m204-bank-import.csv','COMPLETED',1,1,1,1,1,'验收脚本',CURRENT_TIMESTAMP);
INSERT INTO accounting_period_close_run
  (id,entity_id,period_id,attempt_no,status,close_reason,balance_sheet_assets,balance_sheet_liabilities_equity,income_net_profit,cash_net_increase,trial_difference,snapshot_hash,operator_name)
VALUES (910001,@entity_id,@period_id,1,'CHECKED','V8-V24导出验收',77.77,77.77,12.34,88.66,0.00,REPEAT('f',64),'验收脚本');
INSERT INTO accounting_period_close_check
  (id,close_run_id,check_code,check_name,status,issue_count,amount_difference,detail)
VALUES (910001,910001,'M204_EXPORT_RESTORE','导出恢复验收','PASS',0,0.00,'V8-V24导出验收');

INSERT INTO crm_lead
  (id,lead_no,subject,company_name,contact_name,phone,source_code,demand,estimated_amount,owner_user_id,owner_name,status,created_by,updated_by)
VALUES (910001,'M204-LEAD-001','V8-V24导出验收','脱敏潜客公司','王某','TEST-PHONE-LEAD','OTHER','铜材采购',3000.00,0,'验收脚本','OPEN',0,0);
INSERT INTO crm_customer_profile
  (id,partner_id,lifecycle_stage,tier_code,source_code,owner_user_id,owner_name,tags,annual_potential,status,created_by,updated_by)
VALUES (910001,910001,'ACTIVE','KEY','OTHER',0,'验收脚本','V8-V24',5000.00,'ENABLED',0,0);
INSERT INTO crm_opportunity
  (id,opportunity_no,partner_id,source_lead_id,title,stage,probability,estimated_amount,expected_close_date,owner_user_id,owner_name,demand,created_by,updated_by)
VALUES (910001,'M204-OPP-001',910001,910001,'V8-V24导出验收','PROPOSAL',60.00,2000.00,DATE_ADD(CURRENT_DATE,INTERVAL 15 DAY),0,'验收脚本','铜材采购',0,0);
INSERT INTO crm_quote
  (id,quote_no,opportunity_id,partner_id,title,quote_date,valid_until,price_includes_tax,tax_rate,tax_rule_snapshot,original_amount,amount_excluding_tax,tax_amount,total_amount,status,created_by,updated_by)
VALUES (910001,'M204-QUOTE-001',910001,910001,'V8-V24导出验收',CURRENT_DATE,DATE_ADD(CURRENT_DATE,INTERVAL 30 DAY),1,13.0000,'VAT_GOODS_13',113.00,100.00,13.00,113.00,'ACTIVE',0,0);
INSERT INTO crm_quote_item
  (id,quote_id,product_id,product_sku_snapshot,product_name_snapshot,requested_quantity,requested_unit,base_quantity,base_unit,pricing_quantity,price_unit,packaging_plan_json,unit_price,tax_rate,amount_excluding_tax,tax_amount,total_amount)
VALUES (910001,910001,910001,'M204-SKU-001','脱敏紫铜管',1.000000,'kg',1.000000,'kg',1.000000,'kg','{}',113.0000,13.0000,100.00,13.00,113.00);
INSERT INTO crm_quote_conversion
  (id,quote_id,opportunity_id,sales_order_id,converted_by,converted_by_name)
VALUES (910001,910001,910001,910001,0,'验收脚本');
INSERT INTO crm_activity
  (id,target_type,target_id,partner_id,activity_type,subject,occurred_at,outcome,next_action,owner_user_id,owner_name,created_by)
VALUES (910001,'CUSTOMER',910001,910001,'CALL','V8-V24导出验收',CURRENT_TIMESTAMP,'已沟通','继续报价',0,'验收脚本',0);
INSERT INTO crm_followup_plan
  (id,target_type,target_id,partner_id,subject,plan_type,due_at,priority,owner_user_id,owner_name,status,created_by,updated_by)
VALUES (910001,'CUSTOMER',910001,910001,'V8-V24导出验收','REORDER',DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 7 DAY),'HIGH',0,'验收脚本','OPEN',0,0);
INSERT INTO crm_domain_event
  (id,aggregate_type,aggregate_id,event_type,partner_id,actor_user_id,actor_name,payload_json)
VALUES (910001,'QUOTE',910001,'QUOTE_ACTIVATED',910001,0,'验收脚本','{"source":"M204"}');
SQL
} | mysql_root "$database" >/dev/null

json_field() {
  python3 - "$1" "$2" <<'PY'
import json, sys
data=json.load(open(sys.argv[1], encoding='utf-8'))
print(data['data'][sys.argv[2]])
PY
}

task_status() {
  curl -fsS -b "$cookie" "http://127.0.0.1:8193/api/business-exports/$1" > "$runtime/task.json"
  json_field "$runtime/task.json" status
}

wait_status() {
  local id="$1" expected="$2" status=""
  for _ in $(seq 1 120); do
    status="$(task_status "$id")"
    [[ "$status" == "$expected" ]] && return 0
    [[ "$status" == "FAILED" && "$expected" != "FAILED" ]] && { cat "$runtime/task.json" >&2; return 1; }
    sleep 1
  done
  cat "$runtime/task.json" >&2
  return 1
}

post_create() {
  local output="$1" status
  status="$(curl -sS -o "$output" -w '%{http_code}' -b "$cookie" -H 'Content-Type: application/json' \
    -d '{"confirmFullExport":true}' http://127.0.0.1:8193/api/business-exports)"
  if [[ "$status" != "200" ]]; then
    printf 'POST /business-exports returned HTTP %s: ' "$status" >&2
    cat "$output" >&2
    return 1
  fi
}

post_create "$runtime/create-1.json" &
create_pid_1=$!
post_create "$runtime/create-duplicate.json" &
create_pid_2=$!
wait "$create_pid_1" || die "首次完整导出请求失败"
wait "$create_pid_2" || die "并发重复导出请求失败"
first_id="$(json_field "$runtime/create-1.json" id)"
duplicate_id="$(json_field "$runtime/create-duplicate.json" id)"
[[ "$first_id" == "$duplicate_id" ]] || die "活动任务防重复失败"
wait_status "$first_id" COMPLETED || die "首次完整导出失败"

curl -fsS -b "$cookie" -D "$runtime/download.headers" \
  "http://127.0.0.1:8193/api/business-exports/$first_id/download" -o "$runtime/export.zip"
header_sha="$(tr -d '\r' < "$runtime/download.headers" | sed -n 's/^X-Content-SHA256: //Ip')"
[[ "$(sha256sum "$runtime/export.zip" | awk '{print $1}')" == "$header_sha" ]] || die "下载包 SHA-256 响应头不一致"

python3 - "$runtime/export.zip" "$runtime/unpacked" <<'PY'
import pathlib, sys, zipfile
archive=pathlib.Path(sys.argv[1]); target=pathlib.Path(sys.argv[2])
with zipfile.ZipFile(archive) as z:
    for info in z.infolist():
        p=pathlib.PurePosixPath(info.filename)
        if p.is_absolute() or '..' in p.parts: raise SystemExit('unsafe zip member')
    z.extractall(target)
PY
(cd "$runtime/unpacked" && sha256sum -c checksums.sha256 >/dev/null)

for path in README.html manifest.json schema/fields.csv schema/enums.csv schema/business-totals.csv \
  master-data/partner.csv purchasing/purchase_order.csv inventory/inventory_batch.csv sales/sales_order.csv \
  receivables-payables/receivable_movement.csv cash-and-settlement/payment.csv audit/security_audit_log.csv \
  attachments/manifest.csv; do
  [[ -f "$runtime/unpacked/$path" ]] || die "导出包缺少 $path"
done

v8_v24_paths=(
  inventory/inventory_cost_policy.csv
  inventory/inventory_transfer_order.csv
  inventory/inventory_transfer_item.csv
  inventory/inventory_stocktake_order.csv
  inventory/inventory_stocktake_item.csv
  purchasing/purchase_return_order.csv
  purchasing/purchase_return_item.csv
  inventory/inventory_cost_adjustment.csv
  inventory/inventory_cost_adjustment_item.csv
  inventory/inventory_document_event.csv
  inventory/inventory_opening_cost_alignment.csv
  audit/business_posting_rule_version.csv
  audit/business_posting_batch.csv
  audit/business_posting_entry.csv
  audit/business_posting_event.csv
  audit/business_order_revision.csv
  accounting/accounting_entity.csv
  accounting/accounting_period.csv
  accounting/accounting_account.csv
  accounting/accounting_rule_version.csv
  accounting/accounting_account_event.csv
  accounting/accounting_voucher.csv
  accounting/accounting_voucher_line.csv
  accounting/accounting_voucher_event.csv
  accounting/accounting_ledger_entry.csv
  accounting/accounting_generation_issue.csv
  accounting/accounting_generation_result.csv
  accounting/accounting_period_event.csv
  tax-invoices/tax_rate_rule.csv
  tax-invoices/tax_invoice.csv
  tax-invoices/tax_invoice_line.csv
  tax-invoices/tax_invoice_relation.csv
  tax-invoices/tax_invoice_event.csv
  tax-invoices/tax_invoice_use_event.csv
  financial-close/financial_report_line.csv
  financial-close/financial_report_account_map.csv
  financial-close/business_bank_account.csv
  financial-close/bank_reconciliation_session.csv
  financial-close/bank_statement_line.csv
  financial-close/bank_reconciliation_match.csv
  financial-close/bank_reconciliation_difference.csv
  financial-close/bank_reconciliation_pending_system.csv
  financial-close/bank_reconciliation_event.csv
  financial-close/accounting_period_close_run.csv
  financial-close/accounting_period_close_check.csv
  crm/crm_option.csv
  crm/crm_number_sequence.csv
  crm/crm_lead.csv
  crm/crm_customer_profile.csv
  crm/crm_opportunity.csv
  crm/crm_quote.csv
  crm/crm_quote_item.csv
  crm/crm_quote_conversion.csv
  crm/crm_activity.csv
  crm/crm_followup_plan.csv
  crm/crm_domain_event.csv
)
[[ "${#v8_v24_paths[@]}" == "56" ]] || die "V8-V24 导出表合同数量错误"
for path in "${v8_v24_paths[@]}"; do
  [[ -f "$runtime/unpacked/$path" ]] || die "V8-V24 导出包缺少 $path"
done

[[ ! -e "$runtime/unpacked/audit/system_user_session.csv" ]] || die "会话令牌表进入了业务导出"
head -1 "$runtime/unpacked/audit/system_user.csv" | grep -qv 'password_hash' || die "密码哈希进入了业务导出"
grep -R -Fq "$db_password" "$runtime/unpacked" && die "数据库密码进入了业务导出"
grep -R -Fq "$new_password" "$runtime/unpacked" && die "管理员密码进入了业务导出"
grep -q '12.3400' "$runtime/unpacked/master-data/product.csv" || die "十进制原始精度未保留"
grep -q '+08:00' "$runtime/unpacked/master-data/partner.csv" || die "时间未保留时区偏移"
grep -q 'stock.quantity_available.*6.000' "$runtime/unpacked/schema/business-totals.csv" || die "库存汇总不正确"
grep -q 'receivable_movement.amount.debit.*600.00' "$runtime/unpacked/schema/business-totals.csv" || die "应收汇总不正确"
grep -q 'inventory_transfer.total_quantity.completed.*1.250' "$runtime/unpacked/schema/business-totals.csv" || die "库存调拨汇总不正确"
grep -q 'inventory_stocktake.cost_difference.confirmed.*10.00' "$runtime/unpacked/schema/business-totals.csv" || die "库存盘点汇总不正确"
grep -q 'purchase_return.payable_reduction.confirmed.*50.00' "$runtime/unpacked/schema/business-totals.csv" || die "采购退货汇总不正确"
grep -q 'business_posting_entry.amount.inventory_on_hand.increase.*125.00' "$runtime/unpacked/schema/business-totals.csv" || die "业务过账汇总不正确"
grep -q 'accounting_voucher.debit.posted.*77.77' "$runtime/unpacked/schema/business-totals.csv" || die "会计凭证汇总不正确"
grep -q 'accounting_ledger.credit.total.*77.77' "$runtime/unpacked/schema/business-totals.csv" || die "总账汇总不正确"
grep -q 'tax_invoice.total_amount.output.blue.confirmed.*113.00' "$runtime/unpacked/schema/business-totals.csv" || die "税票汇总不正确"
grep -q 'bank_match.amount.active.*88.66' "$runtime/unpacked/schema/business-totals.csv" || die "银行对账汇总不正确"
grep -q 'period_close.net_profit.checked.*12.34' "$runtime/unpacked/schema/business-totals.csv" || die "期末结账汇总不正确"
grep -q 'crm_quote.total_amount.active.*113.00' "$runtime/unpacked/schema/business-totals.csv" || die "CRM 报价汇总不正确"
exported_attachment="$(find "$runtime/unpacked/attachments/inbounds/910001" -type f -name '*脱敏回单.txt' | head -1)"
[[ -f "$exported_attachment" ]] || die "原始附件未导出"
[[ "$(sha256sum "$exported_attachment" | awk '{print $1}')" == "$attachment_sha" ]] || die "导出附件 SHA-256 不一致"
for evidence_spec in \
  "m204-business-evidence.txt:$evidence_sha" \
  "m204-tax-import.csv:$tax_import_sha" \
  "m204-bank-import.csv:$bank_import_sha" \
  "m204-accounting-archive.zip:$accounting_archive_sha"; do
  evidence_name="${evidence_spec%%:*}"
  evidence_expected_sha="${evidence_spec#*:}"
  exported_evidence="$(find "$runtime/unpacked/attachments" -type f -name "*$evidence_name" | head -1)"
  [[ -f "$exported_evidence" ]] || die "新增模块原始文件未导出：$evidence_name"
  [[ "$(sha256sum "$exported_evidence" | awk '{print $1}')" == "$evidence_expected_sha" ]] \
    || die "新增模块原始文件 SHA-256 不一致：$evidence_name"
done

python3 - "$runtime/unpacked/manifest.json" <<'PY'
import json, sys
m=json.load(open(sys.argv[1], encoding='utf-8'))
assert m['formatVersion']=='copper-export/v1'
assert m['scopeType']=='FULL'
assert m['filters']['dataScope']=='ALL'
assert m['schemaVersion']=='30'
assert m['tableCount']==151
assert m['rowCounts']['scan_log']==10000
for table in (
    'inventory_transfer_order', 'inventory_stocktake_order', 'purchase_return_order',
    'inventory_cost_adjustment', 'business_posting_batch', 'accounting_voucher',
    'accounting_ledger_entry', 'tax_invoice', 'bank_reconciliation_session',
    'accounting_period_close_run', 'crm_lead', 'crm_opportunity', 'crm_quote'
):
    assert m['rowCounts'][table] >= 1, table
assert m['attachmentCount']==5
assert m['recordCount']>10000
assert 'system_user.password_hash' in m['excludedSecrets']
PY

printf 'missing attachment' > "$runtime/missing-source"
missing_size="$(stat -c '%s' "$runtime/missing-source")"
missing_sha="$(sha256sum "$runtime/missing-source" | awk '{print $1}')"
printf "INSERT INTO inbound_attachment (id,inbound_id,file_name,content_type,file_size,sha256,storage_path,file_content,uploaded_by) VALUES (910002,910001,'missing.txt','text/plain',%s,'%s','inbounds/910001/missing.txt',NULL,0);\n" "$missing_size" "$missing_sha" \
  | mysql_root "$database" >/dev/null
curl -fsS -b "$cookie" -H 'Content-Type: application/json' -d '{"confirmFullExport":true}' \
  http://127.0.0.1:8193/api/business-exports > "$runtime/create-failure.json"
failed_id="$(json_field "$runtime/create-failure.json" id)"
wait_status "$failed_id" FAILED || die "缺失附件场景未安全失败"
printf 'DELETE FROM inbound_attachment WHERE id=910002;\n' | mysql_root "$database" >/dev/null
curl -fsS -b "$cookie" -H 'Content-Type: application/json' -d '{"confirmRetry":true}' \
  "http://127.0.0.1:8193/api/business-exports/$failed_id/retry" > "$runtime/retry.json"
retry_id="$(json_field "$runtime/retry.json" id)"
[[ "$retry_id" != "$failed_id" ]] || die "重试必须生成新任务并保留原失败记录"
wait_status "$retry_id" COMPLETED || die "失败任务重试未完成"

audit_count="$(printf "SELECT COUNT(*) FROM security_audit_log WHERE action_code LIKE 'BUSINESS_EXPORT_%%';\n" | mysql_root -N -B "$database" | tr -d '[:space:]')"
[[ "$audit_count" -ge 8 ]] || die "导出任务审计记录不完整"
task_count="$(printf 'SELECT COUNT(*) FROM business_export_task;\n' | mysql_root -N -B "$database" | tr -d '[:space:]')"
[[ "$task_count" == "3" ]] || die "防重复、失败与重试任务数量不正确"
table_count="$(printf "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$database' AND table_type='BASE TABLE';\n" | mysql_root -N -B | tr -d '[:space:]')"
[[ "$table_count" == "153" ]] || die "V30 应包含 153 张基础表（含 Flyway 历史表）"

[[ "$(docker inspect --format '{{.Id}}' vm-mysql)" == "$mysql_before" ]] || die "共享 vm-mysql 被替换"
[[ "$(docker inspect --format '{{.Id}}' vm-valkey)" == "$valkey_before" ]] || die "共享 vm-valkey 被替换"

elapsed="$(( $(date +%s) - started_at ))"
echo "m2_business_export_runtime=passed"
echo "mysql_version=$(printf 'SELECT VERSION();\n' | mysql_root -N -B | tr -d '[:space:]')"
echo "database_table_count=$table_count"
echo "exported_table_count=$(json_field "$runtime/task.json" exportedTableCount)"
echo "exported_record_count=$(json_field "$runtime/task.json" exportedRecordCount)"
echo "exported_attachment_count=$(json_field "$runtime/task.json" exportedAttachmentCount)"
echo "archive_bytes=$(stat -c '%s' "$runtime/export.zip")"
echo "runtime_seconds=$elapsed"
echo "duplicate_guard=passed"
echo "missing_attachment_failure=passed"
echo "failed_task_retry=passed"
echo "shared_vm_mysql_unchanged=passed"
echo "shared_vm_valkey_unchanged=passed"
