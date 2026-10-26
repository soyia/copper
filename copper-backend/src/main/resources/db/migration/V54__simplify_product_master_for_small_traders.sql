-- Product master simplification for small copper traders.
-- Historical structured data remains stored, but new SKUs use ordinary series fields.

CREATE TEMPORARY TABLE tmp_simplified_identity_series (
  product_series_id bigint unsigned NOT NULL,
  old_version int unsigned NOT NULL,
  new_version int unsigned NOT NULL,
  PRIMARY KEY (product_series_id)
);

INSERT INTO tmp_simplified_identity_series(product_series_id,old_version,new_version)
SELECT s.id,s.identity_schema_version,s.identity_schema_version+1
FROM product_series s
WHERE EXISTS (
  SELECT 1
  FROM product_identity_rule r
  WHERE r.product_series_id=s.id
    AND r.schema_version=s.identity_schema_version
    AND r.source_type='STRUCTURED'
    AND r.status='ENABLED'
);

INSERT INTO product_identity_schema_revision
  (product_series_id,version_no,change_reason,content_hash,published_by,published_by_name)
SELECT t.product_series_id,t.new_version,
       '小微经销商简化：端口和导体构造退出商品唯一性必填条件',
       sha2(concat('SMALL_TRADER_SIMPLE_IDENTITY|',t.product_series_id,'|',t.new_version),256),
       0,'系统迁移'
FROM tmp_simplified_identity_series t;

INSERT INTO product_identity_rule
  (schema_revision_id,product_series_id,schema_version,dimension_key,source_type,
   series_field_id,reference_type,required,sort_no,status)
SELECT new_revision.id,t.product_series_id,t.new_version,
       old_rule.dimension_key,old_rule.source_type,old_rule.series_field_id,
       old_rule.reference_type,old_rule.required,old_rule.sort_no,old_rule.status
FROM tmp_simplified_identity_series t
JOIN product_identity_schema_revision new_revision
  ON new_revision.product_series_id=t.product_series_id
 AND new_revision.version_no=t.new_version
JOIN product_identity_rule old_rule
  ON old_rule.product_series_id=t.product_series_id
 AND old_rule.schema_version=t.old_version
WHERE old_rule.source_type<>'STRUCTURED'
  AND old_rule.status='ENABLED';

UPDATE product_series s
JOIN tmp_simplified_identity_series t ON t.product_series_id=s.id
SET s.identity_schema_version=t.new_version;

-- Products that were pending only because of the removed structured rule can trade
-- immediately. Products still missing an ordinary required field remain pending.
UPDATE product p
JOIN tmp_simplified_identity_series t ON t.product_series_id=p.product_series_id
SET p.identity_schema_version_snapshot=t.new_version,
    p.identity_status='PUBLISHED',
    p.identity_signature=null
WHERE p.identity_status='PENDING_REVIEW'
  AND NOT EXISTS (
    SELECT 1
    FROM product_identity_rule r
    WHERE r.product_series_id=p.product_series_id
      AND r.schema_version=t.new_version
      AND r.status='ENABLED'
      AND r.required=1
      AND (
        (r.source_type='SERIES_FIELD' AND NOT EXISTS (
          SELECT 1
          FROM product_field_value v
          WHERE v.product_id=p.id
            AND v.series_field_id=r.series_field_id
            AND (
              v.value_decimal IS NOT NULL
              OR nullif(trim(coalesce(v.value_text,'')),'') IS NOT NULL
            )
        ))
        OR (r.source_type='LEGACY_COLUMN' AND (
          (r.dimension_key='GRADE' AND nullif(trim(coalesce(p.grade,'')),'') IS NULL)
          OR (r.dimension_key='TEMPER' AND nullif(trim(coalesce(p.temper,'')),'') IS NULL)
          OR (r.dimension_key='STANDARD' AND nullif(trim(coalesce(p.standard,'')),'') IS NULL)
        ))
      )
  );

DROP TEMPORARY TABLE tmp_simplified_identity_series;

-- Batch laboratory/quality review is outside the target product. Preserve its tables
-- and history, but remove it from assignable ERP permissions.
DELETE rp
FROM security_role_permission rp
JOIN security_permission p ON p.id=rp.permission_id
WHERE p.code IN ('inventory.quality.view','inventory.quality.manage');

UPDATE security_permission
SET status='DISABLED',
    description='已退出日常ERP：历史数据保留，不再提供维护入口'
WHERE code IN ('inventory.quality.view','inventory.quality.manage');
