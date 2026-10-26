-- V67 first-stage referential-integrity baseline for the highest-risk ERP facts.
--
-- Validate every selected relation before the first ALTER TABLE. A legacy
-- orphan therefore blocks the migration before any foreign key is installed.
-- All actions are RESTRICT: business, inventory and accounting history must
-- never disappear through a database cascade.
--
-- Optional columns that use 0 instead of NULL and polymorphic source/ref/target
-- columns are deliberately excluded until their semantics are normalized.

DROP PROCEDURE IF EXISTS v67_assert_no_orphans;

DELIMITER //
CREATE PROCEDURE v67_assert_no_orphans(
    IN p_child_table varchar(64),
    IN p_child_column varchar(64),
    IN p_parent_table varchar(64)
)
BEGIN
    DECLARE v_message varchar(128);

    IF p_child_table NOT REGEXP '^[a-z0-9_]+$'
       OR p_child_column NOT REGEXP '^[a-z0-9_]+$'
       OR p_parent_table NOT REGEXP '^[a-z0-9_]+$' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V67_INVALID_RELATION_IDENTIFIER';
    END IF;

    SET @v67_orphan_count = 0;
    SET @v67_sql = CONCAT(
        'SELECT COUNT(*) INTO @v67_orphan_count FROM `',
        p_child_table,
        '` c LEFT JOIN `',
        p_parent_table,
        '` p ON p.id = c.`',
        p_child_column,
        '` WHERE c.`',
        p_child_column,
        '` IS NOT NULL AND p.id IS NULL'
    );
    PREPARE v67_statement FROM @v67_sql;
    EXECUTE v67_statement;
    DEALLOCATE PREPARE v67_statement;

    IF @v67_orphan_count > 0 THEN
        SET v_message = CONCAT(
            'V67_ORPHAN_BLOCK:',
            p_child_table,
            '.',
            p_child_column,
            ':',
            @v67_orphan_count
        );
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = v_message;
    END IF;
END//
DELIMITER ;

CALL v67_assert_no_orphans('purchase_order_item', 'purchase_order_id', 'purchase_order');
CALL v67_assert_no_orphans('purchase_order_item', 'product_id', 'product');
CALL v67_assert_no_orphans('sales_order_item', 'sales_order_id', 'sales_order');
CALL v67_assert_no_orphans('sales_order_item', 'product_id', 'product');
CALL v67_assert_no_orphans('inbound_item', 'inbound_id', 'inbound_order');
CALL v67_assert_no_orphans('inbound_item', 'product_id', 'product');
CALL v67_assert_no_orphans('outbound_item', 'outbound_id', 'outbound_order');
CALL v67_assert_no_orphans('outbound_item', 'product_id', 'product');
CALL v67_assert_no_orphans('inventory_batch', 'product_id', 'product');
CALL v67_assert_no_orphans('inventory_batch', 'warehouse_id', 'warehouse');
CALL v67_assert_no_orphans('stock_balance', 'product_id', 'product');
CALL v67_assert_no_orphans('stock_balance', 'warehouse_id', 'warehouse');
CALL v67_assert_no_orphans('inventory_movement', 'product_id', 'product');
CALL v67_assert_no_orphans('inventory_movement', 'warehouse_id', 'warehouse');
CALL v67_assert_no_orphans('inventory_movement', 'batch_id', 'inventory_batch');
CALL v67_assert_no_orphans('inventory_transfer_item', 'transfer_id', 'inventory_transfer_order');
CALL v67_assert_no_orphans('inventory_transfer_item', 'source_batch_id', 'inventory_batch');
CALL v67_assert_no_orphans('inventory_stocktake_item', 'stocktake_id', 'inventory_stocktake_order');
CALL v67_assert_no_orphans('inventory_stocktake_item', 'batch_id', 'inventory_batch');
CALL v67_assert_no_orphans('purchase_return_item', 'return_id', 'purchase_return_order');
CALL v67_assert_no_orphans('purchase_return_item', 'inbound_item_id', 'inbound_item');
CALL v67_assert_no_orphans('purchase_return_item', 'batch_id', 'inventory_batch');
CALL v67_assert_no_orphans('sales_return_item', 'sales_return_id', 'sales_return_order');
CALL v67_assert_no_orphans('sales_return_item', 'outbound_item_id', 'outbound_item');
CALL v67_assert_no_orphans('sales_return_item', 'inventory_batch_id', 'inventory_batch');
CALL v67_assert_no_orphans('payment_allocation', 'payment_id', 'payment');
CALL v67_assert_no_orphans('supplier_payment_allocation', 'supplier_payment_id', 'supplier_payment');
CALL v67_assert_no_orphans('accounting_voucher_line', 'voucher_id', 'accounting_voucher');
CALL v67_assert_no_orphans('accounting_voucher_line', 'account_id', 'accounting_account');
CALL v67_assert_no_orphans('accounting_ledger_entry', 'voucher_id', 'accounting_voucher');
CALL v67_assert_no_orphans('accounting_ledger_entry', 'voucher_line_id', 'accounting_voucher_line');
CALL v67_assert_no_orphans('accounting_ledger_entry', 'account_id', 'accounting_account');
CALL v67_assert_no_orphans('tax_invoice_line', 'invoice_id', 'tax_invoice');
CALL v67_assert_no_orphans('tax_invoice_line', 'tax_rate_rule_id', 'tax_rate_rule');
CALL v67_assert_no_orphans('bank_reconciliation_match', 'session_id', 'bank_reconciliation_session');
CALL v67_assert_no_orphans('bank_reconciliation_match', 'statement_line_id', 'bank_statement_line');
CALL v67_assert_no_orphans('inventory_non_trade_document', 'product_id', 'product');
CALL v67_assert_no_orphans('inventory_non_trade_document', 'warehouse_id', 'warehouse');
CALL v67_assert_no_orphans('inventory_non_trade_document', 'batch_id', 'inventory_batch');
CALL v67_assert_no_orphans('purchase_quote_option', 'purchase_order_id', 'purchase_order');
CALL v67_assert_no_orphans('purchase_quote_option', 'supplier_id', 'partner');
CALL v67_assert_no_orphans('purchase_quote_option_item', 'quote_option_id', 'purchase_quote_option');
CALL v67_assert_no_orphans('purchase_quote_option_item', 'product_id', 'product');
CALL v67_assert_no_orphans('sales_cost_allocation', 'treasury_document_id', 'treasury_document');
CALL v67_assert_no_orphans('outbound_delivery', 'outbound_id', 'outbound_order');
CALL v67_assert_no_orphans('outbound_delivery_event', 'delivery_id', 'outbound_delivery');
CALL v67_assert_no_orphans('external_share_link', 'partner_id', 'partner');

CALL v67_assert_no_orphans('inventory_package_instance_usage', 'package_instance_id', 'inventory_package_instance');
CALL v67_assert_no_orphans('inventory_package_instance_usage', 'batch_id', 'inventory_batch');
CALL v67_assert_no_orphans('inventory_package_instance_usage', 'product_id', 'product');
CALL v67_assert_no_orphans('inventory_non_trade_item', 'document_id', 'inventory_non_trade_document');
CALL v67_assert_no_orphans('inventory_non_trade_item', 'product_id', 'product');
CALL v67_assert_no_orphans('inventory_non_trade_item', 'warehouse_id', 'warehouse');
CALL v67_assert_no_orphans('inventory_non_trade_item', 'batch_id', 'inventory_batch');

DROP PROCEDURE v67_assert_no_orphans;

ALTER TABLE purchase_order_item
  ADD CONSTRAINT fk_purchase_order_item_order
    FOREIGN KEY (purchase_order_id) REFERENCES purchase_order(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_purchase_order_item_product
    FOREIGN KEY (product_id) REFERENCES product(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE sales_order_item
  ADD CONSTRAINT fk_sales_order_item_order
    FOREIGN KEY (sales_order_id) REFERENCES sales_order(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_sales_order_item_product
    FOREIGN KEY (product_id) REFERENCES product(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE inbound_item
  ADD CONSTRAINT fk_inbound_item_order
    FOREIGN KEY (inbound_id) REFERENCES inbound_order(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_inbound_item_product
    FOREIGN KEY (product_id) REFERENCES product(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE outbound_item
  ADD CONSTRAINT fk_outbound_item_order
    FOREIGN KEY (outbound_id) REFERENCES outbound_order(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_outbound_item_product
    FOREIGN KEY (product_id) REFERENCES product(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE inventory_batch
  ADD CONSTRAINT fk_inventory_batch_product
    FOREIGN KEY (product_id) REFERENCES product(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_inventory_batch_warehouse
    FOREIGN KEY (warehouse_id) REFERENCES warehouse(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE stock_balance
  ADD CONSTRAINT fk_stock_balance_product
    FOREIGN KEY (product_id) REFERENCES product(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_stock_balance_warehouse
    FOREIGN KEY (warehouse_id) REFERENCES warehouse(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE inventory_movement
  ADD CONSTRAINT fk_inventory_movement_product
    FOREIGN KEY (product_id) REFERENCES product(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_inventory_movement_warehouse
    FOREIGN KEY (warehouse_id) REFERENCES warehouse(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_inventory_movement_batch
    FOREIGN KEY (batch_id) REFERENCES inventory_batch(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE inventory_transfer_item
  ADD CONSTRAINT fk_inventory_transfer_item_order
    FOREIGN KEY (transfer_id) REFERENCES inventory_transfer_order(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_inventory_transfer_item_batch
    FOREIGN KEY (source_batch_id) REFERENCES inventory_batch(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE inventory_stocktake_item
  ADD CONSTRAINT fk_inventory_stocktake_item_order
    FOREIGN KEY (stocktake_id) REFERENCES inventory_stocktake_order(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_inventory_stocktake_item_batch
    FOREIGN KEY (batch_id) REFERENCES inventory_batch(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE purchase_return_item
  ADD CONSTRAINT fk_purchase_return_item_order
    FOREIGN KEY (return_id) REFERENCES purchase_return_order(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_purchase_return_item_inbound
    FOREIGN KEY (inbound_item_id) REFERENCES inbound_item(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_purchase_return_item_batch
    FOREIGN KEY (batch_id) REFERENCES inventory_batch(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE sales_return_item
  ADD CONSTRAINT fk_sales_return_item_order
    FOREIGN KEY (sales_return_id) REFERENCES sales_return_order(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_sales_return_item_outbound
    FOREIGN KEY (outbound_item_id) REFERENCES outbound_item(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_sales_return_item_batch
    FOREIGN KEY (inventory_batch_id) REFERENCES inventory_batch(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE payment_allocation
  ADD CONSTRAINT fk_payment_allocation_payment
    FOREIGN KEY (payment_id) REFERENCES payment(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE supplier_payment_allocation
  ADD CONSTRAINT fk_supplier_allocation_payment
    FOREIGN KEY (supplier_payment_id) REFERENCES supplier_payment(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE accounting_voucher_line
  ADD CONSTRAINT fk_accounting_voucher_line_voucher
    FOREIGN KEY (voucher_id) REFERENCES accounting_voucher(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_accounting_voucher_line_account
    FOREIGN KEY (account_id) REFERENCES accounting_account(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE accounting_ledger_entry
  ADD CONSTRAINT fk_accounting_ledger_voucher
    FOREIGN KEY (voucher_id) REFERENCES accounting_voucher(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_accounting_ledger_voucher_line
    FOREIGN KEY (voucher_line_id) REFERENCES accounting_voucher_line(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_accounting_ledger_account
    FOREIGN KEY (account_id) REFERENCES accounting_account(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE tax_invoice_line
  ADD CONSTRAINT fk_tax_invoice_line_invoice
    FOREIGN KEY (invoice_id) REFERENCES tax_invoice(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_tax_invoice_line_rate
    FOREIGN KEY (tax_rate_rule_id) REFERENCES tax_rate_rule(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE bank_reconciliation_match
  ADD CONSTRAINT fk_bank_reconciliation_match_session
    FOREIGN KEY (session_id) REFERENCES bank_reconciliation_session(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_bank_reconciliation_match_statement
    FOREIGN KEY (statement_line_id) REFERENCES bank_statement_line(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE inventory_non_trade_document
  ADD CONSTRAINT fk_inventory_non_trade_product
    FOREIGN KEY (product_id) REFERENCES product(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_inventory_non_trade_warehouse
    FOREIGN KEY (warehouse_id) REFERENCES warehouse(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_inventory_non_trade_batch
    FOREIGN KEY (batch_id) REFERENCES inventory_batch(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE purchase_quote_option
  ADD CONSTRAINT fk_purchase_quote_order
    FOREIGN KEY (purchase_order_id) REFERENCES purchase_order(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_purchase_quote_supplier
    FOREIGN KEY (supplier_id) REFERENCES partner(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE purchase_quote_option_item
  ADD CONSTRAINT fk_purchase_quote_item_option
    FOREIGN KEY (quote_option_id) REFERENCES purchase_quote_option(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_purchase_quote_item_product
    FOREIGN KEY (product_id) REFERENCES product(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE sales_cost_allocation
  ADD CONSTRAINT fk_sales_cost_treasury_document
    FOREIGN KEY (treasury_document_id) REFERENCES treasury_document(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE outbound_delivery
  ADD CONSTRAINT fk_outbound_delivery_outbound
    FOREIGN KEY (outbound_id) REFERENCES outbound_order(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE outbound_delivery_event
  ADD CONSTRAINT fk_outbound_delivery_event_delivery
    FOREIGN KEY (delivery_id) REFERENCES outbound_delivery(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE external_share_link
  ADD CONSTRAINT fk_external_share_partner
    FOREIGN KEY (partner_id) REFERENCES partner(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE inventory_package_instance_usage
  ADD CONSTRAINT fk_inventory_package_usage_instance
    FOREIGN KEY (package_instance_id) REFERENCES inventory_package_instance(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE inventory_package_instance_usage
  ADD CONSTRAINT fk_inventory_package_usage_batch
    FOREIGN KEY (batch_id) REFERENCES inventory_batch(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE inventory_package_instance_usage
  ADD CONSTRAINT fk_inventory_package_usage_product
    FOREIGN KEY (product_id) REFERENCES product(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE inventory_non_trade_item
  ADD CONSTRAINT fk_inventory_non_trade_item_document
    FOREIGN KEY (document_id) REFERENCES inventory_non_trade_document(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE inventory_non_trade_item
  ADD CONSTRAINT fk_inventory_non_trade_item_product
    FOREIGN KEY (product_id) REFERENCES product(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE inventory_non_trade_item
  ADD CONSTRAINT fk_inventory_non_trade_item_warehouse
    FOREIGN KEY (warehouse_id) REFERENCES warehouse(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE inventory_non_trade_item
  ADD CONSTRAINT fk_inventory_non_trade_item_batch
    FOREIGN KEY (batch_id) REFERENCES inventory_batch(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;
