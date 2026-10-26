CREATE TABLE business_posting_rule_version (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  rule_code varchar(64) NOT NULL,
  rule_name varchar(128) NOT NULL,
  source_type varchar(32) NOT NULL,
  posting_action varchar(32) NOT NULL,
  version_no int unsigned NOT NULL,
  effective_from date NOT NULL,
  effective_to date NULL,
  rule_json longtext NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'DRAFT, ENABLED, DISABLED',
  published_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_business_posting_rule_version (rule_code,version_no),
  KEY idx_business_posting_rule_lookup (source_type,posting_action,status,effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='版本化业务过账规则，不等同于会计凭证规则';

CREATE TABLE business_posting_batch (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  posting_no varchar(64) NOT NULL,
  source_type varchar(32) NOT NULL,
  source_id bigint unsigned NOT NULL,
  source_no varchar(64) NOT NULL DEFAULT '',
  posting_action varchar(32) NOT NULL,
  source_revision_no int unsigned NOT NULL DEFAULT 1,
  rule_version_id bigint unsigned NOT NULL,
  rule_code varchar(64) NOT NULL,
  business_date date NOT NULL,
  partner_id bigint unsigned NOT NULL DEFAULT 0,
  original_batch_id bigint unsigned NOT NULL DEFAULT 0,
  reversal_batch_id bigint unsigned NOT NULL DEFAULT 0,
  idempotency_key varchar(160) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'POSTING' COMMENT 'POSTING, POSTED, REVERSED, FAILED',
  entry_hash char(64) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  legacy_imported tinyint unsigned NOT NULL DEFAULT 0,
  posted_at timestamp NULL DEFAULT NULL,
  reversed_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_business_posting_no (posting_no),
  UNIQUE KEY uk_business_posting_idempotency (idempotency_key),
  KEY idx_business_posting_source (source_type,source_id,id),
  KEY idx_business_posting_status_date (status,business_date),
  KEY idx_business_posting_original (original_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变业务过账批次；冲正通过新批次关联原批次';

CREATE TABLE business_posting_entry (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  posting_batch_id bigint unsigned NOT NULL,
  line_no int unsigned NOT NULL,
  fact_type varchar(48) NOT NULL COMMENT '承诺、库存、在途、应收、应付、结算及包装转换事实',
  direction varchar(16) NOT NULL COMMENT 'INCREASE, DECREASE',
  partner_id bigint unsigned NOT NULL DEFAULT 0,
  product_id bigint unsigned NOT NULL DEFAULT 0,
  warehouse_id bigint unsigned NOT NULL DEFAULT 0,
  inventory_batch_id bigint unsigned NOT NULL DEFAULT 0,
  source_line_type varchar(32) NOT NULL DEFAULT '',
  source_line_id bigint unsigned NOT NULL DEFAULT 0,
  quantity decimal(18,3) NOT NULL DEFAULT 0,
  quantity_unit varchar(64) NOT NULL DEFAULT '',
  amount decimal(18,2) NOT NULL DEFAULT 0,
  currency varchar(8) NOT NULL DEFAULT 'CNY',
  memo varchar(500) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_business_posting_entry_line (posting_batch_id,line_no),
  KEY idx_business_posting_entry_fact (fact_type,partner_id,product_id,warehouse_id),
  KEY idx_business_posting_entry_source_line (source_line_type,source_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务事实分录，为后续会计凭证提供稳定来源';

CREATE TABLE business_posting_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  posting_batch_id bigint unsigned NOT NULL,
  action varchar(32) NOT NULL,
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_business_posting_event_batch (posting_batch_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务过账状态审计事件';

INSERT INTO business_posting_rule_version
  (rule_code,rule_name,source_type,posting_action,version_no,effective_from,rule_json,status,published_at)
VALUES
  ('PURCHASE_COMMITMENT','采购订单承诺过账','PURCHASE_ORDER','COMMITMENT_POST',1,'2026-01-01','{"facts":["PURCHASE_COMMITMENT_QTY","PURCHASE_COMMITMENT_AMOUNT"],"trigger":"CONFIRM_OR_AMEND","balanceImpact":false}','ENABLED',CURRENT_TIMESTAMP),
  ('PURCHASE_COMMITMENT_REVERSAL','采购订单承诺冲回','PURCHASE_ORDER','COMMITMENT_REVERSE',1,'2026-01-01','{"reverseOf":"PURCHASE_COMMITMENT","facts":["PURCHASE_COMMITMENT_QTY","PURCHASE_COMMITMENT_AMOUNT"],"balanceImpact":false}','ENABLED',CURRENT_TIMESTAMP),
  ('SALES_COMMITMENT','销售订单承诺过账','SALES_ORDER','COMMITMENT_POST',1,'2026-01-01','{"facts":["SALES_COMMITMENT_QTY","SALES_COMMITMENT_AMOUNT"],"trigger":"CONFIRM_OR_AMEND","balanceImpact":false}','ENABLED',CURRENT_TIMESTAMP),
  ('SALES_COMMITMENT_REVERSAL','销售订单承诺冲回','SALES_ORDER','COMMITMENT_REVERSE',1,'2026-01-01','{"reverseOf":"SALES_COMMITMENT","facts":["SALES_COMMITMENT_QTY","SALES_COMMITMENT_AMOUNT"],"balanceImpact":false}','ENABLED',CURRENT_TIMESTAMP),
  ('INBOUND_POST','入库审核过账','INBOUND','POST',1,'2026-01-01','{"facts":["INVENTORY_ON_HAND","INVENTORY_COST","PAYABLE"],"trigger":"AUDIT_APPROVE"}','ENABLED',CURRENT_TIMESTAMP),
  ('INBOUND_REVERSE','入库冲正过账','INBOUND','REVERSE',1,'2026-01-01','{"reverseOf":"INBOUND_POST","facts":["INVENTORY_ON_HAND","INVENTORY_COST","PAYABLE"]}','ENABLED',CURRENT_TIMESTAMP),
  ('OUTBOUND_SHIP','销售出库过账','OUTBOUND','SHIP',1,'2026-01-01','{"facts":["INVENTORY_ON_HAND","INVENTORY_COST","SALES_FULFILLMENT"],"trigger":"CONFIRM"}','ENABLED',CURRENT_TIMESTAMP),
  ('OUTBOUND_SETTLE','销售出库结算过账','OUTBOUND','SETTLE',1,'2026-01-01','{"facts":["RECEIVABLE"],"trigger":"PRICE_SETTLED"}','ENABLED',CURRENT_TIMESTAMP),
  ('OUTBOUND_REVERSE','销售出库冲正过账','OUTBOUND','REVERSE',1,'2026-01-01','{"reverseOf":["OUTBOUND_SHIP","OUTBOUND_SETTLE"],"facts":["INVENTORY_ON_HAND","INVENTORY_COST","RECEIVABLE"]}','ENABLED',CURRENT_TIMESTAMP),
  ('SALES_RETURN_POST','销售退货过账','SALES_RETURN','POST',1,'2026-01-01','{"facts":["INVENTORY_ON_HAND","INVENTORY_COST","RECEIVABLE"],"trigger":"CONFIRM"}','ENABLED',CURRENT_TIMESTAMP),
  ('SALES_RETURN_REVERSE','销售退货冲正','SALES_RETURN','REVERSE',1,'2026-01-01','{"reverseOf":"SALES_RETURN_POST","facts":["INVENTORY_ON_HAND","INVENTORY_COST","RECEIVABLE"]}','ENABLED',CURRENT_TIMESTAMP),
  ('PURCHASE_RETURN_POST','采购退货过账','PURCHASE_RETURN','POST',1,'2026-01-01','{"facts":["INVENTORY_ON_HAND","INVENTORY_COST","PAYABLE"],"trigger":"CONFIRM"}','ENABLED',CURRENT_TIMESTAMP),
  ('PURCHASE_RETURN_REVERSE','采购退货冲正','PURCHASE_RETURN','REVERSE',1,'2026-01-01','{"reverseOf":"PURCHASE_RETURN_POST","facts":["INVENTORY_ON_HAND","INVENTORY_COST","PAYABLE"]}','ENABLED',CURRENT_TIMESTAMP),
  ('INVENTORY_TRANSFER_DISPATCH','库存调拨发出','INVENTORY_TRANSFER','DISPATCH',1,'2026-01-01','{"facts":["INVENTORY_ON_HAND","INVENTORY_IN_TRANSIT"]}','ENABLED',CURRENT_TIMESTAMP),
  ('INVENTORY_TRANSFER_RECEIVE','库存调拨收货','INVENTORY_TRANSFER','RECEIVE',1,'2026-01-01','{"facts":["INVENTORY_IN_TRANSIT","INVENTORY_ON_HAND"]}','ENABLED',CURRENT_TIMESTAMP),
  ('INVENTORY_TRANSFER_REVERSE','库存调拨冲正','INVENTORY_TRANSFER','REVERSE',1,'2026-01-01','{"reverseOf":["INVENTORY_TRANSFER_DISPATCH","INVENTORY_TRANSFER_RECEIVE"]}','ENABLED',CURRENT_TIMESTAMP),
  ('INVENTORY_STOCKTAKE_POST','库存盘点过账','INVENTORY_STOCKTAKE','POST',1,'2026-01-01','{"facts":["INVENTORY_ON_HAND","INVENTORY_COST"]}','ENABLED',CURRENT_TIMESTAMP),
  ('INVENTORY_STOCKTAKE_REVERSE','库存盘点冲正','INVENTORY_STOCKTAKE','REVERSE',1,'2026-01-01','{"reverseOf":"INVENTORY_STOCKTAKE_POST"}','ENABLED',CURRENT_TIMESTAMP),
  ('INVENTORY_COST_ADJUST_POST','库存成本调整过账','INVENTORY_COST_ADJUSTMENT','POST',1,'2026-01-01','{"facts":["INVENTORY_COST"]}','ENABLED',CURRENT_TIMESTAMP),
  ('INVENTORY_COST_ADJUST_REVERSE','库存成本调整冲正','INVENTORY_COST_ADJUSTMENT','REVERSE',1,'2026-01-01','{"reverseOf":"INVENTORY_COST_ADJUST_POST"}','ENABLED',CURRENT_TIMESTAMP),
  ('RECEIPT_POST','客户收款核销过账','PAYMENT','POST',1,'2026-01-01','{"facts":["RECEIVABLE_SETTLEMENT"]}','ENABLED',CURRENT_TIMESTAMP),
  ('RECEIPT_REVERSE','客户收款核销冲正','PAYMENT','REVERSE',1,'2026-01-01','{"reverseOf":"RECEIPT_POST","facts":["RECEIVABLE_SETTLEMENT"]}','ENABLED',CURRENT_TIMESTAMP),
  ('SUPPLIER_PAYMENT_POST','供应商付款核销过账','SUPPLIER_PAYMENT','POST',1,'2026-01-01','{"facts":["PAYABLE_SETTLEMENT"]}','ENABLED',CURRENT_TIMESTAMP),
  ('SUPPLIER_PAYMENT_REVERSE','供应商付款核销冲正','SUPPLIER_PAYMENT','REVERSE',1,'2026-01-01','{"reverseOf":"SUPPLIER_PAYMENT_POST","facts":["PAYABLE_SETTLEMENT"]}','ENABLED',CURRENT_TIMESTAMP),
  ('RECEIVABLE_ADJUST_POST','应收调整过账','RECEIVABLE_ADJUSTMENT','POST',1,'2026-01-01','{"facts":["RECEIVABLE_ADJUSTMENT"]}','ENABLED',CURRENT_TIMESTAMP),
  ('RECEIVABLE_ADJUST_REVERSE','应收调整冲正','RECEIVABLE_ADJUSTMENT','REVERSE',1,'2026-01-01','{"reverseOf":"RECEIVABLE_ADJUST_POST","facts":["RECEIVABLE_ADJUSTMENT"]}','ENABLED',CURRENT_TIMESTAMP);

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('posting.view','posting','业务过账','PAGE','查看业务过账','查看规则版本、过账批次、事实分录和勾稽结果',77,'ENABLED'),
  ('posting.manage','posting','业务过账','ACTION','管理业务过账','执行受控重试和处理失败过账；不得直接改余额',78,'ENABLED'),
  ('purchase.amend','purchase','采购管理','ACTION','变更已确认采购订单','通过版本化变更调整已确认采购承诺',79,'ENABLED'),
  ('sales.amend','sales','销售管理','ACTION','变更已确认销售订单','通过版本化变更调整已确认销售承诺',80,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER')
  AND p.code IN ('posting.view','posting.manage','purchase.amend','sales.amend');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE (r.code IN ('FINANCE','MANAGER') AND p.code='posting.view')
   OR (r.code IN ('PURCHASE','MANAGER') AND p.code='purchase.amend')
   OR (r.code IN ('SALES','MANAGER') AND p.code='sales.amend');
