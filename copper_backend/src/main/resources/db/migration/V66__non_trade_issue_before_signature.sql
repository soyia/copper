ALTER TABLE inventory_non_trade_document
  ADD COLUMN issued_by bigint unsigned NOT NULL DEFAULT 0
    COMMENT '确认实物已经发放并扣减库存的操作人'
    AFTER signed_attachment_sha256,
  ADD COLUMN issued_by_name varchar(128) NOT NULL DEFAULT ''
    COMMENT '确认实物发放的操作人姓名快照'
    AFTER issued_by,
  ADD COLUMN issued_at timestamp NULL DEFAULT NULL
    COMMENT '实物发放及库存过账时间'
    AFTER issued_by_name;

UPDATE inventory_non_trade_document
SET issued_by = confirmed_by,
    issued_by_name = confirmed_by_name,
    issued_at = confirmed_at
WHERE status IN ('CONFIRMED','REVERSED')
  AND issued_at IS NULL;

ALTER TABLE inventory_non_trade_document
  MODIFY COLUMN status varchar(32) NOT NULL DEFAULT 'DRAFT'
    COMMENT 'DRAFT, ISSUED_PENDING_SIGNATURE, CONFIRMED, CANCELLED, REVERSED',
  MODIFY COLUMN signed_attachment_id bigint unsigned NULL
    COMMENT '签字归档时选定的实际领用人签字单据附件',
  MODIFY COLUMN signed_attachment_sha256 char(64) NOT NULL DEFAULT ''
    COMMENT '签字归档时冻结的原文件摘要';

ALTER TABLE inventory_non_trade_document
  DROP CHECK chk_inventory_non_trade_status,
  ADD CONSTRAINT chk_inventory_non_trade_status CHECK (
    status IN ('DRAFT','ISSUED_PENDING_SIGNATURE','CONFIRMED','CANCELLED','REVERSED')
  );
