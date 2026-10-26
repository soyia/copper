CREATE TABLE product_import_job (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  job_no varchar(64) NOT NULL,
  template_version varchar(64) NOT NULL,
  source_file_name varchar(255) NOT NULL,
  source_file_format varchar(16) NOT NULL COMMENT 'CSV, XLSX',
  source_file_sha256 char(64) NOT NULL,
  source_file_size bigint unsigned NOT NULL,
  source_storage_path varchar(1000) NOT NULL,
  mapping_json longtext NOT NULL,
  mapping_sha256 char(64) NOT NULL,
  configuration_sha256 char(64) NOT NULL COMMENT '预检时系列字段、单位、包装、参考值及商品目录版本摘要',
  status varchar(32) NOT NULL DEFAULT 'PREVIEWED'
    COMMENT 'PREVIEWED, COMMITTING, COMPLETED, COMPLETED_WITH_ERRORS',
  total_rows int unsigned NOT NULL DEFAULT 0,
  create_rows int unsigned NOT NULL DEFAULT 0,
  safe_update_rows int unsigned NOT NULL DEFAULT 0,
  skip_rows int unsigned NOT NULL DEFAULT 0,
  conflict_rows int unsigned NOT NULL DEFAULT 0,
  invalid_rows int unsigned NOT NULL DEFAULT 0,
  committed_rows int unsigned NOT NULL DEFAULT 0,
  failed_rows int unsigned NOT NULL DEFAULT 0,
  lock_version int unsigned NOT NULL DEFAULT 1,
  claim_token char(36) NOT NULL DEFAULT '',
  heartbeat_at timestamp NULL DEFAULT NULL,
  imported_by bigint unsigned NOT NULL DEFAULT 0,
  imported_by_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  completed_at timestamp NULL DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_import_job_no (job_no),
  UNIQUE KEY uk_product_import_file_mapping
    (source_file_sha256,mapping_sha256,template_version,configuration_sha256),
  KEY idx_product_import_job_status (status,id),
  KEY idx_product_import_job_actor (imported_by,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='商品主数据文件导入作业、原文件证据、映射版本与处理汇总';

CREATE TABLE product_import_row (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  job_id bigint unsigned NOT NULL,
  source_row_no int unsigned NOT NULL,
  raw_data_json longtext NOT NULL,
  normalized_data_json longtext NOT NULL,
  source_hash char(64) NOT NULL,
  product_signature char(64) NOT NULL DEFAULT '',
  validation_status varchar(16) NOT NULL COMMENT 'VALID, INVALID',
  validation_message varchar(1000) NOT NULL DEFAULT '',
  proposed_action varchar(24) NOT NULL
    COMMENT 'CREATE, CREATE_PENDING, SAFE_UPDATE, SKIP, CONFLICT',
  decided_action varchar(24) NOT NULL DEFAULT ''
    COMMENT 'CREATE, CREATE_PENDING, SAFE_UPDATE, SKIP, CONFLICT',
  decision_reason varchar(500) NOT NULL DEFAULT '',
  target_product_id bigint unsigned NOT NULL DEFAULT 0,
  target_product_sku varchar(64) NOT NULL DEFAULT '',
  commit_status varchar(24) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING, SUCCEEDED, FAILED, SKIPPED, BLOCKED',
  commit_error varchar(1000) NOT NULL DEFAULT '',
  imported_product_id bigint unsigned NOT NULL DEFAULT 0,
  row_version int unsigned NOT NULL DEFAULT 1,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  committed_at timestamp NULL DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_import_row_no (job_id,source_row_no),
  KEY idx_product_import_row_action (job_id,validation_status,proposed_action,source_row_no),
  KEY idx_product_import_row_hash (job_id,source_hash),
  KEY idx_product_import_row_target (target_product_id,job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='商品导入逐行原文、规范化快照、建议动作、人工决策及永久提交结果';

CREATE TABLE product_import_conflict (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  job_id bigint unsigned NOT NULL,
  row_id bigint unsigned NOT NULL,
  conflict_type varchar(48) NOT NULL,
  field_key varchar(128) NOT NULL DEFAULT '',
  source_value varchar(1000) NOT NULL DEFAULT '',
  target_value varchar(1000) NOT NULL DEFAULT '',
  severity varchar(16) NOT NULL DEFAULT 'BLOCKING' COMMENT 'BLOCKING, WARNING',
  resolution_status varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN, RESOLVED, IGNORED',
  resolution_note varchar(500) NOT NULL DEFAULT '',
  resolved_by bigint unsigned NOT NULL DEFAULT 0,
  resolved_at timestamp NULL DEFAULT NULL,
  message varchar(1000) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_product_import_conflict_row (row_id,severity,resolution_status,id),
  KEY idx_product_import_conflict_job (job_id,resolution_status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='商品导入未知配置、身份碰撞、重复及人工解决证据';

CREATE TABLE product_import_source_lineage (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  job_id bigint unsigned NOT NULL,
  row_id bigint unsigned NOT NULL,
  source_file_sha256 char(64) NOT NULL,
  source_row_no int unsigned NOT NULL,
  source_column varchar(255) NOT NULL,
  source_value varchar(2000) NOT NULL DEFAULT '',
  normalized_field varchar(128) NOT NULL DEFAULT '',
  normalized_value varchar(2000) NOT NULL DEFAULT '',
  normalization_rule varchar(128) NOT NULL DEFAULT '',
  target_entity_type varchar(32) NOT NULL DEFAULT 'PRODUCT',
  target_entity_id bigint unsigned NOT NULL DEFAULT 0,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_product_import_lineage_row (row_id,id),
  KEY idx_product_import_lineage_target (target_entity_type,target_entity_id,id),
  KEY idx_product_import_lineage_source (source_file_sha256,source_row_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='源文件单元格到规范商品字段及最终业务实体的可追溯血缘';

CREATE TABLE product_import_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  job_id bigint unsigned NOT NULL,
  row_id bigint unsigned NOT NULL DEFAULT 0,
  event_type varchar(48) NOT NULL,
  event_status varchar(16) NOT NULL COMMENT 'SUCCESS, FAILED, INFO',
  detail_json longtext NOT NULL,
  actor_id bigint unsigned NOT NULL DEFAULT 0,
  actor_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_product_import_event_job (job_id,id),
  KEY idx_product_import_event_row (row_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='商品导入预检、逐行决策、提交成功和失败的不可丢失审计事件';
