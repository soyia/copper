CREATE TABLE business_evidence_attachment (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  document_type varchar(40) NOT NULL,
  document_id bigint unsigned NOT NULL,
  evidence_type varchar(32) NOT NULL DEFAULT 'OTHER'
    COMMENT 'ORIGINAL_DOCUMENT, SIGNED_RECEIPT, DELIVERY_PROOF, PAYMENT_PROOF, VERIFICATION_PROOF, VISUAL_COPY, OTHER',
  source_channel varchar(32) NOT NULL DEFAULT 'UPLOAD'
    COMMENT 'UPLOAD, TAX_DIGITAL_ACCOUNT, IMPORT, SCAN, SYSTEM',
  original_file_name varchar(255) NOT NULL,
  archive_file_name varchar(255) NOT NULL DEFAULT '',
  content_type varchar(128) NOT NULL,
  file_size bigint unsigned NOT NULL,
  sha256 char(64) NOT NULL,
  storage_path varchar(512) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, VOIDED',
  note varchar(500) NOT NULL DEFAULT '',
  uploaded_by bigint unsigned NOT NULL DEFAULT 0,
  uploaded_by_name varchar(128) NOT NULL DEFAULT '',
  uploaded_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  voided_by bigint unsigned NOT NULL DEFAULT 0,
  voided_by_name varchar(128) NOT NULL DEFAULT '',
  void_reason varchar(500) NOT NULL DEFAULT '',
  voided_at timestamp NULL DEFAULT NULL,
  active_hash_key varchar(180) GENERATED ALWAYS AS
    (CASE WHEN status='ACTIVE' THEN CONCAT(document_type,':',document_id,':',sha256) ELSE NULL END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_business_evidence_active_hash (active_hash_key),
  KEY idx_business_evidence_document (document_type,document_id,status,id),
  KEY idx_business_evidence_hash (sha256,document_type,document_id),
  KEY idx_business_evidence_uploaded (uploaded_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='跨业务单据电子原始凭证；确认后只能追加或留痕作废，禁止物理删除';

CREATE TABLE tax_invoice_verification_record (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  invoice_id bigint unsigned NOT NULL,
  verification_source varchar(32) NOT NULL
    COMMENT 'TAX_DIGITAL_ACCOUNT, NATIONAL_PLATFORM, LOCAL_TAX_AUTHORITY, MANUAL_REVIEW',
  verification_result varchar(16) NOT NULL COMMENT 'VERIFIED, FAILED',
  verification_reference varchar(128) NOT NULL,
  proof_attachment_id bigint unsigned NOT NULL,
  proof_sha256 char(64) NOT NULL,
  checked_at timestamp NOT NULL,
  query_snapshot varchar(1000) NOT NULL DEFAULT '',
  note varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_tax_invoice_verification (invoice_id,id),
  KEY idx_tax_invoice_verification_proof (proof_attachment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='发票每次查验结果及不可缺失的查验凭证';

CREATE TABLE tax_invoice_import_batch (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  batch_no varchar(64) NOT NULL,
  template_version varchar(32) NOT NULL DEFAULT 'copper-tax-invoice-csv/v1',
  source_file_name varchar(255) NOT NULL,
  source_file_sha256 char(64) NOT NULL,
  source_file_size bigint unsigned NOT NULL,
  source_storage_path varchar(512) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING, COMPLETED, FAILED',
  imported_invoice_count int unsigned NOT NULL DEFAULT 0,
  imported_line_count int unsigned NOT NULL DEFAULT 0,
  error_message varchar(1000) NOT NULL DEFAULT '',
  imported_by bigint unsigned NOT NULL DEFAULT 0,
  imported_by_name varchar(128) NOT NULL DEFAULT '',
  imported_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at timestamp NULL DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tax_invoice_import_batch_no (batch_no),
  KEY idx_tax_invoice_import_status (status,imported_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='标准模板导入批次及原始导入文件证据';

ALTER TABLE tax_invoice
  ADD COLUMN import_batch_id bigint unsigned NOT NULL DEFAULT 0 AFTER source_channel,
  ADD KEY idx_tax_invoice_import_batch (import_batch_id,id);

CREATE TABLE accounting_archive_batch (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  period_id bigint unsigned NOT NULL,
  archive_no varchar(64) NOT NULL,
  archive_mode varchar(16) NOT NULL COMMENT 'PREVIEW, FINAL',
  status varchar(16) NOT NULL DEFAULT 'GENERATING' COMMENT 'GENERATING, COMPLETED, FAILED, SUPERSEDED',
  format_version varchar(64) NOT NULL DEFAULT 'copper-accounting-archive/v1',
  file_name varchar(255) NOT NULL DEFAULT '',
  storage_path varchar(512) NOT NULL DEFAULT '',
  archive_bytes bigint unsigned NOT NULL DEFAULT 0,
  archive_sha256 char(64) NOT NULL DEFAULT '',
  voucher_count int unsigned NOT NULL DEFAULT 0,
  source_attachment_count int unsigned NOT NULL DEFAULT 0,
  generated_by bigint unsigned NOT NULL DEFAULT 0,
  generated_by_name varchar(128) NOT NULL DEFAULT '',
  generated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at timestamp NULL DEFAULT NULL,
  superseded_reason varchar(500) NOT NULL DEFAULT '',
  superseded_at timestamp NULL DEFAULT NULL,
  error_message varchar(1000) NOT NULL DEFAULT '',
  PRIMARY KEY (id),
  UNIQUE KEY uk_accounting_archive_no (archive_no),
  KEY idx_accounting_archive_period (entity_id,period_id,archive_mode,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='按会计期间生成的电子凭证预归档或结账后正式归档包';

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('evidence.view','evidence','电子凭据','ACTION','查看业务附件','查看有权业务单据的附件、原件哈希及查验凭证',92,'ENABLED'),
  ('evidence.manage','evidence','电子凭据','ACTION','维护业务附件','补传业务附件；仅草稿附件允许物理删除，确认后只能留痕作废',93,'ENABLED'),
  ('accounting.archive','accounting','会计总账','ACTION','生成电子凭证归档','生成、下载按期间组织且带摘要校验的电子凭证归档包',94,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER','MANAGER','SALES','PURCHASE','WAREHOUSE','FINANCE')
  AND p.code IN ('evidence.view','evidence.manage');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER','FINANCE') AND p.code='accounting.archive';
