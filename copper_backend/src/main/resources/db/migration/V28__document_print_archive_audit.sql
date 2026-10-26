ALTER TABLE business_evidence_attachment
  MODIFY COLUMN evidence_type varchar(32) NOT NULL DEFAULT 'OTHER'
    COMMENT 'ORIGINAL_DOCUMENT, SIGNED_RECEIPT, SIGNED_STAMPED_COPY, DELIVERY_PROOF, PAYMENT_PROOF, VERIFICATION_PROOF, VISUAL_COPY, OTHER';

CREATE TABLE business_document_output_log (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  document_type varchar(40) NOT NULL,
  document_id bigint unsigned NOT NULL,
  document_no_snapshot varchar(128) NOT NULL,
  status_snapshot varchar(32) NOT NULL,
  output_type varchar(24) NOT NULL COMMENT 'PRINT_OR_PDF',
  template_version varchar(64) NOT NULL,
  output_file_name varchar(255) NOT NULL,
  snapshot_hash char(64) NOT NULL COMMENT '前端打印数据快照SHA-256；用于识别重复输出及事后核对',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_business_document_output (document_type,document_id,id),
  KEY idx_business_document_output_hash (snapshot_hash,document_type,document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='业务单据打印及另存PDF的不可覆盖审计记录';
