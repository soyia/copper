CREATE TABLE electronic_credential (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  credential_no varchar(64) NOT NULL,
  credential_type varchar(32) NOT NULL
    COMMENT 'DIGITAL_INVOICE, BANK_RECEIPT, BANK_STATEMENT',
  file_format varchar(8) NOT NULL COMMENT 'XML, PDF, OFD',
  standard_code varchar(16) NOT NULL DEFAULT '' COMMENT 'EINV, BKER, BKRS',
  standard_version varchar(32) NOT NULL DEFAULT '',
  original_file_name varchar(255) NOT NULL,
  content_type varchar(128) NOT NULL,
  file_size bigint unsigned NOT NULL,
  source_file_sha256 char(64) NOT NULL,
  storage_path varchar(512) NOT NULL,
  parse_status varchar(24) NOT NULL
    COMMENT 'PARSED, PENDING_EXTERNAL, FAILED',
  parse_message varchar(1000) NOT NULL DEFAULT '',
  verification_status varchar(16) NOT NULL DEFAULT 'UNVERIFIED'
    COMMENT 'UNVERIFIED, VERIFIED, FAILED',
  signature_status varchar(16) NOT NULL DEFAULT 'UNVERIFIED'
    COMMENT 'UNVERIFIED, VERIFIED, FAILED, NOT_APPLICABLE',
  external_no varchar(128) NOT NULL DEFAULT '',
  direction_hint varchar(8) NOT NULL DEFAULT '' COMMENT 'INPUT, OUTPUT or empty',
  issue_date date NULL DEFAULT NULL,
  period_from date NULL DEFAULT NULL,
  period_to date NULL DEFAULT NULL,
  accounting_entity_code varchar(64) NOT NULL DEFAULT '',
  accounting_entity_name varchar(255) NOT NULL DEFAULT '',
  counterparty_code varchar(128) NOT NULL DEFAULT '',
  counterparty_name varchar(255) NOT NULL DEFAULT '',
  bank_account_no varchar(128) NOT NULL DEFAULT '',
  currency varchar(8) NOT NULL DEFAULT 'CNY',
  amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00,
  tax_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  total_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  detail_count int unsigned NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, VOIDED',
  void_reason varchar(500) NOT NULL DEFAULT '',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  voided_by bigint unsigned NOT NULL DEFAULT 0,
  voided_by_name varchar(128) NOT NULL DEFAULT '',
  voided_at timestamp NULL DEFAULT NULL,
  active_sha256 char(64) GENERATED ALWAYS AS
    (CASE WHEN status='ACTIVE' THEN source_file_sha256 ELSE NULL END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_electronic_credential_no (entity_id,credential_no),
  UNIQUE KEY uk_electronic_credential_active_sha (entity_id,active_sha256),
  KEY idx_electronic_credential_list (entity_id,status,credential_type,issue_date,id),
  KEY idx_electronic_credential_exception (entity_id,parse_status,verification_status,status,id),
  KEY idx_electronic_credential_external (entity_id,standard_code,external_no,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='财政部电子凭证会计数据标准接收登记；原件摘要不可变，解析不等同验签验真';

CREATE TABLE electronic_credential_verification (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  credential_id bigint unsigned NOT NULL,
  verification_kind varchar(24) NOT NULL COMMENT 'AUTHENTICITY, SIGNATURE',
  verification_source varchar(32) NOT NULL
    COMMENT 'MOF_TOOLKIT, TAX_DIGITAL_ACCOUNT, NATIONAL_PLATFORM, BANK_VALIDATION_TOOL, MANUAL_REVIEW',
  verification_result varchar(16) NOT NULL COMMENT 'VERIFIED, FAILED',
  verification_reference varchar(128) NOT NULL,
  checked_at timestamp NOT NULL,
  result_snapshot varchar(2000) NOT NULL DEFAULT '',
  proof_file_name varchar(255) NOT NULL,
  proof_content_type varchar(128) NOT NULL,
  proof_file_size bigint unsigned NOT NULL,
  proof_sha256 char(64) NOT NULL,
  proof_storage_path varchar(512) NOT NULL,
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_electronic_credential_verification (credential_id,id),
  KEY idx_electronic_credential_verification_proof (proof_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='电子凭证外部验签或验真证据；系统不得以解析成功冒充验证成功';

CREATE TABLE electronic_credential_relation (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  credential_id bigint unsigned NOT NULL,
  relation_type varchar(24) NOT NULL COMMENT 'SOURCE, BOOKING, RECONCILIATION',
  document_type varchar(32) NOT NULL
    COMMENT 'TAX_INVOICE, ACCOUNTING_VOUCHER, BANK_RECONCILIATION, TREASURY_DOCUMENT',
  document_id bigint unsigned NOT NULL,
  document_no varchar(64) NOT NULL,
  evidence_attachment_id bigint unsigned NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, REVERSED',
  note varchar(500) NOT NULL DEFAULT '',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reversed_by bigint unsigned NOT NULL DEFAULT 0,
  reversed_by_name varchar(128) NOT NULL DEFAULT '',
  reversed_reason varchar(500) NOT NULL DEFAULT '',
  reversed_at timestamp NULL DEFAULT NULL,
  active_relation_key varchar(180) GENERATED ALWAYS AS
    (CASE WHEN status='ACTIVE' THEN CONCAT(credential_id,':',relation_type,':',document_type,':',document_id) ELSE NULL END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_electronic_credential_active_relation (active_relation_key),
  KEY idx_electronic_credential_relation (credential_id,status,id),
  KEY idx_electronic_credential_relation_document (document_type,document_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='电子凭证与发票、银行对账及会计凭证分层关联；解除只失效不删除';

CREATE TABLE electronic_credential_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  credential_id bigint unsigned NOT NULL,
  action varchar(32) NOT NULL,
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  summary varchar(1000) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_electronic_credential_event (credential_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='电子凭证接收、验证、关联、解除和作废的不可变处理日志';

CREATE TABLE tax_workpaper (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  period_id bigint unsigned NOT NULL,
  workpaper_no varchar(64) NOT NULL,
  tax_type varchar(24) NOT NULL DEFAULT 'VAT' COMMENT '当前仅 VAT',
  rule_code varchar(64) NOT NULL DEFAULT 'CN_VAT_WORKPAPER_V1',
  rule_version int unsigned NOT NULL DEFAULT 1,
  version_no int unsigned NOT NULL,
  data_cutoff_at timestamp NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, REVIEWED, SUPERSEDED',
  output_net_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  output_tax_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  input_net_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  input_tax_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  deductible_input_tax decimal(18,2) NOT NULL DEFAULT 0.00,
  transferred_out_input_tax decimal(18,2) NOT NULL DEFAULT 0.00,
  estimated_vat_payable decimal(18,2) NOT NULL DEFAULT 0.00,
  ledger_output_tax decimal(18,2) NOT NULL DEFAULT 0.00,
  ledger_input_tax decimal(18,2) NOT NULL DEFAULT 0.00,
  ledger_transferred_out_tax decimal(18,2) NOT NULL DEFAULT 0.00,
  output_ledger_difference decimal(18,2) NOT NULL DEFAULT 0.00,
  input_ledger_difference decimal(18,2) NOT NULL DEFAULT 0.00,
  blocker_count int unsigned NOT NULL DEFAULT 0,
  warning_count int unsigned NOT NULL DEFAULT 0,
  content_sha256 char(64) NOT NULL,
  review_note varchar(1000) NOT NULL DEFAULT '',
  generated_by bigint unsigned NOT NULL DEFAULT 0,
  generated_by_name varchar(128) NOT NULL DEFAULT '',
  generated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed_by bigint unsigned NOT NULL DEFAULT 0,
  reviewed_by_name varchar(128) NOT NULL DEFAULT '',
  reviewed_at timestamp NULL DEFAULT NULL,
  superseded_at timestamp NULL DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tax_workpaper_no (workpaper_no),
  UNIQUE KEY uk_tax_workpaper_version (entity_id,period_id,tax_type,version_no),
  KEY idx_tax_workpaper_period (entity_id,period_id,status,version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='按会计期间冻结的增值税申报工作底稿；仅供复核，不代表已申报';

CREATE TABLE tax_workpaper_line (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  workpaper_id bigint unsigned NOT NULL,
  line_no int unsigned NOT NULL,
  section_code varchar(24) NOT NULL COMMENT 'OUTPUT, INPUT, TRANSFER_OUT, LEDGER, DIFFERENCE, EXCEPTION',
  item_code varchar(64) NOT NULL,
  item_name varchar(255) NOT NULL,
  tax_rate decimal(7,4) NULL DEFAULT NULL,
  amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00,
  tax_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  total_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  source_count int unsigned NOT NULL DEFAULT 0,
  severity varchar(16) NOT NULL DEFAULT 'INFO' COMMENT 'INFO, WARNING, BLOCKER',
  formula varchar(1000) NOT NULL DEFAULT '',
  source_query_snapshot varchar(2000) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tax_workpaper_line (workpaper_id,line_no),
  KEY idx_tax_workpaper_line_section (workpaper_id,section_code,line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='增值税申报工作底稿明细、公式、来源数量和异常证据';

CREATE TABLE tax_workpaper_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  workpaper_id bigint unsigned NOT NULL,
  action varchar(32) NOT NULL COMMENT 'GENERATE, REVIEW, SUPERSEDE',
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  reason varchar(1000) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_tax_workpaper_event (workpaper_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='申报工作底稿生成、复核和被新版本替代的审计链';

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('tax.compliance.view','tax-compliance','票账税合规','PAGE','查看电子凭证与税务底稿','查看结构化电子凭证、处理日志、票账税差异和申报工作底稿',96,'ENABLED'),
  ('tax.compliance.manage','tax-compliance','票账税合规','ACTION','接收和关联电子凭证','上传标准电子凭证、登记验证证据并维护业务关联',97,'ENABLED'),
  ('tax.compliance.review','tax-compliance','票账税合规','ACTION','复核税务工作底稿','生成和复核带版本、截止时点、公式和来源的增值税工作底稿',98,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER','FINANCE') AND p.code LIKE 'tax.compliance.%';

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code='MANAGER' AND p.code='tax.compliance.view';
