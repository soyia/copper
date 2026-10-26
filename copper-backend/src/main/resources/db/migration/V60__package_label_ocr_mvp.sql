ALTER TABLE document_intake_batch
  DROP CHECK chk_document_intake_type,
  ADD COLUMN ocr_engine varchar(32) NOT NULL DEFAULT '' AFTER extracted_text,
  ADD COLUMN ocr_model varchar(128) NOT NULL DEFAULT '' AFTER ocr_engine,
  ADD COLUMN ocr_response_sha256 char(64) NOT NULL DEFAULT '' AFTER ocr_model,
  ADD CONSTRAINT chk_document_intake_type CHECK (
    document_type IN ('SUPPLIER_QUOTE','WEIGH_TICKET','SIGNED_RECEIPT','PACKAGE_LABEL')
  );

ALTER TABLE document_intake_batch
  MODIFY COLUMN document_type varchar(32) NOT NULL
    COMMENT 'SUPPLIER_QUOTE, WEIGH_TICKET, SIGNED_RECEIPT, PACKAGE_LABEL',
  MODIFY COLUMN extraction_mode varchar(24) NOT NULL DEFAULT 'FILE_ONLY'
    COMMENT 'PDF_TEXT, TEXT, PROVIDED_TEXT, OCR, FILE_ONLY',
  COMMENT='报价、磅单、签收单和包装标签的原始文件、OCR审计元数据及待确认候选';
