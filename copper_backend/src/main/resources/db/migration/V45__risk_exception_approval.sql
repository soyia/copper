CREATE TABLE risk_exception_policy (
  entity_id bigint unsigned NOT NULL,
  large_payment_threshold decimal(18,2) NOT NULL DEFAULT 50000.00,
  inventory_adjustment_threshold decimal(18,2) NOT NULL DEFAULT 5000.00,
  high_risk_reversal_threshold decimal(18,2) NOT NULL DEFAULT 50000.00,
  approval_valid_hours int unsigned NOT NULL DEFAULT 72,
  version_no int unsigned NOT NULL DEFAULT 1,
  change_reason varchar(500) NOT NULL DEFAULT '',
  updated_by bigint unsigned NOT NULL DEFAULT 0,
  updated_by_name varchar(128) NOT NULL DEFAULT '',
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP-15轻量例外审批阈值策略';

CREATE TABLE risk_exception_policy_version (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_id bigint unsigned NOT NULL,
  version_no int unsigned NOT NULL,
  large_payment_threshold decimal(18,2) NOT NULL,
  inventory_adjustment_threshold decimal(18,2) NOT NULL,
  high_risk_reversal_threshold decimal(18,2) NOT NULL,
  approval_valid_hours int unsigned NOT NULL,
  change_reason varchar(500) NOT NULL,
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_risk_exception_policy_version (entity_id,version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP-15例外审批策略不可变版本';

CREATE TABLE risk_exception_request (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  request_no varchar(64) NOT NULL,
  entity_id bigint unsigned NOT NULL,
  policy_version int unsigned NOT NULL,
  exception_type varchar(40) NOT NULL,
  risk_level varchar(16) NOT NULL DEFAULT 'HIGH',
  source_type varchar(48) NOT NULL,
  source_id bigint unsigned NOT NULL DEFAULT 0,
  source_no varchar(128) NOT NULL DEFAULT '',
  action_code varchar(32) NOT NULL,
  source_version int unsigned NOT NULL DEFAULT 1,
  source_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  currency varchar(8) NOT NULL DEFAULT 'CNY',
  title varchar(255) NOT NULL,
  request_reason varchar(500) NOT NULL,
  source_snapshot_json json NOT NULL,
  source_snapshot_hash char(64) NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PENDING',
  assigned_employee_id bigint unsigned NOT NULL DEFAULT 0,
  assigned_employee_name varchar(128) NOT NULL DEFAULT '',
  requested_by bigint unsigned NOT NULL DEFAULT 0,
  requested_employee_id bigint unsigned NOT NULL DEFAULT 0,
  requested_by_name varchar(128) NOT NULL DEFAULT '',
  requested_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at timestamp(6) NOT NULL,
  approved_by bigint unsigned NOT NULL DEFAULT 0,
  approved_employee_id bigint unsigned NOT NULL DEFAULT 0,
  approved_by_name varchar(128) NOT NULL DEFAULT '',
  approved_at timestamp(6) NULL DEFAULT NULL,
  decision_reason varchar(500) NOT NULL DEFAULT '',
  rejected_by bigint unsigned NOT NULL DEFAULT 0,
  rejected_by_name varchar(128) NOT NULL DEFAULT '',
  rejected_at timestamp(6) NULL DEFAULT NULL,
  withdrawn_by bigint unsigned NOT NULL DEFAULT 0,
  withdrawn_by_name varchar(128) NOT NULL DEFAULT '',
  withdrawn_at timestamp(6) NULL DEFAULT NULL,
  consumed_by bigint unsigned NOT NULL DEFAULT 0,
  consumed_by_name varchar(128) NOT NULL DEFAULT '',
  consumed_at timestamp(6) NULL DEFAULT NULL,
  result_source_type varchar(48) NOT NULL DEFAULT '',
  result_source_id bigint unsigned NOT NULL DEFAULT 0,
  result_source_no varchar(128) NOT NULL DEFAULT '',
  version_no int unsigned NOT NULL DEFAULT 1,
  active_request_key varchar(255) GENERATED ALWAYS AS (
    CASE WHEN status IN ('PENDING','APPROVED') THEN
      concat(entity_id,'|',source_type,'|',source_id,'|',action_code,'|',source_snapshot_hash)
    ELSE NULL END
  ) STORED,
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_risk_exception_request_no (request_no),
  UNIQUE KEY uk_risk_exception_active_request (active_request_key),
  KEY idx_risk_exception_worklist (status,assigned_employee_id,expires_at,id),
  KEY idx_risk_exception_source (source_type,source_id,action_code,id),
  KEY idx_risk_exception_requester (requested_employee_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP-15单级轻量例外审批申请';

CREATE TABLE risk_exception_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  request_id bigint unsigned NOT NULL,
  action varchar(24) NOT NULL,
  from_status varchar(20) NOT NULL DEFAULT '',
  to_status varchar(20) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_employee_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_risk_exception_event (request_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP-15例外审批不可变事件';

INSERT INTO risk_exception_policy
  (entity_id,large_payment_threshold,inventory_adjustment_threshold,
   high_risk_reversal_threshold,approval_valid_hours,version_no,change_reason)
SELECT id,50000.00,5000.00,50000.00,72,1,
       'ERP-15默认轻量策略；负责人单级授权，普通业务不增加审批层级'
FROM accounting_entity
ON DUPLICATE KEY UPDATE entity_id=values(entity_id);

INSERT IGNORE INTO risk_exception_policy_version
  (entity_id,version_no,large_payment_threshold,inventory_adjustment_threshold,
   high_risk_reversal_threshold,approval_valid_hours,change_reason)
SELECT entity_id,version_no,large_payment_threshold,inventory_adjustment_threshold,
       high_risk_reversal_threshold,approval_valid_hours,change_reason
FROM risk_exception_policy;

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('risk.exception.view','risk-control','经营控制','PAGE','查看例外审批','查看本人可见的高风险例外申请、快照和完整审计轨迹',120,'ENABLED'),
  ('risk.exception.request','risk-control','经营控制','ACTION','提交例外审批','对特价、超信用、大额付款、资金库存调整和高风险冲正提交单级审批',121,'ENABLED'),
  ('risk.exception.approve','risk-control','经营控制','ACTION','批准例外审批','负责人批准、退回或转交例外申请；批准不自动改写业务事实',122,'ENABLED'),
  ('risk.exception.policy.manage','risk-control','经营控制','ACTION','维护例外审批阈值','维护大额付款、库存调整和高风险冲正阈值并保留历史版本',123,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER') AND p.code LIKE 'risk.exception.%';

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('FINANCE','WAREHOUSE','SALES','PURCHASE','MANAGER')
  AND p.code IN ('risk.exception.view','risk.exception.request');
