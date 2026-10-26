CREATE TABLE purchase_followup_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  purchase_order_id bigint unsigned NOT NULL,
  event_type varchar(24) NOT NULL COMMENT 'SUPPLIER_DISPATCHED, ETA_CHANGED, DELAYED, EXCEPTION, RESOLVED',
  occurred_on date NOT NULL,
  expected_arrival_date date DEFAULT NULL,
  carrier_name varchar(128) NOT NULL DEFAULT '',
  tracking_no varchar(128) NOT NULL DEFAULT '',
  exception_type varchar(32) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_purchase_followup_order (purchase_order_id,id),
  KEY idx_purchase_followup_eta (expected_arrival_date,event_type,id),
  CONSTRAINT fk_purchase_followup_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order(id) ON DELETE RESTRICT,
  CONSTRAINT chk_purchase_followup_type CHECK (event_type IN ('SUPPLIER_DISPATCHED','ETA_CHANGED','DELAYED','EXCEPTION','RESOLVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='采购供应商发出、预计到货、延误和异常的追加式跟催事实';

CREATE TABLE purchase_followup_event_item (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  event_id bigint unsigned NOT NULL,
  purchase_order_item_id bigint unsigned NOT NULL,
  dispatched_quantity decimal(20,6) NOT NULL DEFAULT 0,
  unit varchar(32) NOT NULL DEFAULT '',
  PRIMARY KEY (id),
  UNIQUE KEY uk_purchase_followup_event_item (event_id,purchase_order_item_id),
  KEY idx_purchase_followup_item_source (purchase_order_item_id,event_id),
  CONSTRAINT fk_purchase_followup_item_event FOREIGN KEY (event_id) REFERENCES purchase_followup_event(id) ON DELETE RESTRICT,
  CONSTRAINT fk_purchase_followup_item_order_item FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_item(id) ON DELETE RESTRICT,
  CONSTRAINT chk_purchase_followup_quantity CHECK (dispatched_quantity>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='采购供应商发出事件的逐行数量事实';

CREATE TABLE external_confirmation_packet (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  packet_no varchar(64) NOT NULL,
  token_hash char(64) NOT NULL,
  packet_type varchar(32) NOT NULL COMMENT 'CUSTOMER_QUOTE, CUSTOMER_STATEMENT, DELIVERY_SUMMARY',
  source_type varchar(32) NOT NULL,
  source_id bigint unsigned NOT NULL,
  source_no varchar(128) NOT NULL DEFAULT '',
  partner_id bigint unsigned NOT NULL,
  partner_name_snapshot varchar(255) NOT NULL,
  as_of_date date NOT NULL,
  snapshot_json longtext NOT NULL,
  snapshot_hash char(64) NOT NULL,
  expires_at datetime NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, CONFIRMED, DISPUTED, REVOKED, EXPIRED',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  revoked_by bigint unsigned NOT NULL DEFAULT 0,
  revoked_by_name varchar(128) NOT NULL DEFAULT '',
  revoke_reason varchar(500) NOT NULL DEFAULT '',
  revoked_at timestamp NULL DEFAULT NULL,
  view_count bigint unsigned NOT NULL DEFAULT 0,
  last_viewed_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_external_confirmation_packet_no (packet_no),
  UNIQUE KEY uk_external_confirmation_token (token_hash),
  KEY idx_external_confirmation_source (packet_type,source_type,source_id,status,id),
  KEY idx_external_confirmation_partner (partner_id,status,id),
  CONSTRAINT fk_external_confirmation_partner FOREIGN KEY (partner_id) REFERENCES partner(id) ON DELETE RESTRICT,
  CONSTRAINT chk_external_confirmation_packet_type CHECK (packet_type IN ('CUSTOMER_QUOTE','CUSTOMER_STATEMENT','DELIVERY_SUMMARY')),
  CONSTRAINT chk_external_confirmation_status CHECK (status IN ('ACTIVE','CONFIRMED','DISPUTED','REVOKED','EXPIRED')),
  CONSTRAINT chk_external_confirmation_snapshot CHECK (json_valid(snapshot_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='报价、客户对账单和交付摘要的冻结外部确认包；确认不触发财务或履约过账';

CREATE TABLE external_confirmation_record (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  packet_id bigint unsigned NOT NULL,
  decision varchar(16) NOT NULL COMMENT 'CONFIRMED, DISPUTED',
  confirmer_name varchar(128) NOT NULL,
  contact_tail varchar(16) NOT NULL DEFAULT '',
  comment varchar(1000) NOT NULL DEFAULT '',
  snapshot_hash char(64) NOT NULL,
  confirmed_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_external_confirmation_record_packet (packet_id),
  CONSTRAINT fk_external_confirmation_record_packet FOREIGN KEY (packet_id) REFERENCES external_confirmation_packet(id) ON DELETE RESTRICT,
  CONSTRAINT chk_external_confirmation_decision CHECK (decision IN ('CONFIRMED','DISPUTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='外部持链人对冻结内容的单次确认或异议记录；不等于收付款、核销或电子签名';

CREATE TABLE document_intake_batch (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  intake_no varchar(64) NOT NULL,
  document_type varchar(32) NOT NULL COMMENT 'SUPPLIER_QUOTE, WEIGH_TICKET, SIGNED_RECEIPT',
  original_file_name varchar(255) NOT NULL,
  content_type varchar(128) NOT NULL,
  file_size bigint unsigned NOT NULL,
  sha256 char(64) NOT NULL,
  storage_path varchar(512) NOT NULL,
  extraction_mode varchar(24) NOT NULL DEFAULT 'MANUAL_TEXT' COMMENT 'PDF_TEXT, TEXT, PROVIDED_TEXT, FILE_ONLY',
  extracted_text longtext NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'REVIEW_REQUIRED' COMMENT 'REVIEW_REQUIRED, CONFIRMED, REJECTED, FAILED',
  error_message varchar(1000) NOT NULL DEFAULT '',
  uploaded_by bigint unsigned NOT NULL DEFAULT 0,
  uploaded_by_name varchar(128) NOT NULL DEFAULT '',
  reviewed_by bigint unsigned NOT NULL DEFAULT 0,
  reviewed_by_name varchar(128) NOT NULL DEFAULT '',
  reviewed_at timestamp NULL DEFAULT NULL,
  review_note varchar(500) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_document_intake_no (intake_no),
  UNIQUE KEY uk_document_intake_hash_type (sha256,document_type),
  KEY idx_document_intake_status (status,created_at,id),
  CONSTRAINT chk_document_intake_type CHECK (document_type IN ('SUPPLIER_QUOTE','WEIGH_TICKET','SIGNED_RECEIPT')),
  CONSTRAINT chk_document_intake_status CHECK (status IN ('REVIEW_REQUIRED','CONFIRMED','REJECTED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='供应商报价单、磅单和签收单的原始文件及待确认识别批次';

CREATE TABLE document_intake_candidate (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  batch_id bigint unsigned NOT NULL,
  field_code varchar(64) NOT NULL,
  field_label varchar(128) NOT NULL,
  candidate_value varchar(1000) NOT NULL DEFAULT '',
  confirmed_value varchar(1000) NOT NULL DEFAULT '',
  confidence decimal(5,4) NOT NULL DEFAULT 0,
  source_excerpt varchar(1000) NOT NULL DEFAULT '',
  sort_no int NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_document_intake_candidate_field (batch_id,field_code),
  KEY idx_document_intake_candidate_order (batch_id,sort_no,id),
  CONSTRAINT fk_document_intake_candidate_batch FOREIGN KEY (batch_id) REFERENCES document_intake_batch(id) ON DELETE RESTRICT,
  CONSTRAINT chk_document_intake_confidence CHECK (confidence>=0 and confidence<=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='单据识别逐字段候选、置信度及人工确认值';

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('purchase.followup.view','purchase','采购管理','ACTION','查看采购跟催','查看采购供应商发出、预计到货、延误和异常摘要',126,'ENABLED'),
  ('purchase.followup.manage','purchase','采购管理','ACTION','维护采购跟催','追加供应商发出、预计到货、延误和异常事实',127,'ENABLED'),
  ('external.confirm.manage','integration','对外协同','ACTION','管理外部确认','生成、撤销报价、客户对账单和交付摘要确认包',128,'ENABLED'),
  ('document.intake.view','integration','单据接入','ACTION','查看单据候选','查看供应商报价单、磅单和签收单识别候选',129,'ENABLED'),
  ('document.intake.manage','integration','单据接入','ACTION','维护单据候选','上传、人工确认或驳回单据识别候选',130,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE
  (r.code IN ('ADMIN','OWNER','MANAGER') AND p.code IN (
    'purchase.followup.view','purchase.followup.manage','external.confirm.manage',
    'document.intake.view','document.intake.manage'))
  OR (r.code='PURCHASE' AND p.code IN (
    'purchase.followup.view','purchase.followup.manage','document.intake.view','document.intake.manage'))
  OR (r.code='SALES' AND p.code IN (
    'external.confirm.manage','document.intake.view','document.intake.manage'))
  OR (r.code='FINANCE' AND p.code IN ('external.confirm.manage','document.intake.view'))
  OR (r.code='WAREHOUSE' AND p.code IN ('document.intake.view','document.intake.manage'));
