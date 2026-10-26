ALTER TABLE accounting_period
  MODIFY COLUMN status varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN, CLOSED';

INSERT IGNORE INTO accounting_account
  (entity_id,account_code,account_name,category,normal_direction,parent_id,account_level,leaf,
   allow_manual,partner_auxiliary,product_auxiliary,warehouse_auxiliary,effective_from,status)
SELECT id,'1121','应收票据','ASSET','DEBIT',0,1,1,0,'NONE','NONE','NONE','2026-01-01','ENABLED'
FROM accounting_entity WHERE entity_code='DEFAULT';

INSERT IGNORE INTO accounting_account
  (entity_id,account_code,account_name,category,normal_direction,parent_id,account_level,leaf,
   allow_manual,partner_auxiliary,product_auxiliary,warehouse_auxiliary,effective_from,status)
SELECT id,'3103','本年利润','EQUITY','CREDIT',0,1,1,0,'NONE','NONE','NONE','2026-01-01','ENABLED'
FROM accounting_entity WHERE entity_code='DEFAULT';

CREATE TABLE financial_report_line (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  report_type varchar(24) NOT NULL COMMENT 'BALANCE_SHEET, INCOME_STATEMENT',
  line_code varchar(32) NOT NULL,
  line_name varchar(128) NOT NULL,
  line_order int unsigned NOT NULL,
  calculation_type varchar(24) NOT NULL DEFAULT 'ACCOUNT_SUM' COMMENT 'ACCOUNT_SUM, FORMULA',
  formula_code varchar(32) NOT NULL DEFAULT '',
  normal_direction varchar(8) NOT NULL DEFAULT 'DEBIT',
  effective_from date NOT NULL,
  effective_to date NULL DEFAULT NULL,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_financial_report_line (entity_id,report_type,line_code,effective_from),
  KEY idx_financial_report_line_order (entity_id,report_type,status,line_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='版本化财务报表项目';

CREATE TABLE financial_report_account_map (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  report_type varchar(24) NOT NULL,
  line_id bigint unsigned NOT NULL,
  account_id bigint unsigned NOT NULL,
  amount_sign decimal(8,4) NOT NULL DEFAULT 1.0000,
  effective_from date NOT NULL,
  effective_to date NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_financial_report_account_map (entity_id,report_type,account_id,effective_from),
  KEY idx_financial_report_map_line (line_id,account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='科目到报表项目的历史映射';

CREATE TABLE business_bank_account (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  account_code varchar(32) NOT NULL,
  account_name varchar(128) NOT NULL,
  bank_name varchar(128) NOT NULL,
  bank_account_no varchar(128) NOT NULL,
  currency varchar(8) NOT NULL DEFAULT 'CNY',
  ledger_account_id bigint unsigned NOT NULL,
  opening_date date NOT NULL,
  opening_balance decimal(18,2) NOT NULL DEFAULT 0.00,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  disabled_reason varchar(500) NOT NULL DEFAULT '',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_business_bank_account_code (entity_id,account_code),
  UNIQUE KEY uk_business_bank_account_no (entity_id,bank_account_no),
  UNIQUE KEY uk_business_bank_ledger (entity_id,ledger_account_id),
  KEY idx_business_bank_status (entity_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业自有银行账户及总账科目对应';

CREATE TABLE bank_reconciliation_session (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  bank_account_id bigint unsigned NOT NULL,
  period_id bigint unsigned NOT NULL,
  reconciliation_no varchar(64) NOT NULL,
  statement_from date NOT NULL,
  statement_to date NOT NULL,
  opening_balance decimal(18,2) NOT NULL,
  statement_closing_balance decimal(18,2) NOT NULL,
  ledger_closing_balance decimal(18,2) NOT NULL DEFAULT 0.00,
  adjusted_statement_balance decimal(18,2) NOT NULL DEFAULT 0.00,
  adjusted_ledger_balance decimal(18,2) NOT NULL DEFAULT 0.00,
  difference decimal(18,2) NOT NULL DEFAULT 0.00,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, CONFIRMED, REOPENED',
  close_reason varchar(500) NOT NULL DEFAULT '',
  confirmed_by bigint unsigned NOT NULL DEFAULT 0,
  confirmed_by_name varchar(128) NOT NULL DEFAULT '',
  confirmed_at timestamp NULL DEFAULT NULL,
  reopened_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bank_reconciliation_period (bank_account_id,period_id),
  UNIQUE KEY uk_bank_reconciliation_no (entity_id,reconciliation_no),
  KEY idx_bank_reconciliation_status (entity_id,period_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='逐账户逐期间银行对账及余额调节表';

CREATE TABLE bank_statement_line (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  session_id bigint unsigned NOT NULL,
  line_no int unsigned NOT NULL,
  transaction_date date NOT NULL,
  value_date date NOT NULL,
  bank_serial_no varchar(128) NOT NULL DEFAULT '',
  direction varchar(8) NOT NULL COMMENT 'DEBIT, CREDIT',
  amount decimal(18,2) NOT NULL,
  balance decimal(18,2) NOT NULL,
  counterparty_name varchar(255) NOT NULL DEFAULT '',
  counterparty_account varchar(128) NOT NULL DEFAULT '',
  purpose varchar(500) NOT NULL DEFAULT '',
  source_hash char(64) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'UNMATCHED' COMMENT 'UNMATCHED, PARTIAL, MATCHED, EXPLAINED, VOID',
  void_reason varchar(500) NOT NULL DEFAULT '',
  voided_by bigint unsigned NOT NULL DEFAULT 0,
  voided_by_name varchar(128) NOT NULL DEFAULT '',
  voided_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bank_statement_line_no (session_id,line_no),
  UNIQUE KEY uk_bank_statement_hash (session_id,source_hash),
  KEY idx_bank_statement_status (session_id,status,transaction_date,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可覆盖的银行对账单逐笔流水';

CREATE TABLE bank_reconciliation_match (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  session_id bigint unsigned NOT NULL,
  statement_line_id bigint unsigned NOT NULL,
  source_type varchar(32) NOT NULL COMMENT 'PAYMENT, SUPPLIER_PAYMENT, CUSTOMER_ADVANCE_REFUND, ACCEPTANCE_EVENT',
  source_id bigint unsigned NOT NULL,
  source_no varchar(64) NOT NULL,
  source_date date NOT NULL,
  source_direction varchar(8) NOT NULL,
  matched_amount decimal(18,2) NOT NULL,
  cash_flow_category varchar(48) NOT NULL,
  matching_method varchar(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL, RULE',
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, REVERSED',
  reason varchar(500) NOT NULL DEFAULT '',
  matched_by bigint unsigned NOT NULL DEFAULT 0,
  matched_by_name varchar(128) NOT NULL DEFAULT '',
  matched_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reversed_by bigint unsigned NOT NULL DEFAULT 0,
  reversed_by_name varchar(128) NOT NULL DEFAULT '',
  reversed_at timestamp NULL DEFAULT NULL,
  active_match_key varchar(255) GENERATED ALWAYS AS
    (case when status='ACTIVE' then concat(session_id,':',statement_line_id,':',source_type,':',source_id) else null end) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bank_match_active (active_match_key),
  KEY idx_bank_match_source (source_type,source_id,status),
  KEY idx_bank_match_statement (statement_line_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='银行流水与系统真实资金事项的可逆匹配';

CREATE TABLE bank_reconciliation_difference (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  session_id bigint unsigned NOT NULL,
  statement_line_id bigint unsigned NOT NULL,
  difference_type varchar(32) NOT NULL COMMENT 'BANK_NOT_BOOKED, SYSTEM_NOT_BANK, BANK_FEE, INTEREST, OTHER',
  direction varchar(8) NOT NULL,
  amount decimal(18,2) NOT NULL,
  cash_flow_category varchar(48) NOT NULL,
  reason varchar(500) NOT NULL,
  accounting_voucher_id bigint unsigned NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN, RESOLVED, REVERSED',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  resolved_by bigint unsigned NOT NULL DEFAULT 0,
  resolved_by_name varchar(128) NOT NULL DEFAULT '',
  resolved_at timestamp NULL DEFAULT NULL,
  reversed_by bigint unsigned NOT NULL DEFAULT 0,
  reversed_by_name varchar(128) NOT NULL DEFAULT '',
  reversed_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_bank_difference_status (session_id,status,statement_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='银行未达项和差异处理；禁止直接修改余额';

CREATE TABLE bank_reconciliation_pending_system (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  session_id bigint unsigned NOT NULL,
  source_type varchar(32) NOT NULL,
  source_id bigint unsigned NOT NULL,
  source_no varchar(64) NOT NULL,
  source_date date NOT NULL,
  direction varchar(8) NOT NULL,
  amount decimal(18,2) NOT NULL,
  cash_flow_category varchar(48) NOT NULL,
  reason varchar(500) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN, MATCHED, REVERSED',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at timestamp NULL DEFAULT NULL,
  active_source_key varchar(128) GENERATED ALWAYS AS
    (case when status='OPEN' then concat(source_type,':',source_id) else null end) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bank_pending_system_active (active_source_key),
  KEY idx_bank_pending_system_source (source_type,source_id,status),
  KEY idx_bank_pending_system_session (session_id,status,source_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统已记账但银行未达的账户归属和余额调节项';

CREATE TABLE bank_reconciliation_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  session_id bigint unsigned NOT NULL,
  action varchar(32) NOT NULL,
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_bank_reconciliation_event (session_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='银行对账状态和更正审计';

CREATE TABLE accounting_period_close_run (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  period_id bigint unsigned NOT NULL,
  attempt_no int unsigned NOT NULL,
  status varchar(24) NOT NULL COMMENT 'CHECKED, CLOSED, REOPENED, FAILED',
  close_reason varchar(500) NOT NULL,
  profit_voucher_id bigint unsigned NOT NULL DEFAULT 0,
  reversal_voucher_id bigint unsigned NOT NULL DEFAULT 0,
  balance_sheet_assets decimal(18,2) NOT NULL DEFAULT 0.00,
  balance_sheet_liabilities_equity decimal(18,2) NOT NULL DEFAULT 0.00,
  income_net_profit decimal(18,2) NOT NULL DEFAULT 0.00,
  cash_net_increase decimal(18,2) NOT NULL DEFAULT 0.00,
  trial_difference decimal(18,2) NOT NULL DEFAULT 0.00,
  snapshot_hash char(64) NOT NULL,
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  closed_at timestamp NULL DEFAULT NULL,
  reopened_at timestamp NULL DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_accounting_period_close_attempt (period_id,attempt_no),
  KEY idx_accounting_period_close_status (entity_id,period_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='期末结账不可覆盖快照';

CREATE TABLE accounting_period_close_check (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  close_run_id bigint unsigned NOT NULL,
  check_code varchar(48) NOT NULL,
  check_name varchar(128) NOT NULL,
  status varchar(16) NOT NULL COMMENT 'PASS, BLOCKED, WARNING',
  issue_count bigint unsigned NOT NULL DEFAULT 0,
  amount_difference decimal(18,2) NOT NULL DEFAULT 0.00,
  detail varchar(1000) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_accounting_period_close_check (close_run_id,check_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='结账时点业务财务税务一致性检查证据';

INSERT INTO financial_report_line
  (entity_id,report_type,line_code,line_name,line_order,calculation_type,formula_code,normal_direction,effective_from,status)
SELECT e.id,x.report_type,x.line_code,x.line_name,x.line_order,x.calculation_type,x.formula_code,x.normal_direction,'2026-01-01','ENABLED'
FROM accounting_entity e CROSS JOIN (
  SELECT 'BALANCE_SHEET' report_type,'BS_CASH' line_code,'货币资金' line_name,10 line_order,'ACCOUNT_SUM' calculation_type,'' formula_code,'DEBIT' normal_direction UNION ALL
  SELECT 'BALANCE_SHEET','BS_NOTES_RECEIVABLE','应收票据',20,'ACCOUNT_SUM','','DEBIT' UNION ALL
  SELECT 'BALANCE_SHEET','BS_RECEIVABLE','应收账款',30,'ACCOUNT_SUM','','DEBIT' UNION ALL
  SELECT 'BALANCE_SHEET','BS_PREPAY','预付款项',40,'ACCOUNT_SUM','','DEBIT' UNION ALL
  SELECT 'BALANCE_SHEET','BS_OTHER_RECEIVABLE','其他应收款',50,'ACCOUNT_SUM','','DEBIT' UNION ALL
  SELECT 'BALANCE_SHEET','BS_INVENTORY','存货',60,'ACCOUNT_SUM','','DEBIT' UNION ALL
  SELECT 'BALANCE_SHEET','BS_OTHER_ASSET','其他资产',70,'ACCOUNT_SUM','','DEBIT' UNION ALL
  SELECT 'BALANCE_SHEET','BS_ASSET_TOTAL','资产合计',100,'FORMULA','ASSET_TOTAL','DEBIT' UNION ALL
  SELECT 'BALANCE_SHEET','BS_PAYABLE','应付账款',210,'ACCOUNT_SUM','','CREDIT' UNION ALL
  SELECT 'BALANCE_SHEET','BS_ADVANCE','预收款项',220,'ACCOUNT_SUM','','CREDIT' UNION ALL
  SELECT 'BALANCE_SHEET','BS_OTHER_LIABILITY','其他负债',230,'ACCOUNT_SUM','','CREDIT' UNION ALL
  SELECT 'BALANCE_SHEET','BS_LIABILITY_TOTAL','负债合计',290,'FORMULA','LIABILITY_TOTAL','CREDIT' UNION ALL
  SELECT 'BALANCE_SHEET','BS_CAPITAL','实收资本',310,'ACCOUNT_SUM','','CREDIT' UNION ALL
  SELECT 'BALANCE_SHEET','BS_CURRENT_PROFIT','本年利润及未结转损益',320,'FORMULA','CURRENT_PROFIT','CREDIT' UNION ALL
  SELECT 'BALANCE_SHEET','BS_OTHER_EQUITY','其他所有者权益',330,'ACCOUNT_SUM','','CREDIT' UNION ALL
  SELECT 'BALANCE_SHEET','BS_EQUITY_TOTAL','所有者权益合计',390,'FORMULA','EQUITY_TOTAL','CREDIT' UNION ALL
  SELECT 'BALANCE_SHEET','BS_LIABILITY_EQUITY_TOTAL','负债和所有者权益合计',400,'FORMULA','LIABILITY_EQUITY_TOTAL','CREDIT' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_MAIN_REVENUE','主营业务收入',10,'ACCOUNT_SUM','','CREDIT' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_OTHER_REVENUE','其他业务收入',20,'ACCOUNT_SUM','','CREDIT' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_REVENUE_TOTAL','营业收入合计',100,'FORMULA','REVENUE_TOTAL','CREDIT' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_MAIN_COST','主营业务成本',210,'ACCOUNT_SUM','','DEBIT' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_SELLING_EXPENSE','销售费用',220,'ACCOUNT_SUM','','DEBIT' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_ADMIN_EXPENSE','管理费用',230,'ACCOUNT_SUM','','DEBIT' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_FINANCE_EXPENSE','财务费用',240,'ACCOUNT_SUM','','DEBIT' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_OTHER_EXPENSE','其他支出',250,'ACCOUNT_SUM','','DEBIT' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_EXPENSE_TOTAL','成本费用合计',290,'FORMULA','EXPENSE_TOTAL','DEBIT' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_PROFIT_TOTAL','利润总额',300,'FORMULA','PROFIT_TOTAL','CREDIT' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_NET_PROFIT','净利润',400,'FORMULA','NET_PROFIT','CREDIT'
) x WHERE e.entity_code='DEFAULT';

INSERT INTO financial_report_account_map
  (entity_id,report_type,line_id,account_id,amount_sign,effective_from)
SELECT e.id,x.report_type,l.id,a.id,1.0000,'2026-01-01'
FROM accounting_entity e CROSS JOIN (
  SELECT 'BALANCE_SHEET' report_type,'BS_CASH' line_code,'1001' account_code UNION ALL
  SELECT 'BALANCE_SHEET','BS_CASH','1002' UNION ALL
  SELECT 'BALANCE_SHEET','BS_NOTES_RECEIVABLE','1121' UNION ALL
  SELECT 'BALANCE_SHEET','BS_RECEIVABLE','1122' UNION ALL
  SELECT 'BALANCE_SHEET','BS_PREPAY','1123' UNION ALL
  SELECT 'BALANCE_SHEET','BS_OTHER_RECEIVABLE','1231' UNION ALL
  SELECT 'BALANCE_SHEET','BS_INVENTORY','1405' UNION ALL
  SELECT 'BALANCE_SHEET','BS_INVENTORY','1407' UNION ALL
  SELECT 'BALANCE_SHEET','BS_OTHER_ASSET','1901' UNION ALL
  SELECT 'BALANCE_SHEET','BS_PAYABLE','2202' UNION ALL
  SELECT 'BALANCE_SHEET','BS_ADVANCE','2203' UNION ALL
  SELECT 'BALANCE_SHEET','BS_OTHER_LIABILITY','2241' UNION ALL
  SELECT 'BALANCE_SHEET','BS_OTHER_LIABILITY','224199' UNION ALL
  SELECT 'BALANCE_SHEET','BS_CAPITAL','3001' UNION ALL
  SELECT 'BALANCE_SHEET','BS_CURRENT_PROFIT','3103' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_MAIN_REVENUE','5001' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_OTHER_REVENUE','5051' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_MAIN_COST','5401' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_SELLING_EXPENSE','5601' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_ADMIN_EXPENSE','5602' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_FINANCE_EXPENSE','5603' UNION ALL
  SELECT 'INCOME_STATEMENT','IS_OTHER_EXPENSE','5711'
) x
JOIN accounting_account a ON a.entity_id=e.id AND a.account_code=x.account_code
JOIN financial_report_line l ON l.entity_id=e.id AND l.report_type=x.report_type AND l.line_code=x.line_code
WHERE e.entity_code='DEFAULT';

INSERT INTO business_bank_account
  (entity_id,account_code,account_name,bank_name,bank_account_no,currency,ledger_account_id,opening_date,opening_balance,status)
SELECT e.id,'BANK001','基本结算账户','待维护开户银行','待维护账号','CNY',a.id,'2026-01-01',0.00,'ENABLED'
FROM accounting_entity e JOIN accounting_account a ON a.entity_id=e.id AND a.account_code='1002'
WHERE e.entity_code='DEFAULT';

INSERT INTO business_posting_rule_version
  (rule_code,rule_name,source_type,posting_action,version_no,effective_from,rule_json,status,published_at)
VALUES
  ('ACCEPTANCE_EVENT_POST','承兑票据处置过账','ACCEPTANCE_EVENT','POST',1,'2026-01-01',
   '{"facts":["ACCEPTANCE_CASH_RECEIPT","ACCEPTANCE_DISCOUNT_FEE","ACCEPTANCE_BOUNCE_CASH"]}','ENABLED',CURRENT_TIMESTAMP),
  ('ACCEPTANCE_EVENT_REVERSE','承兑票据处置冲正','ACCEPTANCE_EVENT','REVERSE',1,'2026-01-01',
   '{"reverseOf":"ACCEPTANCE_EVENT_POST"}','ENABLED',CURRENT_TIMESTAMP);

INSERT INTO accounting_rule_version
  (entity_id,rule_code,rule_name,source_type,posting_action,fact_type,fact_direction,
   debit_account_id,credit_account_id,version_no,effective_from,status,change_reason,published_at)
SELECT e.id,x.rule_code,x.rule_name,x.source_type,'POST',x.fact_type,x.fact_direction,
       da.id,ca.id,1,'2026-01-01','ENABLED','财务报表与银行对账阶段补齐承兑票据会计语义',CURRENT_TIMESTAMP
FROM accounting_entity e CROSS JOIN (
  SELECT 'RECEIPT_ACCEPTANCE' rule_code,'承兑收票核销应收' rule_name,'PAYMENT' source_type,
         'RECEIVABLE_ACCEPTANCE' fact_type,'DECREASE' fact_direction,'1121' debit_code,'1122' credit_code UNION ALL
  SELECT 'ACCEPTANCE_CASH','承兑到期或贴现实收','ACCEPTANCE_EVENT',
         'ACCEPTANCE_CASH_RECEIPT','INCREASE','1002','1121' UNION ALL
  SELECT 'ACCEPTANCE_FEE','承兑贴现费用','ACCEPTANCE_EVENT',
         'ACCEPTANCE_DISCOUNT_FEE','INCREASE','5603','1121' UNION ALL
  SELECT 'ACCEPTANCE_BOUNCE','已贴现承兑退票追索','ACCEPTANCE_EVENT',
         'ACCEPTANCE_BOUNCE_CASH','DECREASE','1122','1002'
) x
JOIN accounting_account da ON da.entity_id=e.id AND da.account_code=x.debit_code
JOIN accounting_account ca ON ca.entity_id=e.id AND ca.account_code=x.credit_code
WHERE e.entity_code='DEFAULT';

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('financial.report.view','financial-close','财务结账','PAGE','查看财务报表','查看资产负债表、利润表、现金流量表和勾稽结果',91,'ENABLED'),
  ('financial.bank.view','financial-close','财务结账','PAGE','查看银行对账','查看企业银行账户、银行流水、系统资金和余额调节表',92,'ENABLED'),
  ('financial.bank.manage','financial-close','财务结账','ACTION','管理银行对账','维护企业账户、导入流水、匹配、解释差异和确认对账',93,'ENABLED'),
  ('financial.close.manage','financial-close','财务结账','ACTION','执行期末结账','运行结账检查、生成损益结转、结账和规范反结账',94,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER') AND p.code LIKE 'financial.%';

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('FINANCE','MANAGER') AND p.code IN
  ('financial.report.view','financial.bank.view','financial.bank.manage','financial.close.manage');
