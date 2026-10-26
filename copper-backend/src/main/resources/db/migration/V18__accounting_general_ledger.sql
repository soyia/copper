CREATE TABLE accounting_entity (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_code varchar(32) NOT NULL,
  entity_name varchar(255) NOT NULL,
  base_currency varchar(8) NOT NULL DEFAULT 'CNY',
  fiscal_year_start_month tinyint unsigned NOT NULL DEFAULT 1,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_accounting_entity_code (entity_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会计主体';

CREATE TABLE accounting_period (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  fiscal_year int unsigned NOT NULL,
  period_no tinyint unsigned NOT NULL,
  period_name varchar(32) NOT NULL,
  start_date date NOT NULL,
  end_date date NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN, CLOSED',
  close_reason varchar(500) NOT NULL DEFAULT '',
  closed_by bigint unsigned NOT NULL DEFAULT 0,
  closed_by_name varchar(128) NOT NULL DEFAULT '',
  closed_at timestamp NULL DEFAULT NULL,
  reopened_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_accounting_period (entity_id,fiscal_year,period_no),
  UNIQUE KEY uk_accounting_period_dates (entity_id,start_date,end_date),
  KEY idx_accounting_period_status (entity_id,status,start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会计期间；锁定不等同于完整期末结账';

CREATE TABLE accounting_account (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  account_code varchar(32) NOT NULL,
  account_name varchar(128) NOT NULL,
  category varchar(16) NOT NULL COMMENT 'ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE, COST',
  normal_direction varchar(8) NOT NULL COMMENT 'DEBIT, CREDIT',
  parent_id bigint unsigned NOT NULL DEFAULT 0,
  account_level tinyint unsigned NOT NULL DEFAULT 1,
  leaf tinyint unsigned NOT NULL DEFAULT 1,
  allow_manual tinyint unsigned NOT NULL DEFAULT 1,
  partner_auxiliary varchar(16) NOT NULL DEFAULT 'OPTIONAL' COMMENT 'NONE, OPTIONAL, REQUIRED',
  product_auxiliary varchar(16) NOT NULL DEFAULT 'NONE',
  warehouse_auxiliary varchar(16) NOT NULL DEFAULT 'NONE',
  effective_from date NOT NULL,
  effective_to date NULL DEFAULT NULL,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_accounting_account_code (entity_id,account_code),
  KEY idx_accounting_account_parent (entity_id,parent_id,account_code),
  KEY idx_accounting_account_status (entity_id,status,category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='版本边界内稳定的会计科目主档';

CREATE TABLE accounting_rule_version (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  rule_code varchar(96) NOT NULL,
  rule_name varchar(128) NOT NULL,
  source_type varchar(32) NOT NULL,
  posting_action varchar(32) NOT NULL,
  fact_type varchar(48) NOT NULL,
  fact_direction varchar(16) NOT NULL,
  debit_account_id bigint unsigned NOT NULL,
  credit_account_id bigint unsigned NOT NULL,
  version_no int unsigned NOT NULL,
  effective_from date NOT NULL,
  effective_to date NULL DEFAULT NULL,
  status varchar(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'DRAFT, ENABLED, DISABLED',
  change_reason varchar(500) NOT NULL DEFAULT '',
  published_by bigint unsigned NOT NULL DEFAULT 0,
  published_by_name varchar(128) NOT NULL DEFAULT '',
  published_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_accounting_rule_version (entity_id,rule_code,version_no),
  KEY idx_accounting_rule_lookup (entity_id,source_type,posting_action,fact_type,fact_direction,status,effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务事实到会计科目的版本化生成规则';

CREATE TABLE accounting_account_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  account_id bigint unsigned NOT NULL,
  action varchar(32) NOT NULL,
  change_summary varchar(1000) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_accounting_account_event (account_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会计科目主档变更审计';

CREATE TABLE accounting_voucher (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  period_id bigint unsigned NOT NULL,
  voucher_no varchar(64) NOT NULL,
  voucher_type varchar(16) NOT NULL COMMENT 'AUTO, MANUAL, REVERSAL',
  voucher_date date NOT NULL,
  summary varchar(500) NOT NULL,
  source_type varchar(32) NOT NULL DEFAULT '',
  source_id bigint unsigned NOT NULL DEFAULT 0,
  source_no varchar(64) NOT NULL DEFAULT '',
  business_posting_batch_id bigint unsigned NULL DEFAULT NULL,
  original_voucher_id bigint unsigned NOT NULL DEFAULT 0,
  reversal_voucher_id bigint unsigned NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, REVIEWED, POSTED, REVERSED, CANCELLED',
  debit_total decimal(18,2) NOT NULL DEFAULT 0,
  credit_total decimal(18,2) NOT NULL DEFAULT 0,
  entry_hash char(64) NOT NULL DEFAULT '',
  maker_id bigint unsigned NOT NULL DEFAULT 0,
  maker_name varchar(128) NOT NULL DEFAULT '',
  reviewer_id bigint unsigned NOT NULL DEFAULT 0,
  reviewer_name varchar(128) NOT NULL DEFAULT '',
  poster_id bigint unsigned NOT NULL DEFAULT 0,
  poster_name varchar(128) NOT NULL DEFAULT '',
  reviewed_at timestamp NULL DEFAULT NULL,
  posted_at timestamp NULL DEFAULT NULL,
  reversed_at timestamp NULL DEFAULT NULL,
  cancel_reason varchar(500) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_accounting_voucher_no (entity_id,voucher_no),
  KEY idx_accounting_voucher_period (entity_id,period_id,status,voucher_date),
  KEY idx_accounting_voucher_source (source_type,source_id),
  UNIQUE KEY uk_accounting_voucher_generation (business_posting_batch_id,original_voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='记账凭证；业务单据与会计凭证分层';

CREATE TABLE accounting_voucher_line (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  voucher_id bigint unsigned NOT NULL,
  line_no int unsigned NOT NULL,
  account_id bigint unsigned NOT NULL,
  account_code_snapshot varchar(32) NOT NULL,
  account_name_snapshot varchar(128) NOT NULL,
  summary varchar(500) NOT NULL,
  debit_amount decimal(18,2) NOT NULL DEFAULT 0,
  credit_amount decimal(18,2) NOT NULL DEFAULT 0,
  partner_id bigint unsigned NOT NULL DEFAULT 0,
  product_id bigint unsigned NOT NULL DEFAULT 0,
  warehouse_id bigint unsigned NOT NULL DEFAULT 0,
  source_posting_entry_id bigint unsigned NOT NULL DEFAULT 0,
  source_line_type varchar(32) NOT NULL DEFAULT '',
  source_line_id bigint unsigned NOT NULL DEFAULT 0,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_accounting_voucher_line (voucher_id,line_no),
  KEY idx_accounting_voucher_line_account (account_id,partner_id,product_id,warehouse_id),
  KEY idx_accounting_voucher_line_source (source_line_type,source_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='凭证分录及辅助核算快照';

CREATE TABLE accounting_voucher_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  voucher_id bigint unsigned NOT NULL,
  action varchar(32) NOT NULL,
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_accounting_voucher_event (voucher_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='凭证全生命周期审计';

CREATE TABLE accounting_ledger_entry (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  period_id bigint unsigned NOT NULL,
  voucher_id bigint unsigned NOT NULL,
  voucher_line_id bigint unsigned NOT NULL,
  voucher_no varchar(64) NOT NULL,
  voucher_date date NOT NULL,
  account_id bigint unsigned NOT NULL,
  account_code varchar(32) NOT NULL,
  account_name varchar(128) NOT NULL,
  summary varchar(500) NOT NULL,
  debit_amount decimal(18,2) NOT NULL DEFAULT 0,
  credit_amount decimal(18,2) NOT NULL DEFAULT 0,
  partner_id bigint unsigned NOT NULL DEFAULT 0,
  product_id bigint unsigned NOT NULL DEFAULT 0,
  warehouse_id bigint unsigned NOT NULL DEFAULT 0,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_accounting_ledger_voucher_line (voucher_line_id),
  KEY idx_accounting_ledger_account_date (entity_id,account_id,voucher_date,id),
  KEY idx_accounting_ledger_period (entity_id,period_id,account_id),
  KEY idx_accounting_ledger_partner (entity_id,partner_id,account_id,voucher_date),
  KEY idx_accounting_ledger_product (entity_id,product_id,account_id,voucher_date),
  KEY idx_accounting_ledger_warehouse (entity_id,warehouse_id,account_id,voucher_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='已记账不可变会计分录';

CREATE TABLE accounting_generation_issue (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  business_posting_batch_id bigint unsigned NOT NULL,
  issue_code varchar(48) NOT NULL,
  issue_message varchar(1000) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'OPEN',
  retry_count int unsigned NOT NULL DEFAULT 0,
  resolved_voucher_id bigint unsigned NOT NULL DEFAULT 0,
  last_attempt_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_accounting_generation_issue (entity_id,business_posting_batch_id),
  KEY idx_accounting_generation_issue_status (entity_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务过账自动制证失败的可重试问题';

CREATE TABLE accounting_generation_result (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  business_posting_batch_id bigint unsigned NOT NULL,
  outcome varchar(32) NOT NULL COMMENT 'VOUCHER_CREATED, SOURCE_VOUCHER_CANCELLED',
  voucher_count int unsigned NOT NULL DEFAULT 0,
  detail varchar(500) NOT NULL DEFAULT '',
  processed_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_accounting_generation_result (entity_id,business_posting_batch_id),
  KEY idx_accounting_generation_result_outcome (entity_id,outcome,processed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每个业务过账批次的会计处理完成标记';

CREATE TABLE accounting_period_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  period_id bigint unsigned NOT NULL,
  action varchar(32) NOT NULL,
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_accounting_period_event (period_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会计期间锁定与重开审计';

INSERT INTO accounting_entity (entity_code,entity_name,base_currency,fiscal_year_start_month,status)
VALUES ('DEFAULT','本企业','CNY',1,'ENABLED');

INSERT INTO accounting_period (entity_id,fiscal_year,period_no,period_name,start_date,end_date,status)
SELECT e.id,2026,m.period_no,concat('2026年',m.period_no,'月'),
       str_to_date(concat('2026-',lpad(m.period_no,2,'0'),'-01'),'%Y-%m-%d'),
       last_day(str_to_date(concat('2026-',lpad(m.period_no,2,'0'),'-01'),'%Y-%m-%d')),'OPEN'
FROM accounting_entity e CROSS JOIN (
  SELECT 1 period_no UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
  SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL
  SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
) m WHERE e.entity_code='DEFAULT';

INSERT INTO accounting_account
  (entity_id,account_code,account_name,category,normal_direction,allow_manual,
   partner_auxiliary,product_auxiliary,warehouse_auxiliary,effective_from,status)
SELECT e.id,a.code,a.name,a.category,a.direction,a.allow_manual,a.partner_aux,a.product_aux,a.warehouse_aux,'2026-01-01','ENABLED'
FROM accounting_entity e CROSS JOIN (
  SELECT '1001' code,'库存现金' name,'ASSET' category,'DEBIT' direction,1 allow_manual,'NONE' partner_aux,'NONE' product_aux,'NONE' warehouse_aux UNION ALL
  SELECT '1002','银行存款','ASSET','DEBIT',1,'NONE','NONE','NONE' UNION ALL
  SELECT '1122','应收账款','ASSET','DEBIT',0,'REQUIRED','NONE','NONE' UNION ALL
  SELECT '1123','预付账款','ASSET','DEBIT',0,'REQUIRED','NONE','NONE' UNION ALL
  SELECT '1231','其他应收款','ASSET','DEBIT',1,'OPTIONAL','NONE','NONE' UNION ALL
  SELECT '1405','库存商品','ASSET','DEBIT',0,'NONE','OPTIONAL','OPTIONAL' UNION ALL
  SELECT '1407','在途物资','ASSET','DEBIT',0,'NONE','OPTIONAL','OPTIONAL' UNION ALL
  SELECT '1901','待处理财产损溢','ASSET','DEBIT',0,'NONE','OPTIONAL','OPTIONAL' UNION ALL
  SELECT '2202','应付账款','LIABILITY','CREDIT',0,'REQUIRED','NONE','NONE' UNION ALL
  SELECT '2203','预收账款','LIABILITY','CREDIT',0,'REQUIRED','NONE','NONE' UNION ALL
  SELECT '2241','其他应付款','LIABILITY','CREDIT',1,'OPTIONAL','NONE','NONE' UNION ALL
  SELECT '224199','采购暂估及价差','LIABILITY','CREDIT',0,'OPTIONAL','OPTIONAL','OPTIONAL' UNION ALL
  SELECT '3001','实收资本','EQUITY','CREDIT',1,'NONE','NONE','NONE' UNION ALL
  SELECT '5001','主营业务收入','REVENUE','CREDIT',0,'OPTIONAL','OPTIONAL','NONE' UNION ALL
  SELECT '5051','其他业务收入','REVENUE','CREDIT',1,'OPTIONAL','NONE','NONE' UNION ALL
  SELECT '5401','主营业务成本','COST','DEBIT',0,'OPTIONAL','OPTIONAL','OPTIONAL' UNION ALL
  SELECT '5601','销售费用','EXPENSE','DEBIT',1,'OPTIONAL','NONE','NONE' UNION ALL
  SELECT '5602','管理费用','EXPENSE','DEBIT',1,'OPTIONAL','NONE','NONE' UNION ALL
  SELECT '5603','财务费用','EXPENSE','DEBIT',1,'OPTIONAL','NONE','NONE' UNION ALL
  SELECT '5711','营业外支出','EXPENSE','DEBIT',1,'OPTIONAL','NONE','NONE'
) a WHERE e.entity_code='DEFAULT';

INSERT INTO accounting_rule_version
  (entity_id,rule_code,rule_name,source_type,posting_action,fact_type,fact_direction,
   debit_account_id,credit_account_id,version_no,effective_from,status,change_reason,published_at)
SELECT e.id,r.rule_code,r.rule_name,r.source_type,r.posting_action,r.fact_type,r.fact_direction,
       da.id,ca.id,1,'2026-01-01','ENABLED','初始化商贸业务会计规则',CURRENT_TIMESTAMP
FROM accounting_entity e CROSS JOIN (
  SELECT 'INBOUND_INV' rule_code,'采购入库库存成本' rule_name,'INBOUND' source_type,'POST' posting_action,
         'INVENTORY_COST' fact_type,'INCREASE' fact_direction,'1405' debit_code,'224199' credit_code UNION ALL
  SELECT 'INBOUND_AP','采购入库形成应付','INBOUND','POST','PAYABLE','INCREASE','224199','2202' UNION ALL
  SELECT 'OUTBOUND_COGS','销售出库结转成本','OUTBOUND','SHIP','INVENTORY_COST','DECREASE','5401','1405' UNION ALL
  SELECT 'OUTBOUND_AR','销售出库形成应收收入','OUTBOUND','SETTLE','RECEIVABLE','INCREASE','1122','5001' UNION ALL
  SELECT 'SALES_RETURN_INV','销售退货恢复成本','SALES_RETURN','POST','INVENTORY_COST','INCREASE','1405','5401' UNION ALL
  SELECT 'SALES_RETURN_AR','销售退货冲减应收收入','SALES_RETURN','POST','RECEIVABLE','DECREASE','5001','1122' UNION ALL
  SELECT 'PURCHASE_RETURN_INV','采购退货冲减库存','PURCHASE_RETURN','POST','INVENTORY_COST','DECREASE','224199','1405' UNION ALL
  SELECT 'PURCHASE_RETURN_AP','采购退货冲减应付','PURCHASE_RETURN','POST','PAYABLE','DECREASE','2202','224199' UNION ALL
  SELECT 'PURCHASE_RETURN_PREPAY','采购退货形成预付','PURCHASE_RETURN','POST','SUPPLIER_PREPAY','INCREASE','1123','224199' UNION ALL
  SELECT 'TRANSFER_DISPATCH','调拨发出形成在途','INVENTORY_TRANSFER','DISPATCH','INVENTORY_ON_HAND','DECREASE','1407','1405' UNION ALL
  SELECT 'TRANSFER_RECEIVE','调拨收货转入库存','INVENTORY_TRANSFER','RECEIVE','INVENTORY_ON_HAND','INCREASE','1405','1407' UNION ALL
  SELECT 'STOCKTAKE_GAIN','盘盈增加库存','INVENTORY_STOCKTAKE','POST','INVENTORY_COST','INCREASE','1405','1901' UNION ALL
  SELECT 'STOCKTAKE_LOSS','盘亏减少库存','INVENTORY_STOCKTAKE','POST','INVENTORY_COST','DECREASE','1901','1405' UNION ALL
  SELECT 'COST_ADJUST_UP','库存成本调增','INVENTORY_COST_ADJUSTMENT','POST','INVENTORY_COST','INCREASE','1405','1901' UNION ALL
  SELECT 'COST_ADJUST_DOWN','库存成本调减','INVENTORY_COST_ADJUSTMENT','POST','INVENTORY_COST','DECREASE','1901','1405' UNION ALL
  SELECT 'RECEIPT_AR','客户收款核销应收','PAYMENT','POST','RECEIVABLE_SETTLEMENT','DECREASE','1002','1122' UNION ALL
  SELECT 'RECEIPT_ADVANCE','客户无单预收','PAYMENT','POST','CUSTOMER_ADVANCE','INCREASE','1002','2203' UNION ALL
  SELECT 'PAYMENT_AP','供应商付款核销应付','SUPPLIER_PAYMENT','POST','PAYABLE_SETTLEMENT','DECREASE','2202','1002' UNION ALL
  SELECT 'PAYMENT_PREPAY','供应商无单预付','SUPPLIER_PAYMENT','POST','SUPPLIER_PREPAY','INCREASE','1123','1002' UNION ALL
  SELECT 'AR_ADJUST','应收调整','RECEIVABLE_ADJUSTMENT','POST','RECEIVABLE_ADJUSTMENT','DECREASE','5711','1122'
) r
JOIN accounting_account da ON da.entity_id=e.id AND da.account_code=r.debit_code
JOIN accounting_account ca ON ca.entity_id=e.id AND ca.account_code=r.credit_code
WHERE e.entity_code='DEFAULT';

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('accounting.view','accounting','会计核算','PAGE','查看会计核算','查看凭证、科目、期间、账簿和试算平衡',81,'ENABLED'),
  ('accounting.manage','accounting','会计核算','ACTION','管理会计凭证','新增和修改暂存凭证、生成业务凭证',82,'ENABLED'),
  ('accounting.review','accounting','会计核算','ACTION','审核会计凭证','审核或退回会计凭证',83,'ENABLED'),
  ('accounting.post','accounting','会计核算','ACTION','记账及冲销','将已审核凭证记入总账并规范冲销',84,'ENABLED'),
  ('accounting.period.manage','accounting','会计核算','ACTION','管理会计期间','初始化年度、锁定和重新开放会计期间',85,'ENABLED'),
  ('accounting.rule.manage','accounting','会计核算','ACTION','管理会计规则','维护并发布业务事实到会计科目的版本化规则',86,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER') AND p.code LIKE 'accounting.%';

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('FINANCE','MANAGER') AND p.code IN
  ('accounting.view','accounting.manage','accounting.review','accounting.post','accounting.period.manage','accounting.rule.manage');
