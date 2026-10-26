CREATE TEMPORARY TABLE tmp_product_reference_alias_uniqueness_guard (
  reference_type varchar(24) NOT NULL,
  alias_key varchar(255) NOT NULL,
  PRIMARY KEY (reference_type,alias_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Intentionally fail before the schema change if historical aliases resolve
-- one normalized key to more than one reference value.
INSERT INTO tmp_product_reference_alias_uniqueness_guard(reference_type,alias_key)
SELECT reference_type,alias_key
FROM product_reference_alias;

DROP TEMPORARY TABLE tmp_product_reference_alias_uniqueness_guard;

ALTER TABLE product_reference_alias
  DROP INDEX uk_product_reference_alias,
  ADD UNIQUE KEY uk_product_reference_alias (reference_type,alias_key);
