#!/usr/bin/env bash

# Newer business domains are optional when restoring an older immutable release.
# Only fixed queries supplied by this file are accepted; no user data is interpolated.
append_optional_verification_sql() {
  local output="$1" table="$2" column="$3" query="$4" query_hex
  # Hex avoids Bash 3/5 replacement differences and MySQL quote/sql_mode rules.
  query_hex="$(LC_ALL=C printf '%s' "$query" | od -An -v -tx1 | tr -d ' \n')"
  cat >> "$output" <<SQL
SET @copper_optional_sql = IF(EXISTS(
  SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '$table' AND COLUMN_NAME = '$column'
), CONVERT(0x$query_hex USING utf8mb4), 'SELECT NULL WHERE 1=0');
PREPARE copper_optional_statement FROM @copper_optional_sql;
EXECUTE copper_optional_statement;
DEALLOCATE PREPARE copper_optional_statement;
SQL
}

write_table_counts_sql() {
  cat > "$1" <<'SQL'
SET SESSION group_concat_max_len = 10485760;
SELECT GROUP_CONCAT(
  CONCAT(
    'SELECT ', QUOTE(TABLE_NAME),
    ' AS table_name, COUNT(*) AS row_count FROM `',
    REPLACE(TABLE_NAME, '`', '``'), '`'
  )
  ORDER BY TABLE_NAME
  SEPARATOR ' UNION ALL '
) INTO @copper_count_sql
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE';
SET @copper_count_sql = CONCAT(@copper_count_sql, ' ORDER BY table_name');
PREPARE copper_count_statement FROM @copper_count_sql;
EXECUTE copper_count_statement;
DEALLOCATE PREPARE copper_count_statement;
SQL
}

write_business_totals_sql() {
  cat > "$1" <<'SQL'
SELECT 'stock.quantity_available', CAST(COALESCE(SUM(quantity_available), 0) AS CHAR) FROM stock_balance
UNION ALL SELECT 'stock.amount_cost', CAST(COALESCE(SUM(amount_cost), 0) AS CHAR) FROM stock_balance
UNION ALL SELECT 'inventory_batch.quantity_available', CAST(COALESCE(SUM(quantity_available), 0) AS CHAR) FROM inventory_batch
UNION ALL SELECT 'inventory_batch.amount_cost', CAST(COALESCE(SUM(amount_cost), 0) AS CHAR) FROM inventory_batch
UNION ALL SELECT CONCAT('partner_account.balance.', LOWER(account_type)), CAST(COALESCE(SUM(balance), 0) AS CHAR) FROM partner_account GROUP BY account_type
UNION ALL SELECT CONCAT('partner_account.frozen.', LOWER(account_type)), CAST(COALESCE(SUM(frozen_amount), 0) AS CHAR) FROM partner_account GROUP BY account_type
UNION ALL SELECT CONCAT('partner_account.opposite_balance.', LOWER(account_type)), CAST(COALESCE(SUM(opposite_balance), 0) AS CHAR) FROM partner_account GROUP BY account_type
UNION ALL SELECT CONCAT('receivable_movement.amount.', LOWER(direction)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM receivable_movement GROUP BY direction
UNION ALL SELECT CONCAT('payable_movement.amount.', LOWER(direction)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM payable_movement GROUP BY direction
UNION ALL SELECT CONCAT('customer_payment.amount.', LOWER(verification_status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM payment GROUP BY verification_status
UNION ALL SELECT CONCAT('supplier_payment.amount.', LOWER(verification_status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM supplier_payment GROUP BY verification_status
UNION ALL SELECT CONCAT('acceptance_bill.face_amount.', LOWER(status)), CAST(COALESCE(SUM(face_amount), 0) AS CHAR) FROM acceptance_bill GROUP BY status
UNION ALL SELECT 'acceptance_bill.discount_fee.total', CAST(COALESCE(SUM(discount_fee), 0) AS CHAR) FROM acceptance_bill
UNION ALL SELECT 'acceptance_bill.net_proceeds.total', CAST(COALESCE(SUM(net_proceeds), 0) AS CHAR) FROM acceptance_bill
UNION ALL SELECT CONCAT('acceptance_bill_event.cash_amount.', LOWER(event_type)), CAST(COALESCE(SUM(cash_amount), 0) AS CHAR) FROM acceptance_bill_event GROUP BY event_type
UNION ALL SELECT CONCAT('acceptance_bill_event.fee_amount.', LOWER(event_type)), CAST(COALESCE(SUM(fee_amount), 0) AS CHAR) FROM acceptance_bill_event GROUP BY event_type
UNION ALL SELECT 'customer_advance_refund.amount.total', CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM customer_advance_refund
UNION ALL SELECT CONCAT('sales_order_advance_allocation.amount.', LOWER(status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM sales_order_advance_allocation GROUP BY status
UNION ALL SELECT CONCAT('outbound_advance_allocation.amount.', LOWER(status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM outbound_advance_allocation GROUP BY status
UNION ALL SELECT 'partner_account_action.amount.total', CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM partner_account_action
UNION ALL SELECT 'partner_account_log.amount.total', CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM partner_account_log
UNION ALL SELECT CONCAT('receivable_adjustment.amount.', LOWER(adjustment_type), '.', LOWER(status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM receivable_adjustment GROUP BY adjustment_type, status
UNION ALL SELECT CONCAT('sales_order.receivable_amount.', LOWER(payment_status)), CAST(COALESCE(SUM(receivable_amount), 0) AS CHAR) FROM sales_order GROUP BY payment_status
UNION ALL SELECT CONCAT('sales_order.received_amount.', LOWER(payment_status)), CAST(COALESCE(SUM(received_amount), 0) AS CHAR) FROM sales_order GROUP BY payment_status
UNION ALL SELECT CONCAT('purchase_order.total_amount.', LOWER(payment_status)), CAST(COALESCE(SUM(total_amount), 0) AS CHAR) FROM purchase_order GROUP BY payment_status
UNION ALL SELECT CONCAT('purchase_order.paid_amount.', LOWER(payment_status)), CAST(COALESCE(SUM(paid_amount), 0) AS CHAR) FROM purchase_order GROUP BY payment_status
UNION ALL SELECT CONCAT('outbound_order.receivable_amount.', LOWER(settlement_status)), CAST(COALESCE(SUM(receivable_amount), 0) AS CHAR) FROM outbound_order GROUP BY settlement_status
UNION ALL SELECT CONCAT('outbound_order.paid_amount.', LOWER(settlement_status)), CAST(COALESCE(SUM(paid_amount), 0) AS CHAR) FROM outbound_order GROUP BY settlement_status
UNION ALL SELECT CONCAT('inventory_transfer.total_quantity.', LOWER(status)), CAST(COALESCE(SUM(total_quantity), 0) AS CHAR) FROM inventory_transfer_order GROUP BY status
UNION ALL SELECT CONCAT('inventory_transfer.total_cost.', LOWER(status)), CAST(COALESCE(SUM(total_cost), 0) AS CHAR) FROM inventory_transfer_order GROUP BY status
UNION ALL SELECT CONCAT('inventory_stocktake.gain_quantity.', LOWER(status)), CAST(COALESCE(SUM(total_gain_quantity), 0) AS CHAR) FROM inventory_stocktake_order GROUP BY status
UNION ALL SELECT CONCAT('inventory_stocktake.loss_quantity.', LOWER(status)), CAST(COALESCE(SUM(total_loss_quantity), 0) AS CHAR) FROM inventory_stocktake_order GROUP BY status
UNION ALL SELECT CONCAT('inventory_stocktake.cost_difference.', LOWER(status)), CAST(COALESCE(SUM(total_cost_difference), 0) AS CHAR) FROM inventory_stocktake_order GROUP BY status
UNION ALL SELECT CONCAT('purchase_return.total_quantity.', LOWER(status)), CAST(COALESCE(SUM(total_quantity), 0) AS CHAR) FROM purchase_return_order GROUP BY status
UNION ALL SELECT CONCAT('purchase_return.total_amount.', LOWER(status)), CAST(COALESCE(SUM(total_amount), 0) AS CHAR) FROM purchase_return_order GROUP BY status
UNION ALL SELECT CONCAT('purchase_return.payable_reduction.', LOWER(status)), CAST(COALESCE(SUM(payable_reduction_amount), 0) AS CHAR) FROM purchase_return_order GROUP BY status
UNION ALL SELECT CONCAT('purchase_return.supplier_credit.', LOWER(status)), CAST(COALESCE(SUM(supplier_credit_amount), 0) AS CHAR) FROM purchase_return_order GROUP BY status
UNION ALL SELECT CONCAT('inventory_cost_adjustment.difference_amount.', LOWER(status)), CAST(COALESCE(SUM(total_difference_amount), 0) AS CHAR) FROM inventory_cost_adjustment GROUP BY status
UNION ALL SELECT CONCAT('business_posting_batch.count.', LOWER(status)), CAST(COUNT(*) AS CHAR) FROM business_posting_batch GROUP BY status
UNION ALL SELECT CONCAT('business_posting_entry.quantity.', LOWER(fact_type), '.', LOWER(direction)), CAST(COALESCE(SUM(quantity), 0) AS CHAR) FROM business_posting_entry GROUP BY fact_type,direction
UNION ALL SELECT CONCAT('business_posting_entry.amount.', LOWER(fact_type), '.', LOWER(direction)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM business_posting_entry GROUP BY fact_type,direction
UNION ALL SELECT CONCAT('accounting_voucher.debit.', LOWER(status)), CAST(COALESCE(SUM(debit_total), 0) AS CHAR) FROM accounting_voucher GROUP BY status
UNION ALL SELECT CONCAT('accounting_voucher.credit.', LOWER(status)), CAST(COALESCE(SUM(credit_total), 0) AS CHAR) FROM accounting_voucher GROUP BY status
UNION ALL SELECT 'accounting_ledger.debit.total', CAST(COALESCE(SUM(debit_amount), 0) AS CHAR) FROM accounting_ledger_entry
UNION ALL SELECT 'accounting_ledger.credit.total', CAST(COALESCE(SUM(credit_amount), 0) AS CHAR) FROM accounting_ledger_entry
UNION ALL SELECT CONCAT('accounting_generation_issue.count.', LOWER(status)), CAST(COUNT(*) AS CHAR) FROM accounting_generation_issue GROUP BY status
UNION ALL SELECT CONCAT('tax_invoice.amount_excluding_tax.', LOWER(direction), '.', LOWER(invoice_color), '.', LOWER(status)), CAST(COALESCE(SUM(amount_excluding_tax), 0) AS CHAR) FROM tax_invoice GROUP BY direction,invoice_color,status
UNION ALL SELECT CONCAT('tax_invoice.tax_amount.', LOWER(direction), '.', LOWER(invoice_color), '.', LOWER(status)), CAST(COALESCE(SUM(tax_amount), 0) AS CHAR) FROM tax_invoice GROUP BY direction,invoice_color,status
UNION ALL SELECT CONCAT('tax_invoice.total_amount.', LOWER(direction), '.', LOWER(invoice_color), '.', LOWER(status)), CAST(COALESCE(SUM(total_amount), 0) AS CHAR) FROM tax_invoice GROUP BY direction,invoice_color,status
UNION ALL SELECT CONCAT('tax_invoice_relation.total_amount.', LOWER(relation_layer), '.', LOWER(status)), CAST(COALESCE(SUM(total_amount), 0) AS CHAR) FROM tax_invoice_relation GROUP BY relation_layer,status
UNION ALL SELECT CONCAT('bank_account.opening_balance.', LOWER(status)), CAST(COALESCE(SUM(opening_balance), 0) AS CHAR) FROM business_bank_account GROUP BY status
UNION ALL SELECT CONCAT('bank_reconciliation.difference.', LOWER(status)), CAST(COALESCE(SUM(difference), 0) AS CHAR) FROM bank_reconciliation_session GROUP BY status
UNION ALL SELECT CONCAT('bank_statement.amount.', LOWER(direction), '.', LOWER(status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM bank_statement_line GROUP BY direction,status
UNION ALL SELECT CONCAT('bank_match.amount.', LOWER(status)), CAST(COALESCE(SUM(matched_amount), 0) AS CHAR) FROM bank_reconciliation_match GROUP BY status
UNION ALL SELECT CONCAT('bank_difference.amount.', LOWER(status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM bank_reconciliation_difference GROUP BY status
UNION ALL SELECT CONCAT('bank_pending.amount.', LOWER(status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM bank_reconciliation_pending_system GROUP BY status
UNION ALL SELECT CONCAT('period_close.assets.', LOWER(status)), CAST(COALESCE(SUM(balance_sheet_assets), 0) AS CHAR) FROM accounting_period_close_run GROUP BY status
UNION ALL SELECT CONCAT('period_close.liabilities_equity.', LOWER(status)), CAST(COALESCE(SUM(balance_sheet_liabilities_equity), 0) AS CHAR) FROM accounting_period_close_run GROUP BY status
UNION ALL SELECT CONCAT('period_close.net_profit.', LOWER(status)), CAST(COALESCE(SUM(income_net_profit), 0) AS CHAR) FROM accounting_period_close_run GROUP BY status
UNION ALL SELECT CONCAT('period_close.cash_net_increase.', LOWER(status)), CAST(COALESCE(SUM(cash_net_increase), 0) AS CHAR) FROM accounting_period_close_run GROUP BY status
UNION ALL SELECT CONCAT('period_close.trial_difference.', LOWER(status)), CAST(COALESCE(SUM(trial_difference), 0) AS CHAR) FROM accounting_period_close_run GROUP BY status
UNION ALL SELECT CONCAT('period_close_check.issue_count.', LOWER(status)), CAST(COALESCE(SUM(issue_count), 0) AS CHAR) FROM accounting_period_close_check GROUP BY status
UNION ALL SELECT CONCAT('period_close_check.amount_difference.', LOWER(status)), CAST(COALESCE(SUM(amount_difference), 0) AS CHAR) FROM accounting_period_close_check GROUP BY status
UNION ALL SELECT CONCAT('crm_lead.estimated_amount.', LOWER(status)), CAST(COALESCE(SUM(estimated_amount), 0) AS CHAR) FROM crm_lead GROUP BY status
UNION ALL SELECT CONCAT('crm_customer_profile.annual_potential.', LOWER(lifecycle_stage), '.', LOWER(status)), CAST(COALESCE(SUM(annual_potential), 0) AS CHAR) FROM crm_customer_profile GROUP BY lifecycle_stage,status
UNION ALL SELECT CONCAT('crm_opportunity.estimated_amount.', LOWER(stage)), CAST(COALESCE(SUM(estimated_amount), 0) AS CHAR) FROM crm_opportunity GROUP BY stage
UNION ALL SELECT CONCAT('crm_quote.total_amount.', LOWER(status)), CAST(COALESCE(SUM(total_amount), 0) AS CHAR) FROM crm_quote GROUP BY status
UNION ALL SELECT CONCAT('crm_quote.tax_amount.', LOWER(status)), CAST(COALESCE(SUM(tax_amount), 0) AS CHAR) FROM crm_quote GROUP BY status
UNION ALL SELECT 'crm_quote_item.total_amount.total', CAST(COALESCE(SUM(total_amount), 0) AS CHAR) FROM crm_quote_item
UNION ALL SELECT CONCAT('crm_followup_plan.count.', LOWER(status)), CAST(COUNT(*) AS CHAR) FROM crm_followup_plan GROUP BY status
UNION ALL SELECT CONCAT('crm_activity.count.', LOWER(activity_type)), CAST(COUNT(*) AS CHAR) FROM crm_activity GROUP BY activity_type
ORDER BY 1;
SQL
  append_optional_verification_sql "$1" inventory_package_instance remaining_base_quantity \
    "SELECT CONCAT('inventory_package.remaining_base_quantity.', product_id, '.', LOWER(status)), CAST(COALESCE(SUM(remaining_base_quantity), 0) AS CHAR) FROM inventory_package_instance GROUP BY product_id,status"
  append_optional_verification_sql "$1" inventory_package_instance_usage base_quantity \
    "SELECT CONCAT('inventory_package_usage.base_quantity.', product_id, '.', LOWER(status), '.', LOWER(movement_type)), CAST(COALESCE(SUM(base_quantity), 0) AS CHAR) FROM inventory_package_instance_usage GROUP BY product_id,status,movement_type"
  append_optional_verification_sql "$1" inventory_non_trade_item quantity \
    "SELECT CONCAT('inventory_non_trade_item.quantity.', product_id), CAST(COALESCE(SUM(quantity), 0) AS CHAR) FROM inventory_non_trade_item GROUP BY product_id UNION ALL SELECT 'inventory_non_trade_item.amount_cost.total', CAST(COALESCE(SUM(amount_cost), 0) AS CHAR) FROM inventory_non_trade_item"
  append_optional_verification_sql "$1" inventory_non_trade_document total_amount_cost \
    "SELECT CONCAT('inventory_non_trade.total_amount_cost.', LOWER(status)), CAST(COALESCE(SUM(total_amount_cost), 0) AS CHAR) FROM inventory_non_trade_document GROUP BY status UNION ALL SELECT CONCAT('inventory_non_trade.item_count.', LOWER(status)), CAST(COALESCE(SUM(item_count), 0) AS CHAR) FROM inventory_non_trade_document GROUP BY status"
}

write_attachment_references_sql() {
  cat > "$1" <<'SQL'
SELECT 'inbound_attachment', id, storage_path, file_size, sha256
FROM inbound_attachment
WHERE storage_path IS NOT NULL AND storage_path <> ''
UNION ALL
SELECT 'inbound_review_snapshot_attachment', id, storage_path, file_size, sha256
FROM inbound_review_snapshot_attachment
WHERE storage_path IS NOT NULL AND storage_path <> ''
UNION ALL
SELECT 'business_evidence_attachment', id, storage_path, file_size, sha256
FROM business_evidence_attachment
WHERE storage_path IS NOT NULL AND storage_path <> ''
UNION ALL
SELECT 'tax_invoice_import_batch', id, source_storage_path, source_file_size, source_file_sha256
FROM tax_invoice_import_batch
WHERE source_storage_path IS NOT NULL AND source_storage_path <> ''
UNION ALL
SELECT 'bank_statement_import_batch', id, source_storage_path, source_file_size, source_file_sha256
FROM bank_statement_import_batch
WHERE source_storage_path IS NOT NULL AND source_storage_path <> ''
UNION ALL
SELECT 'accounting_archive_batch', id, storage_path, archive_bytes, archive_sha256
FROM accounting_archive_batch
WHERE storage_path IS NOT NULL AND storage_path <> ''
ORDER BY 1, 2;
SQL
  append_optional_verification_sql "$1" electronic_credential storage_path \
    "SELECT 'electronic_credential', id, storage_path, file_size, source_file_sha256 FROM electronic_credential WHERE storage_path IS NOT NULL AND storage_path <> ''"
  append_optional_verification_sql "$1" electronic_credential_verification proof_storage_path \
    "SELECT 'electronic_credential_verification', id, proof_storage_path, proof_file_size, proof_sha256 FROM electronic_credential_verification WHERE proof_storage_path IS NOT NULL AND proof_storage_path <> ''"
  append_optional_verification_sql "$1" product_import_job source_storage_path \
    "SELECT 'product_import_job', id, source_storage_path, source_file_size, source_file_sha256 FROM product_import_job WHERE source_storage_path IS NOT NULL AND source_storage_path <> ''"
  append_optional_verification_sql "$1" document_intake_batch storage_path \
    "SELECT 'document_intake_batch', id, storage_path, file_size, sha256 FROM document_intake_batch WHERE storage_path IS NOT NULL AND storage_path <> ''"
}
