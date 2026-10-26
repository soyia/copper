ALTER TABLE inventory_non_trade_document
  ADD COLUMN selected_package_allocations_json longtext NULL
  AFTER selected_package_instance_ids_json;

UPDATE inventory_non_trade_document
SET selected_package_allocations_json = '[]'
WHERE selected_package_allocations_json IS NULL;
