-- ERP-03: freeze settlement terms on the business document. Historical aging must not
-- move when a partner's current master-data terms are edited later.

ALTER TABLE sales_order
  ADD COLUMN settlement_days_snapshot INT UNSIGNED NULL AFTER business_date,
  ADD COLUMN due_date DATE NULL AFTER settlement_days_snapshot;

ALTER TABLE outbound_order
  ADD COLUMN settlement_days_snapshot INT UNSIGNED NULL AFTER business_date,
  ADD COLUMN due_date DATE NULL AFTER settlement_days_snapshot;

ALTER TABLE purchase_order
  ADD COLUMN settlement_days_snapshot INT UNSIGNED NULL AFTER business_date,
  ADD COLUMN due_date DATE NULL AFTER settlement_days_snapshot;

ALTER TABLE inbound_order
  ADD COLUMN settlement_days_snapshot INT UNSIGNED NULL AFTER business_date,
  ADD COLUMN due_date DATE NULL AFTER settlement_days_snapshot;

UPDATE sales_order o
LEFT JOIN partner_customer_profile p ON p.partner_id=o.customer_id
SET o.settlement_days_snapshot=COALESCE(p.settlement_days,0),
    o.due_date=DATE_ADD(o.business_date,INTERVAL COALESCE(p.settlement_days,0) DAY);

UPDATE outbound_order o
LEFT JOIN partner_customer_profile p ON p.partner_id=o.customer_id
SET o.settlement_days_snapshot=COALESCE(p.settlement_days,0),
    o.due_date=DATE_ADD(o.business_date,INTERVAL COALESCE(p.settlement_days,0) DAY);

UPDATE purchase_order o
LEFT JOIN partner_supplier_profile p ON p.partner_id=o.supplier_id
SET o.settlement_days_snapshot=COALESCE(p.settlement_days,0),
    o.due_date=DATE_ADD(o.business_date,INTERVAL COALESCE(p.settlement_days,0) DAY);

UPDATE inbound_order o
LEFT JOIN partner_supplier_profile p ON p.partner_id=o.supplier_id
SET o.settlement_days_snapshot=COALESCE(p.settlement_days,0),
    o.due_date=DATE_ADD(o.business_date,INTERVAL COALESCE(p.settlement_days,0) DAY);

ALTER TABLE sales_order
  MODIFY settlement_days_snapshot INT UNSIGNED NOT NULL DEFAULT 0,
  MODIFY due_date DATE NOT NULL,
  ADD KEY idx_sales_order_due (due_date,status);

ALTER TABLE outbound_order
  MODIFY settlement_days_snapshot INT UNSIGNED NOT NULL DEFAULT 0,
  MODIFY due_date DATE NOT NULL,
  ADD KEY idx_outbound_due (due_date,status);

ALTER TABLE purchase_order
  MODIFY settlement_days_snapshot INT UNSIGNED NOT NULL DEFAULT 0,
  MODIFY due_date DATE NOT NULL,
  ADD KEY idx_purchase_due (due_date,status);

ALTER TABLE inbound_order
  MODIFY settlement_days_snapshot INT UNSIGNED NOT NULL DEFAULT 0,
  MODIFY due_date DATE NOT NULL,
  ADD KEY idx_inbound_due (due_date,status);
