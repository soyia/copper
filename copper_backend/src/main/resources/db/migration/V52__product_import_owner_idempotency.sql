ALTER TABLE product_import_job
  DROP INDEX uk_product_import_file_mapping,
  ADD UNIQUE KEY uk_product_import_owner_file_mapping
    (imported_by,source_file_sha256,mapping_sha256,template_version,configuration_sha256);
