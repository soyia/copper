CREATE TABLE price_benchmark_version (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  benchmark_code varchar(64) NOT NULL,
  benchmark_name varchar(128) NOT NULL,
  version_no int unsigned NOT NULL,
  price_unit varchar(64) NOT NULL,
  unit_price decimal(18,4) NOT NULL,
  price_includes_tax tinyint unsigned NOT NULL DEFAULT 1,
  tax_rate decimal(7,4) NOT NULL DEFAULT 0.0000,
  effective_date date NOT NULL,
  source_type varchar(24) NOT NULL DEFAULT 'MANUAL',
  source_note varchar(500) NOT NULL DEFAULT '',
  change_reason varchar(500) NOT NULL,
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(100) NOT NULL DEFAULT '系统',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_price_benchmark_version (benchmark_code,version_no),
  UNIQUE KEY uk_price_benchmark_effective_date (benchmark_code,effective_date),
  KEY idx_price_benchmark_effective (benchmark_code,effective_date,id),
  CONSTRAINT chk_price_benchmark_price CHECK (unit_price > 0),
  CONSTRAINT chk_price_benchmark_tax CHECK (tax_rate >= 0 AND tax_rate <= 100),
  CONSTRAINT chk_price_benchmark_source CHECK (source_type IN ('MANUAL','MARKET_REFERENCE','SUPPLIER_NOTICE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='铜基准价不可变生效版本';

CREATE TABLE price_benchmark_sequence (
  benchmark_code varchar(64) NOT NULL,
  last_version int unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (benchmark_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='铜基准价版本并发序列';

CREATE TABLE price_rule_version (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  rule_code varchar(64) NOT NULL,
  rule_name varchar(128) NOT NULL,
  version_no int unsigned NOT NULL,
  business_type varchar(16) NOT NULL COMMENT 'SALES, PURCHASE',
  scope_type varchar(24) NOT NULL COMMENT 'STANDARD, CUSTOMER_LEVEL, CUSTOMER, SUPPLIER',
  partner_id bigint unsigned NOT NULL DEFAULT 0,
  customer_level varchar(32) NOT NULL DEFAULT '',
  agreement_type varchar(24) NOT NULL DEFAULT 'STANDARD' COMMENT 'STANDARD, AGREEMENT, SPECIAL',
  product_id bigint unsigned NOT NULL,
  price_unit varchar(64) NOT NULL,
  minimum_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  maximum_quantity decimal(24,6) DEFAULT NULL,
  pricing_mode varchar(16) NOT NULL COMMENT 'FORMULA, FIXED',
  benchmark_code varchar(64) NOT NULL DEFAULT '',
  fixed_unit_price decimal(18,4) NOT NULL DEFAULT 0.0000,
  processing_fee decimal(18,4) NOT NULL DEFAULT 0.0000,
  specification_fee decimal(18,4) NOT NULL DEFAULT 0.0000,
  packaging_fee decimal(18,4) NOT NULL DEFAULT 0.0000,
  freight_fee decimal(18,4) NOT NULL DEFAULT 0.0000,
  price_includes_tax tinyint unsigned NOT NULL DEFAULT 1,
  tax_rate decimal(7,4) NOT NULL DEFAULT 0.0000,
  effective_from date NOT NULL,
  effective_to date DEFAULT NULL,
  change_reason varchar(500) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PUBLISHED',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(100) NOT NULL DEFAULT '系统',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_price_rule_version (rule_code,version_no),
  KEY idx_price_rule_match (business_type,product_id,price_unit,effective_from,effective_to,status),
  KEY idx_price_rule_scope (scope_type,partner_id,customer_level,agreement_type),
  CONSTRAINT chk_price_rule_business CHECK (business_type IN ('SALES','PURCHASE')),
  CONSTRAINT chk_price_rule_scope CHECK (scope_type IN ('STANDARD','CUSTOMER_LEVEL','CUSTOMER','SUPPLIER')),
  CONSTRAINT chk_price_rule_agreement CHECK (agreement_type IN ('STANDARD','AGREEMENT','SPECIAL')),
  CONSTRAINT chk_price_rule_mode CHECK (pricing_mode IN ('FORMULA','FIXED')),
  CONSTRAINT chk_price_rule_status CHECK (status IN ('PUBLISHED','WITHDRAWN')),
  CONSTRAINT chk_price_rule_quantity CHECK (minimum_quantity >= 0 AND (maximum_quantity IS NULL OR maximum_quantity >= minimum_quantity)),
  CONSTRAINT chk_price_rule_amount CHECK ((pricing_mode = 'FORMULA' OR fixed_unit_price > 0) AND fixed_unit_price >= 0 AND processing_fee >= 0 AND specification_fee >= 0 AND packaging_fee >= 0 AND freight_fee >= 0),
  CONSTRAINT chk_price_rule_tax CHECK (tax_rate >= 0 AND tax_rate <= 100),
  CONSTRAINT chk_price_rule_dates CHECK (effective_to IS NULL OR effective_to >= effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售采购价格规则不可变版本';

ALTER TABLE purchase_order_item
  ADD COLUMN price_source_code varchar(64) NOT NULL DEFAULT 'LEGACY' AFTER packaging_snapshot_hash,
  ADD COLUMN price_source_name varchar(128) NOT NULL DEFAULT '历史价格' AFTER price_source_code,
  ADD COLUMN price_rule_version_id bigint unsigned NOT NULL DEFAULT 0 AFTER price_source_name,
  ADD COLUMN price_snapshot_json json NULL AFTER price_rule_version_id,
  ADD COLUMN no_charge tinyint unsigned NOT NULL DEFAULT 0 AFTER price_snapshot_json;

ALTER TABLE sales_order_item
  ADD COLUMN price_source_code varchar(64) NOT NULL DEFAULT 'LEGACY' AFTER packaging_snapshot_hash,
  ADD COLUMN price_source_name varchar(128) NOT NULL DEFAULT '历史价格' AFTER price_source_code,
  ADD COLUMN price_rule_version_id bigint unsigned NOT NULL DEFAULT 0 AFTER price_source_name,
  ADD COLUMN price_snapshot_json json NULL AFTER price_rule_version_id;

ALTER TABLE crm_quote_item
  ADD COLUMN price_source_code varchar(64) NOT NULL DEFAULT 'LEGACY' AFTER packaging_snapshot_hash,
  ADD COLUMN price_source_name varchar(128) NOT NULL DEFAULT '历史价格' AFTER price_source_code,
  ADD COLUMN price_rule_version_id bigint unsigned NOT NULL DEFAULT 0 AFTER price_source_name,
  ADD COLUMN price_snapshot_json json NULL AFTER price_rule_version_id;

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('pricing.view','pricing','铜材价格中心','MENU','查看铜材价格中心','查看铜基准价、客户协议价、供应商价和可解释取价',64,'ENABLED'),
  ('pricing.manage','pricing','铜材价格中心','ACTION','维护价格版本','新增不可变的基准价和价格规则版本',65,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER','MANAGER','SALES','PURCHASE') AND p.code='pricing.view';

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER') AND p.code='pricing.manage';
