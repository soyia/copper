CREATE TABLE tax_rate_rule (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  rule_code varchar(64) NOT NULL,
  rule_name varchar(128) NOT NULL,
  tax_rate decimal(7,4) NOT NULL,
  calculation_method varchar(16) NOT NULL COMMENT 'GENERAL, SIMPLE, ZERO, EXEMPT',
  invoice_scope varchar(16) NOT NULL DEFAULT 'BOTH' COMMENT 'INPUT, OUTPUT, BOTH',
  version_no int unsigned NOT NULL,
  effective_from date NOT NULL,
  effective_to date NULL DEFAULT NULL,
  legal_basis varchar(1000) NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'DRAFT, ENABLED, DISABLED',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tax_rate_rule_version (rule_code,version_no),
  KEY idx_tax_rate_rule_effective (status,effective_from,effective_to,invoice_scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='税率及征收率版本；历史发票只使用快照';

CREATE TABLE tax_invoice (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  internal_no varchar(64) NOT NULL,
  direction varchar(8) NOT NULL COMMENT 'INPUT, OUTPUT',
  invoice_color varchar(8) NOT NULL COMMENT 'BLUE, RED',
  invoice_type varchar(32) NOT NULL COMMENT 'DIGITAL_SPECIAL, DIGITAL_NORMAL, VAT_SPECIAL, VAT_NORMAL, OTHER',
  invoice_code varchar(32) NOT NULL DEFAULT '',
  invoice_number varchar(32) NOT NULL,
  blue_invoice_id bigint unsigned NOT NULL DEFAULT 0,
  partner_id bigint unsigned NOT NULL,
  partner_business_no_snapshot varchar(32) NOT NULL DEFAULT '',
  partner_name_snapshot varchar(255) NOT NULL,
  partner_tax_no_snapshot varchar(64) NOT NULL DEFAULT '',
  issuer_name varchar(255) NOT NULL,
  issuer_tax_no varchar(64) NOT NULL DEFAULT '',
  recipient_name varchar(255) NOT NULL,
  recipient_tax_no varchar(64) NOT NULL DEFAULT '',
  invoice_date date NOT NULL,
  received_or_issued_date date NOT NULL,
  source_channel varchar(32) NOT NULL DEFAULT 'MANUAL' COMMENT 'TAX_DIGITAL_ACCOUNT, MANUAL, IMPORT',
  currency varchar(8) NOT NULL DEFAULT 'CNY',
  amount_excluding_tax decimal(18,2) NOT NULL,
  tax_amount decimal(18,2) NOT NULL,
  total_amount decimal(18,2) NOT NULL,
  rounding_difference decimal(18,2) NOT NULL DEFAULT 0.00,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, CONFIRMED, VOIDED',
  verification_status varchar(16) NOT NULL DEFAULT 'UNVERIFIED' COMMENT 'UNVERIFIED, VERIFIED, FAILED',
  verification_reference varchar(128) NOT NULL DEFAULT '',
  verified_at timestamp NULL DEFAULT NULL,
  booking_status varchar(16) NOT NULL DEFAULT 'UNCONFIRMED' COMMENT 'UNCONFIRMED, CONFIRMED',
  booked_at timestamp NULL DEFAULT NULL,
  usage_status varchar(24) NOT NULL DEFAULT 'UNCONFIRMED' COMMENT 'UNCONFIRMED, DEDUCTION, NON_DEDUCTION, TRANSFERRED_OUT, NOT_APPLICABLE',
  usage_period varchar(7) NOT NULL DEFAULT '',
  red_confirmation_status varchar(16) NOT NULL DEFAULT 'NOT_REQUIRED' COMMENT 'NOT_REQUIRED, PENDING, CONFIRMED, EXPIRED',
  red_confirmation_no varchar(64) NOT NULL DEFAULT '',
  red_confirmation_initiator varchar(16) NOT NULL DEFAULT '' COMMENT 'ISSUER, RECIPIENT',
  red_confirmation_initiated_at timestamp NULL DEFAULT NULL,
  red_confirmation_expires_at timestamp NULL DEFAULT NULL,
  red_confirmation_confirmed_at timestamp NULL DEFAULT NULL,
  accounting_voucher_id bigint unsigned NOT NULL DEFAULT 0,
  usage_voucher_id bigint unsigned NOT NULL DEFAULT 0,
  transfer_out_voucher_id bigint unsigned NOT NULL DEFAULT 0,
  void_voucher_id bigint unsigned NOT NULL DEFAULT 0,
  source_file_name varchar(255) NOT NULL DEFAULT '',
  source_file_sha256 char(64) NOT NULL DEFAULT '',
  memo varchar(1000) NOT NULL DEFAULT '',
  void_reason varchar(500) NOT NULL DEFAULT '',
  version_no int unsigned NOT NULL DEFAULT 1,
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  confirmed_by bigint unsigned NOT NULL DEFAULT 0,
  confirmed_by_name varchar(128) NOT NULL DEFAULT '',
  confirmed_at timestamp NULL DEFAULT NULL,
  voided_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tax_invoice_internal_no (entity_id,internal_no),
  UNIQUE KEY uk_tax_invoice_external_no (entity_id,invoice_code,invoice_number),
  KEY idx_tax_invoice_list (entity_id,direction,status,invoice_date,id),
  KEY idx_tax_invoice_partner (partner_id,direction,invoice_date),
  KEY idx_tax_invoice_blue (blue_invoice_id,status),
  KEY idx_tax_invoice_reconciliation (status,verification_status,booking_status,usage_status,red_confirmation_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='进销项发票主档；发票、业务、资金和会计凭证分层';

CREATE TABLE tax_invoice_line (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  invoice_id bigint unsigned NOT NULL,
  line_no int unsigned NOT NULL,
  product_id bigint unsigned NOT NULL DEFAULT 0,
  product_sku_snapshot varchar(128) NOT NULL DEFAULT '',
  product_name_snapshot varchar(255) NOT NULL,
  product_spec_snapshot varchar(1000) NOT NULL DEFAULT '',
  unit_snapshot varchar(64) NOT NULL DEFAULT '',
  quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  unit_price_excluding_tax decimal(18,6) NOT NULL DEFAULT 0.000000,
  amount_excluding_tax decimal(18,2) NOT NULL,
  tax_rate_rule_id bigint unsigned NOT NULL,
  tax_rule_code_snapshot varchar(64) NOT NULL,
  tax_rule_name_snapshot varchar(128) NOT NULL,
  tax_rate decimal(7,4) NOT NULL,
  calculation_method_snapshot varchar(16) NOT NULL,
  tax_classification_code varchar(64) NOT NULL DEFAULT '',
  tax_amount decimal(18,2) NOT NULL,
  total_amount decimal(18,2) NOT NULL,
  legal_basis_snapshot varchar(1000) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tax_invoice_line (invoice_id,line_no),
  KEY idx_tax_invoice_line_product (product_id,invoice_id),
  KEY idx_tax_invoice_line_rate (tax_rate_rule_id,invoice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发票明细及税率法规历史快照';

CREATE TABLE tax_invoice_relation (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  invoice_id bigint unsigned NOT NULL,
  relation_layer varchar(16) NOT NULL COMMENT 'CONTRACT, GOODS, SETTLEMENT, FUNDS, ACCOUNTING',
  source_type varchar(32) NOT NULL,
  source_id bigint unsigned NOT NULL,
  source_no varchar(64) NOT NULL,
  source_business_date date NULL DEFAULT NULL,
  partner_id bigint unsigned NOT NULL DEFAULT 0,
  amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00,
  tax_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  total_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, REVERSED',
  matching_basis varchar(500) NOT NULL DEFAULT '',
  reversed_reason varchar(500) NOT NULL DEFAULT '',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  reversed_by bigint unsigned NOT NULL DEFAULT 0,
  reversed_by_name varchar(128) NOT NULL DEFAULT '',
  reversed_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tax_invoice_relation (invoice_id,relation_layer,source_type,source_id),
  KEY idx_tax_invoice_relation_source (source_type,source_id,status),
  KEY idx_tax_invoice_relation_layer (invoice_id,relation_layer,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发票与合同、货、款、资金及凭证分层勾稽';

CREATE TABLE tax_invoice_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  invoice_id bigint unsigned NOT NULL,
  action varchar(32) NOT NULL,
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  reason varchar(1000) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_tax_invoice_event (invoice_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发票、用途确认、红冲及勾稽审计链';

CREATE TABLE tax_invoice_use_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  invoice_id bigint unsigned NOT NULL,
  action varchar(32) NOT NULL COMMENT 'BOOKING_CONFIRM, USAGE_CONFIRM, TRANSFER_OUT',
  from_usage_status varchar(24) NOT NULL DEFAULT '',
  to_usage_status varchar(24) NOT NULL DEFAULT '',
  tax_period varchar(7) NOT NULL DEFAULT '',
  tax_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  accounting_voucher_id bigint unsigned NOT NULL DEFAULT 0,
  evidence_reference varchar(128) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_tax_invoice_use_event (invoice_id,id),
  KEY idx_tax_invoice_use_period (tax_period,to_usage_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='进项用途确认、转出和入账标识审计';

INSERT INTO tax_rate_rule
  (rule_code,rule_name,tax_rate,calculation_method,invoice_scope,version_no,effective_from,legal_basis,status)
VALUES
  ('VAT_GOODS_13','货物及铜材一般计税13%',13.0000,'GENERAL','BOTH',1,'2026-01-01','《中华人民共和国增值税法》第十条、第十六条；自2026年1月1日起施行','ENABLED'),
  ('VAT_GOODS_9','适用9%税率货物',9.0000,'GENERAL','BOTH',1,'2026-01-01','《中华人民共和国增值税法》第十条；财政部 税务总局公告2026年第9号','ENABLED'),
  ('VAT_SERVICE_6','一般服务6%',6.0000,'GENERAL','BOTH',1,'2026-01-01','《中华人民共和国增值税法》第十条','ENABLED'),
  ('VAT_SIMPLE_3','简易计税3%',3.0000,'SIMPLE','BOTH',1,'2026-01-01','《中华人民共和国增值税法》第十一条','ENABLED'),
  ('VAT_ZERO','零税率',0.0000,'ZERO','BOTH',1,'2026-01-01','《中华人民共和国增值税法》第十条','ENABLED'),
  ('VAT_EXEMPT','免征增值税',0.0000,'EXEMPT','BOTH',1,'2026-01-01','按发票开具时有效的免税政策及历史快照执行','ENABLED');

INSERT INTO accounting_account
  (entity_id,account_code,account_name,category,normal_direction,allow_manual,
   partner_auxiliary,product_auxiliary,warehouse_auxiliary,effective_from,status)
SELECT e.id,a.code,a.name,'LIABILITY',a.direction,0,'NONE','NONE','NONE','2026-01-01','ENABLED'
FROM accounting_entity e CROSS JOIN (
  SELECT '22210101' code,'应交增值税-进项税额' name,'DEBIT' direction UNION ALL
  SELECT '22210102','应交增值税-销项税额','CREDIT' UNION ALL
  SELECT '22210103','应交增值税-待认证进项税额','DEBIT'
) a
WHERE e.entity_code='DEFAULT'
  AND NOT EXISTS (SELECT 1 FROM accounting_account x WHERE x.entity_id=e.id AND x.account_code=a.code);

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('invoice.view','invoice','发票税务','PAGE','查看发票税务','查看进销项发票、税率快照、勾稽与差异',87,'ENABLED'),
  ('invoice.manage','invoice','发票税务','ACTION','管理发票草稿及勾稽','新增修改发票草稿并维护票货款账关联',88,'ENABLED'),
  ('invoice.confirm','invoice','发票税务','ACTION','确认及作废发票','确认发票登记、作废并触发规范会计处理',89,'ENABLED'),
  ('invoice.verify','invoice','发票税务','ACTION','查验及用途确认','登记税务查验、入账、抵扣和进项转出结果',90,'ENABLED'),
  ('invoice.red.manage','invoice','发票税务','ACTION','管理红字发票','维护红字确认单并确认红字发票',91,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER') AND p.code LIKE 'invoice.%';

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('FINANCE','MANAGER') AND p.code IN
  ('invoice.view','invoice.manage','invoice.confirm','invoice.verify','invoice.red.manage');
