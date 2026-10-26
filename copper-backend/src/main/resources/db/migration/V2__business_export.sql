CREATE TABLE business_export_task (
  id char(36) NOT NULL,
  format_version varchar(32) NOT NULL DEFAULT 'copper-export/v1',
  scope_type varchar(16) NOT NULL DEFAULT 'FULL',
  filters_json json NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'PENDING',
  progress_percent int unsigned NOT NULL DEFAULT 0,
  current_phase varchar(64) NOT NULL DEFAULT 'WAITING',
  requested_by_user_id bigint unsigned NOT NULL,
  requested_by_name varchar(100) NOT NULL,
  requested_from_ip varchar(64) NOT NULL DEFAULT '',
  retry_of char(36) DEFAULT NULL,
  file_name varchar(255) NOT NULL DEFAULT '',
  relative_path varchar(1000) NOT NULL DEFAULT '',
  archive_bytes bigint unsigned NOT NULL DEFAULT 0,
  archive_sha256 char(64) NOT NULL DEFAULT '',
  exported_table_count int unsigned NOT NULL DEFAULT 0,
  exported_record_count bigint unsigned NOT NULL DEFAULT 0,
  exported_attachment_count bigint unsigned NOT NULL DEFAULT 0,
  error_code varchar(64) NOT NULL DEFAULT '',
  error_message varchar(500) NOT NULL DEFAULT '',
  active_guard tinyint unsigned DEFAULT NULL,
  requested_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at timestamp NULL DEFAULT NULL,
  completed_at timestamp NULL DEFAULT NULL,
  downloaded_at timestamp NULL DEFAULT NULL,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_business_export_active_guard (active_guard),
  KEY idx_business_export_requested_at (requested_at),
  KEY idx_business_export_requester (requested_by_user_id, requested_at),
  KEY idx_business_export_status (status, requested_at),
  CONSTRAINT chk_business_export_active_guard CHECK (active_guard IS NULL OR active_guard = 1),
  CONSTRAINT chk_business_export_scope CHECK (scope_type = 'FULL'),
  CONSTRAINT chk_business_export_progress CHECK (progress_percent <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人可读完整业务导出任务';

INSERT IGNORE INTO security_permission
  (code, module_code, module_name, permission_type, name, description, sort_no, status)
VALUES
  ('data.export', 'data-autonomy', '数据自主', 'ACTION', '完整导出业务数据',
   '生成包含完整经营数据、审计关系和原始附件的人可读导出包', 160, 'ENABLED');

UPDATE security_permission
SET module_code='data-autonomy', module_name='数据自主', permission_type='ACTION',
    name='完整导出业务数据', description='生成包含完整经营数据、审计关系和原始附件的人可读导出包',
    sort_no=160, status='ENABLED'
WHERE code='data.export';

INSERT IGNORE INTO security_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
JOIN security_permission p ON p.code='data.export'
WHERE r.code IN ('ADMIN', 'OWNER');
