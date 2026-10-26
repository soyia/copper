ALTER TABLE inventory_non_trade_document
  ADD COLUMN signed_attachment_id bigint unsigned NULL
    COMMENT '确认库存扣减时选定的领用人签字单据附件'
    AFTER memo,
  ADD COLUMN signed_attachment_sha256 char(64) NOT NULL DEFAULT ''
    COMMENT '确认时冻结的签字单据原文件摘要'
    AFTER signed_attachment_id,
  ADD KEY idx_inventory_non_trade_signed_attachment (signed_attachment_id);
