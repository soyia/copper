ALTER TABLE purchase_order
  ADD COLUMN price_includes_tax tinyint unsigned NOT NULL DEFAULT 1 AFTER source_note,
  ADD COLUMN tax_rate_summary varchar(255) NOT NULL DEFAULT '0.0000%' AFTER price_includes_tax,
  ADD COLUMN tax_rule_snapshot varchar(1000) NOT NULL DEFAULT 'LEGACY_GROSS_AMOUNT_V1' AFTER tax_rate_summary,
  ADD COLUMN amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00 AFTER adjustment_amount,
  ADD COLUMN tax_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount_excluding_tax,
  ADD COLUMN tax_rounding_difference decimal(18,2) NOT NULL DEFAULT 0.00 AFTER tax_amount;

ALTER TABLE purchase_order_item
  ADD COLUMN tax_rate decimal(7,4) NOT NULL DEFAULT 0.0000 AFTER unit_price,
  ADD COLUMN unit_price_excluding_tax decimal(18,6) NOT NULL DEFAULT 0.000000 AFTER tax_rate,
  ADD COLUMN amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount,
  ADD COLUMN tax_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount_excluding_tax,
  ADD COLUMN settlement_total_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER tax_amount,
  ADD COLUMN tax_rounding_difference decimal(18,2) NOT NULL DEFAULT 0.00 AFTER settlement_total_amount;

ALTER TABLE sales_order
  ADD COLUMN price_includes_tax tinyint unsigned NOT NULL DEFAULT 1 AFTER delivery_plan_date,
  ADD COLUMN tax_rate_summary varchar(255) NOT NULL DEFAULT '0.0000%' AFTER price_includes_tax,
  ADD COLUMN tax_rule_snapshot varchar(1000) NOT NULL DEFAULT 'LEGACY_GROSS_AMOUNT_V1' AFTER tax_rate_summary,
  ADD COLUMN amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00 AFTER adjustment_amount,
  ADD COLUMN tax_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount_excluding_tax,
  ADD COLUMN tax_rounding_difference decimal(18,2) NOT NULL DEFAULT 0.00 AFTER tax_amount;

ALTER TABLE sales_order_item
  ADD COLUMN tax_rate decimal(7,4) NOT NULL DEFAULT 0.0000 AFTER unit_price,
  ADD COLUMN unit_price_excluding_tax decimal(18,6) NOT NULL DEFAULT 0.000000 AFTER tax_rate,
  ADD COLUMN amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount,
  ADD COLUMN tax_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount_excluding_tax,
  ADD COLUMN settlement_total_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER tax_amount,
  ADD COLUMN tax_rounding_difference decimal(18,2) NOT NULL DEFAULT 0.00 AFTER settlement_total_amount;

ALTER TABLE inbound_order
  ADD COLUMN price_includes_tax tinyint unsigned NOT NULL DEFAULT 1 AFTER source_type,
  ADD COLUMN tax_rate_summary varchar(255) NOT NULL DEFAULT '0.0000%' AFTER price_includes_tax,
  ADD COLUMN tax_rule_snapshot varchar(1000) NOT NULL DEFAULT 'LEGACY_GROSS_AMOUNT_V1' AFTER tax_rate_summary,
  ADD COLUMN amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00 AFTER adjustment_amount,
  ADD COLUMN tax_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount_excluding_tax,
  ADD COLUMN tax_rounding_difference decimal(18,2) NOT NULL DEFAULT 0.00 AFTER tax_amount;

ALTER TABLE inbound_item
  ADD COLUMN price_includes_tax tinyint unsigned NOT NULL DEFAULT 1 AFTER unit_cost,
  ADD COLUMN tax_rate decimal(7,4) NOT NULL DEFAULT 0.0000 AFTER price_includes_tax,
  ADD COLUMN unit_price_excluding_tax decimal(18,6) NOT NULL DEFAULT 0.000000 AFTER tax_rate,
  ADD COLUMN amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount,
  ADD COLUMN tax_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount_excluding_tax,
  ADD COLUMN settlement_total_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER tax_amount,
  ADD COLUMN tax_rounding_difference decimal(18,2) NOT NULL DEFAULT 0.00 AFTER settlement_total_amount;

ALTER TABLE outbound_order
  ADD COLUMN price_includes_tax tinyint unsigned NOT NULL DEFAULT 1 AFTER source_type,
  ADD COLUMN tax_rate_summary varchar(255) NOT NULL DEFAULT '0.0000%' AFTER price_includes_tax,
  ADD COLUMN tax_rule_snapshot varchar(1000) NOT NULL DEFAULT 'LEGACY_GROSS_AMOUNT_V1' AFTER tax_rate_summary,
  ADD COLUMN amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00 AFTER adjustment_amount,
  ADD COLUMN tax_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount_excluding_tax,
  ADD COLUMN tax_rounding_difference decimal(18,2) NOT NULL DEFAULT 0.00 AFTER tax_amount;

ALTER TABLE outbound_item
  ADD COLUMN price_includes_tax tinyint unsigned NOT NULL DEFAULT 1 AFTER unit_price,
  ADD COLUMN tax_rate decimal(7,4) NOT NULL DEFAULT 0.0000 AFTER price_includes_tax,
  ADD COLUMN unit_price_excluding_tax decimal(18,6) NOT NULL DEFAULT 0.000000 AFTER tax_rate,
  ADD COLUMN amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount,
  ADD COLUMN tax_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount_excluding_tax,
  ADD COLUMN settlement_total_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER tax_amount,
  ADD COLUMN tax_rounding_difference decimal(18,2) NOT NULL DEFAULT 0.00 AFTER settlement_total_amount;

ALTER TABLE purchase_return_order
  ADD COLUMN price_includes_tax tinyint unsigned NOT NULL DEFAULT 1 AFTER business_date,
  ADD COLUMN tax_rate_summary varchar(255) NOT NULL DEFAULT '0.0000%' AFTER price_includes_tax,
  ADD COLUMN tax_rule_snapshot varchar(1000) NOT NULL DEFAULT 'LEGACY_SOURCE_PROPORTION_V1' AFTER tax_rate_summary,
  ADD COLUMN amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00 AFTER total_amount,
  ADD COLUMN tax_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount_excluding_tax,
  ADD COLUMN tax_rounding_difference decimal(18,2) NOT NULL DEFAULT 0.00 AFTER tax_amount;

ALTER TABLE purchase_return_item
  ADD COLUMN price_includes_tax tinyint unsigned NOT NULL DEFAULT 1 AFTER payable_unit_cost,
  ADD COLUMN tax_rate decimal(7,4) NOT NULL DEFAULT 0.0000 AFTER price_includes_tax,
  ADD COLUMN amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount,
  ADD COLUMN tax_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount_excluding_tax,
  ADD COLUMN tax_rounding_difference decimal(18,2) NOT NULL DEFAULT 0.00 AFTER tax_amount;

ALTER TABLE sales_return_order
  ADD COLUMN price_includes_tax tinyint unsigned NOT NULL DEFAULT 1 AFTER business_date,
  ADD COLUMN tax_rate_summary varchar(255) NOT NULL DEFAULT '0.0000%' AFTER price_includes_tax,
  ADD COLUMN tax_rule_snapshot varchar(1000) NOT NULL DEFAULT 'LEGACY_SOURCE_PROPORTION_V1' AFTER tax_rate_summary,
  ADD COLUMN amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00 AFTER total_amount,
  ADD COLUMN tax_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount_excluding_tax,
  ADD COLUMN tax_rounding_difference decimal(18,2) NOT NULL DEFAULT 0.00 AFTER tax_amount;

ALTER TABLE sales_return_item
  ADD COLUMN price_includes_tax tinyint unsigned NOT NULL DEFAULT 1 AFTER unit_price,
  ADD COLUMN tax_rate decimal(7,4) NOT NULL DEFAULT 0.0000 AFTER price_includes_tax,
  ADD COLUMN amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount,
  ADD COLUMN tax_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount_excluding_tax,
  ADD COLUMN tax_rounding_difference decimal(18,2) NOT NULL DEFAULT 0.00 AFTER tax_amount;

UPDATE purchase_order SET amount_excluding_tax=total_amount;
UPDATE purchase_order_item SET unit_price_excluding_tax=unit_price,amount_excluding_tax=amount,settlement_total_amount=amount;
UPDATE sales_order SET amount_excluding_tax=total_amount;
UPDATE sales_order_item SET unit_price_excluding_tax=unit_price,amount_excluding_tax=amount,settlement_total_amount=amount;
UPDATE inbound_order SET amount_excluding_tax=total_amount;
UPDATE inbound_item SET unit_price_excluding_tax=unit_cost,amount_excluding_tax=amount,settlement_total_amount=amount;
UPDATE outbound_order SET amount_excluding_tax=total_amount;
UPDATE outbound_item SET unit_price_excluding_tax=unit_price,amount_excluding_tax=amount,settlement_total_amount=amount;
UPDATE purchase_return_order SET amount_excluding_tax=total_amount;
UPDATE purchase_return_item SET amount_excluding_tax=amount;
UPDATE sales_return_order SET amount_excluding_tax=total_amount;
UPDATE sales_return_item SET amount_excluding_tax=amount;
