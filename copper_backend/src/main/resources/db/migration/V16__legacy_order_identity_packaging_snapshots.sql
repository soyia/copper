-- 包装能力启用前的少量订单没有不可变包装快照。
-- 只对“下单单位 = 库存单位 = 计价单位”的同单位明细建立历史基线；
-- 不读取当前商品包装配置，不推测任何箱/包/重量换算。

UPDATE purchase_order_item
SET packaging_plan_json = CAST(JSON_OBJECT(
      'schemaVersion', 2,
      'versionNo', 0,
      'effectiveRevisionId', 0,
      'contentHash', SHA2(CONCAT('LEGACY_ORDER_IDENTITY|PURCHASE|', id, '|', product_id, '|', base_unit), 256),
      'source', JSON_OBJECT(
        'overrideRevisionId', 0,
        'templateId', 0,
        'seriesId', 0,
        'templateRevisionId', 0,
        'sourceLevel', 'LEGACY_ORDER_IDENTITY'
      ),
      'policy', JSON_OBJECT(
        'minimumOutboundUnit', base_unit,
        'baseUnit', base_unit,
        'defaultSalesUnit', base_unit,
        'minimumSalesUnit', base_unit,
        'roundingPolicy', 'REJECT',
        'defaultPurchaseUnit', base_unit
      ),
      'rules', JSON_ARRAY(),
      'calculation', JSON_OBJECT(
        'pricingQuantity', pricing_quantity,
        'requestedQuantity', requested_quantity,
        'requestedUnit', requested_unit,
        'actualWeightRequired', FALSE,
        'baseQuantity', quantity,
        'priceUnit', price_unit,
        'baseUnit', base_unit,
        'estimated', FALSE,
        'components', JSON_ARRAY(JSON_OBJECT(
          'unit', base_unit,
          'quantity', quantity,
          'baseQuantity', quantity,
          'conversionMode', 'BASE'
        )),
        'quantityBasis', 'ACTUAL'
      ),
      'requestedQuantity', requested_quantity,
      'requestedUnit', requested_unit,
      'baseQuantity', quantity,
      'baseUnit', base_unit,
      'pricingQuantity', pricing_quantity,
      'priceUnit', price_unit,
      'components', JSON_ARRAY(JSON_OBJECT(
        'unit', base_unit,
        'quantity', quantity,
        'baseQuantity', quantity,
        'conversionMode', 'BASE'
      ))
    ) AS CHAR),
    packaging_effective_revision_id = 0
WHERE (packaging_plan_json IS NULL OR TRIM(packaging_plan_json) IN ('', '{}'))
  AND requested_unit = base_unit
  AND price_unit = base_unit
  AND requested_quantity = quantity
  AND pricing_quantity = quantity;

UPDATE purchase_order_item
SET packaging_snapshot_hash = SHA2(packaging_plan_json, 256)
WHERE packaging_effective_revision_id = 0
  AND JSON_UNQUOTE(JSON_EXTRACT(packaging_plan_json, '$.source.sourceLevel')) = 'LEGACY_ORDER_IDENTITY';

UPDATE sales_order_item
SET packaging_plan_json = CAST(JSON_OBJECT(
      'schemaVersion', 2,
      'versionNo', 0,
      'effectiveRevisionId', 0,
      'contentHash', SHA2(CONCAT('LEGACY_ORDER_IDENTITY|SALES|', id, '|', product_id, '|', base_unit), 256),
      'source', JSON_OBJECT(
        'overrideRevisionId', 0,
        'templateId', 0,
        'seriesId', 0,
        'templateRevisionId', 0,
        'sourceLevel', 'LEGACY_ORDER_IDENTITY'
      ),
      'policy', JSON_OBJECT(
        'minimumOutboundUnit', base_unit,
        'baseUnit', base_unit,
        'defaultSalesUnit', base_unit,
        'minimumSalesUnit', base_unit,
        'roundingPolicy', 'REJECT',
        'defaultPurchaseUnit', base_unit
      ),
      'rules', JSON_ARRAY(),
      'calculation', JSON_OBJECT(
        'pricingQuantity', pricing_quantity,
        'requestedQuantity', requested_quantity,
        'requestedUnit', requested_unit,
        'actualWeightRequired', FALSE,
        'baseQuantity', quantity,
        'priceUnit', price_unit,
        'baseUnit', base_unit,
        'estimated', FALSE,
        'components', JSON_ARRAY(JSON_OBJECT(
          'unit', base_unit,
          'quantity', quantity,
          'baseQuantity', quantity,
          'conversionMode', 'BASE'
        )),
        'quantityBasis', 'ACTUAL'
      ),
      'requestedQuantity', requested_quantity,
      'requestedUnit', requested_unit,
      'baseQuantity', quantity,
      'baseUnit', base_unit,
      'pricingQuantity', pricing_quantity,
      'priceUnit', price_unit,
      'components', JSON_ARRAY(JSON_OBJECT(
        'unit', base_unit,
        'quantity', quantity,
        'baseQuantity', quantity,
        'conversionMode', 'BASE'
      ))
    ) AS CHAR),
    packaging_effective_revision_id = 0
WHERE (packaging_plan_json IS NULL OR TRIM(packaging_plan_json) IN ('', '{}'))
  AND requested_unit = base_unit
  AND price_unit = base_unit
  AND requested_quantity = quantity
  AND pricing_quantity = quantity;

UPDATE sales_order_item
SET packaging_snapshot_hash = SHA2(packaging_plan_json, 256)
WHERE packaging_effective_revision_id = 0
  AND JSON_UNQUOTE(JSON_EXTRACT(packaging_plan_json, '$.source.sourceLevel')) = 'LEGACY_ORDER_IDENTITY';
