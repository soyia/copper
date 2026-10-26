-- M4-02: retain session history and make security audit events independently traceable.
ALTER TABLE system_user_session
    ADD COLUMN revoked_at timestamp NULL DEFAULT NULL AFTER expires_at,
    ADD COLUMN revoke_reason varchar(64) NOT NULL DEFAULT '' AFTER revoked_at,
    ADD COLUMN revoked_by_user_id bigint unsigned NOT NULL DEFAULT '0' AFTER revoke_reason,
    ADD KEY idx_user_session_active (user_id, revoked_at, expires_at),
    ADD KEY idx_user_session_revoked (revoked_at, id);

ALTER TABLE system_user
    ADD COLUMN password_changed_at timestamp NULL DEFAULT NULL AFTER last_login_at;

ALTER TABLE security_audit_log
    ADD COLUMN event_id char(32) NULL AFTER id,
    ADD COLUMN result_code varchar(16) NOT NULL DEFAULT 'SUCCESS' AFTER action_code,
    ADD COLUMN failure_code varchar(64) NOT NULL DEFAULT '' AFTER result_code,
    ADD COLUMN session_id bigint unsigned DEFAULT NULL AFTER operator_name,
    ADD COLUMN trace_id char(32) NOT NULL DEFAULT '' AFTER ip_address,
    ADD COLUMN request_method varchar(10) NOT NULL DEFAULT '' AFTER trace_id,
    ADD COLUMN request_path varchar(255) NOT NULL DEFAULT '' AFTER request_method,
    ADD COLUMN user_agent_hash char(64) NOT NULL DEFAULT '' AFTER request_path,
    ADD COLUMN subject_identifier_hash char(64) NOT NULL DEFAULT '' AFTER user_agent_hash,
    ADD COLUMN target_business_key varchar(128) NOT NULL DEFAULT '' AFTER target_id,
    ADD COLUMN occurred_at timestamp(6) NULL DEFAULT NULL AFTER created_at;

UPDATE security_audit_log
SET event_id = LPAD(LOWER(HEX(id)), 32, '0'),
    trace_id = LPAD(LOWER(HEX(id)), 32, '0'),
    occurred_at = created_at
WHERE event_id IS NULL OR occurred_at IS NULL;

ALTER TABLE security_audit_log
    MODIFY COLUMN event_id char(32) NOT NULL,
    MODIFY COLUMN occurred_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD UNIQUE KEY uk_security_audit_event (event_id),
    ADD KEY idx_security_audit_trace (trace_id, occurred_at),
    ADD KEY idx_security_audit_action_result (action_code, result_code, occurred_at),
    ADD KEY idx_security_audit_session (session_id, occurred_at);
