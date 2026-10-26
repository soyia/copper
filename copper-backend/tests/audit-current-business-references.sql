-- Read-only current-state evidence. Run against an explicitly selected database.
-- Keep results private: identifiers and source numbers are business audit data.
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
SELECT database() database_name, @@version mysql_version, utc_timestamp() audited_at_utc;
SELECT version,description,checksum,success FROM flyway_schema_history ORDER BY installed_rank;

-- The exact 54 deterministic references protected by V67; no row is repaired here.
SELECT 'purchase_order_item.purchase_order_id -> purchase_order.id' relation_name, count(*) orphan_count FROM purchase_order_item c LEFT JOIN purchase_order p ON p.id=c.purchase_order_id WHERE c.purchase_order_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'purchase_order_item.product_id -> product.id' relation_name, count(*) orphan_count FROM purchase_order_item c LEFT JOIN product p ON p.id=c.product_id WHERE c.product_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'sales_order_item.sales_order_id -> sales_order.id' relation_name, count(*) orphan_count FROM sales_order_item c LEFT JOIN sales_order p ON p.id=c.sales_order_id WHERE c.sales_order_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'sales_order_item.product_id -> product.id' relation_name, count(*) orphan_count FROM sales_order_item c LEFT JOIN product p ON p.id=c.product_id WHERE c.product_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inbound_item.inbound_id -> inbound_order.id' relation_name, count(*) orphan_count FROM inbound_item c LEFT JOIN inbound_order p ON p.id=c.inbound_id WHERE c.inbound_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inbound_item.product_id -> product.id' relation_name, count(*) orphan_count FROM inbound_item c LEFT JOIN product p ON p.id=c.product_id WHERE c.product_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'outbound_item.outbound_id -> outbound_order.id' relation_name, count(*) orphan_count FROM outbound_item c LEFT JOIN outbound_order p ON p.id=c.outbound_id WHERE c.outbound_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'outbound_item.product_id -> product.id' relation_name, count(*) orphan_count FROM outbound_item c LEFT JOIN product p ON p.id=c.product_id WHERE c.product_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_batch.product_id -> product.id' relation_name, count(*) orphan_count FROM inventory_batch c LEFT JOIN product p ON p.id=c.product_id WHERE c.product_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_batch.warehouse_id -> warehouse.id' relation_name, count(*) orphan_count FROM inventory_batch c LEFT JOIN warehouse p ON p.id=c.warehouse_id WHERE c.warehouse_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'stock_balance.product_id -> product.id' relation_name, count(*) orphan_count FROM stock_balance c LEFT JOIN product p ON p.id=c.product_id WHERE c.product_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'stock_balance.warehouse_id -> warehouse.id' relation_name, count(*) orphan_count FROM stock_balance c LEFT JOIN warehouse p ON p.id=c.warehouse_id WHERE c.warehouse_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_movement.product_id -> product.id' relation_name, count(*) orphan_count FROM inventory_movement c LEFT JOIN product p ON p.id=c.product_id WHERE c.product_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_movement.warehouse_id -> warehouse.id' relation_name, count(*) orphan_count FROM inventory_movement c LEFT JOIN warehouse p ON p.id=c.warehouse_id WHERE c.warehouse_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_movement.batch_id -> inventory_batch.id' relation_name, count(*) orphan_count FROM inventory_movement c LEFT JOIN inventory_batch p ON p.id=c.batch_id WHERE c.batch_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_transfer_item.transfer_id -> inventory_transfer_order.id' relation_name, count(*) orphan_count FROM inventory_transfer_item c LEFT JOIN inventory_transfer_order p ON p.id=c.transfer_id WHERE c.transfer_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_transfer_item.source_batch_id -> inventory_batch.id' relation_name, count(*) orphan_count FROM inventory_transfer_item c LEFT JOIN inventory_batch p ON p.id=c.source_batch_id WHERE c.source_batch_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_stocktake_item.stocktake_id -> inventory_stocktake_order.id' relation_name, count(*) orphan_count FROM inventory_stocktake_item c LEFT JOIN inventory_stocktake_order p ON p.id=c.stocktake_id WHERE c.stocktake_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_stocktake_item.batch_id -> inventory_batch.id' relation_name, count(*) orphan_count FROM inventory_stocktake_item c LEFT JOIN inventory_batch p ON p.id=c.batch_id WHERE c.batch_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'purchase_return_item.return_id -> purchase_return_order.id' relation_name, count(*) orphan_count FROM purchase_return_item c LEFT JOIN purchase_return_order p ON p.id=c.return_id WHERE c.return_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'purchase_return_item.inbound_item_id -> inbound_item.id' relation_name, count(*) orphan_count FROM purchase_return_item c LEFT JOIN inbound_item p ON p.id=c.inbound_item_id WHERE c.inbound_item_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'purchase_return_item.batch_id -> inventory_batch.id' relation_name, count(*) orphan_count FROM purchase_return_item c LEFT JOIN inventory_batch p ON p.id=c.batch_id WHERE c.batch_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'sales_return_item.sales_return_id -> sales_return_order.id' relation_name, count(*) orphan_count FROM sales_return_item c LEFT JOIN sales_return_order p ON p.id=c.sales_return_id WHERE c.sales_return_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'sales_return_item.outbound_item_id -> outbound_item.id' relation_name, count(*) orphan_count FROM sales_return_item c LEFT JOIN outbound_item p ON p.id=c.outbound_item_id WHERE c.outbound_item_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'sales_return_item.inventory_batch_id -> inventory_batch.id' relation_name, count(*) orphan_count FROM sales_return_item c LEFT JOIN inventory_batch p ON p.id=c.inventory_batch_id WHERE c.inventory_batch_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'payment_allocation.payment_id -> payment.id' relation_name, count(*) orphan_count FROM payment_allocation c LEFT JOIN payment p ON p.id=c.payment_id WHERE c.payment_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'supplier_payment_allocation.supplier_payment_id -> supplier_payment.id' relation_name, count(*) orphan_count FROM supplier_payment_allocation c LEFT JOIN supplier_payment p ON p.id=c.supplier_payment_id WHERE c.supplier_payment_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'accounting_voucher_line.voucher_id -> accounting_voucher.id' relation_name, count(*) orphan_count FROM accounting_voucher_line c LEFT JOIN accounting_voucher p ON p.id=c.voucher_id WHERE c.voucher_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'accounting_voucher_line.account_id -> accounting_account.id' relation_name, count(*) orphan_count FROM accounting_voucher_line c LEFT JOIN accounting_account p ON p.id=c.account_id WHERE c.account_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'accounting_ledger_entry.voucher_id -> accounting_voucher.id' relation_name, count(*) orphan_count FROM accounting_ledger_entry c LEFT JOIN accounting_voucher p ON p.id=c.voucher_id WHERE c.voucher_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'accounting_ledger_entry.voucher_line_id -> accounting_voucher_line.id' relation_name, count(*) orphan_count FROM accounting_ledger_entry c LEFT JOIN accounting_voucher_line p ON p.id=c.voucher_line_id WHERE c.voucher_line_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'accounting_ledger_entry.account_id -> accounting_account.id' relation_name, count(*) orphan_count FROM accounting_ledger_entry c LEFT JOIN accounting_account p ON p.id=c.account_id WHERE c.account_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'tax_invoice_line.invoice_id -> tax_invoice.id' relation_name, count(*) orphan_count FROM tax_invoice_line c LEFT JOIN tax_invoice p ON p.id=c.invoice_id WHERE c.invoice_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'tax_invoice_line.tax_rate_rule_id -> tax_rate_rule.id' relation_name, count(*) orphan_count FROM tax_invoice_line c LEFT JOIN tax_rate_rule p ON p.id=c.tax_rate_rule_id WHERE c.tax_rate_rule_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'bank_reconciliation_match.session_id -> bank_reconciliation_session.id' relation_name, count(*) orphan_count FROM bank_reconciliation_match c LEFT JOIN bank_reconciliation_session p ON p.id=c.session_id WHERE c.session_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'bank_reconciliation_match.statement_line_id -> bank_statement_line.id' relation_name, count(*) orphan_count FROM bank_reconciliation_match c LEFT JOIN bank_statement_line p ON p.id=c.statement_line_id WHERE c.statement_line_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_non_trade_document.product_id -> product.id' relation_name, count(*) orphan_count FROM inventory_non_trade_document c LEFT JOIN product p ON p.id=c.product_id WHERE c.product_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_non_trade_document.warehouse_id -> warehouse.id' relation_name, count(*) orphan_count FROM inventory_non_trade_document c LEFT JOIN warehouse p ON p.id=c.warehouse_id WHERE c.warehouse_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_non_trade_document.batch_id -> inventory_batch.id' relation_name, count(*) orphan_count FROM inventory_non_trade_document c LEFT JOIN inventory_batch p ON p.id=c.batch_id WHERE c.batch_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'purchase_quote_option.purchase_order_id -> purchase_order.id' relation_name, count(*) orphan_count FROM purchase_quote_option c LEFT JOIN purchase_order p ON p.id=c.purchase_order_id WHERE c.purchase_order_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'purchase_quote_option.supplier_id -> partner.id' relation_name, count(*) orphan_count FROM purchase_quote_option c LEFT JOIN partner p ON p.id=c.supplier_id WHERE c.supplier_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'purchase_quote_option_item.quote_option_id -> purchase_quote_option.id' relation_name, count(*) orphan_count FROM purchase_quote_option_item c LEFT JOIN purchase_quote_option p ON p.id=c.quote_option_id WHERE c.quote_option_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'purchase_quote_option_item.product_id -> product.id' relation_name, count(*) orphan_count FROM purchase_quote_option_item c LEFT JOIN product p ON p.id=c.product_id WHERE c.product_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'sales_cost_allocation.treasury_document_id -> treasury_document.id' relation_name, count(*) orphan_count FROM sales_cost_allocation c LEFT JOIN treasury_document p ON p.id=c.treasury_document_id WHERE c.treasury_document_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'outbound_delivery.outbound_id -> outbound_order.id' relation_name, count(*) orphan_count FROM outbound_delivery c LEFT JOIN outbound_order p ON p.id=c.outbound_id WHERE c.outbound_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'outbound_delivery_event.delivery_id -> outbound_delivery.id' relation_name, count(*) orphan_count FROM outbound_delivery_event c LEFT JOIN outbound_delivery p ON p.id=c.delivery_id WHERE c.delivery_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'external_share_link.partner_id -> partner.id' relation_name, count(*) orphan_count FROM external_share_link c LEFT JOIN partner p ON p.id=c.partner_id WHERE c.partner_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_package_instance_usage.package_instance_id -> inventory_package_instance.id' relation_name, count(*) orphan_count FROM inventory_package_instance_usage c LEFT JOIN inventory_package_instance p ON p.id=c.package_instance_id WHERE c.package_instance_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_package_instance_usage.batch_id -> inventory_batch.id' relation_name, count(*) orphan_count FROM inventory_package_instance_usage c LEFT JOIN inventory_batch p ON p.id=c.batch_id WHERE c.batch_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_package_instance_usage.product_id -> product.id' relation_name, count(*) orphan_count FROM inventory_package_instance_usage c LEFT JOIN product p ON p.id=c.product_id WHERE c.product_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_non_trade_item.document_id -> inventory_non_trade_document.id' relation_name, count(*) orphan_count FROM inventory_non_trade_item c LEFT JOIN inventory_non_trade_document p ON p.id=c.document_id WHERE c.document_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_non_trade_item.product_id -> product.id' relation_name, count(*) orphan_count FROM inventory_non_trade_item c LEFT JOIN product p ON p.id=c.product_id WHERE c.product_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_non_trade_item.warehouse_id -> warehouse.id' relation_name, count(*) orphan_count FROM inventory_non_trade_item c LEFT JOIN warehouse p ON p.id=c.warehouse_id WHERE c.warehouse_id IS NOT NULL AND p.id IS NULL
UNION ALL
SELECT 'inventory_non_trade_item.batch_id -> inventory_batch.id' relation_name, count(*) orphan_count FROM inventory_non_trade_item c LEFT JOIN inventory_batch p ON p.id=c.batch_id WHERE c.batch_id IS NOT NULL AND p.id IS NULL;

-- V65-V68 packaging identity audit. Only reports ambiguity; never rewrites historical references.
SELECT 'inventory_non_trade_item.nonpositive_packaging_ref' check_name,count(*) problem_count
FROM inventory_non_trade_item WHERE packaging_ref_id<=0
UNION ALL
SELECT 'inventory_non_trade_item.duplicate_packaging_ref',count(*)
FROM (SELECT packaging_ref_id FROM inventory_non_trade_item GROUP BY packaging_ref_id HAVING count(*)>1) duplicates;
SELECT packaging_ref_id,count(*) item_count,group_concat(id ORDER BY id) item_ids,
       group_concat(document_id ORDER BY id) document_ids
FROM inventory_non_trade_item GROUP BY packaging_ref_id HAVING count(*)>1;

-- Separate legacy accounting source-chain audit: historical counts are not current evidence.
SELECT 'accounting_generation_result.business_posting_batch_id' relation_name, count(*) orphan_count
FROM accounting_generation_result r LEFT JOIN business_posting_batch b ON b.id=r.business_posting_batch_id
WHERE b.id IS NULL
UNION ALL
SELECT 'accounting_voucher.business_posting_batch_id',count(*)
FROM accounting_voucher v LEFT JOIN business_posting_batch b ON b.id=v.business_posting_batch_id
WHERE v.business_posting_batch_id IS NOT NULL AND b.id IS NULL
UNION ALL
SELECT 'accounting_generation_issue.business_posting_batch_id',count(*)
FROM accounting_generation_issue i LEFT JOIN business_posting_batch b ON b.id=i.business_posting_batch_id
WHERE b.id IS NULL;

SELECT r.id,r.entity_id,r.business_posting_batch_id,r.outcome,r.voucher_count,r.detail,r.processed_at
FROM accounting_generation_result r LEFT JOIN business_posting_batch b ON b.id=r.business_posting_batch_id
WHERE b.id IS NULL ORDER BY r.id;
SELECT v.id,v.entity_id,v.period_id,v.voucher_no,v.voucher_date,v.source_type,v.source_id,v.source_no,
       v.business_posting_batch_id,v.status,v.debit_total,v.credit_total,v.entry_hash,
       (SELECT count(*) FROM accounting_voucher_line l WHERE l.voucher_id=v.id) voucher_lines,
       (SELECT count(*) FROM accounting_ledger_entry l WHERE l.voucher_id=v.id) ledger_lines
FROM accounting_voucher v LEFT JOIN business_posting_batch b ON b.id=v.business_posting_batch_id
WHERE v.business_posting_batch_id IS NOT NULL AND b.id IS NULL ORDER BY v.id;
SELECT i.id,i.entity_id,i.business_posting_batch_id,i.issue_code,i.issue_message,i.status
FROM accounting_generation_issue i LEFT JOIN business_posting_batch b ON b.id=i.business_posting_batch_id
WHERE b.id IS NULL ORDER BY i.id;
ROLLBACK;
