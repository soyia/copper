-- Copper bar model dimensions are width x thickness in millimetres.
-- V1 retained the source's combined "尺寸" text but omitted the two structured,
-- required SKU fields. Backfill only missing/blank values so customer edits are
-- never overwritten during upgrade.

UPDATE product_spec_preset_value pv
JOIN product_spec_preset p ON p.id = pv.preset_id AND p.product_series_id = 2014
JOIN (
    SELECT 'HTCJ25-3' AS model, '25' AS width_text, 25.0000000000 AS width_value,
           '3' AS thickness_text, 3.0000000000 AS thickness_value
    UNION ALL SELECT 'HTCJ25-6', '25', 25.0000000000, '6', 6.0000000000
    UNION ALL SELECT 'HTCJ30-6', '30', 30.0000000000, '6', 6.0000000000
    UNION ALL SELECT 'HTCJ40-6', '40', 40.0000000000, '6', 6.0000000000
    UNION ALL SELECT 'HTCJ50-6', '50', 50.0000000000, '6', 6.0000000000
    UNION ALL SELECT 'HTCJ60-6', '60', 60.0000000000, '6', 6.0000000000
    UNION ALL SELECT 'HTCJ80-8', '80', 80.0000000000, '8', 8.0000000000
    UNION ALL SELECT 'HTCJ100-6', '100', 100.0000000000, '6', 6.0000000000
) dimensions ON dimensions.model = p.model
JOIN product_series_field sf
  ON sf.id = pv.series_field_id
 AND sf.product_series_id = 2014
 AND sf.field_name IN ('宽度', '厚度')
SET pv.value_text = CASE sf.field_name
        WHEN '宽度' THEN dimensions.width_text
        ELSE dimensions.thickness_text
    END,
    pv.value_decimal = CASE sf.field_name
        WHEN '宽度' THEN dimensions.width_value
        ELSE dimensions.thickness_value
    END
WHERE pv.value_text = '' AND pv.value_decimal IS NULL;

INSERT INTO product_spec_preset_value
    (preset_id, series_field_id, value_text, value_decimal)
SELECT p.id,
       sf.id,
       CASE sf.field_name
           WHEN '宽度' THEN dimensions.width_text
           ELSE dimensions.thickness_text
       END,
       CASE sf.field_name
           WHEN '宽度' THEN dimensions.width_value
           ELSE dimensions.thickness_value
       END
FROM product_spec_preset p
JOIN (
    SELECT 'HTCJ25-3' AS model, '25' AS width_text, 25.0000000000 AS width_value,
           '3' AS thickness_text, 3.0000000000 AS thickness_value
    UNION ALL SELECT 'HTCJ25-6', '25', 25.0000000000, '6', 6.0000000000
    UNION ALL SELECT 'HTCJ30-6', '30', 30.0000000000, '6', 6.0000000000
    UNION ALL SELECT 'HTCJ40-6', '40', 40.0000000000, '6', 6.0000000000
    UNION ALL SELECT 'HTCJ50-6', '50', 50.0000000000, '6', 6.0000000000
    UNION ALL SELECT 'HTCJ60-6', '60', 60.0000000000, '6', 6.0000000000
    UNION ALL SELECT 'HTCJ80-8', '80', 80.0000000000, '8', 8.0000000000
    UNION ALL SELECT 'HTCJ100-6', '100', 100.0000000000, '6', 6.0000000000
) dimensions ON dimensions.model = p.model
JOIN product_series_field sf
  ON sf.product_series_id = 2014
 AND sf.field_name IN ('宽度', '厚度')
LEFT JOIN product_spec_preset_value existing
  ON existing.preset_id = p.id
 AND existing.series_field_id = sf.id
WHERE p.product_series_id = 2014
  AND existing.id IS NULL;

-- Keep the legacy attribute envelope aligned with the final structured values.
-- Existing non-blank structured values win, so an operator's prior correction is
-- retained instead of being replaced by the baseline dimensions above.
UPDATE product_spec_preset p
JOIN product_series_field width_field
  ON width_field.product_series_id = p.product_series_id
 AND width_field.field_name = '宽度'
JOIN product_spec_preset_value width_value
  ON width_value.preset_id = p.id
 AND width_value.series_field_id = width_field.id
JOIN product_series_field thickness_field
  ON thickness_field.product_series_id = p.product_series_id
 AND thickness_field.field_name = '厚度'
JOIN product_spec_preset_value thickness_value
  ON thickness_value.preset_id = p.id
 AND thickness_value.series_field_id = thickness_field.id
SET p.attribute_json = JSON_SET(
        COALESCE(NULLIF(p.attribute_json, ''), '{}'),
        '$.宽度', width_value.value_text,
        '$.厚度', thickness_value.value_text
    )
WHERE p.product_series_id = 2014;
