CREATE TABLE partner_fund_adjustment (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  adjustment_no varchar(64) NOT NULL,
  partner_id bigint unsigned NOT NULL,
  account_type varchar(32) NOT NULL COMMENT 'CUSTOMER_ADVANCE, CUSTOMER_REWARD, SUPPLIER_PREPAY, SUPPLIER_REWARD',
  adjustment_type varchar(40) NOT NULL COMMENT 'OPENING_BALANCE, CUSTOMER_REWARD_GRANT, SUPPLIER_REBATE_GRANT, BALANCE_CORRECTION',
  direction varchar(16) NOT NULL COMMENT 'INCREASE, DECREASE',
  amount decimal(18,2) NOT NULL,
  business_date date NOT NULL,
  basis_no varchar(128) NOT NULL DEFAULT '' COMMENT '协议、对账单、原单或迁移批次号',
  tax_treatment varchar(40) NOT NULL DEFAULT 'NOT_APPLICABLE' COMMENT 'NOT_APPLICABLE, PENDING_PROFESSIONAL_REVIEW, SEPARATE_INVOICE_HANDLING',
  memo varchar(500) NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, CONFIRMED, CANCELLED, REVERSED',
  version_no int unsigned NOT NULL DEFAULT 1,
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  confirmed_by bigint unsigned NOT NULL DEFAULT 0,
  confirmed_by_name varchar(128) NOT NULL DEFAULT '',
  confirmed_at timestamp NULL DEFAULT NULL,
  cancelled_by bigint unsigned NOT NULL DEFAULT 0,
  cancelled_by_name varchar(128) NOT NULL DEFAULT '',
  cancel_reason varchar(500) NOT NULL DEFAULT '',
  cancelled_at timestamp NULL DEFAULT NULL,
  reversal_no varchar(64) NOT NULL DEFAULT '',
  reversal_reason varchar(500) NOT NULL DEFAULT '',
  reversed_by bigint unsigned NOT NULL DEFAULT 0,
  reversed_by_name varchar(128) NOT NULL DEFAULT '',
  reversed_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_partner_fund_adjustment_no (adjustment_no),
  KEY idx_partner_fund_adjustment_partner (partner_id,account_type,business_date,id),
  KEY idx_partner_fund_adjustment_status (status,business_date,id),
  CONSTRAINT chk_partner_fund_adjustment_amount CHECK (amount > 0),
  CONSTRAINT chk_partner_fund_adjustment_direction CHECK (direction IN ('INCREASE','DECREASE')),
  CONSTRAINT chk_partner_fund_adjustment_status CHECK (status IN ('DRAFT','CONFIRMED','CANCELLED','REVERSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP-02 往来资金余额正式调整源单；非现金、非发票业务';

CREATE TABLE partner_fund_adjustment_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  adjustment_id bigint unsigned NOT NULL,
  action varchar(24) NOT NULL COMMENT 'CREATE, CONFIRM, CANCEL, REVERSE',
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_partner_fund_adjustment_event (adjustment_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='余额调整单不可变审批及冲正事件';

ALTER TABLE partner_account_action
  COMMENT='ERP-02 前历史直接账户动作；仅保留查询审计，禁止继续写入';

INSERT IGNORE INTO accounting_account
  (entity_id,account_code,account_name,category,normal_direction,parent_id,account_level,leaf,
   allow_manual,partner_auxiliary,product_auxiliary,warehouse_auxiliary,effective_from,status)
SELECT id,'3104','利润分配-未分配利润','EQUITY','CREDIT',0,1,1,0,'NONE','NONE','NONE','2026-01-01','ENABLED'
FROM accounting_entity;

INSERT IGNORE INTO financial_report_account_map
  (entity_id,report_type,line_id,account_id,amount_sign,effective_from)
SELECT e.id,'BALANCE_SHEET',l.id,a.id,1.0000,'2026-01-01'
FROM accounting_entity e
JOIN financial_report_line l ON l.entity_id=e.id AND l.report_type='BALANCE_SHEET' AND l.line_code='BS_CURRENT_PROFIT'
JOIN accounting_account a ON a.entity_id=e.id AND a.account_code='3104';

INSERT INTO business_posting_rule_version
  (rule_code,rule_name,source_type,posting_action,version_no,effective_from,rule_json,status,published_at)
SELECT 'PARTNER_FUND_ADJUSTMENT_POST','往来资金余额调整确认','PARTNER_FUND_ADJUSTMENT','POST',1,'2026-01-01',
       '{"facts":["PARTNER_FUND_BALANCE"],"trigger":"CONFIRM","cashImpact":false,"taxImpact":false}',
       'ENABLED',CURRENT_TIMESTAMP
WHERE NOT EXISTS (
  SELECT 1 FROM business_posting_rule_version
  WHERE rule_code='PARTNER_FUND_ADJUSTMENT_POST' AND version_no=1
);

INSERT INTO business_posting_rule_version
  (rule_code,rule_name,source_type,posting_action,version_no,effective_from,rule_json,status,published_at)
SELECT 'PARTNER_FUND_ADJUSTMENT_REVERSE','往来资金余额调整冲正','PARTNER_FUND_ADJUSTMENT','REVERSE',1,'2026-01-01',
       '{"reverseOf":"PARTNER_FUND_ADJUSTMENT_POST","cashImpact":false,"taxImpact":false}',
       'ENABLED',CURRENT_TIMESTAMP
WHERE NOT EXISTS (
  SELECT 1 FROM business_posting_rule_version
  WHERE rule_code='PARTNER_FUND_ADJUSTMENT_REVERSE' AND version_no=1
);

INSERT INTO accounting_rule_version
  (entity_id,rule_code,rule_name,source_type,posting_action,fact_type,fact_direction,
   debit_account_id,credit_account_id,version_no,effective_from,status,change_reason,published_at)
SELECT e.id,r.rule_code,r.rule_name,'PARTNER_FUND_ADJUSTMENT','POST',r.fact_type,r.fact_direction,
       da.id,ca.id,1,'2026-01-01','ENABLED','ERP-02 正式余额源单会计基线；税票另行勾稽',CURRENT_TIMESTAMP
FROM accounting_entity e CROSS JOIN (
  SELECT 'PFA_CA_OPENING' rule_code,'客户预收期初' rule_name,'CUSTOMER_ADVANCE_OPENING' fact_type,'INCREASE' fact_direction,'3104' debit_code,'2203' credit_code UNION ALL
  SELECT 'PFA_SP_OPENING','供应商预付期初','SUPPLIER_PREPAY_OPENING','INCREASE','1123','3104' UNION ALL
  SELECT 'PFA_CUSTOMER_REWARD','客户奖励确认','CUSTOMER_REWARD_GRANT','INCREASE','5601','2203' UNION ALL
  SELECT 'PFA_SUPPLIER_REBATE','供应商返利确认','SUPPLIER_REBATE_GRANT','INCREASE','1123','5051' UNION ALL
  SELECT 'PFA_CA_CORR_INC','客户预收差错调增','CUSTOMER_ADVANCE_CORRECTION','INCREASE','5711','2203' UNION ALL
  SELECT 'PFA_CA_CORR_DEC','客户预收差错调减','CUSTOMER_ADVANCE_CORRECTION','DECREASE','2203','5051' UNION ALL
  SELECT 'PFA_CR_CORR_INC','客户奖励差错调增','CUSTOMER_REWARD_CORRECTION','INCREASE','5711','2203' UNION ALL
  SELECT 'PFA_CR_CORR_DEC','客户奖励差错调减','CUSTOMER_REWARD_CORRECTION','DECREASE','2203','5051' UNION ALL
  SELECT 'PFA_SP_CORR_INC','供应商预付差错调增','SUPPLIER_PREPAY_CORRECTION','INCREASE','1123','5051' UNION ALL
  SELECT 'PFA_SP_CORR_DEC','供应商预付差错调减','SUPPLIER_PREPAY_CORRECTION','DECREASE','5711','1123' UNION ALL
  SELECT 'PFA_SR_CORR_INC','供应商返利差错调增','SUPPLIER_REWARD_CORRECTION','INCREASE','1123','5051' UNION ALL
  SELECT 'PFA_SR_CORR_DEC','供应商返利差错调减','SUPPLIER_REWARD_CORRECTION','DECREASE','5711','1123'
) r
JOIN accounting_account da ON da.entity_id=e.id AND da.account_code=r.debit_code
JOIN accounting_account ca ON ca.entity_id=e.id AND ca.account_code=r.credit_code
WHERE NOT EXISTS (
  SELECT 1 FROM accounting_rule_version x
  WHERE x.entity_id=e.id AND x.rule_code=r.rule_code AND x.version_no=1
);

INSERT INTO accounting_rule_version
  (entity_id,rule_code,rule_name,source_type,posting_action,fact_type,fact_direction,
   debit_account_id,credit_account_id,version_no,effective_from,status,change_reason,published_at)
SELECT e.id,r.rule_code,r.rule_name,r.source_type,'POST',r.fact_type,'DECREASE',
       da.id,ca.id,1,'2026-01-01','ENABLED','ERP-02 余额核销不再伪造现金或银行存款',CURRENT_TIMESTAMP
FROM accounting_entity e CROSS JOIN (
  SELECT 'PFA_CA_SETTLE' rule_code,'客户预收余额核销应收' rule_name,'PAYMENT' source_type,
         'CUSTOMER_ADVANCE_SETTLEMENT' fact_type,'2203' debit_code,'1122' credit_code UNION ALL
  SELECT 'PFA_CR_SETTLE','客户奖励余额核销应收','PAYMENT','CUSTOMER_REWARD_SETTLEMENT','2203','1122' UNION ALL
  SELECT 'PFA_SP_SETTLE','供应商预付余额核销应付','SUPPLIER_PAYMENT','SUPPLIER_PREPAY_SETTLEMENT','2202','1123' UNION ALL
  SELECT 'PFA_SR_SETTLE','供应商返利余额核销应付','SUPPLIER_PAYMENT','SUPPLIER_REWARD_SETTLEMENT','2202','1123'
) r
JOIN accounting_account da ON da.entity_id=e.id AND da.account_code=r.debit_code
JOIN accounting_account ca ON ca.entity_id=e.id AND ca.account_code=r.credit_code
WHERE NOT EXISTS (
  SELECT 1 FROM accounting_rule_version x
  WHERE x.entity_id=e.id AND x.rule_code=r.rule_code AND x.version_no=1
);

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('partner.fund.adjust.view','finance','财务往来','PAGE','查看余额调整单','查看奖励、返利、期初及差错余额调整单与审计链',102,'ENABLED'),
  ('partner.fund.adjust.manage','finance','财务往来','ACTION','编制余额调整单','新建余额调整草稿及作废草稿',103,'ENABLED'),
  ('partner.fund.adjust.approve','finance','财务往来','ACTION','确认余额调整单','确认余额调整并触发业务过账及草稿凭证',104,'ENABLED'),
  ('partner.fund.adjust.reverse','finance','财务往来','ACTION','冲正余额调整单','填写原因后冲正已确认余额调整并保留原单',105,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER','FINANCE') AND p.code LIKE 'partner.fund.adjust.%';
