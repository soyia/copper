CREATE TABLE system_upgrade_audit (
  id char(36) NOT NULL,
  upgrade_id varchar(48) NOT NULL,
  source_version varchar(64) NOT NULL,
  target_version varchar(64) NOT NULL,
  source_schema varchar(32) NOT NULL,
  target_schema varchar(32) NOT NULL,
  status varchar(32) NOT NULL,
  backup_id char(32) NOT NULL,
  release_manifest_sha256 char(64) NOT NULL,
  started_at timestamp NOT NULL,
  migration_validated_at timestamp NULL DEFAULT NULL,
  switched_at timestamp NULL DEFAULT NULL,
  completed_at timestamp NULL DEFAULT NULL,
  failure_phase varchar(64) NOT NULL DEFAULT '',
  failure_message varchar(500) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_system_upgrade_audit_upgrade (upgrade_id),
  KEY idx_system_upgrade_audit_target (target_version, completed_at),
  KEY idx_system_upgrade_audit_status (status, started_at),
  CONSTRAINT chk_system_upgrade_audit_status CHECK (
    status IN ('MIGRATING', 'MIGRATION_VALIDATED', 'SWITCHED', 'FAILED', 'ROLLED_BACK')
  ),
  CONSTRAINT chk_system_upgrade_audit_schema CHECK (
    source_schema REGEXP '^[0-9]+$' AND target_schema REGEXP '^[0-9]+$'
  ),
  CONSTRAINT chk_system_upgrade_audit_manifest_sha CHECK (
    release_manifest_sha256 REGEXP '^[0-9a-f]{64}$'
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='产品升级与数据库迁移审计记录';
