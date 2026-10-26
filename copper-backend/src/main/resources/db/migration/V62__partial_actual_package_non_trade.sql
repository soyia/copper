ALTER TABLE inventory_package_instance
  ADD COLUMN remaining_base_quantity decimal(24,6) NULL AFTER actual_base_quantity;

UPDATE inventory_package_instance
SET remaining_base_quantity = actual_base_quantity
WHERE remaining_base_quantity IS NULL;

ALTER TABLE inventory_package_instance
  MODIFY COLUMN remaining_base_quantity decimal(24,6) NOT NULL;

CREATE TABLE inventory_package_instance_usage (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  package_instance_id bigint unsigned NOT NULL,
  batch_id bigint unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  ref_type varchar(32) NOT NULL,
  ref_id bigint unsigned NOT NULL,
  movement_type varchar(32) NOT NULL,
  base_quantity decimal(24,6) NOT NULL,
  base_unit varchar(64) NOT NULL,
  quantity_before decimal(24,6) NOT NULL,
  quantity_after decimal(24,6) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'CONFIRMED',
  reversed_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_package_usage_ref
    (package_instance_id,ref_type,ref_id,movement_type),
  KEY idx_inventory_package_usage_ref (ref_type,ref_id,status),
  KEY idx_inventory_package_usage_instance (package_instance_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='逐件实重包装的部分领用及可冲正审计记录';
