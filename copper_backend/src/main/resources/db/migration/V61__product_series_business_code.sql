ALTER TABLE product_series
    ADD COLUMN code varchar(32) NULL AFTER category_id;

UPDATE product_series
SET code = 'ZTXPG'
WHERE id = 2002
  AND name = '紫铜小盘管'
  AND (code IS NULL OR trim(code) = '');

CREATE UNIQUE INDEX uk_product_series_code ON product_series(code);
