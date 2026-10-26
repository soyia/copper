-- The product catalogue baseline promises that all fifteen product families are
-- available for normal master-data and business-document creation. V1 retained
-- two disabled flags from an early catalogue import, which made the straight
-- tube series and all special-tube series unavailable as a coherent baseline.
UPDATE product_category
SET status = 'ENABLED', updated_at = CURRENT_TIMESTAMP
WHERE id = 106
  AND status <> 'ENABLED';

UPDATE product_series
SET status = 'ENABLED', updated_at = CURRENT_TIMESTAMP
WHERE id = 2004
  AND status <> 'ENABLED';

-- A fitting type alone cannot distinguish the many purchasable sizes of the
-- same fitting. Keep the national-standard size as a required SKU dimension.
UPDATE product_series_field
SET required = 1,
    sku_dimension = 1,
    include_in_spec = 1,
    searchable = 1,
    order_visible = 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 300082
  AND product_series_id = 2008;

-- The source import accidentally stored six cross-section choices as one long
-- choice. They are independent values, and the free-form size description is a
-- second required SKU dimension so differently sized profiled tubes stay unique.
UPDATE product_series_field
SET option_json = '["矩形管", "方形管", "椭圆管", "半圆管", "D型管", "十字花管"]',
    updated_at = CURRENT_TIMESTAMP
WHERE id = 300121
  AND product_series_id = 2012;

UPDATE product_series_field
SET required = 1,
    sku_dimension = 1,
    include_in_spec = 1,
    searchable = 1,
    order_visible = 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 300123
  AND product_series_id = 2012;
