CREATE TABLE sales_risk_policy (
  partner_id bigint unsigned NOT NULL,
  enabled tinyint unsigned NOT NULL DEFAULT 0,
  credit_control_enabled tinyint unsigned NOT NULL DEFAULT 1,
  overdue_control_enabled tinyint unsigned NOT NULL DEFAULT 1,
  max_overdue_days int unsigned NOT NULL DEFAULT 0,
  margin_control_enabled tinyint unsigned NOT NULL DEFAULT 0,
  minimum_margin_rate decimal(7,4) NOT NULL DEFAULT 0.0000,
  version_no int unsigned NOT NULL DEFAULT 0,
  change_reason varchar(500) NOT NULL DEFAULT '',
  updated_by bigint unsigned NOT NULL DEFAULT 0,
  updated_by_name varchar(100) NOT NULL DEFAULT '系统',
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (partner_id),
  KEY idx_sales_risk_policy_enabled (enabled,partner_id),
  CONSTRAINT chk_sales_risk_margin_rate CHECK (minimum_margin_rate >= 0 AND minimum_margin_rate <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户销售信用逾期与最低毛利控制策略';

CREATE TABLE sales_risk_policy_version (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  partner_id bigint unsigned NOT NULL,
  version_no int unsigned NOT NULL,
  enabled tinyint unsigned NOT NULL,
  credit_control_enabled tinyint unsigned NOT NULL,
  credit_limit_snapshot decimal(18,2) NOT NULL,
  overdue_control_enabled tinyint unsigned NOT NULL,
  max_overdue_days int unsigned NOT NULL,
  margin_control_enabled tinyint unsigned NOT NULL,
  minimum_margin_rate decimal(7,4) NOT NULL,
  change_reason varchar(500) NOT NULL,
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(100) NOT NULL DEFAULT '系统',
  effective_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_sales_risk_policy_version (partner_id,version_no),
  KEY idx_sales_risk_policy_version_time (partner_id,effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户销售风险策略不可变版本';

CREATE TABLE sales_risk_snapshot (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  document_type varchar(24) NOT NULL COMMENT 'SALES_ORDER, OUTBOUND',
  document_id bigint unsigned NOT NULL,
  document_no varchar(64) NOT NULL,
  action varchar(24) NOT NULL COMMENT 'CONFIRM, AMEND, SHIP',
  source_revision_no int unsigned NOT NULL DEFAULT 1,
  customer_id bigint unsigned NOT NULL,
  policy_version int unsigned NOT NULL,
  receivable_amount decimal(18,2) NOT NULL,
  confirmed_unshipped_amount decimal(18,2) NOT NULL,
  current_document_amount decimal(18,2) NOT NULL,
  advance_credit_amount decimal(18,2) NOT NULL,
  acceptance_secured_amount decimal(18,2) NOT NULL,
  net_exposure_amount decimal(18,2) NOT NULL,
  credit_limit_amount decimal(18,2) NOT NULL,
  available_credit_amount decimal(18,2) NOT NULL,
  overdue_amount decimal(18,2) NOT NULL,
  maximum_overdue_days int unsigned NOT NULL,
  revenue_excluding_tax decimal(18,2) NOT NULL,
  margin_available tinyint unsigned NOT NULL,
  estimated_cost_amount decimal(18,2) NOT NULL,
  estimated_gross_profit decimal(18,2) NOT NULL,
  estimated_margin_rate decimal(7,4) NOT NULL,
  minimum_margin_rate decimal(7,4) NOT NULL,
  cost_basis varchar(64) NOT NULL,
  issue_codes_json json NOT NULL,
  decision varchar(24) NOT NULL COMMENT 'PASS, OVERRIDDEN',
  override_reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(100) NOT NULL DEFAULT '系统',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_sales_risk_snapshot_action (document_type,document_id,action,source_revision_no),
  KEY idx_sales_risk_snapshot_customer (customer_id,created_at),
  KEY idx_sales_risk_snapshot_override (decision,created_at),
  CONSTRAINT chk_sales_risk_snapshot_document CHECK (document_type IN ('SALES_ORDER','OUTBOUND')),
  CONSTRAINT chk_sales_risk_snapshot_decision CHECK (decision IN ('PASS','OVERRIDDEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售确认发货风险评估与授权快照';

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('sales.risk.policy.manage','sales','销售订单','ACTION','维护客户销售风控策略','维护客户授信、逾期和最低毛利控制策略并保留版本',84,'ENABLED'),
  ('sales.risk.override','sales','销售订单','ACTION','授权销售风险例外','对超信用、逾期或低毛利进行一次性明确授权并保留快照',85,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER')
  AND p.code IN ('sales.risk.policy.manage','sales.risk.override');

DELETE rp FROM security_role_permission rp
JOIN security_role r ON r.id=rp.role_id
JOIN security_permission p ON p.id=rp.permission_id
WHERE r.code IN ('MANAGER','SALES','WAREHOUSE','PURCHASE','FINANCE')
  AND p.code IN ('sales.risk.policy.manage','sales.risk.override');
