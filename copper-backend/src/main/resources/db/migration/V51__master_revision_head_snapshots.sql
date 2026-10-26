-- Preserve the head fields shown alongside immutable product-master revisions.
-- Only the current revision can be reconstructed safely for pre-V51 data.
-- Older revisions intentionally remain NULL instead of borrowing today's head values.

ALTER TABLE product_source_document_revision
  ADD COLUMN document_code_snapshot varchar(64) DEFAULT NULL
    COMMENT 'Source-document code frozen at publication'
    AFTER document_id,
  ADD COLUMN head_status_snapshot varchar(16) DEFAULT NULL
    COMMENT 'Source-document head status frozen at publication'
    AFTER source_type_snapshot;

ALTER TABLE packaging_variant_revision
  ADD COLUMN variant_code_snapshot varchar(64) DEFAULT NULL
    COMMENT 'Packaging-variant code frozen at publication'
    AFTER product_id,
  ADD COLUMN name_snapshot varchar(128) DEFAULT NULL
    COMMENT 'Packaging-variant name frozen at publication'
    AFTER variant_code_snapshot,
  ADD COLUMN head_status_snapshot varchar(16) DEFAULT NULL
    COMMENT 'Packaging-variant head status frozen at publication'
    AFTER name_snapshot;

ALTER TABLE supplier_product_offer_revision
  ADD COLUMN supplier_name_snapshot varchar(255) DEFAULT NULL
    COMMENT 'Supplier display name frozen at publication'
    AFTER supplier_id,
  ADD COLUMN head_status_snapshot varchar(16) DEFAULT NULL
    COMMENT 'Supplier-offer head status frozen at publication'
    AFTER product_id;

ALTER TABLE product_bom_revision
  ADD COLUMN bom_code_snapshot varchar(64) DEFAULT NULL
    COMMENT 'BOM code frozen at publication'
    AFTER parent_product_id,
  ADD COLUMN name_snapshot varchar(128) DEFAULT NULL
    COMMENT 'BOM name frozen at publication'
    AFTER bom_code_snapshot,
  ADD COLUMN head_status_snapshot varchar(16) DEFAULT NULL
    COMMENT 'BOM head status frozen at publication'
    AFTER name_snapshot;

UPDATE product_source_document_revision r
JOIN product_source_document d
  ON d.current_revision_id=r.id AND r.document_id=d.id
SET r.document_code_snapshot=d.document_code,
    r.head_status_snapshot=d.status;

UPDATE packaging_variant_revision r
JOIN packaging_variant v
  ON v.current_revision_id=r.id AND r.packaging_variant_id=v.id
SET r.variant_code_snapshot=v.variant_code,
    r.name_snapshot=v.name,
    r.head_status_snapshot=v.status;

UPDATE supplier_product_offer_revision r
JOIN supplier_product_offer o
  ON o.current_revision_id=r.id AND r.supplier_product_offer_id=o.id
SET r.supplier_name_snapshot=NULL,
    r.head_status_snapshot=o.status;

UPDATE product_bom_revision r
JOIN product_bom b
  ON b.current_revision_id=r.id AND r.bom_id=b.id
SET r.bom_code_snapshot=b.bom_code,
    r.name_snapshot=b.name,
    r.head_status_snapshot=b.status;
