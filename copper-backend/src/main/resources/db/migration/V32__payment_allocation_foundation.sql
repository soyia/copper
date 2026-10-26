-- ERP-01A: establish an auditable allocation line beneath each ordinary
-- customer receipt and supplier payment while preserving the current
-- single-document API and business behavior.

CREATE TABLE payment_allocation (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  allocation_no varchar(64) NOT NULL,
  payment_id bigint unsigned NOT NULL,
  customer_id bigint unsigned NOT NULL,
  target_type varchar(32) NOT NULL COMMENT 'SALES_ORDER, OUTBOUND',
  target_id bigint unsigned NOT NULL,
  allocated_amount decimal(18,2) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, REVERSED',
  capture_source varchar(32) NOT NULL DEFAULT 'CREATION' COMMENT 'CREATION, LEGACY_BACKFILL',
  reversal_reason varchar(500) NOT NULL DEFAULT '',
  reversed_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_allocation_no (allocation_no),
  UNIQUE KEY uk_payment_allocation_target (payment_id, target_type, target_id),
  KEY idx_payment_allocation_payment_status (payment_id, status),
  KEY idx_payment_allocation_customer_target (customer_id, target_type, target_id, status),
  CONSTRAINT chk_payment_allocation_amount CHECK (allocated_amount > 0),
  CONSTRAINT chk_payment_allocation_target_type CHECK (target_type IN ('SALES_ORDER', 'OUTBOUND')),
  CONSTRAINT chk_payment_allocation_status CHECK (status IN ('ACTIVE', 'REVERSED')),
  CONSTRAINT chk_payment_allocation_capture_source CHECK (capture_source IN ('CREATION', 'LEGACY_BACKFILL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户收款核销分配行';

CREATE TABLE supplier_payment_allocation (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  allocation_no varchar(64) NOT NULL,
  supplier_payment_id bigint unsigned NOT NULL,
  supplier_id bigint unsigned NOT NULL,
  target_type varchar(32) NOT NULL COMMENT 'PURCHASE_ORDER, INBOUND',
  target_id bigint unsigned NOT NULL,
  allocated_amount decimal(18,2) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, REVERSED',
  capture_source varchar(32) NOT NULL DEFAULT 'CREATION' COMMENT 'CREATION, LEGACY_BACKFILL',
  reversal_reason varchar(500) NOT NULL DEFAULT '',
  reversed_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_supplier_payment_allocation_no (allocation_no),
  UNIQUE KEY uk_supplier_payment_allocation_target (supplier_payment_id, target_type, target_id),
  KEY idx_supplier_payment_allocation_payment_status (supplier_payment_id, status),
  KEY idx_supplier_payment_allocation_supplier_target (supplier_id, target_type, target_id, status),
  CONSTRAINT chk_supplier_payment_allocation_amount CHECK (allocated_amount > 0),
  CONSTRAINT chk_supplier_payment_allocation_target_type CHECK (target_type IN ('PURCHASE_ORDER', 'INBOUND')),
  CONSTRAINT chk_supplier_payment_allocation_status CHECK (status IN ('ACTIVE', 'REVERSED')),
  CONSTRAINT chk_supplier_payment_allocation_capture_source CHECK (capture_source IN ('CREATION', 'LEGACY_BACKFILL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商付款核销分配行';

-- A legacy row can carry both the order id and its downstream shipment/receipt
-- id. The allocation target is the document the operator selected: the
-- outbound/inbound id wins when present; its parent order remains traceable
-- through the existing document relationship.
INSERT INTO payment_allocation
  (allocation_no, payment_id, customer_id, target_type, target_id, allocated_amount,
   status, capture_source, reversal_reason, reversed_at, created_at, updated_at)
SELECT concat(p.payment_no, '-01'), p.id, p.customer_id,
       CASE WHEN coalesce(p.outbound_id, 0) > 0 THEN 'OUTBOUND' ELSE 'SALES_ORDER' END,
       CASE WHEN coalesce(p.outbound_id, 0) > 0 THEN p.outbound_id ELSE p.sales_order_id END,
       p.amount,
       CASE WHEN p.verification_status = 'REVERSED' THEN 'REVERSED' ELSE 'ACTIVE' END,
       'LEGACY_BACKFILL', p.reversal_reason, p.reversed_at, p.created_at, p.updated_at
FROM payment p
WHERE coalesce(p.outbound_id, 0) > 0 OR coalesce(p.sales_order_id, 0) > 0;

INSERT INTO supplier_payment_allocation
  (allocation_no, supplier_payment_id, supplier_id, target_type, target_id, allocated_amount,
   status, capture_source, reversal_reason, reversed_at, created_at, updated_at)
SELECT concat(p.payment_no, '-01'), p.id, p.supplier_id,
       CASE WHEN coalesce(p.inbound_id, 0) > 0 THEN 'INBOUND' ELSE 'PURCHASE_ORDER' END,
       CASE WHEN coalesce(p.inbound_id, 0) > 0 THEN p.inbound_id ELSE p.purchase_order_id END,
       p.amount,
       CASE WHEN p.verification_status = 'REVERSED' THEN 'REVERSED' ELSE 'ACTIVE' END,
       'LEGACY_BACKFILL', p.reversal_reason, p.reversed_at, p.created_at, p.updated_at
FROM supplier_payment p
WHERE coalesce(p.inbound_id, 0) > 0 OR coalesce(p.purchase_order_id, 0) > 0;
