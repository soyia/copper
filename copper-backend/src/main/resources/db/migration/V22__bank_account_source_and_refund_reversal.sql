ALTER TABLE payment
  ADD COLUMN business_bank_account_id bigint unsigned NULL AFTER account_type,
  ADD KEY idx_payment_business_bank_account (business_bank_account_id);

ALTER TABLE supplier_payment
  ADD COLUMN business_bank_account_id bigint unsigned NULL AFTER account_type,
  ADD KEY idx_supplier_payment_business_bank_account (business_bank_account_id);

ALTER TABLE customer_advance_refund
  ADD COLUMN business_bank_account_id bigint unsigned NULL AFTER refund_method,
  ADD COLUMN status varchar(16) NOT NULL DEFAULT 'ACTIVE' AFTER refunded_date,
  ADD COLUMN reversal_no varchar(64) NOT NULL DEFAULT '' AFTER memo,
  ADD COLUMN reversal_reason varchar(500) NOT NULL DEFAULT '' AFTER reversal_no,
  ADD COLUMN reversed_at timestamp NULL DEFAULT NULL AFTER reversal_reason,
  ADD KEY idx_customer_advance_refund_bank (business_bank_account_id),
  ADD KEY idx_customer_advance_refund_status (status,refunded_date);

ALTER TABLE acceptance_bill_event
  ADD COLUMN business_bank_account_id bigint unsigned NULL AFTER fee_amount,
  ADD KEY idx_acceptance_event_business_bank (business_bank_account_id);

UPDATE payment p
JOIN (SELECT id FROM business_bank_account WHERE status='ENABLED' ORDER BY id LIMIT 1) b
SET p.business_bank_account_id=b.id
WHERE p.payment_method='BANK' AND p.business_bank_account_id IS NULL;

UPDATE supplier_payment p
JOIN (SELECT id FROM business_bank_account WHERE status='ENABLED' ORDER BY id LIMIT 1) b
SET p.business_bank_account_id=b.id
WHERE p.payment_method='BANK' AND p.business_bank_account_id IS NULL;

UPDATE customer_advance_refund r
JOIN (SELECT id FROM business_bank_account WHERE status='ENABLED' ORDER BY id LIMIT 1) b
SET r.business_bank_account_id=b.id
WHERE r.refund_method='BANK' AND r.business_bank_account_id IS NULL;

UPDATE acceptance_bill_event e
JOIN (SELECT id FROM business_bank_account WHERE status='ENABLED' ORDER BY id LIMIT 1) b
SET e.business_bank_account_id=b.id
WHERE e.cash_amount<>0 AND e.business_bank_account_id IS NULL;

ALTER TABLE payment
  ADD CONSTRAINT fk_payment_business_bank_account
    FOREIGN KEY (business_bank_account_id) REFERENCES business_bank_account(id);

ALTER TABLE supplier_payment
  ADD CONSTRAINT fk_supplier_payment_business_bank_account
    FOREIGN KEY (business_bank_account_id) REFERENCES business_bank_account(id);

ALTER TABLE customer_advance_refund
  ADD CONSTRAINT fk_customer_advance_refund_bank
    FOREIGN KEY (business_bank_account_id) REFERENCES business_bank_account(id);

ALTER TABLE acceptance_bill_event
  ADD CONSTRAINT fk_acceptance_event_business_bank
    FOREIGN KEY (business_bank_account_id) REFERENCES business_bank_account(id);

INSERT INTO business_posting_rule_version
  (rule_code,rule_name,source_type,posting_action,version_no,effective_from,rule_json,status,published_at)
SELECT 'CUSTOMER_ADVANCE_REFUND_POST','客户预收款退款过账','CUSTOMER_ADVANCE_REFUND','POST',1,
       '2026-01-01','{"facts":["CUSTOMER_ADVANCE_REFUND"],"trigger":"REFUND"}','ENABLED',CURRENT_TIMESTAMP
WHERE NOT EXISTS (
  SELECT 1 FROM business_posting_rule_version
  WHERE source_type='CUSTOMER_ADVANCE_REFUND' AND posting_action='POST' AND version_no=1
);

INSERT INTO business_posting_rule_version
  (rule_code,rule_name,source_type,posting_action,version_no,effective_from,rule_json,status,published_at)
SELECT 'CUSTOMER_ADVANCE_REFUND_REVERSE','客户预收款退款冲正','CUSTOMER_ADVANCE_REFUND','REVERSE',1,
       '2026-01-01','{"reverseOf":"CUSTOMER_ADVANCE_REFUND_POST"}','ENABLED',CURRENT_TIMESTAMP
WHERE NOT EXISTS (
  SELECT 1 FROM business_posting_rule_version
  WHERE source_type='CUSTOMER_ADVANCE_REFUND' AND posting_action='REVERSE' AND version_no=1
);

INSERT INTO accounting_rule_version
  (entity_id,rule_code,rule_name,source_type,posting_action,fact_type,fact_direction,
   debit_account_id,credit_account_id,version_no,effective_from,status,change_reason,published_at)
SELECT e.id,'CUSTOMER_ADVANCE_REFUND','客户预收款退款','CUSTOMER_ADVANCE_REFUND','POST',
       'CUSTOMER_ADVANCE_REFUND','DECREASE',da.id,ca.id,1,'2026-01-01','ENABLED',
       '客户预收负债减少并形成实际资金流出',CURRENT_TIMESTAMP
FROM accounting_entity e
JOIN accounting_account da ON da.entity_id=e.id AND da.account_code='2203'
JOIN accounting_account ca ON ca.entity_id=e.id AND ca.account_code='1002'
WHERE e.entity_code='DEFAULT'
  AND NOT EXISTS (
    SELECT 1 FROM accounting_rule_version r
    WHERE r.entity_id=e.id AND r.rule_code='CUSTOMER_ADVANCE_REFUND' AND r.version_no=1
  );
