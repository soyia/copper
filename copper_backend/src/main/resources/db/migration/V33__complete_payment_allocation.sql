-- ERP-01: complete ordinary receipt/payment allocation without merging the
-- existing customer-advance and supplier-prepayment business types.

ALTER TABLE payment
  ADD COLUMN fund_nature varchar(16) NOT NULL DEFAULT 'ADVANCE'
    COMMENT 'SETTLEMENT, ADVANCE' AFTER account_type,
  ADD COLUMN allocation_mode varchar(24) NOT NULL DEFAULT 'ADVANCE'
    COMMENT 'ADVANCE, LEGACY_SINGLE, MULTI' AFTER fund_nature,
  ADD COLUMN allocated_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount,
  ADD COLUMN unallocated_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER allocated_amount;

UPDATE payment p
SET p.fund_nature = CASE
      WHEN EXISTS (SELECT 1 FROM payment_allocation a WHERE a.payment_id=p.id) THEN 'SETTLEMENT'
      ELSE 'ADVANCE' END,
    p.allocation_mode = CASE
      WHEN EXISTS (SELECT 1 FROM payment_allocation a WHERE a.payment_id=p.id) THEN 'LEGACY_SINGLE'
      ELSE 'ADVANCE' END,
    p.allocated_amount = CASE
      WHEN EXISTS (SELECT 1 FROM payment_allocation a WHERE a.payment_id=p.id)
      THEN p.amount ELSE 0.00 END,
    p.unallocated_amount = 0.00;

ALTER TABLE payment
  ADD CONSTRAINT chk_payment_fund_nature CHECK (fund_nature IN ('SETTLEMENT', 'ADVANCE')),
  ADD CONSTRAINT chk_payment_allocation_mode CHECK (allocation_mode IN ('ADVANCE', 'LEGACY_SINGLE', 'MULTI')),
  ADD CONSTRAINT chk_payment_allocation_totals CHECK (
    (fund_nature='ADVANCE' AND allocated_amount=0 AND unallocated_amount=0) OR
    (fund_nature='SETTLEMENT' AND allocated_amount>=0 AND unallocated_amount>=0
      AND allocated_amount+unallocated_amount=amount)
  );

ALTER TABLE supplier_payment
  ADD COLUMN fund_nature varchar(16) NOT NULL DEFAULT 'ADVANCE'
    COMMENT 'SETTLEMENT, ADVANCE' AFTER account_type,
  ADD COLUMN allocation_mode varchar(24) NOT NULL DEFAULT 'ADVANCE'
    COMMENT 'ADVANCE, LEGACY_SINGLE, MULTI' AFTER fund_nature,
  ADD COLUMN allocated_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER amount,
  ADD COLUMN unallocated_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER allocated_amount;

UPDATE supplier_payment p
SET p.fund_nature = CASE
      WHEN EXISTS (SELECT 1 FROM supplier_payment_allocation a WHERE a.supplier_payment_id=p.id)
      THEN 'SETTLEMENT' ELSE 'ADVANCE' END,
    p.allocation_mode = CASE
      WHEN EXISTS (SELECT 1 FROM supplier_payment_allocation a WHERE a.supplier_payment_id=p.id)
      THEN 'LEGACY_SINGLE' ELSE 'ADVANCE' END,
    p.allocated_amount = CASE
      WHEN EXISTS (SELECT 1 FROM supplier_payment_allocation a WHERE a.supplier_payment_id=p.id)
      THEN p.amount ELSE 0.00 END,
    p.unallocated_amount = 0.00;

ALTER TABLE supplier_payment
  ADD CONSTRAINT chk_supplier_payment_fund_nature CHECK (fund_nature IN ('SETTLEMENT', 'ADVANCE')),
  ADD CONSTRAINT chk_supplier_payment_allocation_mode CHECK (allocation_mode IN ('ADVANCE', 'LEGACY_SINGLE', 'MULTI')),
  ADD CONSTRAINT chk_supplier_payment_allocation_totals CHECK (
    (fund_nature='ADVANCE' AND allocated_amount=0 AND unallocated_amount=0) OR
    (fund_nature='SETTLEMENT' AND allocated_amount>=0 AND unallocated_amount>=0
      AND allocated_amount+unallocated_amount=amount)
  );

ALTER TABLE payment_allocation
  DROP CHECK chk_payment_allocation_status,
  DROP CHECK chk_payment_allocation_capture_source,
  DROP INDEX uk_payment_allocation_target,
  ADD COLUMN release_reason varchar(500) NOT NULL DEFAULT '' AFTER capture_source,
  ADD COLUMN released_at timestamp NULL DEFAULT NULL AFTER release_reason,
  ADD KEY idx_payment_allocation_target (payment_id, target_type, target_id, status),
  ADD CONSTRAINT chk_payment_allocation_status CHECK (status IN ('ACTIVE', 'RELEASED', 'REVERSED')),
  ADD CONSTRAINT chk_payment_allocation_capture_source
    CHECK (capture_source IN ('CREATION', 'LEGACY_BACKFILL', 'REALLOCATION'));

ALTER TABLE supplier_payment_allocation
  DROP CHECK chk_supplier_payment_allocation_status,
  DROP CHECK chk_supplier_payment_allocation_capture_source,
  DROP INDEX uk_supplier_payment_allocation_target,
  ADD COLUMN release_reason varchar(500) NOT NULL DEFAULT '' AFTER capture_source,
  ADD COLUMN released_at timestamp NULL DEFAULT NULL AFTER release_reason,
  ADD KEY idx_supplier_payment_allocation_target
    (supplier_payment_id, target_type, target_id, status),
  ADD CONSTRAINT chk_supplier_payment_allocation_status CHECK (status IN ('ACTIVE', 'RELEASED', 'REVERSED')),
  ADD CONSTRAINT chk_supplier_payment_allocation_capture_source
    CHECK (capture_source IN ('CREATION', 'LEGACY_BACKFILL', 'REALLOCATION'));

CREATE TABLE payment_allocation_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  allocation_id bigint unsigned NOT NULL,
  payment_id bigint unsigned NOT NULL,
  action varchar(24) NOT NULL COMMENT 'APPLIED, RELEASED, HEAD_REVERSED',
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL,
  amount decimal(18,2) NOT NULL,
  business_date date NOT NULL,
  reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(100) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_payment_allocation_event_payment (payment_id, id),
  KEY idx_payment_allocation_event_allocation (allocation_id, id),
  CONSTRAINT chk_payment_allocation_event_action
    CHECK (action IN ('APPLIED', 'RELEASED', 'HEAD_REVERSED')),
  CONSTRAINT chk_payment_allocation_event_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='客户收款核销分配审计事件';

CREATE TABLE supplier_payment_allocation_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  allocation_id bigint unsigned NOT NULL,
  supplier_payment_id bigint unsigned NOT NULL,
  action varchar(24) NOT NULL COMMENT 'APPLIED, RELEASED, HEAD_REVERSED',
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL,
  amount decimal(18,2) NOT NULL,
  business_date date NOT NULL,
  reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(100) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_supplier_payment_allocation_event_payment (supplier_payment_id, id),
  KEY idx_supplier_payment_allocation_event_allocation (allocation_id, id),
  CONSTRAINT chk_supplier_payment_allocation_event_action
    CHECK (action IN ('APPLIED', 'RELEASED', 'HEAD_REVERSED')),
  CONSTRAINT chk_supplier_payment_allocation_event_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='供应商付款核销分配审计事件';

INSERT INTO payment_allocation_event
  (allocation_id,payment_id,action,from_status,to_status,amount,business_date,reason,
   operator_id,operator_name,created_at)
SELECT a.id,a.payment_id,
       CASE WHEN a.status='REVERSED' THEN 'HEAD_REVERSED' ELSE 'APPLIED' END,
       CASE WHEN a.status='REVERSED' THEN 'ACTIVE' ELSE '' END,a.status,a.allocated_amount,
       p.received_date,
       CASE WHEN a.status='REVERSED' THEN a.reversal_reason ELSE 'ERP-01 历史核销分配回填' END,
       0,'系统迁移',a.created_at
FROM payment_allocation a JOIN payment p ON p.id=a.payment_id;

INSERT INTO supplier_payment_allocation_event
  (allocation_id,supplier_payment_id,action,from_status,to_status,amount,business_date,reason,
   operator_id,operator_name,created_at)
SELECT a.id,a.supplier_payment_id,
       CASE WHEN a.status='REVERSED' THEN 'HEAD_REVERSED' ELSE 'APPLIED' END,
       CASE WHEN a.status='REVERSED' THEN 'ACTIVE' ELSE '' END,a.status,a.allocated_amount,
       p.paid_date,
       CASE WHEN a.status='REVERSED' THEN a.reversal_reason ELSE 'ERP-01 历史核销分配回填' END,
       0,'系统迁移',a.created_at
FROM supplier_payment_allocation a JOIN supplier_payment p ON p.id=a.supplier_payment_id;
