CREATE TABLE data_migration_batch (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  batch_no varchar(64) NOT NULL,
  opening_date date NOT NULL,
  source_file_name varchar(255) NOT NULL,
  source_file_sha256 char(64) NOT NULL,
  source_file_size bigint unsigned NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'PREFLIGHT_PASSED'
    COMMENT 'PREFLIGHT_FAILED, PREFLIGHT_PASSED, APPLIED, CUTOVER, ROLLED_BACK',
  total_rows int unsigned NOT NULL DEFAULT 0,
  valid_rows int unsigned NOT NULL DEFAULT 0,
  error_rows int unsigned NOT NULL DEFAULT 0,
  applied_rows int unsigned NOT NULL DEFAULT 0,
  opening_voucher_id bigint unsigned NOT NULL DEFAULT 0,
  failure_message varchar(1000) NOT NULL DEFAULT '',
  imported_by bigint unsigned NOT NULL DEFAULT 0,
  imported_by_name varchar(128) NOT NULL DEFAULT '',
  applied_by bigint unsigned NOT NULL DEFAULT 0,
  applied_by_name varchar(128) NOT NULL DEFAULT '',
  cutover_by bigint unsigned NOT NULL DEFAULT 0,
  cutover_by_name varchar(128) NOT NULL DEFAULT '',
  rolled_back_by bigint unsigned NOT NULL DEFAULT 0,
  rolled_back_by_name varchar(128) NOT NULL DEFAULT '',
  preflight_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  applied_at timestamp NULL DEFAULT NULL,
  cutover_at timestamp NULL DEFAULT NULL,
  rolled_back_at timestamp NULL DEFAULT NULL,
  last_checked_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_data_migration_batch_no (batch_no),
  UNIQUE KEY uk_data_migration_file (entity_id,opening_date,source_file_sha256),
  KEY idx_data_migration_status (entity_id,status,id),
  CONSTRAINT chk_data_migration_batch_status CHECK
    (status IN ('PREFLIGHT_FAILED','PREFLIGHT_PASSED','APPLIED','CUTOVER','ROLLED_BACK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='主数据和期初迁移批次；切换前可整批撤销，切换后只允许规范调整';

CREATE TABLE data_migration_row (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  batch_id bigint unsigned NOT NULL,
  domain_code varchar(32) NOT NULL
    COMMENT 'PARTNER, WAREHOUSE, PRODUCT, INVENTORY, PARTNER_OPENING, FUND_OPENING, GENERAL_LEDGER',
  source_sheet varchar(64) NOT NULL,
  source_row_no int unsigned NOT NULL,
  source_key varchar(128) NOT NULL,
  payload_json longtext NOT NULL,
  row_hash char(64) NOT NULL,
  validation_status varchar(16) NOT NULL DEFAULT 'VALID' COMMENT 'VALID, ERROR',
  error_code varchar(64) NOT NULL DEFAULT '',
  error_message varchar(1000) NOT NULL DEFAULT '',
  target_type varchar(48) NOT NULL DEFAULT '',
  target_id bigint unsigned NOT NULL DEFAULT 0,
  target_created tinyint unsigned NOT NULL DEFAULT 0,
  rollback_context_json longtext NULL COMMENT '应用前状态快照；仅供切换前精确回滚',
  applied_at timestamp NULL DEFAULT NULL,
  rolled_back_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_data_migration_source_key (batch_id,domain_code,source_key),
  UNIQUE KEY uk_data_migration_sheet_row (batch_id,source_sheet,source_row_no),
  KEY idx_data_migration_validation (batch_id,validation_status,domain_code,source_row_no),
  KEY idx_data_migration_target (target_type,target_id),
  CONSTRAINT fk_data_migration_row_batch FOREIGN KEY (batch_id) REFERENCES data_migration_batch(id),
  CONSTRAINT chk_data_migration_row_domain CHECK
    (domain_code IN ('PARTNER','WAREHOUSE','PRODUCT','INVENTORY','PARTNER_OPENING','FUND_OPENING','GENERAL_LEDGER')),
  CONSTRAINT chk_data_migration_row_validation CHECK (validation_status IN ('VALID','ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='迁移逐行原始快照、校验结果、幂等目标映射和回滚边界';

CREATE TABLE data_migration_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  batch_id bigint unsigned NOT NULL,
  action varchar(32) NOT NULL,
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  reason varchar(1000) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_data_migration_event (batch_id,id),
  CONSTRAINT fk_data_migration_event_batch FOREIGN KEY (batch_id) REFERENCES data_migration_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='期初迁移不可变状态事件';

CREATE TABLE data_migration_check_run (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  batch_id bigint unsigned NOT NULL,
  trigger_type varchar(16) NOT NULL COMMENT 'MANUAL, APPLY, CUTOVER, SCHEDULED',
  status varchar(16) NOT NULL COMMENT 'PASSED, FAILED',
  checked_count int unsigned NOT NULL DEFAULT 0,
  issue_count int unsigned NOT NULL DEFAULT 0,
  summary_json longtext NOT NULL,
  checked_by bigint unsigned NOT NULL DEFAULT 0,
  checked_by_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_data_migration_check_run (batch_id,id),
  CONSTRAINT fk_data_migration_check_run_batch FOREIGN KEY (batch_id) REFERENCES data_migration_batch(id),
  CONSTRAINT chk_data_migration_check_status CHECK (status IN ('PASSED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='迁移切换及持续一致性检查批次';

CREATE TABLE data_migration_check_issue (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  check_run_id bigint unsigned NOT NULL,
  check_code varchar(64) NOT NULL,
  severity varchar(16) NOT NULL DEFAULT 'BLOCKING',
  domain_code varchar(32) NOT NULL,
  expected_value varchar(255) NOT NULL DEFAULT '',
  actual_value varchar(255) NOT NULL DEFAULT '',
  message varchar(1000) NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_data_migration_check_issue (check_run_id,severity,id),
  CONSTRAINT fk_data_migration_check_issue_run FOREIGN KEY (check_run_id) REFERENCES data_migration_check_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='迁移一致性差异明细';

CREATE TABLE data_migration_fund_opening (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  migration_row_id bigint unsigned NOT NULL,
  batch_id bigint unsigned NOT NULL,
  fund_type varchar(16) NOT NULL COMMENT 'BANK, CASH',
  account_code varchar(32) NOT NULL,
  account_name varchar(128) NOT NULL,
  bank_name varchar(128) NOT NULL DEFAULT '',
  bank_account_no varchar(128) NOT NULL DEFAULT '',
  currency varchar(8) NOT NULL DEFAULT 'CNY',
  ledger_account_id bigint unsigned NOT NULL,
  opening_date date NOT NULL,
  opening_balance decimal(18,2) NOT NULL,
  business_bank_account_id bigint unsigned NOT NULL DEFAULT 0,
  business_bank_account_created tinyint NOT NULL DEFAULT 0,
  business_bank_account_before_json longtext NULL,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  rolled_back_at timestamp NULL DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_data_migration_fund_row (migration_row_id),
  KEY idx_data_migration_fund_batch (batch_id,status,id),
  CONSTRAINT fk_data_migration_fund_row FOREIGN KEY (migration_row_id) REFERENCES data_migration_row(id),
  CONSTRAINT fk_data_migration_fund_batch FOREIGN KEY (batch_id) REFERENCES data_migration_batch(id),
  CONSTRAINT chk_data_migration_fund_type CHECK (fund_type IN ('BANK','CASH')),
  CONSTRAINT chk_data_migration_fund_status CHECK (status IN ('ACTIVE','ROLLED_BACK')),
  CONSTRAINT chk_data_migration_fund_amount CHECK (opening_balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='银行和现金期初资金事实';

ALTER TABLE inventory_batch
  ADD COLUMN data_migration_row_id bigint unsigned NULL DEFAULT NULL AFTER source_id,
  ADD UNIQUE KEY uk_inventory_batch_migration_row (data_migration_row_id),
  ADD CONSTRAINT fk_inventory_batch_migration_row FOREIGN KEY (data_migration_row_id) REFERENCES data_migration_row(id);

ALTER TABLE treasury_document
  DROP CHECK chk_treasury_document_type,
  ADD CONSTRAINT chk_treasury_document_type CHECK
    (document_type IN ('CORPORATE_EXPENSE','OTHER_EXPENSE','OTHER_INCOME','OTHER_RECEIVABLE','OTHER_PAYABLE',
                       'PURCHASE_LANDED_COST','OPENING_RECEIVABLE','OPENING_PAYABLE')),
  ADD COLUMN data_migration_row_id bigint unsigned NULL DEFAULT NULL AFTER partner_id,
  ADD UNIQUE KEY uk_treasury_document_migration_row (data_migration_row_id),
  ADD CONSTRAINT fk_treasury_document_migration_row FOREIGN KEY (data_migration_row_id) REFERENCES data_migration_row(id);

ALTER TABLE business_bank_account
  ADD COLUMN data_migration_row_id bigint unsigned NULL DEFAULT NULL AFTER entity_id,
  ADD UNIQUE KEY uk_business_bank_migration_row (data_migration_row_id),
  ADD CONSTRAINT fk_business_bank_migration_row FOREIGN KEY (data_migration_row_id) REFERENCES data_migration_row(id);

ALTER TABLE accounting_voucher
  ADD COLUMN data_migration_batch_id bigint unsigned NULL DEFAULT NULL AFTER source_id,
  ADD UNIQUE KEY uk_accounting_voucher_migration_batch (data_migration_batch_id),
  ADD CONSTRAINT fk_accounting_voucher_migration_batch FOREIGN KEY (data_migration_batch_id) REFERENCES data_migration_batch(id);

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('data.migration.view','system','系统设置','PAGE','查看期初迁移','查看迁移批次、预检错误、切换勾稽和持续一致性结果',156,'ENABLED'),
  ('data.migration.manage','system','系统设置','ACTION','上传并预检期初迁移','下载模板、上传工作簿并修正预检错误，不形成业务事实',157,'ENABLED'),
  ('data.migration.apply','system','系统设置','ACTION','应用期初迁移','将已通过预检的批次应用为可追溯业务事实和待切换凭证',158,'ENABLED'),
  ('data.migration.rollback','system','系统设置','ACTION','撤销未切换迁移','仅在无后续业务且未切换时整批撤销，保留批次和审计',159,'ENABLED'),
  ('data.migration.cutover','system','系统设置','ACTION','确认迁移切换','复核库存、往来、资金和试算勾稽后确认切换；切换后不得整批无痕回滚',160,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER') AND p.code LIKE 'data.migration.%';

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code='FINANCE' AND p.code IN ('data.migration.view','data.migration.manage');
